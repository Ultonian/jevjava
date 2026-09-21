/**
 * Test support: {@link net.codefinch.jev.test.RecordingJevClient} implements {@link
 * net.codefinch.jev.JevClient} in memory, returning scripted or generated answers and recording
 * every request, so application code can be tested without the network. Depend on it with {@code
 * test} scope.
 */
package net.codefinch.jev.test;
