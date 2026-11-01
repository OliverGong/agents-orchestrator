/**
 * @ClassName TeamLogControllerTest
 * @Description Web layer test (standalone MockMvc) of the team-log read side
 *              under {@code /api/v1/logs}: the facet parameters (window,
 *              category, level, run/scenario ids, free text) pass through to
 *              the store, the limit is clamped to the server maximum and an
 *              unparseable window surfaces as 400 via the global handler.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog.web;
import com.oli.orchestrator_demo.shared.web.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.teamlog.web.TeamLogController;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;

class TeamLogControllerTest {

  private TeamLogStore store;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    store = mock(TeamLogStore.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new TeamLogController(store))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void passesEveryFacetThroughToTheStore() throws Exception {
    when(store.search(any(TeamLogStore.TeamLogQuery.class))).thenReturn(List.of(
        new TeamLogEntry(1L, Instant.parse("2026-11-01T08:00:00Z"), TeamLogEntry.AUDIT,
            TeamLogEntry.INFO, "meta-plane", null, null, "PORTAL_SAVED", "meta.portal:p1", null)));

    mockMvc.perform(get("/api/v1/logs")
            .param("window", "24h").param("category", "AUDIT").param("level", "INFO")
            .param("runId", "run-1").param("scenarioId", "scn-a")
            .param("q", "portal").param("limit", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].category").value("AUDIT"))
        .andExpect(jsonPath("$[0].action").value("PORTAL_SAVED"))
        .andExpect(jsonPath("$[0].message").value("meta.portal:p1"));

    ArgumentCaptor<TeamLogStore.TeamLogQuery> captor =
        ArgumentCaptor.forClass(TeamLogStore.TeamLogQuery.class);
    verify(store).search(captor.capture());
    TeamLogStore.TeamLogQuery query = captor.getValue();
    assertThat(query.category()).isEqualTo("AUDIT");
    assertThat(query.level()).isEqualTo("INFO");
    assertThat(query.runId()).isEqualTo("run-1");
    assertThat(query.scenarioId()).isEqualTo("scn-a");
    assertThat(query.text()).isEqualTo("portal");
    assertThat(query.limit()).isEqualTo(50);
    assertThat(query.since()).isNotNull(); // "24h" window -> a bounded instant
  }

  @Test
  void allWindowDisablesTheTimeFacetAndClampsTheLimit() throws Exception {
    when(store.search(any(TeamLogStore.TeamLogQuery.class))).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/logs").param("window", "all").param("limit", "99999"))
        .andExpect(status().isOk());

    ArgumentCaptor<TeamLogStore.TeamLogQuery> captor =
        ArgumentCaptor.forClass(TeamLogStore.TeamLogQuery.class);
    verify(store).search(captor.capture());
    assertThat(captor.getValue().since()).isNull();
    assertThat(captor.getValue().limit()).isEqualTo(1000);
  }

  @Test
  void rejectsUnparseableWindowsAsBadRequest() throws Exception {
    mockMvc.perform(get("/api/v1/logs").param("window", "bogus"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid request"));
  }
}
