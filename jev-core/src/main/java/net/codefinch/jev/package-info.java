/**
 * Jev Java Unofficial SDK (jevjavauosdk), an independently maintained client for TypeSafe AI's Jev
 * System One API.
 *
 * <p>The public surface is small: build a {@link net.codefinch.jev.model.Questions} set, wrap the
 * input in a {@link net.codefinch.jev.model.State}, submit a {@link
 * net.codefinch.jev.model.SystemOneRequest} through a {@link net.codefinch.jev.JevClient}, and read
 * the sealed {@link net.codefinch.jev.model.Answer} values off the {@link
 * net.codefinch.jev.model.SystemOneResponse}.
 *
 * <p>This project is not affiliated with, endorsed by, or supported by TypeSafe AI. Behavioural
 * parity with the official SDKs is documented row by row in {@code docs/PARITY.md}.
 */
package net.codefinch.jev;
