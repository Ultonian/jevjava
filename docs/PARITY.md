# Parity matrix

How every observable behaviour of jev-java compares with the API contract and the two official
SDKs. This is a source audit against pinned snapshots, not a claim that a live API test has passed
(live probes are marked as such and run only with `JEV_RUN_LIVE_TESTS=1`).

Policy: the OpenAPI schema establishes wire legality; each SDK's behaviour is recorded as
observed; every disagreement gets one explicit Java decision. **Divergence** in a Java cell means the
Java behaviour differs from the named SDK on purpose.

## Pinned sources

| Source | Version | Pin |
|---|---|---|
| JavaScript `@typesafe-ai/sdk` | 0.6.0 | `66880ccded6cb642dc1809620c2b108c33730214` |
| Python `typesafe-sdk` | 0.7.0 | `2ce5c65f13646cab6e6f782328194c9d85f3300a` |
| OpenAPI | `info.version` 0.2.0 | [`upstream/openapi-0.2.0-2026-09-20.json`](upstream/openapi-0.2.0-2026-09-20.json) |
| Docs | 20 Sep 2026 | https://docs.typesafe.ai (`.md` suffix for Markdown) |
| Rust port parity audit | `gilljon/typesafe-ai-rs` @ `06f5220` | `docs/PARITY.md` there (audits Python 0.6.0 / JS 0.6.0) |

Kinds: `serialization`, `client-validation`, `server-limit`, `transport`, `error-mapping`, `config`.
Tests are in `jev-core/src/test/java`. A row is ticked (`[x]`) only when its test exists and passes.

## Wire types and serialisation (Phase 1)

| | Behaviour | API (OpenAPI/docs) | Python 0.7.0 | JavaScript 0.6.0 | Java decision | Kind | Test |
|---|---|---|---|---|---|---|---|
| [x] | Content positions accept text, object, array | `string \| object \| array` (+`null` where noted) | `JSONContent` | `EntryType` | `Content` sealed: `Text`, `JsonObject`, `JsonArray`, `Null` | serialization | `ContentTest.textObjectArrayNullAreTheFourKinds` |
| [x] | Top-level number/boolean as content | not in schema | not in type | not in type | rejected (`IllegalArgumentException`); nested kept | client-validation | `ContentTest.topLevelNumberAndBooleanAreRejectedButNestedOnesKept` |
| [x] | Content is deeply immutable | — | Pydantic frozen models (response); inputs not copied | inputs not copied | normalised to plain JSON on entry (nested `POJONode` serialised now, `BinaryNode` → base64 text, missing node rejected), fresh tree on `toJson()`, nested collections snapshotted | serialization | `ContentTest.mutating*`, `nestedPojoNodeIsSnapshottedNotReferenced`, `nestedBinaryNodeBecomesDetachedBase64Text`, `RequestWriterTest.serialisesTheSameBytesAfterCallerMutatesItsInputs` |
| [x] | `state` null | rejected (no `null` branch) | `JSONContent` excludes None | `EntryType` allows null; tests preserve it | `= API`: `State.of(Content.NULL)` throws (**divergence from JS**) | client-validation | `QuestionsTest.stateRejectsNullContent` |
| [x] | `instructions` omitted vs explicit `null` | both legal; no default declared; equivalence unproven | typed serializer drops top-level `None` (cannot send `null`); nested nulls kept | sends `null` (default) or omits when absent | `Optional.empty()` omits, `Content.NULL` sends `null` (= JS; **divergence from Python**, which cannot send it) | serialization | `RequestWriterTest.explicitNullsArePreservedEverywhereTheApiAllowsThem`, `omittedInstructionsAndNestedArraysWithNulls` |
| [x] | noul `criteria` partial (`true` only) | accepted (`NoulCriteria` has no `required`) | `NoulCriteria` TypedDict `total=False` | `{true?, false?}` | `NoulCriteria.ofTrue/ofFalse`; each side `Optional<Content>` | serialization | `QuestionsTest.noulCriteriaFactories`, `RequestWriterTest.pythonOmitsOnlyUnsetFieldsAndKeepsNestedNulls` |
| [x] | noul `criteria: null` (explicit) | legal | cannot send (top-level None dropped) | sends `null` | **not representable**: `Optional<NoulCriteria>` omits only. Equivalent to omission at the schema level (**divergence from JS**, = Python) | serialization | `RequestWriterTest.explicitNullsArePreservedEverywhereTheApiAllowsThem` (asserts omission) |
| [x] | choice description `null` = undescribed label | documented | `Mapping[str, JSONContent \| None]` | `null` allowed | `ChoiceCriteria.Builder.option(label)` → `Content.NULL`; `of(Map)` accepts null values | serialization | `QuestionsTest.choiceCriteriaBuilderAndMapForm`, `RequestWriterTest.quickstartRequestHasFixedFieldOrderAndShapes` |
| [x] | choice label order preserved on the wire | — | dict order | object order | `LinkedHashMap`, insertion order | serialization | `QuestionsTest.choiceCriteriaBuilderAndMapForm` |
| [x] | score levels `null` | rejected (no `null` branch) | `Sequence[JSONContent]` (no None) | `EntryType` allows null | `= API`/`= Python`: rejected at build (**divergence from JS**) | client-validation | `QuestionsTest.scoreNeedsAtLeastTwoNonNullLevels` |
| [x] | score criteria must be a list | `type: array` | throws on mapping | throws on non-array | unrepresentable (`List<Content>`) | client-validation | (by construction) |
| [x] | Request field order | — | `state, model, questions` | `model, state, questions` (spread) | fixed `model, state, questions` for byte-stable bodies | serialization | `RequestWriterTest.quickstartRequestHasFixedFieldOrderAndShapes` |
| [x] | Question `type` discriminator first, then `instructions`, then `criteria` | — | Pydantic field order | literal order | fixed order | serialization | `RequestWriterTest.quickstartRequestHasFixedFieldOrderAndShapes` |
| [x] | Per-call model override | `model` required on the wire | `system_one(model=...)` | `request.model` | `SystemOneRequest.withModel`; precedence request → client default | serialization | `RequestWriterTest.perCallModelOverridesTheResolvedDefault` |
| [x] | Response `model`, `answers`, `usage` required | required | required | typed | `JevResponseValidationException` naming the field | serialization | `ResponseParserTest.malformedResponsesNameTheField`, `bodyMustBeJsonObject` |
| [x] | `usage.input_tokens` / `output_tokens` | **required** integers | public `Usage`: `int \| None = None` (accepts `{}`) | required in type | `= API`: required (**divergence from Python**) | serialization | `ResponseParserTest.emptyUsageIsRejectedAsDocumentedDivergence` |
| [x] | Unknown response fields | — | ignored (`extra="ignore"`) | ignored | ignored at every level | serialization | `ResponseParserTest.unknownFieldsAreIgnored` |
| [x] | Unknown answer `type` (string) | `Answer` is a closed union | dropped with a warning; raw body kept | trusted as-is | dropped, logged at DEBUG, raw body kept | serialization | `ResponseParserTest.unknownAnswerTypeIsDroppedButStaysInRawBody` |
| [x] | Missing / non-string answer `type`, non-object answer | `type` required | validation error at `answers.<id>.type` | trusted | `JevResponseValidationException` at `answers.<id>.type` | serialization | `ResponseParserTest.malformedResponsesNameTheField` |
| [x] | Malformed known answer (missing/mistyped required field) | required fields per type | validation error with field path | trusted | `JevResponseValidationException` with the same paths as Python's tests | serialization | `ResponseParserTest.malformedResponsesNameTheField` |
| [x] | Missing numbers never read as `0` | — | validation error | `undefined` | boxed parse + presence check | serialization | `ResponseParserTest.missingNumbersNeverBecomeZero` |
| [x] | Unrepresentable numbers (`long` out of range, `double` overflow) | integers / numbers | Pydantic `int`/`float` (Python ints are unbounded; floats overflow to `inf`) | JS numbers | rejected with the field path; never wrap or become infinite | serialization | `ResponseParserTest.unrepresentableNumbersAreRejectedNotWrapped`, `numericBoundariesThatFitAreAccepted` |
| [x] | Body is exactly one JSON document | — | `from_json` (strict) | `response.json()` (strict) | `FAIL_ON_TRAILING_TOKENS`; trailing content → validation error at `$` | serialization | `ResponseParserTest.trailingContentAfterValidDocumentIsRejectedOnBothEndpoints` |
| [x] | `answers` non-empty on the wire | `minProperties: 1` | `min_length=1` | not checked | rejected at `answers`; a body whose answers are *all* unknown types still yields an empty typed map | serialization | `ResponseParserTest.emptyWireAnswersAreRejectedButAllUnknownTypesYieldAnEmptyTypedMap` |
| [x] | Score `legend` / `probabilities` keys are integers | string keys on the wire | decoded as `int` | string keys | `Map<Integer, …>`, ordered by level; non-numeric or negative key → validation error | serialization | `ResponseParserTest.parsesAllThreeAnswerKindsFromTheDocsExample`, `malformedResponsesNameTheField` |
| [x] | `legend` values structured | `string \| object \| array` | `JSONContent` | `T[score]` | `Content` (non-null) | serialization | `ResponseParserTest.structuredLegendContentIsKept` |
| [x] | Raw response retained | — | `raw_http_response` | `withResponse()` | `SystemOneResponse.rawBody()`, `headers()`, `requestId()` | serialization | `ResponseParserTest.parsesAllThreeAnswerKindsFromTheDocsExample` |
| [x] | Request id from `x-typesafe-request-id` | header | property (raises if absent) | optional | `Optional<String>`; header lookup case-insensitive | serialization | `RequestOptionsTest.responseMetadataIsCaseInsensitiveAndExtractsRequestId` |
| [x] | Missing-answer / wrong-kind accessors | — | `KeyError` / typed views | TS types | `JevMissingAnswerException`, `JevAnswerTypeException` (Java-only names) | serialization | `AnswersTest` |
| [x] | `GET /v1/models` body `{models: [{name, description, release_date}]}` | all required | validated | wrapper validated | validated with field paths; extra fields ignored | serialization | `ResponseParserTest.parsesModelsAndIgnoresExtraFields`, `malformedModelsNameTheField` |

## Client-side validation vs server limits

| | Behaviour | API | Python 0.7.0 | JavaScript 0.6.0 | Java decision | Kind | Test |
|---|---|---|---|---|---|---|---|
| [x] | At least one question | `questions.minProperties: 1` | throws | throws | `= all`: `Questions.build()` throws | client-validation | `QuestionsTest.emptySetIsRejectedLikeBothUpstreamSdks` |
| [x] | Question ids unique and non-blank | object keys | dict keys | object keys | duplicate / blank id throws | client-validation | `QuestionsTest.idsMustBeUniqueAndNonBlank` |
| [x] | Score minimum levels | `minItems: 1`; **server accepts 1 (observed live)** | ≥1 | ≥2 | `= JS` (≥2): the docs say "should have at least two" (**divergence from Python and from what the server accepts**) | client-validation | `QuestionsTest.scoreNeedsAtLeastTwoNonNullLevels` |
| [x] | Score maximum levels | not in schema; **server enforces with HTTP 400 (observed live)** | not checked | not checked | not enforced client-side (= both SDKs); surfaces as `JevBadRequestException` | server-limit | `QuestionsTest.scoreNeedsAtLeastTwoNonNullLevels`, `LiveApiTest.probeUndocumentedInSchemaLimits` |
| [x] | Choice maximum options | not in schema; **server enforces with HTTP 400 (observed live)** | not checked | not checked | not enforced client-side (= both SDKs); surfaces as `JevBadRequestException` | server-limit | `LiveApiTest.probeUndocumentedInSchemaLimits` |
| [x] | Choice minimum options | not in schema; **server rejects an empty choice with HTTP 400 (observed live)** | not checked | not checked | not enforced client-side (= both SDKs) | server-limit | live probe (raw JSON) |
| [x] | Choice option labels unique and non-empty | object keys | dict keys | object keys | throws | client-validation | `QuestionsTest.choiceCriteriaRejectsBadInput` |
| [x] | Choice/score `criteria` present | `required` | throws (dict form) | type-level | unrepresentable without | client-validation | (by construction) |
| [x] | Unknown fields on a question | schema is open | typed constructors reject; raw dicts forwarded | type-level | builders cannot produce them; no raw-dict path in v0.x | client-validation | (by construction) |
| [ ] | Token limits (64k / 32k) | docs only | not checked | not checked | not enforced; documented | server-limit | — |

## Errors (Phase 1: mapping and messages; Phase 2: transport)

| | Behaviour | API/docs | Python 0.7.0 | JavaScript 0.6.0 | Java decision | Kind | Test |
|---|---|---|---|---|---|---|---|
| [x] | 400 / 401 / 403 / 404 / 422 / 429 | documented statuses | `TypeSafe{BadRequest,Authentication,PermissionDenied,NotFound,UnprocessableEntity,RateLimit}Error` | same names without prefix | `Jev…Exception` with the same stems | error-mapping | `JevApiExceptionTest.statusMapsToTheUpstreamClass` |
| [x] | Any 5xx incl. 529 | 529 = overloaded (docs) | `TypeSafeInternalServerError` (≥500) | `InternalServerError` (≥500) | `JevInternalServerException` + `isOverloaded()`; **no** `JevOverloadedException` | error-mapping | `JevApiExceptionTest.rateLimitExposesRetryAfterAndServerErrorKnowsOverloaded` |
| [x] | Other statuses | — | `TypeSafeAPIError` | `APIError` | `JevApiException` base | error-mapping | `JevApiExceptionTest.statusMapsToTheUpstreamClass` |
| [x] | Message extraction order | — | text; `error`; `error.message`; `message`; `detail`; `detail.message`; `detail[].msg`; raw ≤200 | same | same order; raw fallback truncated to 200 + `…`; JSON decoded regardless of content type; non-JSON kept as text | error-mapping | `JevApiExceptionTest.messageIsExtractedInUpstreamOrder`, `longFallbackIsTruncatedTo200Chars` |
| [x] | Empty body | — | `"<status> status code (no body)"` | same | same | error-mapping | `JevApiExceptionTest.messageIsExtractedInUpstreamOrder` |
| [x] | Endpoint and request id in the message | — | `"POST <url>: 400 msg (request_id=…)"` | request id as property | same framing as Python | error-mapping | `JevApiExceptionTest.endpointAndRequestIdFrameTheMessage` |
| [x] | 422 field errors | FastAPI `HTTPValidationError` | `detail[]` joined into the message | message only | `JevUnprocessableEntityException.fieldErrors()` (`loc` minus `body`, `msg`, `type`); empty for other shapes | error-mapping | `JevApiExceptionTest.unprocessableEntityExposesFieldErrorsOnlyForFastApiShape` |
| [x] | 429 exposes server delay | `retry-after-ms` / `retry-after` | `retry_after` property | `retryAfter` | `JevRateLimitException.retryAfter()` | error-mapping | `JevApiExceptionTest.rateLimitExposesRetryAfterAndServerErrorKnowsOverloaded` |
| [x] | `retry-after-ms` preferred; seconds or HTTP-date; negative/non-finite ignored; past date → 0 | — | `parse_retry_after` | `parseRetryAfter` | `RetryAfter.parse` | error-mapping | `RetryAfterTest` |
| [x] | Invalid 2xx body | — | `TypeSafeAPIResponseValidationError(field_path)` | — | `JevResponseValidationException.fieldPath()` | error-mapping | `ResponseParserTest.malformedResponsesNameTheField` |
| [x] | Raw body, headers, status kept verbatim on every API error | — | yes | yes | yes; headers case-insensitive, unmodifiable | error-mapping | `JevApiExceptionTest.endpointAndRequestIdFrameTheMessage` |
| [x] | Authentication statuses | **observed live:** invalid key → **401**, missing key → **403** (`error_type: authentication_error` in both) | 401 → `TypeSafeAuthenticationError`, 403 → `TypeSafePermissionDeniedError` | same | same mapping; `JevPermissionDeniedException` Javadoc notes that 403 can mean "no key at all" | error-mapping | `LiveApiTest.probeInvalidKeyStatus`, `HttpJevClientTest.missingKeyStyle403IsPermissionDeniedWithTheServerMessage` |
| [x] | Connection / timeout hierarchy | — | `TypeSafeAPITimeoutError extends TypeSafeAPIConnectionError` | `APITimeoutError extends APIConnectionError` | `JevTimeoutException extends JevConnectionException`; Java-only `JevDeadlineExceededException`, `JevInterruptedException` | error-mapping | `JevApiExceptionTest.transportAndJavaOnlyExceptionsFormHierarchy` |

## Configuration, headers, retries and lifecycle (Phase 2)

| | Behaviour | API/docs | Python 0.7.0 | JavaScript 0.6.0 | Java decision | Kind | Test |
|---|---|---|---|---|---|---|---|
| [x] | Env vars | — | `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL`, `TYPESAFE_LOG_LEVEL`; trimmed, blank = unset | same | same | config | `JevClientBuilderTest.environmentIsTrimmedAndBlankMeansUnset`, `logLevelNamesFromTheEnvironment` |
| [x] | Explicit config beats env beats defaults | — | yes | yes | yes | config | `JevClientBuilderTest.explicitValuesBeatTheEnvironment`, `defaultsWhenOnlyTheKeyIsSet` |
| [x] | Missing key | — | error names `TYPESAFE_API_KEY` | same | same message | config | `JevClientBuilderTest.missingKeyNamesTheVariable` |
| [x] | Defaults | — | base `https://api.typesafe.ai`, model `jev-latest`, timeout 10 s | same | same; deadline 30 s (Java-only); trailing slashes stripped from base URL; path prefix kept | config | `JevClientBuilderTest.defaultsWhenOnlyTheKeyIsSet`, `HttpJevClientTest.baseUrlPrefixAndTrailingSlashesAreHandled` |
| [x] | Identification headers | — | `User-Agent`/`X-TypeSafe-SDK: typesafe-sdk/<v>`, `X-TypeSafe-Runtime: python/… (os; arch)` | same with `node/…` | `jev-java/<v>` and `java/<Runtime.version()> (<os.name>; <os.arch>)` | transport | `HttpJevClientTest.systemOneSendsTheDocumentedRequestAndParsesTheResponse` |
| [x] | `Authorization: Bearer`, `Accept: application/json`, `Content-Type` on bodies only | schema | yes | yes | yes | transport | `HttpJevClientTest.systemOneSends…`, `modelsSendsGetWithoutBodyOrContentType` |
| [x] | `X-TypeSafe-Retry-Count` | — | absent on first attempt, `1..n` on retries; caller value stripped | same | same | transport | `HttpJevClientTest.retriesThenSucceedsWithRetryCountHeaderAndBackoff`, `sdkHeadersAlwaysWinAndMergeIsCaseInsensitive` |
| [x] | Header merge | — | case-insensitive; request > client; SDK headers win; `Content-Type` forced on bodies | same; JS strips `Content-Type` on bodyless GET | case-insensitive; request > client; SDK headers and `Content-Type: application/json` on bodies always win; no `Content-Type` on GET (= JS) | transport | `HttpJevClientTest.sdkHeadersAlwaysWinAndMergeIsCaseInsensitive` |
| [x] | `RequestOptions` headers case-insensitive; timeout strictly positive; deadline tri-state (inherit / `ZERO` = disabled / positive) | — | per-request `timeout`, `retry` (whole policy) | per-request `timeout`, `retry`, `signal` | `RequestOptions`; `noDeadline()` | config | `RequestOptionsTest` |
| [x] | Per-call model override; concurrent calls independent | `model` on the wire | `system_one(model=)` | `request.model` | `SystemOneRequest.withModel`; no client state per call | transport | `HttpJevClientTest.perCallModelOverridesAndConcurrentCallsUseTheirOwnModel` |
| [x] | Retried statuses | docs: retry 429/529 | 408, 429, 500–599 | same | same | transport | `RetryPolicyTest.defaultsMatchBothUpstreamSdks`, `retryabilityRules` |
| [x] | Connection / timeout retried, individually switchable | — | yes | yes | yes | transport | `RetryPolicyTest.retryabilityRules`, `HttpJevClientTest.connectionFailureIsRetriedThenMapped`, `stalledHeadersTimeOutPerAttemptAndAreRetried` |
| [x] | Max retries 2; backoff 500 ms ×2 ≤ 5 s; 25 % subtractive jitter | — | yes | yes | same (pure function, injected random) | transport | `RetryPolicyTest.backoffDoublesFrom500msCapsAt5sAndSubtractsUpTo25Percent`, `HttpJevClientTest.retriesThenSucceeds…`, `retriesExhaustedThrowsTheLastFailure` |
| [x] | `retry-after-ms` preferred over `retry-after`; server delay not jittered | — | yes | yes | yes | transport | `HttpJevClientTest.retryAfterIsHonouredPreferringMsAndIgnoredAboveTheCap` |
| [x] | Server delay cap | — | none | 60 s, then backoff | `= JS` 60 s (**divergence from Python**) | transport | `RetryPolicyTest.serverDelayWinsWhenHonouredAndWithinCap`, `HttpJevClientTest.retryAfterIsHonoured…` |
| [x] | Budget / deadline | — | 30 s retry-admission budget; running attempt never interrupted | none (`AbortSignal`) | 30 s **hard** operation deadline measured from submission (executor queueing included): no retry admitted that would breach it; an active attempt is capped by the remaining time and cancelled; a queued async call expires on the client's own timer even if its executor never runs it; expiry cancels the call *before* the failure is delivered, and delivery runs on an SDK virtual thread so application callbacks can never stall the timer; `JevDeadlineExceededException` with the last failure as cause (**Java-only; divergence from Python**) | transport | `HttpJevClientTest.deadlineRefusesRetryThatWouldBreachIt`, `deadlineExpiringDuringAnActiveAttemptCancelsIt`, `queuedCallExpiresAtTheDeadlineWhileTheExecutorIsStillBlocked`, `blockingCallbackDoesNotBlockOtherDeadlines`, `deadlineCanBeDisabledPerCallAndPerClient` |
| [x] | Per-attempt timeout covers headers **and** body | — | httpx timeouts | whole attempt incl. body | whole attempt incl. body; timed-out exchange is cancelled | transport | `HttpJevClientTest.stalledHeadersTimeOut…`, `stalledBodyTimesOutToo` |
| [x] | Per-request retry override | — | replaces the whole policy | partial field override | partial override applied to the client policy (= JS); wholesale replacement possible | config | `RetryPolicyTest.requestOptionsApplyPartialOverrides`, `HttpJevClientTest.perCallRetryOverrideIsPartial` |
| [x] | Retry predicate | — | extra exception types + predicate | none | `Predicate<JevException>` (may opt into response-validation retries); never consulted for cancel/interrupt/deadline | config | `RetryPolicyTest.predicateExtendsButTerminalFailuresNeverRetry`, `HttpJevClientTest.predicateCanOptIntoRetryingValidationFailures` |
| [x] | Error mapping end to end; validation errors carry the endpoint; not retried by default | — | yes | yes | yes | error-mapping | `HttpJevClientTest.nonRetriedStatusesMapWithoutRetrying`, `missingKeyStyle403IsPermissionDeniedWithTheServerMessage`, `malformedSuccessBodyIsValidationErrorWithEndpoint` |
| [x] | Cancellation aborts the exchange and backoff; no further attempt; permissive predicate cannot revive; races with the next retry lose; the call is dead before any completion callback runs | — | task cancellation | `AbortSignal` → `APIUserAbortError` | future `cancel()` → `CancellationException`; works with SDK-owned and caller-supplied executors | transport | `HttpJevClientTest.cancelBeforeStartNeverSendsRequest`, `cancelDuringAnAttemptAbortsIt`, `cancelDuringBackoffStartsNoFurtherAttemptEvenWithPermissivePredicate`, `cancelRacingTheNextRetryIsRespectedOnBothExecutors`, `cancelPreventsRetryWhileTheCancellationCallbackIsBlocked` |
| [x] | Completion delivery | — | n/a | n/a | every async completion (deadline, failure, success, close, executor rejection) is published on a fresh SDK virtual thread, never on the deadline scheduler, the closing thread or the operation executor; there is no pooled delivery executor and therefore no rejection or inline fallback path | transport | `HttpJevClientTest.completionsArePublishedOnSdkVirtualThreadsForEveryOutcome`, `rejectedDeliveryAndCloseInTheHandoffGapStillPublishOnVirtualThreads` |
| [x] | Interrupting a sync call | — | n/a | n/a | `JevInterruptedException`, flag re-asserted, terminal | transport | `HttpJevClientTest.interruptingSyncCallThrowsAndReassertsTheFlag` |
| [x] | Resource lifetime | — | `close()` closes supplied clients too | runtime-managed | `close()` bounded by grace period **plus** publication timeout: waits the grace period for in-flight calls, then completes every outstanding future with `CancellationException` (queued ones included, whether or not a worker ever ran), cancels their handles, then shuts down **only** SDK-owned `HttpClient`/executor; when `close()` returns normally every outstanding result is done (it waits up to a configurable publication timeout, default 5 s, for the completion CAS on a delivery thread — never for callbacks); if that timeout expires or the closer is interrupted, `close()` still shuts down owned resources and then **throws** `JevException` (interrupt re-asserted), so an unmet guarantee is observable; never runs application callbacks on the closing thread; explicitly cancelled calls release their tracking entry synchronously (no growth on long-lived clients); this holds for **every** `close()` invocation: a concurrent or repeated call waits for the first closer's outcome within the same bound, re-checks publication, and throws under the same rules — resources are shut down exactly once; admission is atomic with the closed transition so a racing call is rejected or cancelled, never lost; idempotent; later calls throw `IllegalStateException` | transport | `HttpJevClientTest.closeWaitsThenCancelsAndRejectsNewCalls`, `closeCompletesQueuedFuturesBeforeTheirWorkerEverRuns`, `closeWaitsForPublicationButNotForCallbacks`, `resultsAreAlwaysTerminalWhenCloseReturns` (200 trials), `publicationTimeoutMakesCloseThrowAfterShuttingDownOwnedResources`, `interruptedPublicationWaitMakesCloseThrowAndReassertTheFlag`, `explicitCancellationReleasesTrackingInEveryState`, `concurrentCloseObservesTheFirstClosersOutcome`, `repeatedCloseAfterSuccessfulShutdownReturnsNormally`, `interruptedConcurrentCloserThrowsAndReassertTheFlag`, `closeIsBoundedDespiteBlockingCallback`, `callRacingCloseNeverEscapesShutdown`, `closeWithSdkOwnedResourcesTerminatesThem` |
| [x] | Redirects | — | httpx: not followed | fetch: followed | never followed, for the SDK-owned transport **and** injected ones: the builder rejects an `HttpClient` whose policy is not `Redirect.NEVER`; a 3xx surfaces as `JevApiException` with that status | transport | `HttpJevClientTest.redirectsAreNeverFollowedAndInjectedTransportsMustAgree` |
| [x] | Logging levels and redaction | — | `authorization`, `proxy-authorization`, `x-api-key`, `api-key`, `cookie`, `set-cookie` + names containing `token`/`secret` | credential headers with suffix kept | every diagnostic (retries included) gated by the client's level; attempt summaries at DEBUG carry only exception class, status, attempt and delay; headers, bodies and body-derived error messages at TRACE only; full redaction of Python's list plus `token`/`secret` names on request and response headers; `ClientConfig.toString()` omits the key and redacts default headers | config | `HttpJevClientTest.loggingHonoursTheClientLevelAndRedactsCredentials` (captures JUL records at OFF/INFO/DEBUG/TRACE), `debugRetryLogsCarryNoBodyText`, `configToStringRedactsCredentials` |
| [ ] | Browser refusal | — | n/a | `dangerouslyAllowBrowser` | n/a | config | — |
| [ ] | `extra_body` | schema does not forbid extra request properties | shallow-merged, last-write-wins | forwarded | **out of scope in v0.x** | config | — |

Mock server: the JDK's `com.sun.net.httpserver.HttpServer` (`TestServer`), chosen over WireMock/MockWebServer
because it needs no dependency and can stall headers or a half-sent body precisely.

## Live probes (run 21 Sep 2026 against `api.typesafe.ai`, model `jev-1.13.0`)

Observed with a real key via `LiveApiTest` (`JEV_RUN_LIVE_TESTS=1`) plus a throwaway raw-JSON probe
for shapes the Java builders cannot express.

| Probe | Question | Result |
|---|---|---|
| `GET /v1/models` + one tiny systemone call | returned `model` is a versioned id | **yes**: `jev-1.13.0`; `usage` present (284/20 tokens for a one-noul call); `/v1/models` lists `jev-latest` and `jev-preview` |
| `release_date` format | docs/OpenAPI say `YYYY-MM-DD` | **ISO-8601 timestamp** with offset (`2026-09-10T18:38:01.391457+00:00`); Java keeps it as a `String` — do not parse as a date |
| 11-level score | is the docs' 10-level limit enforced? | **yes, HTTP 400** `Too many score levels. Must have at most 10 levels.` (not 422, not in the schema) |
| 256-option choice | is the docs' 255-option limit enforced? | **yes, HTTP 400** `Too many choices. Must have at most 255 choices.` |
| 1-level score (raw JSON) | server minimum | **accepted (200)**: legend `{"0": …}`, score 0.0, confidence 1.0. The server minimum is 1 (= schema `minItems: 1`, = Python); Java's ≥2 follows the docs' recommendation and the JS SDK |
| empty choice criteria | server minimum | **HTTP 400** `Choice question must have at least one choice: c`; not enforced client-side (= both SDKs) |
| `instructions` omitted vs explicit `null` | identical answers? | **equivalent**: 0.91/0.91/0.91 vs 0.91/0.91/0.92 over three paired calls (run-to-run variance ±0.01) |
| noul `criteria: null` (raw JSON) | accepted? | **accepted (200)**; Java's omission is equivalent |
| partial noul criteria (`true` only) | accepted? | **accepted (200)** |
| unknown field on a question (raw JSON) | accepted? | **accepted (200), ignored** — the schema is open; Java builders simply cannot produce one |
| `state: null` (raw JSON) | rejected? | **HTTP 422** `Field required` at `body.state` (null is treated as missing) |
| `null` score level (raw JSON) | rejected? | **HTTP 422** at `body.questions.s.score.criteria.0` (must be string/object/array) |
| array state | accepted? | **accepted (200)** |
| invalid API key | 401 or 403? | **401** `Cannot authenticate with the server. Please check your API key and try again.` (body `{"detail": {"error_type": "authentication_error", "message": …}}`) |
| missing API key (no header) | | **403** `Must supply an API key! Check your request and try again.` (same body shape) — so 403 can mean "no key", 401 "bad key" |
