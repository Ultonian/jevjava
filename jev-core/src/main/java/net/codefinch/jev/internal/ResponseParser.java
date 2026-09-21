package net.codefinch.jev.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.Answer;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.Content;
import net.codefinch.jev.JevResponseValidationException;
import net.codefinch.jev.ModelList;
import net.codefinch.jev.ModelMetadata;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.Usage;

/**
 * Parses successful response bodies against the API schema.
 *
 * <p>Strict on what the schema requires, lenient on what it does not: unknown fields anywhere are
 * ignored; an answer whose {@code type} is an unrecognised string is dropped and logged at WARNING
 * (as the Python SDK does); an answer with a missing or non-string {@code type}, or a known type
 * with missing or mistyped required fields, fails the whole response with a {@link
 * JevResponseValidationException} naming the field. Required numbers are checked for presence, type
 * and representability (a {@code long} in range, a finite {@code double}) before conversion, so a
 * missing value can never read as {@code 0} and an out-of-range one can never wrap or become
 * infinite. The body must be exactly one JSON document and {@code answers} must be non-empty on the
 * wire (schema {@code minProperties: 1}); answers of unknown type are then dropped, which may leave
 * the typed map empty.
 */
public final class ResponseParser {
  private static final Logger LOG = System.getLogger(ResponseParser.class.getName());

  private ResponseParser() {}

  /** Parses a {@code POST /v1/systemone} success body. */
  public static SystemOneResponse parseSystemOne(
      int status, Map<String, List<String>> headers, String body, String endpoint) {
    Context ctx = new Context(status, headers, body, endpoint);
    JsonNode root = ctx.root();
    String model = ctx.text(root, "model", "model");
    JsonNode answersNode = ctx.object(root, "answers", "answers");
    if (answersNode.isEmpty()) {
      throw ctx.invalid("answers"); // schema: minProperties 1
    }
    Map<String, Answer> answers = new LinkedHashMap<>();
    answersNode
        .properties()
        .forEach(
            entry -> {
              Answer answer = answer(ctx, entry.getKey(), entry.getValue());
              if (answer != null) {
                answers.put(entry.getKey(), answer);
              }
            });
    JsonNode usageNode = ctx.object(root, "usage", "usage");
    Usage usage =
        new Usage(
            ctx.integer(usageNode, "input_tokens", "usage.input_tokens"),
            ctx.integer(usageNode, "output_tokens", "usage.output_tokens"));
    return new SystemOneResponse(
        model, Answers.of(answers), usage, ResponseMetadata.of(headers, body));
  }

  /** Parses a {@code GET /v1/models} success body. */
  public static ModelList parseModels(
      int status, Map<String, List<String>> headers, String body, String endpoint) {
    Context ctx = new Context(status, headers, body, endpoint);
    JsonNode root = ctx.root();
    JsonNode modelsNode = root.get("models");
    if (modelsNode == null || !modelsNode.isArray()) {
      throw ctx.invalid("models");
    }
    List<ModelMetadata> models = new ArrayList<>();
    int i = 0;
    for (JsonNode m : modelsNode) {
      String path = "models." + i++;
      if (!m.isObject()) {
        throw ctx.invalid(path);
      }
      models.add(
          new ModelMetadata(
              ctx.text(m, "name", path + ".name"),
              ctx.text(m, "description", path + ".description"),
              ctx.text(m, "release_date", path + ".release_date")));
    }
    return new ModelList(models, ResponseMetadata.of(headers, body));
  }

  private static Answer answer(Context ctx, String id, JsonNode node) {
    String path = "answers." + id;
    if (!node.isObject()) {
      throw ctx.invalid(path + ".type");
    }
    JsonNode type = node.get("type");
    if (type == null || !type.isTextual()) {
      throw ctx.invalid(path + ".type");
    }
    return switch (type.textValue()) {
      case "noul" -> new NoulAnswer(ctx.number(node, "noul", path + ".noul"));
      case "choice" ->
          new ChoiceAnswer(
              ctx.text(node, "choice", path + ".choice"),
              ctx.stringProbabilities(node, path),
              ctx.number(node, "confidence", path + ".confidence"));
      case "score" ->
          new ScoreAnswer(
              ctx.number(node, "score", path + ".score"),
              ctx.legend(node, path),
              ctx.levelProbabilities(node, path),
              ctx.number(node, "confidence", path + ".confidence"));
      default -> {
        LOG.log(
            Level.WARNING,
            "Ignoring answer '{0}' with unrecognized type '{1}'; it remains in the raw body",
            id,
            type.textValue());
        yield null;
      }
    };
  }

  /** Per-parse state: the source needed to build a precise validation exception. */
  private record Context(
      int status, Map<String, List<String>> headers, String body, String endpoint) {

    JsonNode root() {
      JsonNode root;
      try {
        root = Json.parse(body);
      } catch (JsonProcessingException e) {
        throw invalid("$");
      }
      if (root == null || !root.isObject()) {
        throw invalid("$");
      }
      return root;
    }

    JevResponseValidationException invalid(String path) {
      return new JevResponseValidationException(status, headers, body, endpoint, path);
    }

    String text(JsonNode parent, String field, String path) {
      JsonNode value = parent.get(field);
      if (value == null || !value.isTextual()) {
        throw invalid(path);
      }
      return value.textValue();
    }

    JsonNode object(JsonNode parent, String field, String path) {
      JsonNode value = parent.get(field);
      if (value == null || !value.isObject()) {
        throw invalid(path);
      }
      return value;
    }

    double number(JsonNode parent, String field, String path) {
      JsonNode value = parent.get(field);
      if (value == null) {
        throw invalid(path);
      }
      return finite(value, path);
    }

    /** A finite double; rejects non-numbers, overflow to infinity and NaN. */
    double finite(JsonNode value, String path) {
      if (!value.isNumber()) {
        throw invalid(path);
      }
      double d = value.doubleValue();
      if (!Double.isFinite(d)) {
        throw invalid(path);
      }
      return d;
    }

    long integer(JsonNode parent, String field, String path) {
      JsonNode value = parent.get(field);
      if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
        throw invalid(path);
      }
      return value.longValue();
    }

    Map<String, Double> stringProbabilities(JsonNode node, String path) {
      JsonNode probabilities = object(node, "probabilities", path + ".probabilities");
      Map<String, Double> out = new LinkedHashMap<>();
      probabilities
          .properties()
          .forEach(
              e ->
                  out.put(e.getKey(), finite(e.getValue(), path + ".probabilities." + e.getKey())));
      return out;
    }

    Map<Integer, Double> levelProbabilities(JsonNode node, String path) {
      JsonNode probabilities = object(node, "probabilities", path + ".probabilities");
      Map<Integer, Double> out = new LinkedHashMap<>();
      probabilities
          .properties()
          .forEach(
              e -> {
                String p = path + ".probabilities." + e.getKey();
                out.put(level(e.getKey(), p), finite(e.getValue(), p));
              });
      return out;
    }

    Map<Integer, Content> legend(JsonNode node, String path) {
      JsonNode legend = object(node, "legend", path + ".legend");
      Map<Integer, Content> out = new LinkedHashMap<>();
      legend
          .properties()
          .forEach(
              e -> {
                String p = path + ".legend." + e.getKey();
                if (e.getValue().isNull() || !Content.isContentType(e.getValue().getNodeType())) {
                  throw invalid(p);
                }
                out.put(level(e.getKey(), p), Content.fromJson(e.getValue()));
              });
      return out;
    }

    int level(String key, String path) {
      try {
        int level = Integer.parseInt(key);
        if (level < 0) {
          throw invalid(path);
        }
        return level;
      } catch (NumberFormatException e) {
        throw invalid(path);
      }
    }
  }
}
