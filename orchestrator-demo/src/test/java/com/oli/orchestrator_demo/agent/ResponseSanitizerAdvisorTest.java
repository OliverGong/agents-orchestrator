/**
 * @ClassName ResponseSanitizerAdvisorTest
 * @Description Unit tests for the LLM output sanitizer: Markdown fenced JSON,
 *              JSON wrapped in prose and pure JSON pass-through, plus the
 *              end-to-end regression proving that entity() parsing survives a
 *              fenced model answer.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.model.ItineraryResponse;
import com.oli.orchestrator_demo.model.ResearchResponse;

class ResponseSanitizerAdvisorTest {

  @Test
  void leavesPureJsonUntouched() {
    String json = "{\"attractions\":[\"Senso-ji\"]}";
    assertThat(ResponseSanitizerAdvisor.sanitize(json)).isSameAs(json);
  }

  @Test
  void stripsMarkdownFences() {
    String fenced = "```json\n{\"attractions\":[\"Senso-ji\"],\"travelTips\":[],\"foodOptions\":[]}\n```";
    assertThat(ResponseSanitizerAdvisor.sanitize(fenced))
        .isEqualTo("{\"attractions\":[\"Senso-ji\"],\"travelTips\":[],\"foodOptions\":[]}");
  }

  @Test
  void extractsJsonEmbeddedInProse() {
    String prose = "Here is the requested JSON:\n{\"attractions\":[\"Senso-ji\"]}\nHope this helps!";
    assertThat(ResponseSanitizerAdvisor.sanitize(prose)).isEqualTo("{\"attractions\":[\"Senso-ji\"]}");
  }

  @Test
  void keepsPlainTextWithoutJsonUnchanged() {
    String text = "Sorry, I cannot answer that.";
    assertThat(ResponseSanitizerAdvisor.sanitize(text)).isSameAs(text);
  }

  @Test
  void toleratesNullAndBlank() {
    assertThat(ResponseSanitizerAdvisor.sanitize(null)).isNull();
    assertThat(ResponseSanitizerAdvisor.sanitize("  ")).isEqualTo("  ");
  }

  @Test
  void entityParsingSurvivesFencedModelOutput() {
    var stub = ChatClientStubs.modelReturning("""
        ```json
        {"attractions":["Senso-ji","Ueno Park"],"travelTips":["Buy a Suica card"],"foodOptions":["Ramen"]}
        ```
        """);
    ChatClient client = ChatClient.builder(stub.model())
        .defaultAdvisors(new ResponseSanitizerAdvisor())
        .build();

    ResearchResponse parsed = client.prompt().user("research Tokyo").call().entity(ResearchResponse.class);

    assertThat(parsed.attractions()).isEqualTo(List.of("Senso-ji", "Ueno Park"));
  }

  @Test
  void wrapsBareArrayIntoSingleArrayTypedProperty() {
    String schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
        + "\"properties\":{\"dayPlans\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}},"
        + "\"required\":[\"dayPlans\"]}";
    assertThat(ResponseSanitizerAdvisor.sanitize("[{\"day\":1}]", schema))
        .isEqualTo("{\"dayPlans\":[{\"day\":1}]}");
  }

  @Test
  void leavesBareArrayUnchangedWhenSchemaHasMultipleProperties() {
    String schema = "{\"type\":\"object\","
        + "\"properties\":{\"attractions\":{\"type\":\"array\"},\"travelTips\":{\"type\":\"array\"}},"
        + "\"required\":[\"attractions\",\"travelTips\"]}";
    assertThat(ResponseSanitizerAdvisor.sanitize("[\"Senso-ji\"]", schema)).isEqualTo("[\"Senso-ji\"]");
  }

  @Test
  void leavesBareArrayUnchangedWithoutSchemaHint() {
    assertThat(ResponseSanitizerAdvisor.sanitize("[\"Senso-ji\"]")).isEqualTo("[\"Senso-ji\"]");
  }

  @Test
  void entityParsingSurvivesBareArrayForSingleFieldRecord() {
    var stub = ChatClientStubs.modelReturning("""
        [{"day":1,"morning":"Senso-ji","afternoon":"Ueno Park","evening":"Ramen tasting","estimatedCost":250}]
        """);
    ChatClient client = ChatClient.builder(stub.model())
        .defaultAdvisors(new ResponseSanitizerAdvisor())
        .build();

    ItineraryResponse parsed = client.prompt().user("plan one day").call().entity(ItineraryResponse.class);

    assertThat(parsed.dayPlans()).hasSize(1);
    assertThat(parsed.dayPlans().get(0).morning()).isEqualTo("Senso-ji");
  }
}
