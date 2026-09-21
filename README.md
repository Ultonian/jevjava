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
