package net.codefinch.jev;

/**
 * The answer to a {@link NoulQuestion}: the probability of yes. A noul has no separate confidence,
 * because a two-outcome distribution is fully described by this one number.
 *
 * @param noul probability of yes, in {@code [0, 1]}
 */
public record NoulAnswer(double noul) implements Answer {

  @Override
  public String type() {
    return "noul";
  }
}
