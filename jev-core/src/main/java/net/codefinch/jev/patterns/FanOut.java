package net.codefinch.jev.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Question;
import net.codefinch.jev.Questions;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.SystemOneResponse;

/**
 * One question per item in a single call. Every question in a call sees the same {@code state} and
 * question ids are never shown to the model, so each question must carry its item itself: this
 * helper binds it <em>structurally</em>, as instructions of the form {@code {"task": <task>,
 * "item": <rendered item>}}. There is no way to build a fan-out whose questions do not name their
 * item.
 *
 * <p>Ids are {@code item_0 … item_n} in item order; {@link #answers(SystemOneResponse)} maps them
 * back. Combine with a shared {@code state} that carries whatever the items are judged against.
 *
 * @param <T> the item type
 */
public final class FanOut<T> {
  private final List<T> items;
  private final Questions questions;

  private FanOut(List<T> items, Questions questions) {
    this.items = List.copyOf(items);
    this.questions = questions;
  }

  /** A yes/no question per item. */
  public static <T> FanOut<T> noul(List<T> items, Function<T, Content> render, String task) {
    return noul(items, render, task, null);
  }

  /** A yes/no question per item, with criteria. */
  public static <T> FanOut<T> noul(
      List<T> items, Function<T, Content> render, String task, NoulCriteria criteria) {
    return build(
        items,
        render,
        task,
        instructions -> new NoulQuestion(Optional.of(instructions), Optional.ofNullable(criteria)));
  }

  /** A choice question per item. */
  public static <T> FanOut<T> choice(
      List<T> items, Function<T, Content> render, String task, ChoiceCriteria criteria) {
    Objects.requireNonNull(criteria, "criteria");
    return build(
        items,
        render,
        task,
        instructions -> new ChoiceQuestion(Optional.of(instructions), criteria));
  }

  /** A score question per item. */
  public static <T> FanOut<T> score(
      List<T> items, Function<T, Content> render, String task, List<String> levels) {
    Objects.requireNonNull(levels, "levels");
    List<Content> content = levels.stream().map(Content::of).toList();
    return build(
        items, render, task, instructions -> new ScoreQuestion(Optional.of(instructions), content));
  }

  /** Renders items that are already text. */
  public static Function<String, Content> text() {
    return Content::of;
  }

  private static <T> FanOut<T> build(
      List<T> items, Function<T, Content> render, String task, Function<Content, Question> make) {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(render, "render");
    Objects.requireNonNull(task, "task");
    if (items.isEmpty()) {
      throw new IllegalArgumentException("at least one item is required");
    }
    List<T> copy = List.copyOf(items);
    Questions.Builder builder = Questions.builder();
    for (int i = 0; i < copy.size(); i++) {
      Content rendered = Objects.requireNonNull(render.apply(copy.get(i)), "rendered item");
      if (rendered.isNull()) {
        throw new IllegalArgumentException("item " + i + " rendered to JSON null");
      }
      Map<String, Object> instructions = new LinkedHashMap<>();
      instructions.put("task", task);
      instructions.put("item", rendered.toJson());
      builder.put(id(i), make.apply(Content.of(instructions)));
    }
    return new FanOut<>(copy, builder.build());
  }

  /** The question id for an item index. */
  public static String id(int index) {
    return "item_" + index;
  }

  /** The items, in order. */
  public List<T> items() {
    return items;
  }

  /** The questions to send (one per item). */
  public Questions questions() {
    return questions;
  }

  /** The answers mapped back onto the items, in item order. */
  public List<ItemAnswer<T>> answers(SystemOneResponse response) {
    Objects.requireNonNull(response, "response");
    List<ItemAnswer<T>> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      out.add(new ItemAnswer<>(items.get(i), i, id(i), response.answers().get(id(i))));
    }
    return Collections.unmodifiableList(out);
  }
}
