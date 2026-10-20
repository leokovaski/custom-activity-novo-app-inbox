package com.inbox.activity.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lambda da API — recebe TODAS as rotas do API Gateway (HTTP API, $default):
 *
 *   GET  /config.json          contrato da activity (BASE_URL injetada)
 *   GET  /health               healthcheck
 *   GET  /  /index.html /css /js /images   UI de configuração (servida do próprio jar)
 *   POST /journey/execute      valida JWT -> envia para a fila SQS FIFO -> 200 em ms
 *   POST /journey/{save|publish|validate|stop}   ciclo de vida
 *
 * A deduplicação dos retries do SFMC é nativa da fila FIFO:
 * MessageDeduplicationId = hash(activityInstanceId|contactKey|definitionInstanceId).
 * MessageGroupId = contactKey (preserva paralelismo entre contatos).
 */
public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> LIFECYCLE = Set.of("save", "publish", "validate", "stop");

  private final String baseUrl = env("BASE_URL", "").replaceAll("/$", "");
  private final String queueUrl = env("QUEUE_URL", "");
  private final JwtVerifier jwt = new JwtVerifier(env("JWT_SECRET", ""));
  private final SfmcClient sfmc = new SfmcClient();
  private final SqsClient sqs = queueUrlSet() ? SqsClient.create() : null;

  private static String env(String name, String def) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? def : v;
  }

  private boolean queueUrlSet() {
    return !env("QUEUE_URL", "").isBlank();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    String method = event.getRequestContext().getHttp().getMethod();
    String path = event.getRequestContext().getHttp().getPath();

    try {
      if ("GET".equals(method)) {
        return switch (path) {
          case "/config.json" -> json(200, ConfigJson.build(baseUrl));
          case "/health" -> json(200, Map.of("status", "ok", "queue", queueUrlSet()));
          default -> staticResource(path);
        };
      }

      if ("POST".equals(method) && path.startsWith("/journey/")) {
        String action = path.substring("/journey/".length());
        String rawJwt = event.getIsBase64Encoded()
            ? new String(Base64.getDecoder().decode(event.getBody()), StandardCharsets.UTF_8)
            : event.getBody();

        if ("execute".equals(action)) return execute(rawJwt);
        if (LIFECYCLE.contains(action)) return lifecycle(action, rawJwt);
      }

      return json(404, Map.of("error", "não encontrado"));
    } catch (io.jsonwebtoken.JwtException e) {
      System.err.println("[JWT] Falha na validação: " + e.getMessage());
      return json(401, Map.of("error", "JWT inválido"));
    } catch (Exception e) {
      System.err.println("[API] Erro: " + e);
      // 500 faz o Journey Builder tentar novamente (retry)
      return json(500, Map.of("error", "Erro na execução"));
    }
  }

  // ---------------------------------------------------------------------------
  // EXECUTE — chamado para CADA contato que passa pela activity
  // ---------------------------------------------------------------------------
  private APIGatewayV2HTTPResponse execute(String rawJwt) throws Exception {
    Map<String, Object> payload = jwt.verify(rawJwt);
    // ponytail: payload tem PII (contactKey, e-mail). Ligar DEBUG_PAYLOAD=true só pra inspecionar.
    if ("true".equalsIgnoreCase(env("DEBUG_PAYLOAD", "false"))) {
      System.out.println("[EXECUTE] payload=" + MAPPER.writeValueAsString(payload));
    }
    Map<String, Object> inArgs = flattenInArguments(payload);

    // Payload do worker: todos os inArguments (visíveis + ocultos) + campos gerados aqui
    Map<String, Object> job = new HashMap<>(inArgs);
    job.put("requestId", UUID.randomUUID().toString());
    job.put("eventDate", Instant.now().toString());
    job.put("activityId", payload.get("activityId"));
    job.put("activityInstanceId", payload.get("activityInstanceId"));
    job.put("definitionInstanceId", payload.get("definitionInstanceId"));
    job.putIfAbsent("journeyId", payload.get("journeyId"));

    if (sqs != null) {
      // ---------- MODO FILA (produção) ----------
      String dedupId = sha256(job.get("activityInstanceId") + "|" + job.get("contactKey")
          + "|" + job.get("definitionInstanceId"));
      String groupId = String.valueOf(job.getOrDefault("contactKey", "default"));

      sqs.sendMessage(SendMessageRequest.builder()
          .queueUrl(queueUrl)
          .messageBody(MAPPER.writeValueAsString(job))
          .messageDeduplicationId(dedupId)
          .messageGroupId(groupId)
          .build());

      return json(200, Map.of("branchResult", "ok"));
    }

    // ---------- MODO INLINE (dev, sem QUEUE_URL) ----------
    String currentName = sfmc.lookupJourneyName((String) job.get("journeyId"));
    if (currentName != null) job.put("journeyName", currentName);
    System.out.printf("[EXECUTE] contactKey=%s journeyName=%s journeyId=%s%n",
        job.get("contactKey"), job.get("journeyName"), job.get("journeyId"));
    return json(200, Map.of("branchResult", "ok"));
  }

  private APIGatewayV2HTTPResponse lifecycle(String action, String rawJwt) {
    Map<String, Object> payload = jwt.verify(rawJwt);
    System.out.printf("[%s] %s%n", action.toUpperCase(), payload);
    return json(200, Map.of("success", true));
  }

  // ---------------------------------------------------------------------------
  // UI estática servida do classpath (resources/static dentro do jar)
  // ---------------------------------------------------------------------------
  private APIGatewayV2HTTPResponse staticResource(String path) throws Exception {
    if ("/".equals(path) || path.isEmpty()) path = "/index.html";

    // Fallback do ícone: enquanto icon.png não existir no jar, serve o SVG
    boolean iconFallback = "/images/icon.png".equals(path)
        && getClass().getResource("/static/images/icon.png") == null;
    String resourcePath = "/static" + (iconFallback ? "/images/icon.svg" : path);

    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      if (in == null) return json(404, Map.of("error", "não encontrado"));
      byte[] bytes = in.readAllBytes();
      String contentType = contentType(iconFallback ? "/images/icon.svg" : path);

      boolean binary = contentType.startsWith("image/") && !contentType.contains("svg");
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(200)
          .withHeaders(Map.of("Content-Type", contentType))
          .withBody(binary ? Base64.getEncoder().encodeToString(bytes)
                           : new String(bytes, StandardCharsets.UTF_8))
          .withIsBase64Encoded(binary)
          .build();
    }
  }

  private static String contentType(String path) {
    if (path.endsWith(".html")) return "text/html; charset=utf-8";
    if (path.endsWith(".css")) return "text/css; charset=utf-8";
    if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (path.endsWith(".svg")) return "image/svg+xml";
    if (path.endsWith(".png")) return "image/png";
    return "application/octet-stream";
  }

  // ---------------------------------------------------------------------------
  private static APIGatewayV2HTTPResponse json(int status, Object body) {
    try {
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(status)
          .withHeaders(Map.of("Content-Type", "application/json"))
          .withBody(MAPPER.writeValueAsString(body))
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> flattenInArguments(Map<String, Object> payload) {
    Map<String, Object> flat = new HashMap<>();
    if (payload.get("inArguments") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) flat.putAll((Map<String, Object>) m);
      }
    }
    return flat;
  }

  private static String sha256(String value) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
