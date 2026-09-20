/**
 * Unofficial Java client for TypeSafe AI's Jev System One API.
 *
 * <p>The public surface is small: build a {@link net.codefinch.jev.Questions} set, wrap the input
 * in a {@link net.codefinch.jev.State}, submit a {@link net.codefinch.jev.SystemOneRequest} through
 * a {@link net.codefinch.jev.JevClient}, and read the sealed {@link net.codefinch.jev.Answer}
 * values off the {@link net.codefinch.jev.SystemOneResponse}.
 *
 * <p>This project is not affiliated with TypeSafe AI. Behavioural parity with the official SDKs is
 * documented row by row in {@code docs/PARITY.md}.
 */
package net.codefinch.jev;
