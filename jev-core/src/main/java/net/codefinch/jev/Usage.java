package net.codefinch.jev;

/**
 * Token usage for one request. Both counters are required by the API schema; a response missing
 * either fails validation (a deliberate divergence from the Python SDK, whose public model treats
 * them as optional).
 *
 * @param inputTokens billable input tokens used to evaluate the request
 * @param outputTokens output tokens used to answer the questions
 */
public record Usage(long inputTokens, long outputTokens) {}
