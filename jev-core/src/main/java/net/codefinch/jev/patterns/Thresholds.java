package net.codefinch.jev.patterns;

/** Shared validation for values in the unit interval. */
final class Thresholds {
  private Thresholds() {}

  static void requireUnit(double value, String name) {
    if (!(value >= 0 && value <= 1)) { // also rejects NaN
      throw new IllegalArgumentException(name + " must be a finite number in [0, 1]; got " + value);
    }
  }

  static void requireOrdered(double lower, double upper, String lowerName, String upperName) {
    requireUnit(lower, lowerName);
    requireUnit(upper, upperName);
    if (lower > upper) {
      throw new IllegalArgumentException(
          lowerName + " (" + lower + ") must not exceed " + upperName + " (" + upper + ")");
    }
  }
}
