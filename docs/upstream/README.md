# Upstream reference material

- `openapi-0.2.0-2026-09-20.json` — verbatim copy of `https://api.typesafe.ai/openapi.json`
  fetched 20 Sep 2026 (`info.version` 0.2.0, SHA-256 `a191f8a7df6bd6fedced8120dd0fd106f88575d1d1c8360d08900a6c7c0360d5`).
  The authority on wire legality; see [PARITY.md](../PARITY.md).

Test fixtures derived from the pinned upstream test suites live next to the tests, in
`jev-core/src/test/resources/fixtures/`:

| Fixture | Source |
|---|---|
| `requests/js-null-preservation.json` | `typesafe-sdk-js` `test/client.test.ts`, "preserves null state, instructions, and criteria values" |
| `requests/js-omitted-instructions-and-arrays.json` | `typesafe-sdk-js` `test/client.test.ts`, "allows omitted instructions and preserves JSON arrays" |
| `responses/python-unknown-fields.json` | `typesafe-sdk-python` `tests/test_responses.py` (unknown fields ignored) |
| `responses/python-unknown-answer-type.json` | `typesafe-sdk-python` `tests/test_responses.py` (unknown answer type skipped) |
| `responses/python-structured-legend.json` | `typesafe-sdk-python` `tests/test_responses.py` (structured legend) |
| `responses/usage-empty.json` | Python public `Usage` accepts this; the API schema and Java do not |
| `responses/docs-all-three.json` | shapes from https://docs.typesafe.ai/api |
| `responses/models.json` | OpenAPI `ModelMetadataList` example, plus an extra field |

The malformed-response cases in `ResponseParserTest` and the error-message cases in
`JevApiExceptionTest` are transcribed inline from `tests/test_responses.py`, `tests/test_errors.py`
(Python) and `test/errors.test.ts` (JavaScript) at the pinned commits.
