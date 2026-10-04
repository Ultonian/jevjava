/**
 * Immutable request and response data for the Jev System One API: content, state, questions,
 * criteria, answers, usage and model metadata. Build requests with {@link
 * net.codefinch.jev.model.Questions} and submit them through {@link net.codefinch.jev.JevClient}.
 *
 * <p>Sealed question and answer hierarchies live together here so exhaustive switches remain
 * available to callers. Client configuration stays in {@code net.codefinch.jev}.
 */
package net.codefinch.jev.model;
