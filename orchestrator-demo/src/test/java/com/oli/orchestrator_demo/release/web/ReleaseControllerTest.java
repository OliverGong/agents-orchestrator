/**
 * @ClassName ReleaseControllerTest
 * @Description Web layer test (standalone MockMvc) of the release pipeline
 *              surface under {@code /api/v1/releases}: the registry listing
 *              (optionally scenario-scoped), publish, package re-export as an
 *              attachment download and 400 mapping of validation failures via
 *              the global exception handler.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.release.web;
import com.oli.orchestrator_demo.shared.web.GlobalExceptionHandler;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.release.web.ReleaseController;
import com.oli.orchestrator_demo.release.ReleasePackage;
import com.oli.orchestrator_demo.release.ReleaseRecord;
import com.oli.orchestrator_demo.release.ReleaseService;

class ReleaseControllerTest {

  private ReleaseService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = Mockito.mock(ReleaseService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ReleaseController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  private ReleaseRecord record(long id) {
    return new ReleaseRecord(id, "kyoto-classic", "team-portal", "v1.2.0", "first release", "HTML",
        Instant.parse("2026-11-01T08:00:00Z"),
        "a".repeat(64), "{\"format\":\"orchestrator-release-package\"}");
  }

  private ReleasePackage pkg() {
    return new ReleasePackage(ReleasePackage.FORMAT, ReleasePackage.FORMAT_VERSION, "kyoto-classic",
        "v1.2.0", "first release", "HTML", "team-portal", "团队门户", "https://portal.example.com",
        Instant.parse("2026-11-01T08:00:00Z"), ReleasePackage.TOOL, null, null);
  }

  @Test
  void exposesTheRegistryWithOptionalScenarioScope() throws Exception {
    when(service.list(null)).thenReturn(List.of(record(1)));
    when(service.list("kyoto-classic")).thenReturn(List.of(record(2), record(1)));

    mockMvc.perform(get("/api/v1/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].scenarioId").value("kyoto-classic"))
        .andExpect(jsonPath("$[0].packageHash").value("a".repeat(64)));

    mockMvc.perform(get("/api/v1/releases").param("scenarioId", "kyoto-classic"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].releaseVersion").value("v1.2.0"));
  }

  @Test
  void publishReturnsTheAppendedRecord() throws Exception {
    when(service.publish("kyoto-classic")).thenReturn(record(7));

    mockMvc.perform(post("/api/v1/releases/kyoto-classic/publish"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.portalId").value("team-portal"))
        .andExpect(jsonPath("$.releaseVersion").value("v1.2.0"));

    Mockito.verify(service).publish("kyoto-classic");
  }

  @Test
  void exportsThePackageAsAnAttachmentDownload() throws Exception {
    when(service.export(1)).thenReturn(pkg());

    mockMvc.perform(get("/api/v1/releases/1/package"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.containsString("attachment")))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.containsString("kyoto-classic-v1.2.0.json")))
        .andExpect(jsonPath("$.format").value(ReleasePackage.FORMAT))
        .andExpect(jsonPath("$.portalId").value("team-portal"));
  }

  @Test
  void validationFailuresMapTo400() throws Exception {
    when(service.publish("ghost"))
        .thenThrow(new IllegalArgumentException("Unknown scenario: ghost"));

    mockMvc.perform(post("/api/v1/releases/ghost/publish"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("ghost")));
  }
}
