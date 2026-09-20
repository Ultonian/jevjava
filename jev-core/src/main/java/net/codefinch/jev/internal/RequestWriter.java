package net.codefinch.jev.internal;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Question;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.SystemOneRequest;

/**
 * Serialises a {@link SystemOneRequest} to the wire JSON. An {@code Optional.empty()} field is
 * omitted; {@link Content#NULL} is written as JSON {@code null}. Field order is fixed ({@code
 * model}, {@code state}, {@code questions}) for stable bytes.
 */
public final class RequestWriter {
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private RequestWriter() {}

  /** The request body; {@code defaultModel} is used unless the request overrides the model. */
  public static ObjectNode toJson(SystemOneRequest request, String defaultModel) {
    ObjectNode body = NODES.objectNode();
    body.put("model", request.model().orElse(defaultModel));
    body.set("state", request.state().content().toJson());
    ObjectNode questions = body.putObject("questions");
    for (Map.Entry<String, Question> entry : request.questions().asMap().entrySet()) {
      questions.set(entry.getKey(), question(entry.getValue()));
    }
    return body;
  }

  /** The request body as compact JSON text. */
  public static String write(SystemOneRequest request, String defaultModel) {
    return Json.write(toJson(request, defaultModel));
  }

  private static ObjectNode question(Question question) {
    ObjectNode node = NODES.objectNode();
    node.put("type", question.type());
    optional(node, "instructions", question.instructions());
    switch (question) {
      case NoulQuestion noul -> noul.criteria().ifPresent(c -> node.set("criteria", criteria(c)));
      case ChoiceQuestion choice -> {
        ObjectNode criteria = node.putObject("criteria");
        choice.criteria().options().forEach((label, desc) -> criteria.set(label, desc.toJson()));
      }
      case ScoreQuestion score -> {
        ArrayNode criteria = node.putArray("criteria");
        score.criteria().forEach(level -> criteria.add(level.toJson()));
      }
    }
    return node;
  }

  private static ObjectNode criteria(NoulCriteria criteria) {
    ObjectNode node = NODES.objectNode();
    optional(node, "true", criteria.trueDescription());
    optional(node, "false", criteria.falseDescription());
    return node;
  }

  private static void optional(ObjectNode node, String field, Optional<Content> value) {
    value.ifPresent(v -> node.set(field, v.toJson()));
  }
}
