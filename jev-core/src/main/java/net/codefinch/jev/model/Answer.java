package net.codefinch.jev.model;

/**
 * One answer in a response. Sealed so a {@code switch} over the three primitives is exhaustive.
 *
 * <p>Answer kinds the API adds in future are dropped from {@link Answers} (and logged) rather than
 * failing the response; they remain visible in {@link SystemOneResponse#rawBody()}.
 *
 * @since 0.1.0
 */
public sealed interface Answer permits NoulAnswer, ChoiceAnswer, ScoreAnswer {

  /** The wire {@code type} discriminator. */
  String type();
}
