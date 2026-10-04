package net.codefinch.jev.test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.internal.Json;
import net.codefinch.jev.model.Answer;
import net.codefinch.jev.model.Answers;
import net.codefinch.jev.model.ChoiceAnswer;
import net.codefinch.jev.model.ModelMetadata;
import net.codefinch.jev.model.NoulAnswer;
import net.codefinch.jev.model.ScoreAnswer;
import net.codefinch.jev.model.Usage;

/**
 * Renders real wire JSON for scripted responses. Using the core's internal codec is deliberate:
 * these modules are released together, and sharing it keeps fake and HTTP serialization aligned.
 */
final class WireJson {
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private WireJson() {}

  static String systemOne(String model, Answers answers, Usage usage) {
    ObjectNode root = NODES.objectNode();
    root.put("model", model);
    ObjectNode answersNode = root.putObject("answers");
    answers.asMap().forEach((id, a) -> answersNode.set(id, answer(a)));
    ObjectNode usageNode = root.putObject("usage");
    usageNode.put("input_tokens", usage.inputTokens());
    usageNode.put("output_tokens", usage.outputTokens());
    return Json.write(root);
  }

  static String models(List<ModelMetadata> models) {
    ObjectNode root = NODES.objectNode();
    ArrayNode list = root.putArray("models");
    for (ModelMetadata m : models) {
      ObjectNode node = list.addObject();
      node.put("name", m.name());
      node.put("description", m.description());
      node.put("release_date", m.releaseDate());
    }
    return Json.write(root);
  }

  private static ObjectNode answer(Answer answer) {
    ObjectNode node = NODES.objectNode();
    node.put("type", answer.type());
    switch (answer) {
      case NoulAnswer n -> node.put("noul", n.noul());
      case ChoiceAnswer c -> {
        node.put("choice", c.choice());
        ObjectNode p = node.putObject("probabilities");
        c.probabilities().forEach(p::put);
        node.put("confidence", c.confidence());
      }
      case ScoreAnswer s -> {
        node.put("score", s.score());
        ObjectNode legend = node.putObject("legend");
        for (Map.Entry<Integer, ?> e : s.legend().entrySet()) {
          legend.set(Integer.toString(e.getKey()), s.legend().get(e.getKey()).toJson());
        }
        ObjectNode p = node.putObject("probabilities");
        s.probabilities().forEach((level, prob) -> p.put(Integer.toString(level), prob));
        node.put("confidence", s.confidence());
      }
    }
    return node;
  }
}
