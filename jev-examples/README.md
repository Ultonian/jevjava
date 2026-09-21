# jev-examples

Runnable examples, one file each, with the questions and the thresholds at the top of the file —
that is what a reviewer reads. Each mirrors a page of the TypeSafe docs and demonstrates one part
of the SDK. Every example runs against the live API when `TYPESAFE_API_KEY` is set and against
its own `RecordingJevClient` fake otherwise; CI runs them all through the fakes.

```sh
./mvnw -q -DskipTests install
./mvnw -q -pl jev-examples exec:java                                   # all examples
./mvnw -q -pl jev-examples exec:java -Dexec.mainClass=net.codefinch.jev.examples.Rerank
JEV_RUN_LIVE_TESTS=1 TYPESAFE_API_KEY=… ./mvnw -q -pl jev-examples test -Dtest=LiveExamplesTest
```

| Example | Mirrors | Shows |
|---|---|---|
| `TicketTriage` | quickstart | three questions, `NoulThreshold`, `ConfidenceGate`, `Composite` |
| `SupportTicketFanOut` | [patterns/fan-out](https://docs.typesafe.ai/patterns/fan-out) | speculative questions in one call; code reads only the relevant answers |
| `ResumeScreening` | [patterns/composite-scoring](https://docs.typesafe.ai/patterns/composite-scoring) | `Composite` with two role weightings; concurrent `systemOneAsync` calls |
| `VoiceBanking` | [patterns/confidence-routing](https://docs.typesafe.ai/patterns/confidence-routing) | per-action `ConfidenceGate`s: 0.6 floor, 0.85 to approve a transfer |
| `IntentRouting` | [patterns/intent-routing](https://docs.typesafe.ai/patterns/intent-routing) | intent + complexity → cheapest capable handler |
| `LineSearch` | [cookbooks/semantic_find](https://docs.typesafe.ai/cookbooks/semantic_find) | structured state; a `Choice` over undescribed line ids plus an "exists" `Noul` |
| `Rerank` | [cookbooks/rerank_typesafe](https://docs.typesafe.ai/cookbooks/rerank_typesafe) | `FanOut`: one `Noul` per candidate in a single call, sorted by probability |
| `EntityAlignment` | [cookbooks/entity_alignment](https://docs.typesafe.ai/cookbooks/entity_alignment) | a `Score` chooses the outcome, three `Noul`s explain it; arithmetic stays in code |

The thresholds are the docs' worked numbers or plausible stand-ins. They are application
decisions: start conservative and tune against your own data.
