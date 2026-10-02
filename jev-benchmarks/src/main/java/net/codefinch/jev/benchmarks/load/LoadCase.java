package net.codefinch.jev.benchmarks.load;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Deliberately selected cells, not a Cartesian product of configuration dimensions. */
public record LoadCase(Submission submission, int concurrency, Variant variant, boolean control) {
  /** Caller invocation style. */
  public enum Submission {
    SYNC,
    ASYNC
  }

  /** One change from the representative reused-client baseline at a time. */
  public enum Variant {
    BASE,
    IMMEDIATE,
    LARGE,
    DELAY10,
    DELAY100,
    BODY10,
    NOOP,
    MICROMETER,
    PLATFORM,
    LOGGING,
    MODELS,
    ERROR400,
    ERROR503,
    COLD,
    OFFSET_NOOP,
    OFFSET_MICROMETER
  }

  /** Reject invalid cells before creating any client or server. */
  public LoadCase {
    java.util.Objects.requireNonNull(submission);
    java.util.Objects.requireNonNull(variant);
    if (!List.of(1, 8, 32, 128).contains(concurrency)
        || (variant == Variant.IMMEDIATE && (concurrency != 8 || control))
        || (variant == Variant.PLATFORM && submission != Submission.ASYNC)
        || (variant == Variant.COLD && (concurrency != 1 || submission != Submission.SYNC))) {
      throw new IllegalArgumentException("Invalid load cell");
    }
  }

  /** Stable file-safe cell identity. */
  public String id() {
    return (control ? "control-" : "sdk-")
        + submission.name().toLowerCase(java.util.Locale.ROOT)
        + "-"
        + concurrency
        + "-"
        + variant.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Prepared content shape. */
  public String payload() {
    return variant == Variant.LARGE ? "content256k" : "ticket";
  }

  /** Delay before sending headers. */
  public int headerDelayMillis() {
    return switch (variant) {
      case DELAY10 -> 10;
      case DELAY100 -> 100;
      default -> 0;
    };
  }

  /** Delay after headers, before sending response bytes. */
  public int bodyDelayMillis() {
    return variant == Variant.BODY10 ? 10 : 0;
  }

  /** Fixed status for a workload; clean baselines never retry. */
  public int status() {
    return switch (variant) {
      case ERROR400 -> 400;
      case ERROR503 -> 503;
      default -> 200;
    };
  }

  /** Whether signed completion-offset instrumentation is selected. */
  public boolean offsets() {
    return variant == Variant.OFFSET_NOOP || variant == Variant.OFFSET_MICROMETER;
  }

  /** Whether a metrics registry participates in observer work. */
  public boolean metrics() {
    return variant == Variant.MICROMETER || variant == Variant.OFFSET_MICROMETER;
  }

  /** Whether there is any SDK observer; the baseline installs none. */
  public boolean observed() {
    return !control && (variant == Variant.NOOP || metrics() || offsets());
  }

  /** Transport-only async comparison with matching payload, delay, status and concurrency. */
  public LoadCase controlCase() {
    Variant transport =
        switch (variant) {
          case NOOP, MICROMETER, PLATFORM, LOGGING, OFFSET_NOOP, OFFSET_MICROMETER -> Variant.BASE;
          default -> variant;
        };
    return new LoadCase(
        variant == Variant.COLD ? Submission.SYNC : Submission.ASYNC, concurrency, transport, true);
  }

  /** Selected workloads, with matching controls first; durations/forks are set by the launcher. */
  public static List<LoadCase> selection(String group) {
    List<LoadCase> sdk = new ArrayList<>();
    switch (group) {
      case "immediate" -> {
        return List.of(
            new LoadCase(Submission.SYNC, 8, Variant.IMMEDIATE, false),
            new LoadCase(Submission.ASYNC, 8, Variant.IMMEDIATE, false));
      }
      case "async-executor" -> {
        return List.of(
            new LoadCase(Submission.ASYNC, 8, Variant.BASE, false),
            new LoadCase(Submission.ASYNC, 8, Variant.PLATFORM, false),
            new LoadCase(Submission.SYNC, 8, Variant.BASE, false));
      }
      case "representative" -> {
        sdk.add(new LoadCase(Submission.SYNC, 8, Variant.BASE, false));
        sdk.add(new LoadCase(Submission.ASYNC, 8, Variant.BASE, false));
      }
      case "core", "matrix" -> {
        for (Submission style : Submission.values()) {
          for (int concurrency : List.of(1, 8, 32, 128)) {
            sdk.add(new LoadCase(style, concurrency, Variant.BASE, false));
          }
        }
        if (group.equals("matrix")) {
          for (Variant variant :
              List.of(
                  Variant.LARGE,
                  Variant.DELAY10,
                  Variant.DELAY100,
                  Variant.BODY10,
                  Variant.NOOP,
                  Variant.MICROMETER,
                  Variant.PLATFORM,
                  Variant.LOGGING,
                  Variant.MODELS,
                  Variant.ERROR400,
                  Variant.ERROR503)) {
            sdk.add(new LoadCase(Submission.ASYNC, 32, variant, false));
          }
          sdk.add(new LoadCase(Submission.SYNC, 1, Variant.COLD, false));
        }
      }
      case "offsets" -> {
        for (Variant variant : List.of(Variant.OFFSET_NOOP, Variant.OFFSET_MICROMETER)) {
          sdk.add(new LoadCase(Submission.ASYNC, 32, variant, false));
        }
      }
      case "pinning" -> {
        sdk.add(new LoadCase(Submission.SYNC, 32, Variant.DELAY10, false));
        sdk.add(new LoadCase(Submission.ASYNC, 32, Variant.DELAY10, false));
      }
      default ->
          throw new IllegalArgumentException(
              "Expected representative|core|matrix|offsets|pinning|async-executor|immediate");
    }
    LinkedHashSet<LoadCase> all = new LinkedHashSet<>();
    if (!group.equals("offsets") && !group.equals("pinning")) {
      sdk.stream().map(LoadCase::controlCase).forEach(all::add);
    }
    all.addAll(sdk);
    return List.copyOf(all);
  }
}
