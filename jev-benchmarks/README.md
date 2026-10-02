# SDK benchmarks

This unpublished developer tool measures where the Java SDK spends time and allocates memory.
Use it to investigate SDK overhead, compare runtime or executor choices, and check whether an
apparent improvement survives repeated measurements. It needs no API key and never calls the
live service or reads `.env`.

| Workload | What it measures |
|---|---|
| Components (JMH) | Content/request construction, JSON serialization and parsing, rejected responses, scoring and thresholds |
| Local HTTP | The real SDK against a managed loopback server, with plain JDK transport controls; sync/async, concurrency, payloads, delays, observers and cold clients |
| Immediate transport | The real SDK with an in-memory HTTP client; isolates serialization, parsing and call lifecycle work from sockets and server work |

The module adds no dependencies to the published SDK. Maven and CI compile it and run harness
correctness tests; timed benchmarks and JFR recordings run only when explicitly invoked.

## Build and smoke-test

Run from the repository root with JDK 21 or newer (CI verifies 21 and 25):

```sh
./mvnw -pl jev-benchmarks -am verify
java -jar jev-benchmarks/target/benchmarks.jar -l

java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunBenchmarks \
  jev-benchmarks/results/components-smoke smoke timing all

java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunLoad \
  jev-benchmarks/results/http-smoke smoke representative
```

Each output directory must be **new**. Results under `jev-benchmarks/results/` are ignored by Git
and survive `mvn clean`. Smoke runs check execution and accounting, not performance.
JMH coordination and HTTP workloads require permission to open loopback sockets.

## Measure components

`RunBenchmarks OUTPUT MODE PROFILER GROUP` records raw JMH JSON, console output, a manifest and
`summary.md`. Use separate timing and allocation passes:

```sh
java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunBenchmarks \
  jev-benchmarks/results/components-timing baseline timing core

java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunBenchmarks \
  jev-benchmarks/results/components-gc baseline gc core
```

Modes are `smoke` and `baseline`; profilers are `timing` and `gc` (allocation/GC metrics).
Groups are `core` (20 construction/wire/rejection cases), `all` (29 cases including scoring and
thresholds), `ticket`, `sizes` and `structure`. Fixtures vary content size, nested JSON structure
and question count; their source and hashes are recorded.

Baseline uses one thread, three fresh JVM forks, five one-second warm-up iterations and five
one-second measurement iterations. Both `core` passes together take about 20 minutes plus startup.
Smoke uses one fork and one 100 ms warm-up/measurement iteration per case. Sub-nanosecond threshold
results are especially sensitive to the harness and JIT.

## Measure calls

`RunLoad OUTPUT MODE GROUP [forward|reverse]` records per-fork results and an aggregate summary:

```sh
java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunLoad \
  jev-benchmarks/results/http-baseline baseline representative

java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunLoad \
  jev-benchmarks/results/immediate-pilot pilot immediate
```

| Group | Selection | Modes | Duration including warm-up, before startup |
|---|---|---|---|
| `representative` | Sync, async and transport control at concurrency 8 | `smoke`, `baseline` | Baseline: 6 min |
| `core` | Sync/async and controls at concurrency 1, 8, 32, 128 | `smoke`, `baseline` | Baseline: 24 min |
| `matrix` | Core plus payload, delay, observer, executor, logging, error and cold-client cases | `smoke`, `baseline` | Baseline: 64 min |
| `async-executor` | Default async, eight-platform-thread operation executor, sync; concurrency 8 | `smoke`, `pilot` | Pilot: 2 min |
| `immediate` | Sync/async with immediate in-memory transport; concurrency 8 | `smoke`, `pilot` | Pilot: 80 sec |

Baseline uses three forks per case; pilot uses one. Both warm up for 10 seconds and measure for
30 seconds per fork. Smoke uses one fork with 100 ms warm-up and 300 ms measurement.
`reverse` is supported only for `async-executor` and `immediate`.

Each virtual caller waits for its result before submitting another call. Latency includes
submission through result observation. Warm-up and measurement drain separately; throughput
counts completions inside the measurement window, while latency also includes drain completions.
Retries are disabled. The immediate client consumes request bytes and invokes the real response
body handler, but deliberately replaces network completion behavior.

## Diagnose and repeat

Restricted JFR profiles capture execution/allocation samples and pinning without environment or
system-property events. Validate the profile on your selected JDK before recording:

```sh
java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.VerifyRecording \
  jev-benchmarks/results/jfr-check long

java -cp jev-benchmarks/target/benchmarks.jar \
  net.codefinch.jev.benchmarks.reporting.RunLoad \
  jev-benchmarks/results/http-jfr diagnostic-long representative
```

Repeat with `diagnostic-long-control` in a new directory to assess recording overhead. Long modes
use 30-second measurement windows and 10 ms sampling. Short modes (`diagnostic` and
`diagnostic-control`) use 3-second windows; `offsets` measures signed observer completion offsets,
and `pinning` exercises delayed sync/async calls. Use `short` in the profile check for those modes.
Analysis selects the measured window; raw recordings also contain setup and cleanup. Zero pin
events means none observed at the 1 ms threshold, not proof of no pinning.

The optional [study scripts](scripts/README.md) automate repeated runs, CPU affinity, host telemetry,
artifact verification and descriptive comparisons. They include fixed executor, immediate-transport,
runtime and A/A repeatability protocols. They do not implement a general SDK A/B regression test.

## Read results responsibly

Runners pin a 512 MiB heap and G1, sanitize child environments, and record JDK/vendor, Git state,
configuration, fixture and JAR identities. Failed or partial runs retain evidence and are marked
failed. Start with `summary.md`, then inspect `manifest.json` and raw `results.json`.

Finish builds and tests before measuring; run sequentially on an otherwise idle host. For a
comparison, keep the harness, fixtures, JDK distribution/version, heap and CPU allocation matched,
change one factor, alternate order and repeat enough times to estimate noise. Archive the exact
JAR and source with the results; keep runs, downloads and recordings out of Git.

Local HTTP results combine client, driver and server costs; controls do not isolate an exact SDK
cost. The `representative` group lacks a concurrency-one control, so capacity is inconclusive.
Use `core` for headroom checks and inspect queue growth and control scaling. Immediate-transport
results describe a synthetic workload. Process heap changes are not allocated bytes, sampled JFR
weights are not an allocation census, and none of these workloads measures live-service latency,
TLS or HTTP/2 performance. There are no automated performance thresholds.
