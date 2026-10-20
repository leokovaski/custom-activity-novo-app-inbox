package com.inbox.activity.aws;

import java.util.List;
import java.util.Map;

/**
 * config.json — o "contrato" que o Journey Builder busca para montar a activity.
 * Gerado dinamicamente para injetar a BASE_URL (a URL do API Gateway).
 */
public final class ConfigJson {

  private ConfigJson() {}

  private static Map<String, Object> field(String name, boolean nullable) {
    return Map.of(name, Map.of("dataType", "Text", "isNullable", nullable, "direction", "in"));
  }

  public static Map<String, Object> build(String base) {
    Map<String, Object> lang = Map.of(
        "en-US", Map.of(
            "name", "Novo App Inbox",
            "description", "Envia uma mensagem de inbox no app do cliente"),
        "pt-BR", Map.of(
            "name", "Novo App Inbox",
            "description", "Envia uma mensagem de inbox no app do cliente"));

    Map<String, Object> execute = Map.of(
        "inArguments", List.of(
            Map.of("contactKey", "{{Contact.Key}}"),
            Map.of("classificacao", "Padrão"),
            Map.of("title", ""),
            Map.of("message", ""),
            Map.of("midiaTipo", "icone"),
            Map.of("icone", ""),
            Map.of("imagemUrl", ""),
            Map.of("cta", ""),
            Map.of("urlTipo", "nenhum"),
            Map.of("url", ""),
            Map.of("dataInicio", ""),
            Map.of("dataFim", ""),
            Map.of("journeyName", ""),
            Map.of("journeyId", ""),
            Map.of("tipoNotificacao", "Novidade")),
        "outArguments", List.of(),
        "url", base + "/journey/execute",
        "verb", "POST",
        "body", "",
        "header", "",
        "format", "json",
        "useJwt", true,
        "timeout", 10000);

    Map<String, Object> configurationArguments = Map.of(
        "save", Map.of("url", base + "/journey/save", "verb", "POST", "useJwt", true),
        "publish", Map.of("url", base + "/journey/publish", "verb", "POST", "useJwt", true),
        "validate", Map.of("url", base + "/journey/validate", "verb", "POST", "useJwt", true),
        "stop", Map.of("url", base + "/journey/stop", "verb", "POST", "useJwt", true));

    Map<String, Object> schema = Map.of(
        "arguments", Map.of(
            "execute", Map.of(
                "inArguments", List.of(
                    field("contactKey", false), field("classificacao", false),
                    field("title", false), field("message", false),
                    field("midiaTipo", false), field("icone", true), field("imagemUrl", true),
                    field("cta", true), field("urlTipo", false), field("url", true),
                    field("dataInicio", true), field("dataFim", true),
                    field("journeyName", true), field("journeyId", true),
                    field("tipoNotificacao", false)),
                "outArguments", List.of())));

    return Map.of(
        "workflowApiVersion", "1.1",
        "metaData", Map.of(
            "icon", base + "/images/icon.png",
            "category", "customer"),
        "type", "REST",
        "lang", lang,
        "arguments", Map.of("execute", execute),
        "configurationArguments", configurationArguments,
        "userInterfaces", Map.of(
            "configModal", Map.of("height", 720, "width", 640, "fullscreen", false)),
        "schema", schema);
  }
}
