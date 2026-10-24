<img src="https://www.alye.ai/brand/alye-logo.png" alt="Alye" width="120">

# alye (Java)

[Website](https://www.alye.ai) · [Docs](https://www.alye.ai/docs) · [LLM prices](https://www.alye.ai/models) · [Sign up free](https://app.alye.ai/register)

Records every LLM call your Java services make: model, tokens, cost, latency, time to first token and errors. Wrap your OpenAI or Anthropic client, or add one listener to your LangChain4j models. Java 11+, no required dependencies.

## Install

**Maven**

```xml
<dependency>
  <groupId>ai.alye</groupId>
  <artifactId>alye</artifactId>
  <version>0.1.0</version>
</dependency>
```

**Gradle (Kotlin)**

```kotlin
implementation("ai.alye:alye:0.1.0")
```

**Gradle (Groovy)**

```groovy
implementation 'ai.alye:alye:0.1.0'
```

Set your SDK key as an environment variable (keep it out of your code):

```bash
export ALYE_API_KEY=alye_live_...      # from Setup in Alye
```

## Start

```java
import ai.alye.Alye;
import ai.alye.AlyeOptions;

Alye.init(AlyeOptions.builder()
        .service("support-reply")      // one name per microservice
        .env("production")
        .build());
```

Calls are sent to Alye at `https://api.alye.ai/ingest`; set `ALYE_ENDPOINT` only if you run your own server. Without an API key the SDK prints calls to the console instead.

## OpenAI and Anthropic

Wrap the client once where you create it, and use the wrapped one:

```java
OpenAIClient openai = Alye.wrap(OpenAIOkHttpClient.fromEnv());
AnthropicClient claude = Alye.wrap(AnthropicOkHttpClient.fromEnv());

openai.chat().completions().create(params);   // recorded
```

Recorded: `chat().completions().create` and `createStreaming` (including structured outputs), `responses()`, `embeddings()`, and Anthropic `messages()` and `beta().messages()`, sync and async (`client.async()`). Streamed OpenAI chat completions are asked to include token usage unless you set `streamOptions` yourself.

For Azure OpenAI or an OpenAI-compatible provider, name it so the right prices apply:

```java
OpenAIClient azure = Alye.wrap(azureClient, "azure.ai.openai");
OpenAIClient deepseek = Alye.wrap(deepseekClient, "deepseek");
```

`withRawResponse()` calls are not recorded.

## LangChain4j

Add the listener to any chat model (OpenAI, Anthropic, Gemini, Bedrock, Azure, Mistral, Ollama, ...):

```java
import ai.alye.langchain4j.AlyeChatModelListener;

ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-5-mini")
        .listeners(List.of(new AlyeChatModelListener()))
        .build();
```

## Tag customers (optional)

If your app serves many customers, tag each request with your own customer id (an account or tenant id, not a name or email):

```java
try (Scope s = Alye.context(Tags.customer("northwind").session(ticket.id()).tag("feature", "refunds"))) {
    answer(ticket);
}
```

Tags apply to calls made on that thread until the scope closes.

## Group steps into one trace (optional)

```java
String reply = Alye.span("answer_ticket", () -> agent.run(ticket));
```

Model calls made inside become children of the span, so one agent run shows as a single trace.

## Providers that are not wrapped

```java
try (LlmCall call = Alye.llmCall("voyage", "voyage-3", "embeddings")) {
    EmbedResult r = voyage.embed(texts);
    call.setUsage(r.totalTokens(), 0L);
}
```

On failure call `call.fail(e)` before the call closes.

## Settings

Every option can be set on `AlyeOptions.builder()` or as an environment variable.

| Option | Environment variable | Default |
|---|---|---|
| `apiKey` | `ALYE_API_KEY` | none |
| `service` | `ALYE_SERVICE` or `OTEL_SERVICE_NAME` | `unknown_service` |
| `env` | `ALYE_ENV` | `production` |
| `endpoint` | `ALYE_ENDPOINT` | `https://api.alye.ai/ingest` when an API key is set; without a key, calls are printed |
| `region` | `ALYE_REGION` | none: the cloud region your calls run in, for regional prices |
| `exporter` | `ALYE_EXPORTER` | `otlp` (`console`, `none`, or your own `SpanExporter`) |
| `release` | `ALYE_RELEASE` | none |
| `debug` | `ALYE_DEBUG` | `false` |
| `disabled` | `ALYE_DISABLED` | `false` |

Other options: `flushInterval` (2 s), `heartbeatInterval` (60 s; zero turns it off), `timeout` (5 s), `maxQueue` (10,000), `maxBatch` (512), `shutdownHook` (on), `resourceAttribute(key, value)`.

## How it sends data

- Calls are queued in memory and sent in batches from a background daemon thread, so your request path never waits on the network. If the queue fills up, new calls are dropped and counted rather than slowing you down.
- Format: OTLP/HTTP JSON, gzip, to `{endpoint}/v1/traces` with an `x-api-key` header, the same as the Python and Node.js SDKs. Retries 408, 429 and 5xx with backoff. A rejected key is logged once.
- A heartbeat goes to `{endpoint}/v1/heartbeat` every 60 s with the SDK version, the AI libraries found and queue stats.
- Queued calls are sent when the JVM exits normally. In jobs and serverless handlers, call `Alye.flush()` before returning.
- Prompts and responses are not sent by the Java SDK.

## Tests

```bash
./gradlew test
```

The tests run the real `openai-java`, `anthropic-java` and LangChain4j libraries against a local fake server, so no API keys or network are needed.

## License

Apache-2.0
