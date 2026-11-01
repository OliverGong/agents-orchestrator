/**
 * @ClassName MetaControllerTest
 * @Description Web layer test (standalone MockMvc) of the metadata
 *              maintenance plane under {@code /api/v1/meta}: the versioned
 *              state read, the runtime configuration update, portal
 *              upsert/delete (blank ids rejected as 400) and per-scenario
 *              release upsert/delete (releases pointing at unknown or
 *              disabled portals rejected as 400 via the global handler).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.meta.web;
import com.oli.orchestrator_demo.kernel.shared.web.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.governance.meta.web.MetaController;
import com.oli.orchestrator_demo.governance.meta.MetaRegistry;
import com.oli.orchestrator_demo.governance.meta.MetaState;
import com.oli.orchestrator_demo.governance.teamlog.infra.NoopTeamLogStore;
import com.oli.orchestrator_demo.governance.teamlog.TeamLogRecorder;

import tools.jackson.databind.json.JsonMapper;

class MetaControllerTest {

  private MetaRegistry registry;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    registry = Mockito.mock(MetaRegistry.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new MetaController(registry, new TeamLogRecorder(new NoopTeamLogStore(), JsonMapper.builder().build())))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  private MetaState state() {
    return new MetaState(3, Instant.parse("2026-11-01T08:00:00Z"),
        new MetaState.RuntimeConfig("qwen3:8b", 120, 4, null, null),
        List.of(new MetaState.PortalConfig("team-portal", "团队门户", "WEB_PORTAL",
            "https://portal.example.com", true)),
        List.of(new MetaState.ReleaseConfig("scn-1", "team-portal", "v1.0.0", "initial", "HTML")));
  }

  @Test
  void exposesTheVersionedMetaState() throws Exception {
    when(registry.state()).thenReturn(state());

    mockMvc.perform(get("/api/v1/meta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.runtime.modelBackend").value("qwen3:8b"))
        .andExpect(jsonPath("$.runtime.requestTimeoutSeconds").value(120))
        .andExpect(jsonPath("$.runtime.temperature").doesNotExist())
        .andExpect(jsonPath("$.portals[0].id").value("team-portal"))
        .andExpect(jsonPath("$.portals[0].enabled").value(true))
        .andExpect(jsonPath("$.releases[0].scenarioId").value("scn-1"))
        .andExpect(jsonPath("$.releases[0].portalId").value("team-portal"));
  }

  @Test
  void updatesTheRuntimeConfiguration() throws Exception {
    MetaState updated = state();
    when(registry.updateRuntime(any(MetaState.RuntimeConfig.class))).thenReturn(updated);

    mockMvc.perform(put("/api/v1/meta/runtime")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"modelBackend":"gpt-4o","requestTimeoutSeconds":60,
                 "maxWaveConcurrency":2,"temperature":0.5,"topP":0.9}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.runtime.modelBackend").value("qwen3:8b"));
  }

  @Test
  void rejectsBlankPortalIdsAsBadRequest() throws Exception {
    mockMvc.perform(post("/api/v1/meta/portals")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":"  ","name":"空门户","kind":"WEB_PORTAL","endpoint":"https://x","enabled":true}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid request"));
  }

  @Test
  void savesAndDeletesPortals() throws Exception {
    when(registry.savePortal(any(MetaState.PortalConfig.class))).thenReturn(state());
    when(registry.deletePortal("team-portal")).thenReturn(state());

    mockMvc.perform(post("/api/v1/meta/portals")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":"team-portal","name":"团队门户","kind":"WEB_PORTAL",
                 "endpoint":"https://portal.example.com","enabled":true}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.portals[0].id").value("team-portal"));

    mockMvc.perform(delete("/api/v1/meta/portals/team-portal"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3));
  }

  @Test
  void rejectsReleasesPointingAtUnavailablePortals() throws Exception {
    when(registry.portalAvailable("ghost")).thenReturn(false);

    mockMvc.perform(put("/api/v1/meta/releases/scn-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"portalId":"ghost","version":"v1.0.0","changelog":"x","artifactType":"HTML"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            "portal 'ghost' does not exist or is disabled"));
  }

  @Test
  void savesAndDeletesPerScenarioReleases() throws Exception {
    when(registry.portalAvailable("team-portal")).thenReturn(true);
    when(registry.saveRelease(any(MetaState.ReleaseConfig.class))).thenReturn(state());
    when(registry.deleteRelease(eq("scn-1"))).thenReturn(state());

    mockMvc.perform(put("/api/v1/meta/releases/scn-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"portalId":"team-portal","version":"v1.0.0","changelog":"initial",
                 "artifactType":"HTML"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.releases[0].scenarioId").value("scn-1"));

    mockMvc.perform(delete("/api/v1/meta/releases/scn-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.releases[0].version").value("v1.0.0"));
  }
}
