# Custom Activity — Novo App Inbox

Custom Activity do Journey Builder (Salesforce Marketing Cloud) que envia uma mensagem de inbox no app para cada contato que passa pela activity.

Java 21, serverless: a API roda como função HTTP e o processamento como consumidor de uma fila FIFO. Os arquivos de provisionamento de infraestrutura ficam fora deste repositório; aqui está só a aplicação.

## Fluxo

```
Journey Builder ──POST /journey/execute (JWT)──▶ ApiHandler ──▶ fila FIFO ──▶ WorkerHandler
                                                   │ valida JWT, monta o job,          │ lógica de negócio
                                                   │ enfileira, responde 200           │ (envio da mensagem)
                                                   └ serve config.json e a UI
```

- `ApiHandler` responde em milissegundos ao Journey Builder e deixa o trabalho para a fila.
- Deduplicação dos retries do SFMC é feita pela fila FIFO: `MessageDeduplicationId` = hash de `activityInstanceId|contactKey|definitionInstanceId`; `MessageGroupId` = `contactKey`.
- `WorkerHandler` devolve falhas parciais (`ReportBatchItemFailures`): só a mensagem que falhou volta para a fila.

## Estrutura

```
pom.xml                                  fat jar (maven-shade)
src/main/java/com/inbox/activity/aws/
  ApiHandler.java                        rotas: config.json, health, UI estática, /journey/*
  WorkerHandler.java                     consumidor da fila — lógica de negócio aqui
  ConfigJson.java                        contrato (config.json) do Journey Builder
  JwtVerifier.java                       HS256 com o JWT Signing Secret do Installed Package
  SfmcClient.java                        OAuth2 + lookup do nome atual da jornada (cache 10 min)
src/main/resources/static/
  index.html, js/customActivity.js       UI de configuração (Postmonger)
  css/styles.css, images/icon.svg
```

## Campos configurados na UI

| Campo | Regra | Obrigatório |
|---|---|---|
| `title` | até 28 caracteres | sim |
| `message` | até 160 caracteres | sim |
| `icone` | lista `ICONS` em `customActivity.js` | sim |
| `cta` | até 30 caracteres | não |
| `urlTipo` | `nenhum`, `interno` ou `externo` | sim |
| `url` | deeplink ou link externo | se `urlTipo != nenhum` |
| `dataInicio` / `dataFim` | `datetime-local`, sem fuso | não |

**Dados variáveis.** O botão **+ Dado variável** lista os campos da Data Extension de entrada da jornada (Postmonger `requestSchema`) e insere uma tag no título ou na mensagem. A tag é salva como `{{Event.DEAudience-xxx.Campo}}` e o Journey Builder resolve o valor por contato antes de chamar o execute. Tags não contam no limite de caracteres.

**Fixos, enviados sem aparecer na UI:** `classificacao = Padrão`, `midiaTipo = icone`, `tipoNotificacao = Novidade`, `journeyId`, `journeyName`, `contactKey`.

**Previsto para o futuro:** `midiaTipo = imagem` com `imagemUrl`. O seletor já existe no `index.html` com a classe `hidden` (`#grupoMidiaTipo`); remova a classe para habilitar.

## Payload entregue ao worker

```json
{
  "requestId": "3f6b2a1e-9c4d-4e8a-b1f0-7d2c5a9e6b41",
  "eventDate": "2026-10-20T15:32:08.117Z",
  "activityId": "a1b2c3d4-0000-4000-8000-000000000001",
  "activityInstanceId": "e5f6a7b8-0000-4000-8000-000000000002",
  "definitionInstanceId": "c9d0e1f2-0000-4000-8000-000000000003",
  "journeyId": "7b8c9d0e-0000-4000-8000-000000000004",
  "journeyName": "Boas-vindas App",
  "contactKey": "0031U00001AbCdEfQAM",
  "tipoNotificacao": "Novidade",
  "classificacao": "Padrão",
  "title": "Olá Maria!",
  "message": "Sua oferta exclusiva já está disponível no app.",
  "midiaTipo": "icone",
  "icone": "gift",
  "imagemUrl": "",
  "cta": "Ver agora",
  "urlTipo": "interno",
  "url": "app://inbox/oferta/123",
  "dataInicio": "2026-10-20T09:00",
  "dataFim": "2026-10-31T23:59"
}
```

`requestId`, `eventDate` e os IDs de activity são gerados pela API no execute. O worker consulta o nome atual da jornada no SFMC e o usa no lugar de `journeyName` quando disponível.

## Variáveis de ambiente

| Variável | Onde | Descrição |
|---|---|---|
| `BASE_URL` | API | URL pública da API; injetada no `config.json` |
| `JWT_SECRET` | API | JWT Signing Secret do Installed Package (mínimo 32 caracteres) |
| `QUEUE_URL` | API e worker | URL da fila FIFO. Sem ela, a API processa inline (modo dev) |
| `DEBUG_PAYLOAD` | API e worker | `true` loga o payload completo (contém PII; só para inspeção) |
| `SFMC_AUTH_URL`, `SFMC_CLIENT_ID`, `SFMC_CLIENT_SECRET` | worker | Opcionais; habilitam o lookup do nome da jornada |

## Build

```bash
mvn package -DskipTests
# target/custom-activity-aws-1.0.0.jar
```

Handlers: `com.inbox.activity.aws.ApiHandler::handleRequest` (HTTP) e `com.inbox.activity.aws.WorkerHandler::handleRequest` (fila).

## Marketing Cloud

1. Installed Package → componente **Journey Builder Activity** com Endpoint URL = `BASE_URL/`.
2. Copie o JWT Signing Secret do pacote para `JWT_SECRET`.
3. Valide: `GET /health` e `GET /config.json`.
4. Se o schema do `config.json` mudar, reabra e salve as activities já colocadas em jornadas.
