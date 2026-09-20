package net.codefinch.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A question that rates the state against an ordered rubric. Level {@code i} of the answer is
 * {@code criteria.get(i)}.
 *
 * <p>Validation follows the JavaScript SDK and the docs: at least two levels (the server itself
 * accepts one). Levels cannot be JSON {@code null} (the API rejects it with 422). The server
 * enforces a maximum of 10 levels with HTTP 400 ({@link JevBadRequestException}); like both
 * official SDKs, that upper bound is not checked client-side.
 *
 * @param instructions the question; see {@link Question#instructions()}
 * @param criteria at least two non-null level descriptions, lowest first
 */
public record ScoreQuestion(Optional<Content> instructions, List<Content> criteria)
    implements Question {

  /** Minimum number of levels accepted client-side. */
  public static final int MIN_LEVELS = 2;

  /** Validates and snapshots the components. */
  public ScoreQuestion {
    Objects.requireNonNull(instructions, "instructions");
    Objects.requireNonNull(criteria, "criteria");
    if (criteria.size() < MIN_LEVELS) {
      throw new IllegalArgumentException(
          "score criteria must have at least " + MIN_LEVELS + " levels; got " + criteria.size());
    }
    for (int i = 0; i < criteria.size(); i++) {
      Content level = criteria.get(i);
      Objects.requireNonNull(level, "criteria[" + i + "]");
      if (level.isNull()) {
        throw new IllegalArgumentException("score level " + i + " must not be JSON null");
      }
    }
    criteria = Collections.unmodifiableList(new ArrayList<>(criteria));
  }

  /** A score from text instructions and text levels. */
  public static ScoreQuestion of(String instructions, List<String> levels) {
    return new ScoreQuestion(Optional.of(Content.of(instructions)), textLevels(levels));
  }

  /** A score from content instructions and content levels. */
  public static ScoreQuestion of(Content instructions, List<Content> levels) {
    return new ScoreQuestion(Optional.of(instructions), levels);
  }

  @Override
  public String type() {
    return "score";
  }

  private static List<Content> textLevels(List<String> levels) {
    Objects.requireNonNull(levels, "levels");
    List<Content> out = new ArrayList<>(levels.size());
    for (String level : levels) {
      out.add(Content.of(Objects.requireNonNull(level, "level")));
    }
    return out;
  }
}
