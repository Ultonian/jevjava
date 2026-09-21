# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Upstream tracked: `@typesafe-ai/sdk` 0.6.0, `typesafe-sdk` (Python) 0.7.0, OpenAPI `info.version` 0.2.0.

## [Unreleased]

### Security
- Jackson 2.19.0 → 2.22.2: fixes GHSA-r7wm-3cxj-wff9 (jackson-core) and CVE-2026-54512 /
  CVE-2026-54513 (jackson-databind), all HIGH, reported by Trivy on the first CI run.

### Added
- Project skeleton with the quality gate (google-java-format via Spotless, Checkstyle Google style,
  SpotBugs max effort, JaCoCo 85 % line gate on `jev-core`, `-Werror`, pre-commit hooks, CI on
  JDK 21 and 25 with Trivy).
- Phase 1 of `jev-core`: deeply immutable `Content`; `State`; `NoulQuestion`/`ChoiceQuestion`/
  `ScoreQuestion` with builders; `Questions`; `SystemOneRequest` with per-call model override; the
  sealed `Answer` hierarchy and `Answers` accessors; `SystemOneResponse`, `Usage`, `ModelList`,
  `ModelMetadata`, `ResponseMetadata`; the `Jev*Exception` hierarchy with upstream-order message
  extraction and 422 field errors; `RetryAfter` parsing; `RequestOptions`; the `JevClient`
  interface (HTTP implementation follows in Phase 2).
- `docs/PARITY.md`: the parity matrix against the pinned Python 0.7.0 and JavaScript 0.6.0 SDKs and
  OpenAPI 0.2.0; 47 rows proven by tests, 20 pending Phase 2 / live probes.

- Phase 2 of `jev-core`: the HTTP `JevClient` (`JevClient.builder()`, `fromEnv()`) on
  `java.net.http` with virtual-thread async, `RetryPolicy` reproducing both upstream SDKs' defaults
  (408/429/5xx, 2 retries, 500 ms→5 s backoff with 25 % subtractive jitter, `retry-after-ms` /
  `retry-after`, 60 s server-delay cap), a hard 30 s operation deadline (Java-only), per-call
  `RequestOptions` (timeout, deadline, headers, partial retry override), cancellation that reaches
  the in-flight exchange and backoff, interruption handling, bounded `close()`, env configuration
  (`TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL`, `TYPESAFE_LOG_LEVEL`), and
  opt-in live probes (`JEV_RUN_LIVE_TESTS=1`).

### Changed
- Live API observations (21 Sep 2026) recorded in PARITY.md and asserted in `LiveApiTest`: the
  10-level / 255-option limits are server-enforced with HTTP 400, an invalid key is 401 (missing
  key 403), omitted and `null` instructions are equivalent, `release_date` is an ISO timestamp.

### Fixed (Phase 2 review, sixth pass)
- A concurrent `close()` carries one absolute deadline across its two waits (for the first closer,
  then for publication), so it can no longer spend the publication timeout twice and exceed the
  documented grace + publication bound.

### Fixed (Phase 2 review, fifth pass)
- A concurrent or repeated `close()` no longer returns as a silent no-op: it waits for the first
  closer's shutdown, re-checks that every future is done, and throws under the same
  timeout/interruption rules, so the "returned normally ⇒ all done" guarantee holds for every call.

### Fixed (Phase 2 review, fourth pass)
- Explicitly cancelled calls release their tracking entry immediately (they previously accumulated
  for the life of the client).
- The publication wait in `close()` is an explicit, configurable `publicationTimeout` (default
  5 s); expiry or interruption makes `close()` throw `JevException` after shutting down owned
  resources instead of returning as if every result were done.

### Fixed (Phase 2 review, third pass)
- `close()` returns only once every outstanding result is terminal: it waits for publication (a
  state transition on a delivery thread) but never for application callbacks; verified over 200
  trials with caller-owned resources.
- Completions are published on a fresh virtual thread each (no pooled delivery executor), so a
  `close()` racing the hand-off can no longer make a callback run inline on the deadline scheduler
  or the operation executor.

### Fixed (Phase 2 fix review)
- Public future completion is delivered on an SDK virtual thread after lifecycle bookkeeping, so
  application callbacks can no longer stall the deadline scheduler, unbound `close()`, or observe a
  cancelled future while its HTTP exchange could still retry.
- Call admission is atomic with the transition to closed; a call racing `close()` is rejected or
  cancelled, never sent after shutdown returned.
- DEBUG retry diagnostics carry only exception class, status, attempt and delay; body-derived
  messages moved to TRACE.

### Fixed (Phase 2 review)
- The operation deadline is measured from submission, so executor queueing counts; a queued
  async call now expires on the client's own timer even when its executor never runs it, and an
  expired task never sends a request.
- `close()` completes every outstanding future with `CancellationException`, including calls still
  queued on a caller-owned executor, so joining SDK futures after shutdown cannot hang.
- Retry diagnostics honour the client's log level; log capture tests cover OFF/INFO/DEBUG/TRACE
  and prove credential redaction on request and response headers.
- Injected `HttpClient`s must use `Redirect.NEVER`; the never-follow guarantee is tested for both
  transport ownership modes.
- `ClientConfig.toString()` omits the API key and redacts credential-bearing default headers.

### Fixed (Phase 1 review)
- Build launches on JDK 21 again (a JDK-25-only JVM flag was removed).
- `Content` normalises nested `POJONode`/`BinaryNode` values on entry, closing a hole in the
  deep-immutability contract.
- Response parsing rejects out-of-range `long` counters and non-finite doubles, trailing content
  after the JSON document, and an empty `answers` object (schema `minProperties: 1`).
- `RequestOptions.deadline` has three states: inherit, disabled (`ZERO` / `noDeadline()`), set.
- Javadoc validation is bound to `verify` (policy `all,-missing`, warnings fatal); the Maven
  pre-commit hook is unconditional and JSON syntax is checked.
