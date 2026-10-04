package net.codefinch.jev.benchmarks.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.codefinch.jev.Content;
import net.codefinch.jev.benchmarks.components.RejectedResponseBenchmarks;
import net.codefinch.jev.exception.JevResponseValidationException;
import net.codefinch.jev.internal.Json;
import net.codefinch.jev.internal.RequestWriter;
import net.codefinch.jev.internal.ResponseParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ticket",
        "content16k",
        "content256k",
        "structured16k",
        "questions10",
        "questions100"
      })
  void requestAndResponseHaveMatchingShapesAndExpectedValues(String scenario) throws Exception {
    var input = Payloads.content(scenario);
    var request = Payloads.request(input, Payloads.count(scenario));
    var wire = Json.parse(RequestWriter.write(request, "jev-latest"));
    var response =
        ResponseParser.parseSystemOne(200, Map.of(), Payloads.response(scenario), "/v1/systemone");
    assertThat(wire.get("state")).isEqualTo(input);
    assertThat(wire.get("questions").size()).isEqualTo(Payloads.count(scenario));
    assertThat(response.answers().asMap()).hasSize(Payloads.count(scenario));
    assertThat(response.answers().asMap().keySet())
        .containsExactlyElementsOf(request.questions().ids());
    assertThat(response.answers().noul("q0").noul()).isEqualTo(0.93);
    assertThat(response.answers().choice("q1").choice()).isEqualTo("billing");
    assertThat(response.answers().score("q2").score()).isEqualTo(1.3);
    assertThat(response.answers().score("q2").legend().get(1).toJson()).isEqualTo(input);
  }

  @Test
  void copiesInputsAndScalesTheIntendedAxes() {
    ObjectNode input = (ObjectNode) Payloads.content("ticket");
    Content snapshot = Content.fromJson(input);
    input.removeAll();
    assertThat(snapshot.toJson().path("ticket").path("open").asBoolean()).isTrue();
    assertThat(Payloads.content("ticket").path("ticket").path("text").asText()).hasSize(1024);
    assertThat(Payloads.content("content16k").path("ticket").path("text").asText()).hasSize(16384);
    assertThat(Payloads.content("content256k").path("ticket").path("text").asText())
        .hasSize(262144);
    assertThat(Payloads.content("questions100")).isEqualTo(Payloads.content("ticket"));
    assertThatThrownBy(() -> Payloads.content("typo")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void structuredScenarioCopiesNestedContainersAndRemainsAboutSixteenKibibytes() {
    var input = Payloads.content("structured16k");
    var snapshot = Content.fromJson(input);
    assertThat(Json.write(input).getBytes(StandardCharsets.UTF_8).length).isBetween(14000, 22000);
    var events = input.path("ticket").path("events");
    assertThat(events.size()).isEqualTo(200);
    ((ObjectNode) events.get(0).get("detail")).put("amount", -1);
    assertThat(
            snapshot
                .toJson()
                .path("ticket")
                .path("events")
                .get(0)
                .path("detail")
                .path("amount")
                .asInt())
        .isZero();
    var prepared = Payloads.questions(100);
    assertThat(RequestWriter.write(Payloads.request(input, prepared), "jev-latest"))
        .isEqualTo(RequestWriter.write(Payloads.request(input, 100), "jev-latest"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-model", "invalid-confidence"})
  void expectedParserFailuresAreReturnedToJmh(String rejection) {
    var benchmark = new RejectedResponseBenchmarks();
    benchmark.rejection = rejection;
    benchmark.prepare();
    JevResponseValidationException failure = benchmark.reject();
    assertThat(failure.status()).isEqualTo(200);
    assertThat(failure.fieldPath())
        .isEqualTo(rejection.equals("missing-model") ? "model" : "answers.department.confidence");
  }

  @Test
  void copiedFixtureMatchesProvenanceAndInventoryUsesActualWireBytes() throws Exception {
    try (var in = Payloads.class.getResourceAsStream("/fixtures/docs-all-three.json")) {
      assertThat(in).isNotNull();
      assertThat(Payloads.hash(in.readAllBytes()))
          .isEqualTo(Payloads.readTree("provenance.json").path("sourceSha256").asText());
    }
    var inventory = Json.toTree(Payloads.inventory());
    for (String scenario : Payloads.CASES) {
      byte[] response = Payloads.response(scenario).getBytes(StandardCharsets.UTF_8);
      assertThat(inventory.path(scenario).path("responseBytes").asInt()).isEqualTo(response.length);
      assertThat(inventory.path(scenario).path("responseSha256").asText())
          .isEqualTo(Payloads.hash(response));
    }
  }
}
