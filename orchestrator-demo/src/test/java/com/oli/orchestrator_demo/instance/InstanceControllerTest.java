/**
 * @ClassName InstanceControllerTest
 * @Description Web layer test (standalone MockMvc) of the v2.3 correlation
 *              API: the instance list applies the business/scenario/status
 *              facets, the detail view assembles the durable registry row, the
 *              in-memory run timeline, the durable per-stage LM rows and the
 *              run's team-log trail, and unknown run ids return 404.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.instance;

import static com.oli.orchestrator_demo.Await.until;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.ChatClientStubs.CollectingInstanceStore;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.NoopRunMetricsStore;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.RunMetricsStore;
import com.oli.orchestrator_demo.observability.RunTimeline.RunStatus;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;

class InstanceControllerTest {

  private final CollectingInstanceStore registry = new CollectingInstanceStore();
  private final ObservabilityActor observability = ChatClientStubs.actor(new NoopRunMetricsStore(), registry);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    List<TeamLogEntry> logEntries = List.of(
        new TeamLogEntry(1L, Instant.now(), TeamLogEntry.RUN, TeamLogEntry.INFO, "orchestrator",
            "run-1", "scn-a", "RUN_STARTED", "run started with template 'tpl' (SEQUENTIAL)", null));
    TeamLogStore logStore = new TeamLogStore() {
      @Override
      public void append(TeamLogEntry entry) {
        // not used by the read side
      }

      @Override
      public List<TeamLogEntry> search(TeamLogQuery query) {
        if (query.runId() == null) {
          return List.of();
        }
        return logEntries.stream().filter(e -> query.runId().equals(e.runId())).toList();
      }

      @Override
      public String name() {
        return "collecting";
      }
    };
    mockMvc = MockMvcBuilders.standaloneSetup(new InstanceController(registry, observability,
        new NoopRunMetricsStore(), logStore)).build();
  }

  private void seedRun(String runId, String businessId, String scenarioId) {
    observability.tell(AgentObservabilityEvent.runStarted(runId, "tpl", scenarioId, businessId,
        "SEQUENTIAL", "{\"destination\":\"Kyoto\"}"));
    observability.tell(AgentObservabilityEvent.stageCompleted(runId, "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 100, 1,
        new AgentObservabilityEvent.LmUsage(120, 45, 165, "qwen3:8b", 1), "{}", "{}"));
    observability.tell(AgentObservabilityEvent.runCompleted(runId, "{\"review\":{}}"));
    until(() -> observability.timeline(runId).map(t -> t.status() == RunStatus.COMPLETED).orElse(false));
    until(() -> registry.find(runId).map(i -> "COMPLETED".equals(i.status())).orElse(false));
  }

  @Test
  void listsInstancesWithBusinessTriggerFacets() throws Exception {
    seedRun("run-1", "biz-42", "scn-a");
    seedRun("run-2", "biz-43", "scn-a");

    mockMvc.perform(get("/api/v1/instances").param("window", "all"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].businessId").exists());
  }

  @Test
  void filtersInstancesByBusinessId() throws Exception {
    seedRun("run-1", "biz-42", "scn-a");
    seedRun("run-2", "biz-43", "scn-a");

    mockMvc.perform(get("/api/v1/instances").param("businessId", "biz-42").param("window", "all"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].runId").value("run-1"))
        .andExpect(jsonPath("$[0].businessId").value("biz-42"))
        .andExpect(jsonPath("$[0].status").value("COMPLETED"))
        .andExpect(jsonPath("$[0].requestJson").value("{\"destination\":\"Kyoto\"}"));
  }

  @Test
  void assemblesTheFullContextOfOneInstantiation() throws Exception {
    seedRun("run-1", "biz-42", "scn-a");

    mockMvc.perform(get("/api/v1/instances/run-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance.runId").value("run-1"))
        .andExpect(jsonPath("$.instance.businessId").value("biz-42"))
        .andExpect(jsonPath("$.instance.status").value("COMPLETED"))
        .andExpect(jsonPath("$.run.runId").value("run-1"))
        .andExpect(jsonPath("$.run.status").value("COMPLETED"))
        .andExpect(jsonPath("$.run.waves[0].stages[0].agentName").value("research"))
        .andExpect(jsonPath("$.run.waves[0].stages[0].usage.totalTokens").value(165))
        .andExpect(jsonPath("$.logs.length()").value(1))
        .andExpect(jsonPath("$.logs[0].runId").value("run-1"));
  }

  @Test
  void unknownRunReturns404() throws Exception {
    mockMvc.perform(get("/api/v1/instances/does-not-exist"))
        .andExpect(status().isNotFound());
  }
}
