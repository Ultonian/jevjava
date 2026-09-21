/**
 * Micrometer metrics for a {@link net.codefinch.jev.JevClient}: register a {@link
 * net.codefinch.jev.micrometer.JevMetrics} with {@code
 * JevClient.builder().observer(JevMetrics.builder(registry).build())}. Default tags are bounded;
 * per-question tags require an explicit allowlist.
 */
package net.codefinch.jev.micrometer;
