# Release strategy

Draft for review. The branch, packaging and publication automation described here still needs
implementing. The first proposed release is `0.1.0`.

## Branches and versions

Keep `main` open for development. Cut a release branch when the intended feature set is complete
and its starting commit has passed CI; stabilize and qualify the release there.

| Ref | Purpose | First-release example |
|---|---|---|
| `main` | Features and fixes for the next minor release | Move to `0.2.0-SNAPSHOT` after the cut |
| `release/0.1` | Stabilization, then maintenance of the 0.1 line | `0.1.0-SNAPSHOT`, then candidate `0.1.0`, then `0.1.1-SNAPSHOT` |
| `v0.1.0` | Immutable, approved release commit | Created only after candidate qualification |

Version the parent and all modules together. Patch releases preserve compatibility; during 0.x,
put intentional breaking changes in a new minor release and document migration steps. Keeping a
release branch does not promise indefinite maintenance of every line.

Release branches accept fixes, dependency/security corrections and release documentation.
Normally fix `main` first and cherry-pick the relevant commit with `-x`. If a release fix lands
first, forward-port it promptly. Keep version bumps branch-specific; do not merge all of `main`
into a release branch. Require reviewed PRs and passing checks on both branches, and protect
stable tags against replacement or deletion.

## Candidate to release

1. **Cut:** create `release/0.1` from a green, recorded commit. Bump `main` to the next snapshot
   in a separate commit so normal development can continue.
2. **Prepare:** on the release branch, set the final Maven version (`0.1.0`), finalize the
   changelog, dependency examples and SCM metadata, and set a fixed reproducible-build timestamp.
   Build from a clean committed checkout on a pinned JDK 21 distribution and patch version.
3. **Archive:** build and sign one publication bundle. Identify it as candidate 1 using its full
   commit SHA, workflow run ID and SHA-256 hashes. Retain the exact SDK artifacts, benchmark JAR,
   source archive and build logs. A candidate is an internal label, not a public Maven version
   or stable Git tag.
4. **Qualify:** complete the checks and benchmark protocol below against that candidate.
   Any change to released code, dependencies or packaging creates a new candidate and resets
   qualification. Retain failed candidates and their results for diagnosis.
5. **Validate with Central:** upload the qualified bundle as a user-managed deployment and wait
   for validation. Record its deployment ID. Validation does not publish it; the Portal supports
   a separate promotion operation. See the [Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
6. **Release:** review the evidence, create the annotated `v0.1.0` tag at the exact candidate
   commit, then explicitly authorize publication of that validated deployment. Verify the tag,
   commit, version and bundle hashes agree. Publish the existing bundle without rebuilding it.
7. **Confirm:** wait for Central publication, resolve all three libraries from a fresh Maven
   repository and run the consumer smoke test again. Publish the GitHub release with installation
   instructions, changelog and benchmark summary. Bump the maintenance branch to `0.1.1-SNAPSHOT`.

Using the final version inside an unpublished candidate avoids an RC-to-final rebuild: this SDK
embeds its version in a resource used by request headers, so changing `-rc.1` to the final version
would change the tested artifacts. Keep candidates out of shared snapshot repositories and
developers' normal Maven caches.

If publication fails after tagging, retry promotion of the same deployment after inspecting its
state; do not replace the tag or silently rebuild. A defect requiring changed artifacts after a
stable tag gets a new version. Published Central components cannot be replaced or removed through
the normal release process. See [Central immutability](https://central.sonatype.org/publish/requirements/immutability/).

## Release checks

- Run the full Maven verification on JDK 21 and 25, repository pre-commit checks, Trivy and CodeQL
  on the candidate SHA. A failed scanner download is an incomplete check, not a pass. Retry
  transient failures with bounded backoff and respect server retry instructions.
- Build the release artifacts once on JDK 21. Separately test those exact JARs on JDK 21 and 25
  using a small consumer outside the reactor and an isolated Maven repository populated from the
  bundle. Exercise core requests, the recording fake and Micrometer integration without live HTTP.
- Inspect the published POMs and dependency graph: no snapshot dependencies, resolvable parent/BOMs,
  correct Java baseline, license, project/developer/SCM metadata, and no benchmark dependencies in
  the libraries. Verify signatures and that sources and Javadoc match the binary artifacts.
- Review the public API, README examples, release notes and known limitations. From the second
  release onward, compare API compatibility with the previous release; introduce an automated
  compatibility check before it becomes a recurring manual task.
- Run the existing opt-in live probes once before the first release using protected credentials
  and retain a sanitized result. This checks current service compatibility; performance tests
  remain local and need no API key. An unavailable service leaves that check explicitly pending.

## Benchmark the candidate before tagging

Run after functional stabilization, on a quiet dedicated machine rather than a shared CI runner.
Use the [existing benchmark commands](../jev-benchmarks/README.md) and archive the benchmark JAR
built from the same clean candidate checkout. Verify its SDK class/resource entries match the
release JARs; the current study scripts cannot prove build provenance from a supplied JAR alone.

Use one archived benchmark JAR across pinned JDK 21 and 25 runtimes from the same OpenJDK vendor
(Temurin is already used in CI). Record exact runtime builds and hashes. JDK 27 is an optional
additional result; it does not extend the tested support matrix by itself.

| Workload, per JDK | Protocol | Approximate timed cost |
|---|---|---|
| Components | `RunBenchmarks OUTPUT baseline timing core`, then a separate `baseline gc core` | 20 minutes |
| Local HTTP | Five independent `RunLoad OUTPUT baseline representative` passes; three forks per cell | 30 minutes |

Allow about 100 minutes across both JDKs, plus startup and analysis. Each invocation needs a fresh
output directory. Pin the workload to a recorded set of eight physical cores, account for their
SMT siblings, finish builds first, and record host load/frequency plus the runners' heap/GC settings.
Do not run other tests or benchmarks concurrently.

The first release establishes a baseline. Summarize throughput, p50/p95/p99 latency, allocation
and variability separately by workload and runtime. Average fork rates within each HTTP pass,
then report variation across the five pass means; do not treat individual calls as independent
replicates. Label averaged trial percentiles as such, rather than pooled latency percentiles.
Retain all passes, including unfavorable ones.

Correctness/accounting failures block qualification. Investigate unstable results and material
unexpected changes before signing off; no automatic performance percentage gate exists yet.
The `representative` group lacks a same-fork concurrency-1 control, so it cannot establish server
headroom. Do not turn its transport comparison into a claim about maximum SDK capacity.

For a later release comparison, first declare the practical effect of interest and a paired,
interleaved old/new protocol using matched harness, dependencies where applicable, host and runtime.
The current `run-study.py` supports identical-source A/A only; a real A/B requires reviewed tooling
changes. Historical results from different dependency or runtime builds are context, not a matched
baseline. Use immediate transport or windowed JFR only when a specific result needs explaining.

Keep raw runs out of Git. Attach a compressed evidence archive to the eventual GitHub release,
including raw results, manifests, source/JAR hashes and host/runtime details. Put a concise results
table and limitations in the release notes. CI artifacts can hold candidates, but retain accepted
release evidence durably rather than relying on their expiry window.

## Publishing setup to implement before the cut

1. **Central access:** verify a namespace covering `net.codefinch.jev`, confirm its ownership, and
   configure a Portal token and signing key in a protected release environment. Namespace access
   is a prerequisite, not something this repository proves. See [namespace registration](https://central.sonatype.org/register/namespace/).
2. **Packaging profile:** attach source/Javadoc JARs and signatures; pin the Central publishing
   plugin. Publish `jev-parent` plus `jev-core`, `jev-test` and `jev-micrometer`. Explicitly exclude
   `jev-examples` and `jev-benchmarks` from the Central bundle and assert its contents in a dry run;
   do not rely solely on their existing `maven.deploy.skip` flags. The parent POM must be published
   because consumers inherit it. See [artifact requirements](https://central.sonatype.org/publish/requirements/).
3. **Candidate workflow:** add release-branch CI coverage (`release/**`; pushes currently cover
   only `main`) and a manually dispatched candidate build. Use the publishing plugin's
   `skipPublishing` mode to create a bundle without uploading. Keep automatic publication disabled.
   See [plugin configuration](https://central.sonatype.org/publish/publish-portal-maven/).
4. **Promotion workflow:** accept the stable tag and recorded candidate/deployment identity,
   enforce successful checks and artifact hashes, and require release-environment approval before
   publication. Use restricted credentials and serialize publication per version. Retrying must
   inspect existing deployment state before uploading or publishing again.
5. **Rehearsal:** produce an unpublished bundle, inspect its exact module list, signatures and
   metadata, and run the isolated consumer tests. Make security scanning reliable under repository
   throttling without suppressing errors. Then cut the first release branch and qualify its candidate.

Snapshot publication from `main`, public RC artifacts and a general performance regression service
can wait until there is a concrete need. The first release needs a dependable candidate, evidence
and promotion path.
