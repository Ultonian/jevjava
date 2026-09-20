package net.codefinch.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The options of a {@link ChoiceQuestion}: labels in insertion order, each with a description or
 * {@link Content#NULL} for a label the model should interpret by its name alone.
 *
 * <p>The server requires at least one option and accepts at most 255, rejecting both violations
 * with HTTP 400 ({@link JevBadRequestException}); like both official SDKs, neither bound is checked
 * client-side.
 */
public final class ChoiceCriteria {
  private final Map<String, Content> options;

  private ChoiceCriteria(Map<String, Content> options) {
    this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
  }

  /** Starts building a set of options. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Options from a map whose values are descriptions ({@code String}, {@code Map}, {@code List},
   * {@link Content}) or {@code null} for undescribed labels. Insertion order is preserved; use a
   * {@link LinkedHashMap} or the builder when order matters.
   */
  public static ChoiceCriteria of(Map<String, ?> options) {
    Objects.requireNonNull(options, "options");
    Builder builder = new Builder();
    for (Map.Entry<String, ?> entry : options.entrySet()) {
      builder.option(entry.getKey(), toContent(entry.getValue()));
    }
    return builder.build();
  }

  /** Labels mapped to their descriptions, in insertion order. Unmodifiable. */
  public Map<String, Content> options() {
    return options;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ChoiceCriteria other && options.equals(other.options);
  }

  @Override
  public int hashCode() {
    return options.hashCode();
  }

  @Override
  public String toString() {
    return "ChoiceCriteria" + options;
  }

  private static Content toContent(Object value) {
    return switch (value) {
      case null -> Content.NULL;
      case Content c -> c;
      case String s -> Content.of(s);
      case Map<?, ?> m -> Content.fromJson(net.codefinch.jev.internal.Json.toTree(m));
      case java.util.List<?> l -> Content.of(l);
      default ->
          throw new IllegalArgumentException(
              "description must be a String, Map, List, Content or null; got "
                  + value.getClass().getName());
    };
  }

  /** Builds {@link ChoiceCriteria} one option at a time. */
  public static final class Builder {
    private final Map<String, Content> options = new LinkedHashMap<>();

    private Builder() {}

    /** An option interpreted by its label alone (sent as JSON {@code null}). */
    public Builder option(String label) {
      return option(label, Content.NULL);
    }

    /** An option with a text description. */
    public Builder option(String label, String description) {
      return option(label, Content.of(description));
    }

    /** An option with a content description ({@link Content#NULL} for undescribed). */
    public Builder option(String label, Content description) {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(description, "description");
      if (label.isEmpty()) {
        throw new IllegalArgumentException("option label must not be empty");
      }
      if (options.putIfAbsent(label, description) != null) {
        throw new IllegalArgumentException("duplicate option label: " + label);
      }
      return this;
    }

    /** Finishes the criteria. */
    public ChoiceCriteria build() {
      return new ChoiceCriteria(options);
    }
  }
}
