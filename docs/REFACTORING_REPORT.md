# Refactoring implementation and verification

Branch: `refactor/readability-maintenance`
Starting commit: `04b729e`
Implementation and verification: 22 September 2026

## Result

The implementation follows the readability plan while preserving the public API, wire behavior, defaults, four Maven modules, artifact coordinates, runtime dependencies, Java baseline, and build gates. No intended behavior changes were introduced.

The original 993-line `HttpJevClient` is now a 369-line facade and lifecycle owner. Its responsibilities are separated as follows:

| Component | Responsibility |
|---|---|
| `HttpJevClient` | Endpoint specifications, atomic admission, outstanding calls, bounded close and owned-resource shutdown |
| `CallExecution<T>` | Per-call retry/deadline policy, atomic attempt/terminal decisions, cancellation, observers and publication |
| `HttpExchange` | Request construction, protected headers, a single complete HTTP exchange and transport error mapping |
| `CallSpec<T>` | Immutable endpoint information and response parser |

The new helpers are package-private. The cancellation-aware future and observer reservation queue remain inside the call owner. Attempt start and outcome claiming remain adjacent, synchronized decisions; request construction is still inside the attempt's observer-slot cleanup. The call receives a registry-removal callback, not the facade or its lifecycle lock.

`RecordingJevClient` retains its independent implementation. Its fields are grouped by script, recording and lifecycle ownership; `executeAndPublish` names the async execution boundary, and `awaitPublication` names its existing bounded, parking-based wait. No HTTP policy engine or shared production lifecycle framework was added.

## Review checkpoints

| Stage | Commit | Change |
|---|---|---|
| Baseline and test organization | `4e9ff10` | Ownership documentation, focused transport/observer suites, explicit fixture and relocation inventory |
| Shared contracts | `3a64d30` | Eight public lifecycle scenarios, each executed against HTTP and recording clients |
| HTTP boundary | `3c8c04e` | Single-exchange helper and immutable call specification |
| Call ownership | `8a42ffe` | Per-call execution, terminal decision, observation and publication owner |
| Fake and final documentation | `b03ab41` | Fake organization, linked architecture map and verification results |

Start review with [the ownership and threading map](INTERNAL_ARCHITECTURE.md), then inspect `CallExecution.claimOutcome`, `startAttempt`, `attempt`, and `publishResult` alongside the facade's admission and shutdown methods. [The test inventory](REFACTORING_TEST_INVENTORY.md) maps moved regression methods to their new suites.

## Verification

Clean reactor verification passed on both OpenJDK 25 and Azul Java 21. The initial sandbox run could not open local HTTP sockets; verification was rerun with loopback access. No application defect was inferred from that sandbox failure.

| Module | Reported cases | Executed successfully | Live cases skipped |
|---|---:|---:|---:|
| `jev-core` | 246 | 243 | 3 |
| `jev-test` | 29 | 29 | 0 |
| `jev-micrometer` | 5 | 5 | 0 |
| `jev-examples` | 18 | 17 | 1 |
| **Total** | **298** | **294** | **4** |

There were no test failures or errors. The baseline was 279 reported cases with the same four live skips. The increase is 16 shared-contract cases plus three delivery-fixture regression cases; no original cases were removed.

A source comparison confirmed all 70 original methods in `HttpJevClientTest` and `CallObserverTest` retained their bodies and annotations, ignoring formatting and comments. A discovery audit caught and restored a multiline parameter source before the relocation checkpoint was finalized. The final inventory includes all six of its status cases.

The shared contracts cover closed-client rejection, queued async execution, virtual-thread publication, explicit cancellation, caller executor ownership, late responses, blocked success/cancellation callbacks, and admission racing close. The fake module retains its existing one-carrier JVM configuration.

Repeated concurrency verification also passed on both JDKs:

- Ten rounds per JDK of 13 selected attempt-ordering, construction-failure, publication-handoff and concurrent-close cases: 260 executions.
- Five rounds per JDK of all 29 shared-contract and recording-fake cases under the existing one-carrier settings: 290 executions.
- Total: 550 additional case executions with no failures or errors. The repetitions retain the deterministic checkpoints; they do not substitute timing sleeps for them.

Core coverage in the refactoring-stage Java 21 clean build was 1,484/1,529 lines (97.06%) and 559/628 branches (89.01%). The baseline Java 21 clean build was 1,464/1,506 lines (97.21%) and 551/618 branches (89.16%). Extraction introduces helper/construction paths and changes the denominator; original test bodies and cases remain intact. No thresholds, exclusions, assertions, or JVM production requirements were weakened.

The compiler's `-Werror`, formatting, Checkstyle, JaCoCo, Javadoc and SpotBugs gates passed. All nine pre-commit hooks passed with `pre-commit run --all-files`; `git diff --check` also passed.

## Compatibility and scope

A sorted `javap -protected -s` inventory of publicly declared classes across all four modules, including nested public types and existing public internals, is identical to the baseline: 1,720 inventory lines. The comparison excludes only the generated source-filename banner. New helpers add no public classes. Source review additionally confirmed unchanged public constants, wire serializers/parsers, configuration defaults and Maven/build configuration.

A normalized comparison of 32 critical call, shutdown and request-specification method bodies confirmed their preservation after explicit renaming and delegation to the HTTP helper. Constructor dependency wiring, public execution entry points and registry release were reviewed separately. These checks complement the integration tests; examples still compile and execute unchanged.

Authenticated live tests remain opt-in and were not run. Hosted CI is tracked on the PR; publication and Phase 4 release readiness remain separate from this refactoring verification.

## CI follow-up: callback-thread assertion

The initial Java 21 CI run at `b03ab41` failed the shared contract's callback-thread assertion. The test called `get()` on the source result before inspecting the callback. A waiting `CompletableFuture.get()` can help process pending continuations, so the waiting platform thread could run that callback even though the SDK completed the source on a virtual thread. This follows standard [CompletableFuture execution rules](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html); the local Java 21 source confirms `timedGet` calls `postComplete` after observing completion.

The original test reproduced the failure 10 times in 2,000 HTTP/fake cases on Java 21. It now waits on the dependent callback stage first and then reads the source result, preserving both assertions. Public documentation now distinguishes SDK publication from the threads that may execute non-async callbacks. Runtime implementation behavior is unchanged.

After the fix, clean reactor verification passed on Java 21 and 25 (291 executed cases and four live skips each). The affected shared contract also passed 2,000 cases on each JDK with one virtual-thread carrier: 4,000 executions and zero failures.

## Review follow-up: atomic test delivery release

[The review finding](https://github.com/Ultonian/jevjava/pull/1#discussion_r4070329056) identified an inherited race in `HttpTestFixture.HoldingDelivery`. Its open check and enqueue could overlap release's iteration/clear, stranding or deleting a task; concurrent releases could also launch a queued task twice.

The fixture now protects open/check/enqueue and open/snapshot/clear with one monitor and starts captured tasks after unlocking. Its default queue remains a `CopyOnWriteArrayList` for existing diagnostic reads. Controlled queue and launch collaborators allow its own regression tests to force the relevant interleavings.

All three new `HoldingDeliveryTest` cases fail with the original unsynchronized operations and pass with the fix. They cover late enqueueing, clearing during release, duplicate release, and progress while an earlier launch is held. A further 300 cases per JDK passed with one virtual-thread carrier (600 total). Clean Java 21 and 25 builds pass with 298 reported cases, 294 executed successfully and four live skips. Production implementation code is unchanged by this fixture fix.

## Reproduce the main gates

Run builds sequentially because they share output directories:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
JAVA_HOME=/home/mlmartin/.jdks/azul-21.0.11 ./mvnw --batch-mode --no-transfer-progress clean verify
pre-commit run --all-files
git diff --check
```

Use the appropriate Java 21 installation on another machine. For the shared contracts and the existing single-carrier fake lifecycle tests:

```sh
./mvnw -pl jev-test -am \
  -Dtest=ClientContractTest,RecordingJevClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

For the transport concurrency suites:

```sh
./mvnw -pl jev-core \
  -Dtest=HttpCancellationTest,HttpDeadlineTest,HttpPublicationTest,HttpShutdownTest,HttpObserverLifecycleTest,CallObserverLifecycleTest \
  test
```
