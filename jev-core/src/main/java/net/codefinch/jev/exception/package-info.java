/**
 * SDK-specific failures: API responses, response validation, transport errors, operation deadlines,
 * interruption and typed answer access. All extend {@link
 * net.codefinch.jev.exception.JevException}. Argument validation can also throw standard Java
 * exceptions.
 *
 * <p>{@link net.codefinch.jev.RetryPolicy#DEFAULT} retries HTTP 408, 429 and 5xx responses, {@link
 * net.codefinch.jev.exception.JevConnectionException} and per-attempt {@link
 * net.codefinch.jev.exception.JevTimeoutException}, within retry and deadline budgets. Other HTTP
 * statuses and malformed successful responses are not retried by default. {@link
 * net.codefinch.jev.exception.JevInterruptedException} and {@link
 * net.codefinch.jev.exception.JevDeadlineExceededException} are terminal even with a custom retry
 * predicate. Cancellation uses {@link java.util.concurrent.CancellationException} and is also
 * terminal. Missing/wrong-kind answer access is local validation, not an HTTP retry.
 */
package net.codefinch.jev.exception;
