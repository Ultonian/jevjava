# Internal architecture

The SDK retains four modules: `jev-core` implements the public API and transport, `jev-test` supplies scripted/recording clients, `jev-micrometer` adapts observations to metrics, and `jev-examples` exercises consumer usage. Java 21 is the minimum runtime; verification also runs on Java 25.

## Ownership

| Component | Responsibility |
|---|---|
| `HttpJevClient` | Endpoint specifications, admission, outstanding-call registry, OPEN/CLOSING/CLOSED phase, deadline scheduler lifetime, and resource shutdown |
| `CallExecution<T>` | One operation's retry/deadline policy, attempt/terminal ordering, cancellation handle, public result, observer queue, and publication |
| `HttpExchange` | Request construction, protected headers, one HTTP exchange including its body, and transport failure mapping |
| `CallSpec<T>` | Immutable endpoint, method, body and parser |
| `Diagnostics` | Per-client filtering and delivery to the existing logger |
| `RecordingJevClient` | Script selection, recording, atomic admission, and independently published results |

New helpers are package-private. Existing public internal classes retain their visibility: `JevClientBuilder` imports `ClientConfig`, `HttpJevClient`, and `Sleeper` across a Java package boundary; `jev-test` uses `Json`, and its tests also use `ResponseParser`. No production dependency runs from core back to test support.

## Thread and lock rules

The client admission lock owns phase transitions and registration. Resolve caller options before taking it. Close changes phase under the lock, then cancels operations and shuts resources down outside it.

A call's monitor orders attempt starts against terminal claims. Starting an attempt reserves its observer position before a terminal event can be queued. The attempt's `finally` settles that position even when request construction fails. One terminal claimant emits the call event. Observer slots preserve order within a call; unrelated calls have no relative ordering guarantee.

The operation executor runs async work; synchronous work runs on its caller. The separate deadline scheduler can expire work still queued on that executor. Cancellation stops the exchange and backoff before publishing the cancellation. Public-result and observer delivery use independent tasks, normally fresh virtual threads, with virtual-thread fallback after rejected delivery. Explicit `future.cancel()` retains CompletableFuture's caller-thread callback semantics. Non-async continuations follow standard `CompletableFuture` rules: a caller attaching after completion or a waiting thread helping completion may also run them. Tests checking publication-thread identity must await the dependent callback stage before calling `get()` on the source result.

Do not complete public futures, cancel HTTP work, invoke observers, or submit arbitrary executor work under transition locks. Do not acquire client admission while holding the call monitor. Observer queue construction under the call monitor links an incomplete slot; filling the slot and dispatching application code occur outside that monitor.

## Three completion facts

1. The operation claims its terminal outcome and disarms its timer.
2. Its public future becomes done, before synchronous continuations finish running.
3. Observer tasks and application continuations eventually return.

Shutdown waits for the second fact, never the third. Tracking removal can occur after continuations return; therefore registry emptiness is not a publication test. Calls remain registered through the publication handoff. Repeated/concurrent closers observe the first shutdown and check public-result state within their own bounded budget. Interruption reasserts the interrupt flag and reports an unmet close guarantee.

The fake keeps separate state and no HTTP retry engine. Its private `executeAndPublish` and `awaitPublication` helpers make execution and publication waiting explicit. Its lifecycle lock covers closed-check, recording and future registration. Script selection uses the script lock after admission. Responder execution and result publication happen outside both locks. Close snapshots admitted futures, publishes cancellations independently, and parks while waiting for future state so it remains usable on a single virtual-thread carrier.

## Reading paths and regression tests

| Behavior | Reading path | Tests |
|---|---|---|
| Ordinary request | Facade builds spec and admits call; call executes exchange and parser, claims outcome, publishes | [HttpRequestTest](../jev-core/src/test/java/net/codefinch/jev/HttpRequestTest.java), [CallObserverTest](../jev-core/src/test/java/net/codefinch/jev/CallObserverTest.java) |
| Retry | Call computes remaining budget, executes attempt, applies existing policy and cancellable backoff | [HttpRetryTest](../jev-core/src/test/java/net/codefinch/jev/HttpRetryTest.java), [RetryPolicyTest](../jev-core/src/test/java/net/codefinch/jev/RetryPolicyTest.java) |
| Deadline | Call arms independent scheduler; expiry claims outcome, cancels work, and publishes | [HttpDeadlineTest](../jev-core/src/test/java/net/codefinch/jev/HttpDeadlineTest.java), [HttpPublicationTest](../jev-core/src/test/java/net/codefinch/jev/HttpPublicationTest.java) |
| Cancellation | Cancellation-aware future claims outcome before cancelling handle and publishing | [HttpCancellationTest](../jev-core/src/test/java/net/codefinch/jev/HttpCancellationTest.java) |
| Observer ordering | Attempt start reserves slot; attempt cleanup fills it; terminal event follows | [CallObserverLifecycleTest](../jev-core/src/test/java/net/codefinch/jev/CallObserverLifecycleTest.java), [HttpObserverLifecycleTest](../jev-core/src/test/java/net/codefinch/jev/HttpObserverLifecycleTest.java) |
| Concurrent close | Client stops admission, cancels admitted work, checks publication and resource ownership | [HttpShutdownTest](../jev-core/src/test/java/net/codefinch/jev/HttpShutdownTest.java), [HttpPublicationTest](../jev-core/src/test/java/net/codefinch/jev/HttpPublicationTest.java) |
| Shared fake/HTTP guarantees | Public client contract through two controlled fixture adapters | [ClientContractTest](../jev-test/src/test/java/net/codefinch/jev/test/ClientContractTest.java) |
| Logging and metrics | Existing diagnostic sink and observer adapter | [HttpDiagnosticsTest](../jev-core/src/test/java/net/codefinch/jev/HttpDiagnosticsTest.java), [JevMetricsTest](../jev-micrometer/src/test/java/net/codefinch/jev/micrometer/JevMetricsTest.java), [JevMetricsIntegrationTest](../jev-micrometer/src/test/java/net/codefinch/jev/micrometer/JevMetricsIntegrationTest.java) |

The controlled `HttpTestFixture.HoldingDelivery` queue also uses one monitor for open/check/enqueue and open/snapshot/clear. It launches tasks outside the monitor so concurrent release and new submissions can proceed independently. [HoldingDeliveryTest](../jev-core/src/test/java/net/codefinch/jev/HoldingDeliveryTest.java) forces the enqueue/clear and concurrent-release interleavings.

See [the test relocation inventory](REFACTORING_TEST_INVENTORY.md) for original method locations.

## Where to change behavior

| Change | Starting point |
|---|---|
| Endpoint or wire request | Public request types, `RequestWriter`, facade's `CallSpec` factory |
| Response parsing or validation | `ResponseParser`, response types and wire fixtures |
| Headers or transport mapping | `HttpExchange` |
| Retry eligibility/backoff | `RetryPolicy`, `RetryAfter`; operation sequencing in `CallExecution` |
| Admission or close budget | `HttpJevClient` |
| Attempt, deadline or observer ordering | `CallExecution`; keep atomic decisions together |
| Diagnostic level or redaction | `Diagnostics`, `Redaction`, originating parser/call/exchange |
| Metric names and cardinality | `jev-micrometer` observer adapter |
| Fake scripts and history | `RecordingJevClient`, `ScriptedAnswers`, `WireJson` |

Public API signatures, Maven coordinates, runtime dependencies, wire shapes and defaults are compatibility boundaries. A behavior change discovered during refactoring needs its own explanation and regression test.
