# Jev Java Unofficial SDK examples

Runnable examples for Java 21+, one file each, with questions and thresholds defined together.
Each demonstrates a pattern from the service documentation. Run commands below from the repository
root. A non-blank `TYPESAFE_API_KEY` selects the live API; otherwise each example uses its own
`RecordingJevClient` fake. CI exercises the examples through those fakes.

```sh
./mvnw -q -DskipTests install
env -u TYPESAFE_API_KEY ./mvnw -q -pl jev-examples exec:java           # all examples, offline
env -u TYPESAFE_API_KEY ./mvnw -q -pl jev-examples exec:java -Dexec.mainClass=net.codefinch.jev.examples.Rerank
```

For a live run, export your key or source a configured `.env` file yourself; the SDK does not load
it automatically. Live examples make real calls and consume account usage. `JEV_RUN_LIVE_TESTS`
controls test opt-in only; it is not required by the executable examples.

```sh
./mvnw -q -pl jev-examples exec:java  # live when TYPESAFE_API_KEY is non-blank
JEV_RUN_LIVE_TESTS=1 ./mvnw -q -pl jev-examples test -Dtest=LiveExamplesTest
```

| Example | Mirrors | Shows |
|---|---|---|
| [TicketTriage](src/main/java/net/codefinch/jev/examples/TicketTriage.java) | quickstart | three questions, `NoulThreshold`, `ConfidenceGate`, `Composite` |
| [SupportTicketFanOut](src/main/java/net/codefinch/jev/examples/SupportTicketFanOut.java) | [patterns/fan-out](https://docs.typesafe.ai/patterns/fan-out) | speculative questions in one call; code reads only the relevant answers |
| [ResumeScreening](src/main/java/net/codefinch/jev/examples/ResumeScreening.java) | [patterns/composite-scoring](https://docs.typesafe.ai/patterns/composite-scoring) | `Composite` with two role weightings; concurrent `systemOneAsync` calls |
| [VoiceBanking](src/main/java/net/codefinch/jev/examples/VoiceBanking.java) | [patterns/confidence-routing](https://docs.typesafe.ai/patterns/confidence-routing) | per-action `ConfidenceGate`s: 0.6 floor, 0.85 to approve a transfer |
| [IntentRouting](src/main/java/net/codefinch/jev/examples/IntentRouting.java) | [patterns/intent-routing](https://docs.typesafe.ai/patterns/intent-routing) | intent + complexity → cheapest capable handler |
| [LineSearch](src/main/java/net/codefinch/jev/examples/LineSearch.java) | [cookbooks/semantic_find](https://docs.typesafe.ai/cookbooks/semantic_find) | structured state; a `Choice` over undescribed line ids plus an "exists" `Noul` |
| [Rerank](src/main/java/net/codefinch/jev/examples/Rerank.java) | [cookbooks/rerank_typesafe](https://docs.typesafe.ai/cookbooks/rerank_typesafe) | `FanOut`: one `Noul` per candidate in a single call, sorted by probability |
| [EntityAlignment](src/main/java/net/codefinch/jev/examples/EntityAlignment.java) | [cookbooks/entity_alignment](https://docs.typesafe.ai/cookbooks/entity_alignment) | a `Score` chooses the outcome, three `Noul`s explain it; arithmetic stays in code |

The thresholds are the docs' worked numbers or plausible stand-ins. They are application
decisions: start conservative and tune against your own data.
