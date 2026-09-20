package net.codefinch.jev.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** The single shared, immutable Jackson mapper used for all wire traffic. */
public final class Json {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private Json() {}

  /** Converts an arbitrary Java value (maps, lists, strings, numbers, POJOs) to a JSON tree. */
  public static JsonNode toTree(Object value) {
    return MAPPER.valueToTree(value);
  }

  /** Parses exactly one complete JSON document; trailing content is an error. */
  public static JsonNode parse(String json) throws JsonProcessingException {
    return MAPPER.readTree(json);
  }

  /** Serialises a JSON tree to its compact text form. */
  public static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unserialisable JSON tree", e);
    }
  }
}
