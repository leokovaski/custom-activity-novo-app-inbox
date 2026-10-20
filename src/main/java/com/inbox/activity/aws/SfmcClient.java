package com.inbox.activity.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente da REST API do SFMC (OAuth2 client-credentials + cache de token).
 * Busca o nome ATUAL da jornada pelo journeyId (imune a renomeações).
 * Opcional: sem SFMC_AUTH_URL/SFMC_CLIENT_ID/SFMC_CLIENT_SECRET, retorna null.
 *
 * Obs.: em Lambda, o cache vive enquanto a instância (execution environment)
 * estiver quente — suficiente para amortizar o custo em rajadas.
 */
public class SfmcClient {

  private static final Duration NAME_CACHE_TTL = Duration.ofMinutes(10);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String authUrl = env("SFMC_AUTH_URL");
  private final String clientId = env("SFMC_CLIENT_ID");
  private final String clientSecret = env("SFMC_CLIENT_SECRET");
  private final HttpClient http = HttpClient.newHttpClient();

  private volatile String token;
  private volatile String restUrl;
  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  private record CachedName(String name, Instant expiresAt) {}
  private final Map<String, CachedName> cache = new ConcurrentHashMap<>();

  private static String env(String name) {
    String v = System.getenv(name);
    return v == null ? "" : v;
  }

  public boolean isEnabled() {
    return !authUrl.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
  }

  /** Nome atual da jornada, ou null se lookup indisponível/falhar. */
  public String lookupJourneyName(String journeyId) {
    if (journeyId == null || journeyId.isBlank() || !isEnabled()) return null;

    CachedName cached = cache.get(journeyId);
    if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.name();

    try {
      ensureToken();
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(restUrl + "/interaction/v1/interactions/" + journeyId))
          .header("Authorization", "Bearer " + token)
          .GET()
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());

      String name = MAPPER.readTree(resp.body()).path("name").asText(null);
      if (name != null) cache.put(journeyId, new CachedName(name, Instant.now().plus(NAME_CACHE_TTL)));
      return name;
    } catch (Exception e) {
      System.err.println("[JOURNEY LOOKUP] " + e.getMessage());
      return null;
    }
  }

  private synchronized void ensureToken() throws Exception {
    if (token != null && Instant.now().isBefore(tokenExpiresAt)) return;

    String body = MAPPER.writeValueAsString(Map.of(
        "grant_type", "client_credentials",
        "client_id", clientId,
        "client_secret", clientSecret));

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(authUrl + "/v2/token"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) throw new IllegalStateException("Auth SFMC falhou: HTTP " + resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());
    this.token = json.path("access_token").asText();
    this.restUrl = json.path("rest_instance_url").asText().replaceAll("/$", "");
    this.tokenExpiresAt = Instant.now().plusSeconds(json.path("expires_in").asLong() - 60);
  }
}
