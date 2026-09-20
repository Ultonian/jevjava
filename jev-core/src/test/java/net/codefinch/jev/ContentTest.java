package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;

class ContentTest {

  @Test
  void textObjectArrayNullAreTheFourKinds() {
    assertThat(Content.of("hi")).isInstanceOf(Content.Text.class);
    assertThat(Content.of(Map.of("a", 1))).isInstanceOf(Content.JsonObject.class);
    assertThat(Content.of(List.of(1, 2))).isInstanceOf(Content.JsonArray.class);
    assertThat(Content.NULL).isInstanceOf(Content.Null.class);
    assertThat(Content.NULL.isNull()).isTrue();
    assertThat(Content.of("hi").isNull()).isFalse();
    assertThat(Content.of("hi").asText()).contains("hi");
    assertThat(Content.NULL.asText()).isEmpty();
    assertThat(Content.of(Map.of()).asText()).isEmpty();
  }

  @Test
  void topLevelNumberAndBooleanAreRejectedButNestedOnesKept() {
    assertThatThrownBy(() -> Content.fromJson(Json.toTree(42)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("top-level number");
    assertThatThrownBy(() -> Content.fromJson(Json.toTree(true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("top-level boolean");
    Content nested = Content.of(Map.of("n", 42, "b", true, "list", List.of(1, false)));
    assertThat(nested.toJson().get("n").intValue()).isEqualTo(42);
    assertThat(nested.toJson().get("b").booleanValue()).isTrue();
    assertThat(nested.toJson().get("list").get(1).booleanValue()).isFalse();
  }

  @Test
  void mutatingTheInputNodeAfterConstructionHasNoEffect() {
    ObjectNode input = JsonNodeFactory.instance.objectNode().put("k", "v");
    Content content = Content.fromJson(input);
    input.put("k", "changed").put("extra", 1);
    assertThat(content.toJson().get("k").textValue()).isEqualTo("v");
    assertThat(content.toJson().has("extra")).isFalse();
  }

  @Test
  void mutatingNestedInputCollectionsAfterConstructionHasNoEffect() {
    List<Object> inner = new ArrayList<>(List.of("a"));
    Map<String, Object> outer = new HashMap<>();
    outer.put("inner", inner);
    Content content = Content.of(outer);
    inner.add("b");
    outer.put("more", "x");
    assertThat(content.toJson().get("inner")).hasSize(1);
    assertThat(content.toJson().has("more")).isFalse();
  }

  @Test
  void mutatingTheNodeReturnedByToJsonHasNoEffect() {
    Content content = Content.of(List.of("a"));
    JsonNode out = content.toJson();
    ((ArrayNode) out).add("b");
    assertThat(content.toJson()).hasSize(1);
    assertThat(Json.write(content.toJson())).isEqualTo("[\"a\"]");
  }

  /** Review P2: a nested POJONode kept a live reference to the caller's list. */
  @Test
  void nestedPojoNodeIsSnapshottedNotReferenced() {
    List<String> values = new ArrayList<>(List.of("before"));
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.putPOJO("values", values);
    Content content = Content.fromJson(node);
    values.add("after");
    assertThat(Json.write(content.toJson())).isEqualTo("{\"values\":[\"before\"]}");
    assertThat(content.toJson().get("values").getNodeType()).isEqualTo(JsonNodeType.ARRAY);
    // Output boundary: nothing in the returned tree is a POJO or binary node either.
    assertThat(pojoOrBinary(content.toJson())).isFalse();
  }

  /** Review P2: a nested BinaryNode shared the caller's byte array. */
  @Test
  void nestedBinaryNodeBecomesDetachedBase64Text() {
    byte[] bytes = {1, 2, 3};
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("bytes", bytes);
    Content content = Content.fromJson(node);
    bytes[0] = 4;
    assertThat(Json.write(content.toJson())).isEqualTo("{\"bytes\":\"AQID\"}");
    assertThat(content.toJson().get("bytes").isTextual()).isTrue();
    // Same result through the Map factory, which routes byte[] through Jackson.
    assertThat(Json.write(Content.of(Map.of("bytes", new byte[] {1, 2, 3})).toJson()))
        .isEqualTo("{\"bytes\":\"AQID\"}");
  }

  @Test
  void missingNodeInsideTreeIsRejected() {
    ArrayNode array = JsonNodeFactory.instance.arrayNode();
    array.add(JsonNodeFactory.instance.objectNode());
    array.add(com.fasterxml.jackson.databind.node.MissingNode.getInstance());
    assertThatThrownBy(() -> Content.fromJson(array))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing node");
    assertThatThrownBy(
            () -> Content.fromJson(com.fasterxml.jackson.databind.node.MissingNode.getInstance()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static boolean pojoOrBinary(JsonNode node) {
    if (node.isPojo() || node.isBinary()) {
      return true;
    }
    for (JsonNode child : node) {
      if (pojoOrBinary(child)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void valueSemantics() {
    assertThat(Content.of(Map.of("a", 1))).isEqualTo(Content.of(Map.of("a", 1)));
    assertThat(Content.of(Map.of("a", 1))).hasSameHashCodeAs(Content.of(Map.of("a", 1)));
    assertThat(Content.of(List.of(1))).isEqualTo(Content.of(List.of(1)));
    assertThat(Content.of(List.of(1))).hasSameHashCodeAs(Content.of(List.of(1)));
    assertThat(Content.of(Map.of("a", 1))).isNotEqualTo(Content.of(List.of(1)));
    assertThat(Content.of(List.of(1))).isNotEqualTo(Content.of(Map.of("a", 1)));
    assertThat(Content.of("x")).isEqualTo(Content.of("x"));
    assertThat(Content.of(Map.of("a", 1))).hasToString("{\"a\":1}");
    assertThat(Content.of(List.of(1))).hasToString("[1]");
    assertThat(Content.NULL).hasToString("null");
    assertThat(Content.of("t").toJson().textValue()).isEqualTo("t");
    assertThat(Content.NULL.toJson().isNull()).isTrue();
  }

  @Test
  void fromJsonRoundTripsEveryContentKind() {
    for (String json : List.of("\"text\"", "{\"a\":[1,null]}", "[null,{\"b\":true}]", "null")) {
      JsonNode node = parse(json);
      assertThat(Json.write(Content.fromJson(node).toJson())).isEqualTo(Json.write(node));
    }
  }

  private static JsonNode parse(String json) {
    try {
      return Json.parse(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }
}
