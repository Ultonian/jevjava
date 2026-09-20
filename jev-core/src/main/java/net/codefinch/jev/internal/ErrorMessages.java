package net.codefinch.jev.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import net.codefinch.jev.JevUnprocessableEntityException.FieldError;

/**
 * Best-effort extraction of a human message from an error body, in the upstream Python SDK's order:
 * a text body; {@code error} string; {@code error.message}; {@code message}; {@code detail} string;
 * {@code detail.message}; the {@code msg} entries of a {@code detail} list; otherwise the raw body
 * truncated to {@value #MAX_RAW_BODY_IN_MESSAGE} characters. JSON is decoded regardless of the
 * response content type; a body that is not valid JSON is used as text.
 */
public final class ErrorMessages {

  /**
   * Maximum raw-body length placed in an exception message (upstream {@code
   * MAX_ERROR_BODY_LENGTH}).
   */
  public static final int MAX_RAW_BODY_IN_MESSAGE = 200;

  private ErrorMessages() {}

  /** Extracts a message, or returns null when the body is empty. */
  public static String extract(String rawBody) {
    if (rawBody == null || rawBody.isEmpty()) {
      return null;
    }
    JsonNode body = tryParse(rawBody);
    if (body == null || body.isTextual()) {
      // Not JSON (or a JSON string): the text itself is the message.
      String text = body == null ? rawBody : body.textValue();
      return text.isEmpty() ? null : truncate(text);
    }
    String extracted = fromJson(body);
    return extracted != null ? extracted : truncate(rawBody);
  }

  /** Validation entries of a FastAPI-shaped 422 body, or an empty list for anything else. */
  public static List<FieldError> fieldErrors(String rawBody) {
    JsonNode body = tryParse(rawBody);
    if (body == null || !body.isObject() || !body.path("detail").isArray()) {
      return List.of();
    }
    List<FieldError> out = new ArrayList<>();
    for (JsonNode entry : body.get("detail")) {
      if (!entry.isObject() || !entry.path("msg").isTextual()) {
        continue;
      }
      String type = entry.path("type").isTextual() ? entry.get("type").textValue() : "";
      out.add(new FieldError(path(entry.get("loc")), entry.get("msg").textValue(), type));
    }
    return List.copyOf(out);
  }

  private static String fromJson(JsonNode body) {
    if (!body.isObject()) {
      return null;
    }
    JsonNode error = body.get("error");
    if (error != null && error.isTextual()) {
      return nonEmpty(error.textValue());
    }
    if (error != null && error.isObject() && error.path("message").isTextual()) {
      return nonEmpty(error.get("message").textValue());
    }
    if (body.path("message").isTextual()) {
      return nonEmpty(body.get("message").textValue());
    }
    JsonNode detail = body.get("detail");
    if (detail == null) {
      return null;
    }
    if (detail.isTextual()) {
      return nonEmpty(detail.textValue());
    }
    if (detail.isObject() && detail.path("message").isTextual()) {
      return nonEmpty(detail.get("message").textValue());
    }
    if (detail.isArray()) {
      StringJoiner joiner = new StringJoiner("; ");
      for (JsonNode entry : detail) {
        if (!entry.isObject() || !entry.path("msg").isTextual()) {
          continue;
        }
        String path = path(entry.get("loc"));
        joiner.add(
            path.isEmpty()
                ? entry.get("msg").textValue()
                : path + ": " + entry.get("msg").textValue());
      }
      return joiner.length() == 0 ? null : joiner.toString();
    }
    return null;
  }

  /**
   * Dotted {@code loc} without a leading {@code body} segment; empty if {@code loc} is not a list.
   */
  private static String path(JsonNode loc) {
    if (loc == null || !loc.isArray()) {
      return "";
    }
    StringJoiner joiner = new StringJoiner(".");
    for (JsonNode item : loc) {
      String segment = item.isTextual() ? item.textValue() : item.toString();
      if (!"body".equals(segment)) {
        joiner.add(segment);
      }
    }
    return joiner.toString();
  }

  private static String nonEmpty(String s) {
    return s.isEmpty() ? null : s;
  }

  private static String truncate(String s) {
    return s.length() > MAX_RAW_BODY_IN_MESSAGE ? s.substring(0, MAX_RAW_BODY_IN_MESSAGE) + "…" : s;
  }

  private static JsonNode tryParse(String rawBody) {
    try {
      return Json.parse(rawBody);
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
