/**
 * @ClassName ScenarioMetricsControllerTest
 * @Description Web layer test (standalone MockMvc) of the durable LM metrics
 *              read side: the global {@code /api/v1/metrics/lm} endpoint and
 *              the per-scenario {@code /api/v1/scenarios/{id}/metrics}
 *              endpoint expose the aggregated stage summaries (counts,
 *              duration, token sums) and pass the window/scenario parameters
 *              through to the store.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.observability.RunMetricsStore;
import com.oli.orchestrator_demo.observability.RunMetricsStore.StageMetricsSummary;

class ScenarioMetricsControllerTest {

  private RunMetricsStore store;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    store = Mockito.mock(RunMetricsStore.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ScenarioMetricsController(store)).build();
  }

  @Test
  void exposesGlobalAndPerScenarioAggregates() throws Exception {
    StageMetricsSummary summary = new StageMetricsSummary("scn-tokyo", "research", "research",
        "qwen3:8b", 3, 1, 2, 150.5, 300L, 340, 150, 490, Instant.parse("2026-11-01T08:00:00Z"));
    Mockito.when(store.aggregate(any(Instant.class), isNull())).thenReturn(List.of(summary));
    Mockito.when(store.aggregate(any(Instant.class), eq("scn-tokyo"))).thenReturn(List.of(summary));

    mockMvc.perform(get("/api/v1/metrics/lm").param("window", "7d"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].scenarioId").value("scn-tokyo"))
        .andExpect(jsonPath("$[0].stage").value("research"))
        .andExpect(jsonPath("$[0].stageCount").value(3))
        .andExpect(jsonPath("$[0].failedCount").value(1))
        .andExpect(jsonPath("$[0].retries").value(2))
        .andExpect(jsonPath("$[0].avgDurationMs").value(150.5))
        .andExpect(jsonPath("$[0].maxDurationMs").value(300))
        .andExpect(jsonPath("$[0].promptTokens").value(340))
        .andExpect(jsonPath("$[0].completionTokens").value(150))
        .andExpect(jsonPath("$[0].totalTokens").value(490));

    mockMvc.perform(get("/api/v1/scenarios/scn-tokyo/metrics").param("window", "12h"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].totalTokens").value(490));
  }

  @Test
  void emptyStoreAnswersWithAnEmptyList() throws Exception {
    Mockito.when(store.aggregate(any(Instant.class), isNull())).thenReturn(List.of());
    mockMvc.perform(get("/api/v1/metrics/lm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }
}
