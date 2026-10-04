# Upstream fixture provenance

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

Upstream versions are pinned in the [compatibility reference](../../docs/PARITY.md#pinned-references).
