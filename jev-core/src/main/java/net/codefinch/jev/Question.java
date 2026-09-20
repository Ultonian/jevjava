package net.codefinch.jev;

import java.util.Optional;

/** One question in a request. Exactly one of the three System One primitives. */
public sealed interface Question permits NoulQuestion, ChoiceQuestion, ScoreQuestion {

  /** The wire {@code type} discriminator: {@code noul}, {@code choice} or {@code score}. */
  String type();

  /**
   * The question as content. Empty means the {@code instructions} key is omitted from the wire;
   * {@link Content#NULL} means an explicit JSON {@code null} is sent.
   */
  Optional<Content> instructions();
}
