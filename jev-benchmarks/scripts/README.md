# Repeated benchmark studies

These optional Python scripts run fixed protocols around the Java runners described in the
[benchmark README](../README.md). Running measurements requires Linux, Python 3.12+, `taskset`, CPU
affinity support and a clean committed checkout. Archive audits and script tests need only Python
3.12+, the same floor as the [contributor tools](../../CONTRIBUTING.md#setup-and-checks).
Build and verify the JAR from that checkout before running; the scripts archive it and
the source, but cannot prove that a supplied JAR was built from the current source.

Choose eight available physical cores with `lscpu -e=CPU,CORE,SOCKET,ONLINE`, account for their SMT
siblings, and pass their CPU IDs explicitly with `--cpus`. Affinity does not reserve those cores.
Finish all builds/tests first and keep the host otherwise idle. All output directories must be
new and should live under the Git-ignored `jev-benchmarks/results/`. Fixed experiments require
`--cpus`; there is no machine-specific default. Telemetry uses `/proc` and `/sys`; optional frequency,
power and AMD P-state probes record `unavailable` when the host does not expose them.

## Fixed experiments

| Script | Protocol | JDK | Duration before startup/analysis |
|---|---|---|---|
| `executor-pilot.py` | Six triplets of default async, platform-executor async and sync; alternating order | 21 | 12 min |
| `immediate-study.py` | Six sync/async pairs with immediate transport; alternating order | 21, 25 or 27 (`--jdk`, default 21) | 8 min |
| `diagnostic-study.py` | Three JFR-on/off pairs of control/sync/async HTTP passes; ON/OFF, OFF/ON, ON/OFF | 21 | 12 min |
| `runtime-study.py` | Six rounds of immediate sync/async pairs on all three runtimes | Temurin 21, 25 and 27 GA | 24 min |

For example, after replacing the Java path and CPU IDs with your own:

```sh
python3 -B jev-benchmarks/scripts/immediate-study.py run \
  jev-benchmarks/results/immediate-study \
  --java /path/to/jdk21/bin/java --jdk 21 \
  --jar jev-benchmarks/target/benchmarks.jar --cpus 0,1,2,3,4,5,6,7

python3 -B jev-benchmarks/scripts/immediate-study.py audit \
  jev-benchmarks/results/immediate-study
```

The executor and diagnostic scripts use the same interface without `--jdk`. Before the diagnostic
study, run the README's `VerifyRecording ... long` check on the selected JDK. Each script exposes
`--help`. `run` writes `plan.json`, `study.json`, `summary.json`, source/JAR archives, launch and host
logs, and Java results. `audit` revalidates artifacts and prints a summary without modifying them.
The summaries are descriptive; latency summaries average trial percentiles, not pooled calls.

## Runtime comparison input

`runtime-study.py` takes `--runtimes metadata.json` instead of `--java`/`--jdk`. Supply a JSON object
with keys `"21"`, `"25"`, `"27"`, each containing this structure with absolute paths and actual hashes:

```json
{
  "feature": 21,
  "archive": "/path/to/verified/OpenJDK21.tar.gz",
  "archiveSha256": "SHA256_OF_DOWNLOADED_ARCHIVE",
  "java": "/path/to/extracted/jdk21/bin/java",
  "javaSha256": "SHA256_OF_JAVA_EXECUTABLE"
}
```

Download Linux x64 HotSpot builds from the official Eclipse Adoptium releases, verify their
published checksums, and retain release/download URLs with the metadata. The script checks file
hashes and requires GA Eclipse Adoptium runtimes; it additionally pins modules, `libjvm` and the
release file. It does not download JDKs or authenticate download provenance for you.

```sh
python3 -B jev-benchmarks/scripts/runtime-study.py run \
  jev-benchmarks/results/runtime-study --runtimes /path/to/metadata.json \
  --jar jev-benchmarks/target/benchmarks.jar --cpus 0,1,2,3,4,5,6,7
python3 -B jev-benchmarks/scripts/runtime-study.py audit \
  jev-benchmarks/results/runtime-study
```

All runtimes share one JAR. The round orders are 21/25/27, 21/27/25, 25/27/21, 25/21/27,
27/21/25, 27/25/21. Odd rounds run sync first; even rounds run async first. Each runtime occupies
each position twice, once per call order. Analysis compares runtimes within the same round;
it does not establish a regression threshold or SDK optimization verdict.

## Repeatability and A/A control

`run-study.py` and `analyse-study.py` support a fixed identical-source negative control, not an
arbitrary base/candidate comparison. Independently build identical sources in clean A/B checkouts
for JDK 21 and 25; archive the four JARs, build logs and source outside `target/`. Check that class
entries match across A/B builds. Use absolute paths in a plan like this:

```json
{
  "kind": "A/A negative control",
  "sourceCommit": "FULL_COMMIT_SHARED_BY_BOTH_CHECKOUTS",
  "sourceArchiveSha256": "SHA256_OF_ARCHIVED_SOURCE",
  "cpus": [0, 1, 2, 3, 4, 5, 6, 7],
  "taskset": "/usr/bin/taskset",
  "runOrder": "A1 B1 B2 A2 A3 A4 A5, JDK21 block then JDK25 block",
  "runs": [
    {
      "id": "jdk21-A1",
      "jdk": 21,
      "arm": "A",
      "replicate": 1,
      "java": "/path/to/jdk21/bin/java",
      "jar": "/path/to/archive/benchmarks-jdk21-A.jar",
      "jarSha256": "SHA256_OF_JAR",
      "snapshot": "/path/to/clean-A-checkout"
    }
  ]
}
```

Expand `runs` to all fourteen entries in the stated order. Each pass runs `baseline representative`
with three forks per case (126 trials total, about 84 minutes before startup). Both arms must have
the same source commit, runtime and configuration within each JDK block.

```sh
python3 -B jev-benchmarks/scripts/run-study.py \
  /path/to/plan.json jev-benchmarks/results/repeatability
python3 -B jev-benchmarks/scripts/analyse-study.py jev-benchmarks/results/repeatability
```

The analyser writes `forks.csv`, `noise-summary.json` and `noise-summary.md`. It computes sample
SD/CV across five A passes, each the mean of three fork rates. The two A/A pairs are descriptive
checks for false improvements; they cannot establish a confidence interval or practical threshold.
Retain failed and unfavourable passes. Changing the protocol requires reviewing the analysis too.

## Maintaining the scripts

Hyphenated filenames are stable CLI entry points. Their implementations live in importable
modules with underscores; `study_support.py` owns shared identity, telemetry and process cleanup.
Keep each fixed protocol's schedule and analysis together. Changes to the Java harness produce a
new JAR identity: keep old archives intact and build fresh baselines before comparing SDK changes.

Fast script tests and a pinned Ruff lint check run in pre-commit/CI without timed workloads:

```sh
python3 -B -m unittest discover -s jev-benchmarks/scripts -v
pre-commit run ruff-check --all-files
```
