/**
 * Small composition helpers mirroring the documented patterns: {@link
 * net.codefinch.jev.patterns.FanOut} (one question per item, item bound structurally), {@link
 * net.codefinch.jev.patterns.Composite} (weighted scores), {@link
 * net.codefinch.jev.patterns.ConfidenceGate} and {@link net.codefinch.jev.patterns.NoulThreshold}
 * (three-way routing on confidence or probability). Thresholds are always caller-supplied; the
 * docs' numbers are examples, not defaults.
 */
package net.codefinch.jev.patterns;
