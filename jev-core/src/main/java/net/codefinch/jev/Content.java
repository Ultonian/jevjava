package net.codefinch.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.codefinch.jev.internal.Json;

/**
 * A value in any free-text position of the API: text, a JSON object, a JSON array, or JSON {@code
 * null}. Mirrors the upstream {@code EntryType}.
 *
 * <p>A top-level number or boolean is not content and is rejected; nested inside an object or array
 * they are ordinary JSON values and are kept.
 *
 * <p>Every {@code Content} is deeply immutable. Jackson trees passed in are deep-copied, trees
 * handed out by {@link #toJson()} are fresh copies, and no accessor exposes internal state. A
 * request built from content therefore serialises to the same bytes on every retry, regardless of
 * what the caller later does to the objects it built the content from.
 */
public sealed interface Content
    permits Content.Text, Content.JsonObject, Content.JsonArray, Content.Null {

  /** JSON {@code null}. */
  Content NULL = new Null();

  /** Text content. */
  static Content of(String text) {
    return new Text(text);
  }

  /** A JSON object, snapshotted from the map (nested values are converted by Jackson). */
  static Content of(Map<String, ?> object) {
    Objects.requireNonNull(object, "object");
    return fromJson(Json.toTree(object));
  }

  /** A JSON array, snapshotted from the list (nested values are converted by Jackson). */
  static Content of(List<?> array) {
    Objects.requireNonNull(array, "array");
    return fromJson(Json.toTree(array));
  }

  /**
   * Content from a Jackson tree. The tree is normalised into a private copy made only of plain JSON
   * values, so later mutation of {@code node}, or of any Java object embedded in it, has no effect:
   * nested {@code POJONode}s are serialised now and nested {@code BinaryNode}s become their base64
   * text, exactly as Jackson would write them.
   *
   * @throws IllegalArgumentException if the node is a top-level number, boolean, binary, POJO or
   *     missing node, or contains a missing node
   */
  static Content fromJson(JsonNode node) {
    Objects.requireNonNull(node, "node");
    return switch (node.getNodeType()) {
      case STRING -> new Text(node.textValue());
      case OBJECT -> new JsonObject(Pure.copy(node));
      case ARRAY -> new JsonArray(Pure.copy(node));
      case NULL -> NULL;
      default ->
          throw new IllegalArgumentException(
              "Content must be text, a JSON object, a JSON array or null; got a top-level "
                  + node.getNodeType().name().toLowerCase(java.util.Locale.ROOT));
    };
  }

  /** A fresh JSON tree for this content. Mutating it does not affect this value. */
  JsonNode toJson();

  /** The text if this is {@link Text}, otherwise empty. */
  default Optional<String> asText() {
    return Optional.empty();
  }

  /** True for {@link #NULL}. */
  default boolean isNull() {
    return false;
  }

  /** Text content. */
  record Text(String value) implements Content {
    /** Validates the text. */
    public Text {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public JsonNode toJson() {
      return TextNode.valueOf(value);
    }

    @Override
    public Optional<String> asText() {
      return Optional.of(value);
    }
  }

  /** A JSON object. The tree is private; {@link #toJson()} returns a copy. */
  final class JsonObject implements Content {
    private final JsonNode tree;

    private JsonObject(JsonNode tree) {
      this.tree = tree;
    }

    @Override
    public JsonNode toJson() {
      return tree.deepCopy();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof JsonObject other && tree.equals(other.tree);
    }

    @Override
    public int hashCode() {
      return tree.hashCode();
    }

    @Override
    public String toString() {
      return Json.write(tree);
    }
  }

  /** A JSON array. The tree is private; {@link #toJson()} returns a copy. */
  final class JsonArray implements Content {
    private final JsonNode tree;

    private JsonArray(JsonNode tree) {
      this.tree = tree;
    }

    @Override
    public JsonNode toJson() {
      return tree.deepCopy();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof JsonArray other && tree.equals(other.tree);
    }

    @Override
    public int hashCode() {
      return tree.hashCode();
    }

    @Override
    public String toString() {
      return Json.write(tree);
    }
  }

  /** JSON {@code null}. Use {@link Content#NULL}. */
  final class Null implements Content {
    private Null() {}

    @Override
    public JsonNode toJson() {
      return NullNode.getInstance();
    }

    @Override
    public boolean isNull() {
      return true;
    }

    @Override
    public String toString() {
      return "null";
    }
  }

  /**
   * Builds trees containing only immutable scalar nodes and fresh containers, so a {@code
   * deepCopy()} of the result is fully independent of anything the caller holds.
   */
  final class Pure {
    private Pure() {}

    static JsonNode copy(JsonNode node) {
      return switch (node.getNodeType()) {
        case OBJECT -> {
          ObjectNode out = JsonNodeFactory.instance.objectNode();
          node.properties().forEach(e -> out.set(e.getKey(), copy(e.getValue())));
          yield out;
        }
        case ARRAY -> {
          ArrayNode out = JsonNodeFactory.instance.arrayNode(node.size());
          node.forEach(child -> out.add(copy(child)));
          yield out;
        }
        case STRING, NUMBER, BOOLEAN, NULL -> node; // immutable value nodes
        case BINARY -> TextNode.valueOf(Base64.getEncoder().encodeToString(binary(node)));
        case POJO -> copy(Json.toTree(((POJONode) node).getPojo()));
        case MISSING ->
            throw new IllegalArgumentException("Content must not contain a missing node");
      };
    }

    private static byte[] binary(JsonNode node) {
      try {
        return node.binaryValue();
      } catch (IOException e) {
        throw new IllegalArgumentException("unreadable binary node", e);
      }
    }
  }

  /** Whether a node type is representable as content at the top level. */
  static boolean isContentType(JsonNodeType type) {
    return type == JsonNodeType.STRING
        || type == JsonNodeType.OBJECT
        || type == JsonNodeType.ARRAY
        || type == JsonNodeType.NULL;
  }
}
