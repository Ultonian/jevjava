package net.codefinch.jev.patterns;

import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.ScoreAnswer;

/**
 * Routes a Choice or Score answer three ways on its {@code confidence}: {@code ACT} at or above
 * {@code high}, {@code CONFIRM} at or above {@code low}, otherwise {@code ESCALATE}. Thresholds are
 * caller-supplied; there are no defaults because the right values depend on the cost of being
 * wrong.
 *
 * <p>Not for Noul answers, whose {@code noul} is a probability of yes, not a confidence: use {@link
 * NoulThreshold}.
 *
 * @param low below this, escalate to a person
 * @param high at or above this, act without confirmation
 */
public record ConfidenceGate(double low, double high) {

  /** The routing decision. */
  public enum Decision {
    /** Confident enough to act automatically. */
    ACT,
    /** Plausible; act after confirmation. */
    CONFIRM,
    /** Too uncertain; hand to a person or a slower path. */
    ESCALATE
  }

  /** Validates {@code 0 <= low <= high <= 1}, both finite. */
  public ConfidenceGate {
    Thresholds.requireOrdered(low, high, "low", "high");
  }

  /** A gate with the given thresholds. */
  public static ConfidenceGate of(double low, double high) {
    return new ConfidenceGate(low, high);
  }

  /** The decision for a confidence value in {@code [0, 1]}. */
  public Decision decide(double confidence) {
    Thresholds.requireUnit(confidence, "confidence");
    if (confidence >= high) {
      return Decision.ACT;
    }
    return confidence >= low ? Decision.CONFIRM : Decision.ESCALATE;
  }

  /** The decision for a choice answer. */
  public Decision decide(ChoiceAnswer answer) {
    return decide(answer.confidence());
  }

  /** The decision for a score answer. */
  public Decision decide(ScoreAnswer answer) {
    return decide(answer.confidence());
  }
}
