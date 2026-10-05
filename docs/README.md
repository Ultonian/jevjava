# SDK documentation

Jev Java Unofficial SDK (`jevjavauosdk`) is an independent Java 21+ client for the Jev System One
API. It is not affiliated with, endorsed by, or supported by TypeSafe AI.

- [Install and make a request](../README.md#usage): dependencies, client configuration and typed answers.
- [Choose the right imports](../README.md#packages): client, model, exception and routing packages.
- [Handle errors and retries](../README.md#errors-and-retries): failure types, retry policy and deadlines.
- [Add metrics or test without the API](../README.md#metrics-and-testing): Micrometer and the recording client.
- [Run the examples](../jev-examples/README.md): eight complete application patterns, with a fake or the live service.
- [Understand compatibility](PARITY.md): validation, lifecycle behavior and differences from the pinned official SDKs.
- [Inspect the pinned API schema](upstream/README.md): the reference contract used during implementation.

The SDK is pre-release and must currently be installed from source. Java 21 is the minimum runtime;
CI requires verification on Temurin JDK 21 and 25. Rolling latest-GA checks and weekly EA probes
are advisory; their failures are reported without expanding the required support matrix. Maven coordinates remain `net.codefinch.jev:jev-core` despite the repository
rename. Public imports and behavior may change before the first release.

For development setup and live-test instructions, see [CONTRIBUTING](../CONTRIBUTING.md).
Report suspected vulnerabilities privately through [SECURITY](../SECURITY.md).
