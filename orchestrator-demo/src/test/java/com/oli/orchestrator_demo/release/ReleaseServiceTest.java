/**
 * @ClassName ReleaseServiceTest
 * @Description Verifies the release pipeline end to end against the real
 *              file-backed planes (control plane, scenario registry, meta
 *              registry, release registry): a publish resolves the release
 *              configuration, requires an available portal, builds the
 *              portable package (scenario snapshot + dry-run evidence +
 *              portal/release metadata), hashes it (SHA-256 over the exact
 *              stored JSON), appends the registry and audits the publish.
 *              Failure paths (unknown scenario, missing release config,
 *              unavailable portal) throw structured 400-class errors.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.release;
import com.oli.orchestrator_demo.release.infra.FileReleaseStore;
import com.oli.orchestrator_demo.scenario.DryRunEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.oli.orchestrator_demo.agent.AgentModelProperties;
import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.infra.FileControlPlaneStore;
import com.oli.orchestrator_demo.meta.infra.FileMetaStore;
import com.oli.orchestrator_demo.meta.MetaRegistry;
import com.oli.orchestrator_demo.meta.MetaState;
import com.oli.orchestrator_demo.workflow.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.scenario.DryRunReport;
import com.oli.orchestrator_demo.scenario.infra.FileScenarioStore;
import com.oli.orchestrator_demo.scenario.ScenarioRegistry;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogRecorder;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import tools.jackson.databind.json.JsonMapper;

class ReleaseServiceTest {

  @TempDir
  Path tempDir;

  private ScenarioRegistry scenarios;
  private MetaRegistry meta;
  private ReleaseService service;
  private List<TeamLogEntry> auditLog;

  @BeforeEach
  void setUp() {
    WorkflowTemplateHolder holder = new WorkflowTemplateHolder(new WorkflowProperties());
    AgentConfigRegistry controlPlane = new AgentConfigRegistry(new AgentModelProperties(),
        new WorkflowProperties(), holder,
        new FileControlPlaneStore(tempDir.resolve("control-plane.json"), JsonMapper.builder().build()));
    controlPlane.initialize();
    scenarios = new ScenarioRegistry(
        new FileScenarioStore(tempDir.resolve("scenarios.json"), JsonMapper.builder().build()),
        controlPlane, new com.oli.orchestrator_demo.scenario.DryRunEngine(controlPlane));
    scenarios.initialize();
    meta = new MetaRegistry(new FileMetaStore(tempDir.resolve("meta-plane.json"),
        JsonMapper.builder().build()));

    auditLog = new CopyOnWriteArrayList<>();
    TeamLogStore collecting = new TeamLogStore() {
      @Override
      public void append(TeamLogEntry entry) {
        auditLog.add(entry);
      }

      @Override
      public List<TeamLogEntry> search(TeamLogQuery query) {
        return List.of();
      }

      @Override
      public String name() {
        return "collecting";
      }
    };
    service = new ReleaseService(scenarios, meta,
        new FileReleaseStore(tempDir.resolve("release-registry.json"), JsonMapper.builder().build()),
        JsonMapper.builder().build(), new TeamLogRecorder(collecting, JsonMapper.builder().build()));
  }

  private void enablePortal(String portalId, boolean enabled) {
    meta.savePortal(new MetaState.PortalConfig(portalId, "门户 " + portalId, "WEB_PORTAL",
        "https://portal.example.com/" + portalId, enabled));
  }

  @Test
  void publishBuildsThePortablePackageAndAppendsTheRegistry() {
    enablePortal("team-portal", true);
    meta.saveRelease(new MetaState.ReleaseConfig("kyoto-classic", "team-portal", "v1.2.0",
        "first release", "HTML"));

    ReleaseRecord record = service.publish("kyoto-classic");

    assertThat(record.id()).isEqualTo(1L);
    assertThat(record.scenarioId()).isEqualTo("kyoto-classic");
    assertThat(record.portalId()).isEqualTo("team-portal");
    assertThat(record.releaseVersion()).isEqualTo("v1.2.0");
    // The recorded hash is the SHA-256 of the exact stored package JSON.
    assertThat(record.packageHash())
        .isEqualTo(sha256(record.packageJson()));

    ReleasePackage pkg = service.export(record.id());
    assertThat(pkg.format()).isEqualTo(ReleasePackage.FORMAT);
    assertThat(pkg.formatVersion()).isEqualTo(ReleasePackage.FORMAT_VERSION);
    assertThat(pkg.tool()).isEqualTo(ReleasePackage.TOOL);
    assertThat(pkg.scenarioId()).isEqualTo("kyoto-classic");
    assertThat(pkg.releaseVersion()).isEqualTo("v1.2.0");
    assertThat(pkg.changelog()).isEqualTo("first release");
    assertThat(pkg.artifactType()).isEqualTo("HTML");
    assertThat(pkg.portalId()).isEqualTo("team-portal");
    assertThat(pkg.portalEndpoint()).isEqualTo("https://portal.example.com/team-portal");
    assertThat(pkg.scenario().id()).isEqualTo("kyoto-classic");
    DryRunReport plan = pkg.resolvedPlan();
    assertThat(plan).isNotNull();
    assertThat(plan.valid()).isTrue();

    // The publish is audited with the resolved metadata.
    assertThat(auditLog).hasSize(1);
    TeamLogEntry entry = auditLog.get(0);
    assertThat(entry.category()).isEqualTo(TeamLogEntry.AUDIT);
    assertThat(entry.source()).isEqualTo("release-pipeline");
    assertThat(entry.action()).isEqualTo("SCENARIO_PUBLISHED");
    assertThat(entry.message()).isEqualTo("release:" + record.id());
    assertThat(entry.detail()).contains("kyoto-classic").contains(record.packageHash());

    // The registry keeps the record listable, newest first.
    assertThat(service.list(null)).extracting(ReleaseRecord::id).containsExactly(record.id());
    assertThat(service.list("other-scenario")).isEmpty();
  }

  @Test
  void artifactTypeFallsBackToTheScenarioSnapshot() {
    enablePortal("team-portal", true);
    meta.saveRelease(new MetaState.ReleaseConfig("kyoto-classic", "team-portal", "v0.1.0",
        "fallback", null));

    ReleaseRecord record = service.publish("kyoto-classic");

    assertThat(service.export(record.id()).artifactType())
        .isEqualTo(scenarios.scenario("kyoto-classic").artifactType().name());
  }

  @Test
  void unknownScenariosAreRejected() {
    assertThatThrownBy(() -> service.publish("ghost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown scenario");
  }

  @Test
  void publishRequiresAResolvedReleaseConfiguration() {
    assertThatThrownBy(() -> service.publish("kyoto-classic"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has no release configuration");
  }

  @Test
  void publishRequiresAnAvailablePortal() {
    enablePortal("off-portal", false);
    meta.saveRelease(new MetaState.ReleaseConfig("kyoto-classic", "off-portal", "v1.0.0", "", "HTML"));

    assertThatThrownBy(() -> service.publish("kyoto-classic"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not available")
        .hasMessageContaining("off-portal");

    // A release config without any portal is equally unavailable.
    meta.saveRelease(new MetaState.ReleaseConfig("kyoto-classic", null, "v1.0.0", "", "HTML"));
    assertThatThrownBy(() -> service.publish("kyoto-classic"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not available");
  }

  private static String sha256(String json) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    }
    catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
