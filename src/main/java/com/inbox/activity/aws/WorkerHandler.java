package com.inbox.activity.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lambda do worker — consumidor da fila SQS FIFO (competing consumers).
 *
 * A própria AWS puxa as mensagens (event source mapping), controla a
 * concorrência (MaximumConcurrency), o retry e o envio para a DLQ após
 * maxReceiveCount falhas — não há processo residente para operar.
 *
 * Retorna SQSBatchResponse com as falhas parciais (ReportBatchItemFailures):
 * só as mensagens que falharam voltam para a fila; as demais são confirmadas.
 */
public class WorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final SfmcClient sfmc = new SfmcClient();

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

    for (SQSEvent.SQSMessage message : event.getRecords()) {
      try {
        processJob(MAPPER.readValue(message.getBody(), new TypeReference<Map<String, Object>>() {}));
      } catch (Exception e) {
        System.err.println("[WORKER] Erro ao processar " + message.getMessageId() + ": " + e.getMessage());
        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  private void processJob(Map<String, Object> job) throws Exception {
    // ponytail: job tem PII. Ligar DEBUG_PAYLOAD=true no worker só pra inspecionar.
    if ("true".equalsIgnoreCase(System.getenv("DEBUG_PAYLOAD"))) {
      System.out.println("[WORKER] job=" + MAPPER.writeValueAsString(job));
    }
    // Nome atual da jornada (com cache) — à prova de renomeação
    String currentName = sfmc.lookupJourneyName((String) job.get("journeyId"));
    String journeyName = currentName != null ? currentName : (String) job.get("journeyName");

    System.out.printf("[WORKER] contactKey=%s title=\"%s\" journey=\"%s\" (%s)%n",
        job.get("contactKey"), job.get("title"), journeyName, job.get("journeyId"));

    // =====================================================================
    // >>> AQUI VAI A SUA LÓGICA DE NEGÓCIO <<<
    // Ex.: chamar a API de inbox do app com job.get("title"),
    // job.get("message"), job.get("contactKey")...
    // Lançar exceção => a mensagem volta para a fila (e depois DLQ).
    // =====================================================================
  }
}
