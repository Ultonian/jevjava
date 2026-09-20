package net.codefinch.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The named questions of one request, in insertion order. Question ids are for your code; they are
 * not shown to the model, so each question must carry its complete meaning in its instructions.
 *
 * <p>Never empty: the API requires at least one question, and both official SDKs reject an empty
 * set before sending.
 */
public final class Questions {
  private final Map<String, Question> byId;

  private Questions(Map<String, Question> byId) {
    this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
  }

  /** Starts building a question set. */
  public static Builder builder() {
    return new Builder();
  }

  /** A question set from an ordered map. */
  public static Questions of(Map<String, ? extends Question> questions) {
    Builder builder = new Builder();
    questions.forEach(builder::put);
    return builder.build();
  }

  /** A single-question set. */
  public static Questions of(String id, Question question) {
    return new Builder().put(id, question).build();
  }

  /** The questions by id, in insertion order. Unmodifiable. */
  public Map<String, Question> asMap() {
    return byId;
  }

  /** The ids in insertion order. */
  public Set<String> ids() {
    return byId.keySet();
  }

  /** Number of questions. */
  public int size() {
    return byId.size();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Questions other && byId.equals(other.byId);
  }

  @Override
  public int hashCode() {
    return byId.hashCode();
  }

  @Override
  public String toString() {
    return "Questions" + byId;
  }

  /** Builds a {@link Questions} set. */
  public static final class Builder {
    private final Map<String, Question> byId = new LinkedHashMap<>();

    private Builder() {}

    /** Adds any question. */
    public Builder put(String id, Question question) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(question, "question");
      if (id.isBlank()) {
        throw new IllegalArgumentException("question id must not be blank");
      }
      if (byId.putIfAbsent(id, question) != null) {
        throw new IllegalArgumentException("duplicate question id: " + id);
      }
      return this;
    }

    /** Adds a yes/no question. */
    public Builder noul(String id, String instructions) {
      return put(id, NoulQuestion.of(instructions));
    }

    /** Adds a yes/no question with criteria. */
    public Builder noul(String id, String instructions, NoulCriteria criteria) {
      return put(id, NoulQuestion.of(instructions, criteria));
    }

    /** Adds a choice question. */
    public Builder choice(String id, String instructions, ChoiceCriteria criteria) {
      return put(id, ChoiceQuestion.of(instructions, criteria));
    }

    /** Adds a choice question from a label-to-description map ({@code null} = undescribed). */
    public Builder choice(String id, String instructions, Map<String, ?> options) {
      return put(id, ChoiceQuestion.of(instructions, ChoiceCriteria.of(options)));
    }

    /** Adds a score question with text levels, lowest first. */
    public Builder score(String id, String instructions, List<String> levels) {
      return put(id, ScoreQuestion.of(instructions, levels));
    }

    /** Finishes the set; fails if it is empty. */
    public Questions build() {
      if (byId.isEmpty()) {
        throw new IllegalArgumentException("at least one question is required");
      }
      return new Questions(byId);
    }
  }
}
