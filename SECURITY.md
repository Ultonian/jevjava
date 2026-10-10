# Security policy

## Report privately

Use [GitHub private vulnerability reporting](https://github.com/Ultonian/jevjavauosdk/security/advisories/new)
for suspected vulnerabilities in this SDK. Private reporting is enabled for this repository.
Do not open a public issue or pull request containing vulnerability details.

Include the SDK version or commit, JDK version, affected module, expected impact and a minimal
sanitized reproducer. Replace API keys and private request/response data with synthetic values.
This is an independently maintained unofficial SDK; service-side problems belong with the service
operator. The maintainer will assess reports and coordinate disclosure, without a guaranteed
response time.

## Supported versions

No stable version has been released yet. During development, report issues against the current
`main` commit. Once releases begin, security fixes target the latest patch of the latest released
0.x minor line. Older minor lines and historical snapshots have no maintenance commitment.

## Checks and data handling

Pre-commit and CI run Trivy using [the repository policy](trivy.yaml). The scan fails on
HIGH/CRITICAL vulnerabilities with available fixes, misconfigurations and secrets; it is not a
guarantee that the SDK or its dependencies are vulnerability-free. See
[CONTRIBUTING](CONTRIBUTING.md#setup-and-checks) for coverage and exclusions.

The SDK validates API keys before constructing authorization headers and never follows redirects.
Credential-header redaction does not redact application data in DEBUG/TRACE bodies or exceptions.
Review and sanitize diagnostics before sharing them, even in a private report. If a real key has
been exposed, revoke or rotate it through the service provider.
