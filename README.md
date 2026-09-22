# jev-java

**Unofficial** Java 21+ SDK for [TypeSafe AI](https://typesafe.ai)'s Jev (System One API).
This project is not affiliated with, endorsed by, or supported by TypeSafe AI.

Status: pre-release, not yet on Maven Central. The client, the helpers, Micrometer metrics, a
recording test fake and a runnable example are complete; see [docs/PARITY.md](docs/PARITY.md) for
how each behaviour compares with the official Python and JavaScript SDKs and the live API.

| Module | Runtime dependencies | What it is |
|---|---|---|
| `jev-core` | Jackson only | The client, request/answer types, `patterns` helpers |
| `jev-micrometer` | `jev-core`, Micrometer | `JevMetrics`, a `CallObserver` recording timers, token counters and allowlisted confidence summaries |
| `jev-test` | `jev-core` | `RecordingJevClient`, an in-memory `JevClient` with scripted answers and recorded requests (use with `test` scope) |
| `jev-examples` | — | eight runnable examples mirroring the docs; not published |

## Usage

Not on Maven Central yet. Until it is, install locally and depend on the snapshot:

```sh
git clone https://github.com/Ultonian/jevjava && cd jevjava && ./mvnw -q -DskipTests install
```

```xml
<dependency>
  <groupId>net.codefinch.jev</groupId>
  <artifactId>jev-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Every snippet below is compiled and run in CI (`jev-examples/…/ReadmeUsageTest`).

### Create a client

```java
JevClient client = JevClient.fromEnv();          // TYPESAFE_API_KEY, optional TYPESAFE_BASE_URL etc.

JevClient configured =
    JevClient.builder()
        .apiKey(apiKey)
        .defaultModel("jev-latest")                // or a versioned id such as "jev-1.13.0"
        .timeout(Duration.ofSeconds(10))           // per HTTP attempt, headers and body
        .deadline(Duration.ofSeconds(30))          // whole operation incl. retries; noDeadline() to disable
        .retryPolicy(RetryPolicy.DEFAULT.withMaxRetries(3))
        .build();
```

A client is thread-safe and meant to live as long as your application; `close()` it on shutdown
(it is `AutoCloseable`).

### Ask

The **state** is what every question is judged against — text, or a JSON object when the context
has several parts. Each **question** is one of three primitives; ids are for your code and are
not shown to the model, so each question must say what it means.

```java
State state = State.of(Map.of("ticket", Map.of("text", ticketText, "customer_tier", "gold")));

Questions questions =
    Questions.builder()
        .noul("refund_requested", "Does `ticket.text` ask for money back?",
            NoulCriteria.of("Wants a refund, chargeback or credit", "No request for money back"))
        .choice("department", "Which team should handle `ticket.text`?",
            ChoiceCriteria.builder()
                .option("billing", "Invoices, charges, refunds")
                .option("shipping", "Delivery, tracking, damaged parcels")
                .option("other")                                   // undescribed: the name alone
                .build())
        .score("severity", "How severe is the problem for the customer?",
            List.of("Cosmetic", "Inconvenient, workaround exists", "Blocking"))
        .build();

SystemOneResponse response = client.systemOne(state, questions);
```

### Read the answers

```java
Answers answers = response.answers();
double refund = answers.noul("refund_requested").noul();        // probability of yes, 0..1
ChoiceAnswer dept = answers.choice("department");              // choice(), probabilities(), confidence()
ScoreAnswer severity = answers.score("severity");              // score() may fall between levels; legend()

String summary =
    switch (answers.get("department").orElseThrow()) {         // sealed: the switch is exhaustive
      case NoulAnswer n -> "yes with p=" + n.noul();
      case ChoiceAnswer c -> c.choice() + " (confidence " + c.confidence() + ")";
      case ScoreAnswer sc -> "level " + sc.score() + " of " + sc.topLevel();
    };

response.model();       // the versioned model that answered, e.g. "jev-1.13.0"
response.usage();       // input and output tokens
response.requestId();   // x-typesafe-request-id, for support
```

Typed accessors throw `JevMissingAnswerException` for an unknown id and `JevAnswerTypeException`
for the wrong primitive; `answers.get(id)` returns an `Optional` instead.

### Per-call options and models

```java
SystemOneRequest request = SystemOneRequest.of(state, questions).withModel("jev-preview");
RequestOptions options =
    RequestOptions.builder()
        .timeout(Duration.ofSeconds(5))
        .retry(p -> p.withMaxRetries(0))          // partial override of the client's policy
        .header("X-Request-Source", "batch-job")
        .build();
SystemOneResponse r = client.systemOne(request, options);
```

### Async

```java
CompletableFuture<SystemOneResponse> future = client.systemOneAsync(state, questions);
future.thenAccept(r -> route(r.answers()));      // standard CompletableFuture callback semantics
future.cancel(true);                             // aborts the HTTP exchange and any backoff
```

### Errors and retries

Everything the SDK throws is a `JevException` (unchecked). HTTP errors are `JevApiException`
subclasses named after the status — `JevAuthenticationException` (401),
`JevPermissionDeniedException` (403; also what a request with *no* key gets),
`JevUnprocessableEntityException` (422, with `fieldErrors()`), `JevRateLimitException` (429,
with `retryAfter()`), `JevInternalServerException` (any 5xx, `isOverloaded()` for 529) — each
carrying the status, headers, raw body and request id. Transport failures are
`JevConnectionException` / `JevTimeoutException`; an expired deadline is
`JevDeadlineExceededException`.

Retries match the official SDKs: 408, 429 and 5xx, connection errors and timeouts; two retries
with 500 ms → 5 s backoff and 25 % jitter; `retry-after-ms` / `Retry-After` honoured up to 60 s.

```java
try {
  client.systemOne(state, questions);
} catch (JevRateLimitException e) {
  e.retryAfter().ifPresent(delay -> log.warn("rate limited; server suggests {}", delay));
} catch (JevApiException e) {
  log.error("{} {} request_id={}", e.status(), e.getMessage(), e.requestId().orElse("-"));
}
```

### Configuration

| Setting | Builder | Environment | Default |
|---|---|---|---|
| API key | `apiKey` | `TYPESAFE_API_KEY` | required |
| Base URL | `baseUrl` | `TYPESAFE_BASE_URL` | `https://api.typesafe.ai` |
| Default model | `defaultModel` | `TYPESAFE_DEFAULT_MODEL` | `jev-latest` |
| Log level | `logLevel` | `TYPESAFE_LOG_LEVEL` | `warn`. As in the official SDKs: `info` = one line per attempt and retry; `debug` adds headers (credentials redacted) and bodies. Emitted via `System.Logger` |
| Per-attempt timeout | `timeout` | — | 10 s |
| Operation deadline | `deadline` / `noDeadline()` | — | 30 s |
| Retry policy | `retryPolicy` | — | `RetryPolicy.DEFAULT` |
| Transport / executor | `httpClient` / `executor` | — | SDK-owned; caller-supplied ones are never shut down |

## Routing on answers

Thresholds are always yours; the SDK ships none.

```java
NoulThreshold refund = NoulThreshold.of(0.2, 0.8);      // NO / UNSURE / YES
ConfidenceGate routing = ConfidenceGate.of(0.5, 0.85);  // ESCALATE / CONFIRM / ACT
switch (routing.decide(r.answers().choice("department"))) {
  case ACT -> route(r.answers().choice("department").choice());
  case CONFIRM -> suggest(...);
  case ESCALATE -> queueForTriage();
}
```

`FanOut` asks one question per item in a single call, binding each item into its own
instructions (ids are never shown to the model), and maps the answers back:

```java
FanOut<String> spam = FanOut.noul(messages, FanOut.text(), "Is this message unsolicited advertising?");
for (ItemAnswer<String> a : spam.answers(client.systemOne(state, spam.questions()))) {
  if (a.noul().noul() > 0.9) { ... }
}
```

## Metrics and testing

```java
JevMetrics metrics = JevMetrics.builder(registry).questionTags(Set.of("department")).build();
JevClient client = JevClient.builder().observer(metrics).build();   // jev.call, jev.attempt, jev.tokens, jev.confidence
```

```java
RecordingJevClient fake = new RecordingJevClient()                  // jev-test
    .enqueue(ScriptedAnswers.neutral(questions).noul("refund_requested", 0.95));
// ... run the code under test with `fake` as its JevClient, then inspect fake.requests()
```

## Examples

Eight runnable examples in [`jev-examples`](jev-examples/README.md), each mirroring a page of the
TypeSafe docs (speculative fan-out, composite scoring, confidence-gated routing, intent routing,
line search, re-ranking, entity alignment) with its questions and thresholds in one file. They
run against the live API with `TYPESAFE_API_KEY`, otherwise against a scripted fake.

```sh
./mvnw -q -DskipTests install && ./mvnw -q -pl jev-examples exec:java
```

## Building

```sh
./mvnw verify          # compile (-Werror), Checkstyle, tests, JaCoCo >= 85 % on core, SpotBugs
pre-commit install --install-hooks   # once per clone; runs the same gate before every commit
```

The gate needs no credentials. To also run the live probes, copy `.env.example` to `.env`
(git-ignored), add your key, and source it into the shell first:

```sh
set -a; . ./.env; set +a
./mvnw verify                        # LiveApiTest / LiveExamplesTest now run against the API
```
