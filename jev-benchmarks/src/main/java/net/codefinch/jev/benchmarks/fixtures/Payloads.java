package net.codefinch.jev.benchmarks.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.internal.Json;
import net.codefinch.jev.internal.RequestWriter;
import net.codefinch.jev.model.Questions;
import net.codefinch.jev.model.State;
import net.codefinch.jev.model.SystemOneRequest;

/** Deterministic synthetic workloads with independent content-size and question-count axes. */
public final class Payloads {
  /** Cases used for serialization and parsing; no Cartesian product. */
  public static final List<String> CASES =
      List.of(
          "ticket", "content16k", "content256k", "structured16k", "questions10", "questions100");

  private Payloads() {}

  /** Fresh structured input, inspired by TicketTriage, with nested arrays and scalars. */
  public static JsonNode content(String scenario) {
    int size =
        switch (scenario) {
          case "ticket", "structured16k", "questions10", "questions100" -> 1024;
          case "content16k" -> 16 * 1024;
          case "content256k" -> 256 * 1024;
          default -> throw new IllegalArgumentException("Unknown fixture: " + scenario);
        };
    String sample = "I was charged twice for order 4411; please refund one. ";
    String text = sample.repeat(size / sample.length() + 1).substring(0, size);
    Map<String, Object> ticket = new LinkedHashMap<>();
    ticket.put("text", text);
    ticket.put("priority", 2);
    ticket.put("open", true);
    ticket.put("tags", List.of("billing", "refund"));
    if (scenario.equals("structured16k")) {
      List<Map<String, Object>> events = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", i);
        event.put("text", "Charge adjustment requested");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("flags", List.of(true, false));
        detail.put("amount", i);
        event.put("detail", detail);
        events.add(event);
      }
      ticket.put("events", events);
    }
    return Json.toTree(Map.of("ticket", ticket));
  }

  /** Question count; ordinary ticket cases exercise all three answer kinds. */
  public static int count(String scenario) {
    return switch (scenario) {
      case "ticket", "content16k", "content256k", "structured16k" -> 3;
      case "questions10" -> 10;
      case "questions100" -> 100;
      default -> throw new IllegalArgumentException("Unknown fixture: " + scenario);
    };
  }

  /** Caller-owned question data, prepared before measurement. */
  public record QuestionInput(List<String> ids, Map<String, String> choices, List<String> legend) {
    /** Retains deterministic ordering and immutable inputs. */
    public QuestionInput {
      ids = List.copyOf(ids);
      choices = Collections.unmodifiableMap(new LinkedHashMap<>(choices));
      legend = List.copyOf(legend);
    }
  }

  /** Allocates synthetic IDs and descriptions outside timed construction. */
  public static QuestionInput questions(int count) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add("q" + i);
    }
    Map<String, String> choices = new LinkedHashMap<>();
    choices.put("billing", "Payments");
    choices.put("support", "Technical help");
    choices.put("other", "Other");
    return new QuestionInput(ids, choices, List.of("Cosmetic", "Degraded, workaround", "Blocking"));
  }

  /** Convenience fixture builder for setup and inventory, outside measurement. */
  public static SystemOneRequest request(JsonNode content, int count) {
    return request(content, questions(count));
  }

  /** Measures SDK construction using prepared caller data. */
  public static SystemOneRequest request(JsonNode content, QuestionInput input) {
    Questions.Builder questions = Questions.builder();
    for (int i = 0; i < input.ids().size(); i++) {
      String id = input.ids().get(i);
      switch (i % 3) {
        case 0 -> questions.noul(id, "Does ticket.text request money back?");
        case 1 -> questions.choice(id, "Which team handles ticket.text?", input.choices());
        default -> questions.score(id, "How severe is the problem?", input.legend());
      }
    }
    return SystemOneRequest.of(
        State.of(net.codefinch.jev.model.Content.fromJson(content)), questions.build());
  }

  /** Success response derived from the copied all-three fixture, with matching question IDs. */
  public static String response(String scenario) {
    ObjectNode root = (ObjectNode) Json.toTree(readTree("docs-all-three.json"));
    JsonNode original = root.get("answers");
    ObjectNode answers = root.putObject("answers");
    String[] kinds = {"refund_requested", "department", "severity"};
    for (int i = 0; i < count(scenario); i++) {
      ObjectNode answer = original.get(kinds[i % 3]).deepCopy();
      if (i == 2) {
        // Structured score legends are legal content; grow parsed content, not ignored padding.
        ((ObjectNode) answer.get("legend")).set("1", content(scenario));
      }
      answers.set("q" + i, answer);
    }
    return Json.write(root);
  }

  /** Malformed success bodies for parser failure cost; not HTTP error mapping. */
  public static String rejected(String kind) {
    ObjectNode root = (ObjectNode) readTree("docs-all-three.json");
    switch (kind) {
      case "missing-model" -> root.remove("model");
      case "invalid-confidence" ->
          ((ObjectNode) root.get("answers").get("department")).put("confidence", "invalid");
      default -> throw new IllegalArgumentException("Unknown rejection: " + kind);
    }
    return Json.write(root);
  }

  /** Reads a module-owned resource independently of the working directory. */
  public static JsonNode readTree(String name) {
    try (var in = Payloads.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("Missing fixture: " + name);
      }
      return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Actual UTF-8 sizes and hashes of every generated wire case, recorded outside timed work. */
  public static Map<String, Object> inventory() {
    Map<String, Object> all = new LinkedHashMap<>();
    for (String scenario : CASES) {
      String request =
          RequestWriter.write(request(content(scenario), count(scenario)), "jev-latest");
      String response = response(scenario);
      all.put(
          scenario,
          Map.of(
              "questions",
              count(scenario),
              "requestBytes",
              request.getBytes(StandardCharsets.UTF_8).length,
              "responseBytes",
              response.getBytes(StandardCharsets.UTF_8).length,
              "requestSha256",
              hash(request.getBytes(StandardCharsets.UTF_8)),
              "responseSha256",
              hash(response.getBytes(StandardCharsets.UTF_8))));
    }
    return all;
  }

  /** SHA-256 for fixture and executable identities. */
  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
