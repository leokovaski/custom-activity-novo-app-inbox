package com.inbox.activity.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Valida o JWT enviado pelo Marketing Cloud (useJwt=true no config.json),
 * assinado (HS256) com o JWT Signing Secret do Installed Package.
 */
public class JwtVerifier {

  private final SecretKey key;
  private final boolean enabled;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public JwtVerifier(String secret) {
    this.enabled = secret != null && !secret.isBlank();
    this.key = enabled ? Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)) : null;
    if (!enabled) {
      System.err.println("[JWT] JWT_SECRET não configurado — validação desabilitada (apenas dev).");
    }
  }

  /** Verifica a assinatura e retorna as claims (payload do SFMC). */
  @SuppressWarnings("unchecked")
  public Map<String, Object> verify(String rawJwt) {
    if (!enabled) {
      // Modo dev sem segredo: decodifica sem validar (NÃO usar em produção)
      int i = rawJwt.indexOf('.');
      int j = rawJwt.lastIndexOf('.');
      byte[] payload = Base64.getUrlDecoder().decode(rawJwt.substring(i + 1, j));
      try {
        return MAPPER.readValue(payload, Map.class);
      } catch (Exception e) {
        throw new IllegalArgumentException("Payload JWT ilegível", e);
      }
    }
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(rawJwt).getPayload();
  }
}
