package net.codefinch.jev.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.codefinch.jev.Answer;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.SystemOneResponse;

/**
 * A {@link CallObserver} that records Micrometer meters.
 *
 * <p>Meters (with {@code prefix} = {@code jev} by default):
 *
 * <ul>
 *   <li>{@code jev.call} — timer per call, the whole operation including retries, backoff and
 *       queueing; tags {@code operation}, {@code outcome}, {@code status}, {@code model}.
 *   <li>{@code jev.attempt} — timer per HTTP attempt; tags {@code operation}, {@code status},
 *       {@code attempt}, {@code outcome} ({@code success}/{@code error}).
 *   <li>{@code jev.tokens} — counter of {@code usage} tokens; tags {@code type} ({@code
 *       input}/{@code output}), {@code model}.
 *   <li>{@code jev.confidence} — distribution summary of Choice/Score confidence, and {@code
 *       jev.noul} of Noul probabilities, tagged {@code question} and {@code type} — <strong>only
 *       for question ids in the allowlist</strong>, because ids are caller-controlled and would
 *       otherwise create unbounded series.
 * </ul>
 *
 * <p>Tag values are bounded: {@code operation} has two values, {@code outcome} four, {@code status}
 * the HTTP statuses seen (or {@code n/a}), {@code attempt} is capped by the retry policy, and
 * {@code model} is the versioned model id returned by the server (or {@code unknown}).
 */
public final class JevMetrics implements CallObserver {
  private final MeterRegistry registry;
  private final String prefix;
  private final Set<String> questionTags;
  private final Tags commonTags;

  private JevMetrics(Builder b) {
    this.registry = b.registry;
    this.prefix = b.prefix;
    this.questionTags = Collections.unmodifiableSet(new HashSet<>(b.questionTags));
    this.commonTags = b.commonTags;
  }

  /** Starts configuring metrics for a registry. */
  public static Builder builder(MeterRegistry registry) {
    return new Builder(registry);
  }

  /** Metrics with all defaults (no question tags). */
  public static JevMetrics of(MeterRegistry registry) {
    return builder(registry).build();
  }

  /** The question ids for which confidence/probability summaries are recorded. */
  public Set<String> questionTags() {
    return questionTags;
  }

  @Override
  public void onAttempt(Attempt attempt) {
    Timer.builder(prefix + ".attempt")
        .description("One HTTP attempt against the Jev API")
        .tags(commonTags)
        .tag("operation", attempt.operation())
        .tag("status", status(attempt.status().isPresent() ? attempt.status().getAsInt() : -1))
        .tag("attempt", Integer.toString(attempt.attempt()))
        .tag("outcome", attempt.failure().isPresent() ? "error" : "success")
        .register(registry)
        .record(attempt.elapsed());
  }

  @Override
  public void onCall(Call call) {
    String model = call.model().orElse("unknown");
    Timer.builder(prefix + ".call")
        .description("One Jev call, including retries, backoff and queueing")
        .tags(commonTags)
        .tag("operation", call.operation())
        .tag("outcome", call.outcome().name().toLowerCase(Locale.ROOT))
        .tag("status", status(call.status().isPresent() ? call.status().getAsInt() : -1))
        .tag("model", model)
        .register(registry)
        .record(call.elapsed());
    call.response().ifPresent(response -> recordResponse(response, model));
  }

  private void recordResponse(SystemOneResponse response, String model) {
    tokens("input", model).increment(response.usage().inputTokens());
    tokens("output", model).increment(response.usage().outputTokens());
    if (questionTags.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Answer> e : response.answers().asMap().entrySet()) {
      if (!questionTags.contains(e.getKey())) {
        continue;
      }
      switch (e.getValue()) {
        case NoulAnswer n -> summary(prefix + ".noul", e.getKey(), "noul", model).record(n.noul());
        case ChoiceAnswer c ->
            summary(prefix + ".confidence", e.getKey(), "choice", model).record(c.confidence());
        case ScoreAnswer s ->
            summary(prefix + ".confidence", e.getKey(), "score", model).record(s.confidence());
      }
    }
  }

  private Counter tokens(String type, String model) {
    return Counter.builder(prefix + ".tokens")
        .description("Tokens reported in usage")
        .tags(commonTags)
        .tag("type", type)
        .tag("model", model)
        .register(registry);
  }

  private DistributionSummary summary(String name, String question, String type, String model) {
    return DistributionSummary.builder(name)
        .description(
            "Answer confidence (choice/score) or probability (noul) per allowlisted question")
        .tags(commonTags)
        .tag("question", question)
        .tag("type", type)
        .tag("model", model)
        .scale(1.0)
        .register(registry);
  }

  private static String status(int status) {
    return status < 0 ? "n/a" : Integer.toString(status);
  }

  /** Builds {@link JevMetrics}. */
  public static final class Builder {
    private final MeterRegistry registry;
    private String prefix = "jev";
    private final Set<String> questionTags = new HashSet<>();
    private Tags commonTags = Tags.empty();

    private Builder(MeterRegistry registry) {
      this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Meter name prefix; default {@code jev}. */
    public Builder prefix(String prefix) {
      Objects.requireNonNull(prefix, "prefix");
      if (prefix.isBlank()) {
        throw new IllegalArgumentException("prefix must not be blank");
      }
      this.prefix = prefix;
      return this;
    }

    /**
     * Question ids whose answers get a per-question confidence/probability summary. Off by default;
     * only allowlist ids you control, since each adds a meter series.
     */
    public Builder questionTags(Set<String> ids) {
      Objects.requireNonNull(ids, "ids");
      ids.forEach(id -> questionTags.add(Objects.requireNonNull(id, "id")));
      return this;
    }

    /** Tags added to every meter (e.g. {@code service}). */
    public Builder commonTags(Iterable<Tag> tags) {
      this.commonTags = Tags.of(tags);
      return this;
    }

    /** Finishes the metrics observer. */
    public JevMetrics build() {
      return new JevMetrics(this);
    }
  }
}
