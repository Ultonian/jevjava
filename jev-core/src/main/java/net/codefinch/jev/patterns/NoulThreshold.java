package net.codefinch.jev.patterns;

import net.codefinch.jev.NoulAnswer;

/**
 * Routes a Noul answer three ways on its probability of yes: {@code YES} at or above {@code yes},
 * {@code NO} at or below {@code no}, otherwise {@code UNSURE}. Thresholds are caller-supplied:
 * raise {@code yes} when acting on a false yes is expensive, lower {@code no} when missing a true
 * yes is expensive.
 *
 * @param no at or below this, treat as no
 * @param yes at or above this, treat as yes
 */
public record NoulThreshold(double no, double yes) {

  /** The routing decision. */
  public enum Decision {
    /** Probability at or above the yes threshold. */
    YES,
    /** Probability at or below the no threshold. */
    NO,
    /** In between: let a person decide. */
    UNSURE
  }

  /** Validates {@code 0 <= no <= yes <= 1}, both finite. */
  public NoulThreshold {
    Thresholds.requireOrdered(no, yes, "no", "yes");
  }

  /** A threshold pair. */
  public static NoulThreshold of(double no, double yes) {
    return new NoulThreshold(no, yes);
  }

  /** A single cut-off: yes at or above it, no below (no unsure band). */
  public static NoulThreshold at(double cutoff) {
    Thresholds.requireUnit(cutoff, "cutoff");
    return new NoulThreshold(Math.nextDown(cutoff) < 0 ? 0 : Math.nextDown(cutoff), cutoff);
  }

  /** The decision for a probability in {@code [0, 1]}. */
  public Decision decide(double probability) {
    Thresholds.requireUnit(probability, "probability");
    if (probability >= yes) {
      return Decision.YES;
    }
    return probability <= no ? Decision.NO : Decision.UNSURE;
  }

  /** The decision for a noul answer. */
  public Decision decide(NoulAnswer answer) {
    return decide(answer.noul());
  }
}
