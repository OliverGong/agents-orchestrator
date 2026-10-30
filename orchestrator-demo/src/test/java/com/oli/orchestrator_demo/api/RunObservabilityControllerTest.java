/**
 * @ClassName RunObservabilityControllerTest
 * @Description Web layer test (standalone MockMvc): the observability read
 *              side exposes run timelines grouped into dependency waves (with
 *              the parallel flag and the agent context JSON) and returns 404
 *              for unknown runs.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import static com.oli.orchestrator_demo.Await.until;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.RunTimeline.RunStatus;

class RunObservabilityControllerTest {

  private final ObservabilityActor observability = ChatClientStubs.actor();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new RunObservabilityController(observability)).build();
  }

  @Test
  void unknownRunReturns404() throws Exception {
    mockMvc.perform(get("/api/trips/runs/does-not-exist"))
        .andExpect(status().isNotFound());
  }

  @Test
  void exposesRunTimelineGroupedByWaves() throws Exception {
    String runId = "run-1";
    observability.tell(AgentObservabilityEvent.runStarted(runId, "configured", "CONCURRENT"));
    observability.tell(AgentObservabilityEvent.stageCompleted(runId, "research", "research", 0,
        List.of(), "CONCURRENT", "vt-research", 11, "{\"destination\":\"Tokyo\"}", "{\"researchResponse\":{}}"));
    observability.tell(AgentObservabilityEvent.stageCompleted(runId, "budget", "budget", 1,
        List.of("itinerary"), "CONCURRENT", "vt-budget", 21, "{\"destination\":\"Tokyo\"}", "{\"budgetResponse\":{}}"));
    observability.tell(AgentObservabilityEvent.stageCompleted(runId, "itinerary", "itinerary", 1,
        List.of("budget"), "CONCURRENT", "vt-itinerary", 25, "{\"destination\":\"Tokyo\"}", "{\"itineraryResponse\":{}}"));
    observability.tell(AgentObservabilityEvent.runCompleted(runId, "{\"reviewResponse\":{}}"));

    until(() -> observability.timeline(runId).map(t -> t.status() == RunStatus.COMPLETED).orElse(false));

    mockMvc.perform(get("/api/trips/runs/" + runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(runId))
        .andExpect(jsonPath("$.mode").value("CONCURRENT"))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.finalContext").value("{\"reviewResponse\":{}}"))
        .andExpect(jsonPath("$.waves.length()").value(2))
        .andExpect(jsonPath("$.waves[0].parallel").value(false))
        .andExpect(jsonPath("$.waves[0].stages[0].stageName").value("research"))
        .andExpect(jsonPath("$.waves[0].stages[0].outputContext").value("{\"researchResponse\":{}}"))
        .andExpect(jsonPath("$.waves[1].parallel").value(true))
        .andExpect(jsonPath("$.waves[1].stages[0].parallelPeers[0]").value("itinerary"));

    mockMvc.perform(get("/api/trips/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runId").value(runId))
        .andExpect(jsonPath("$[0].stageCount").value(3))
        .andExpect(jsonPath("$[0].failedStages").value(0));
  }
}
