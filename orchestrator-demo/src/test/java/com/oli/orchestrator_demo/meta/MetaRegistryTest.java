/**
 * @ClassName MetaRegistryTest
 * @Description Verifies the file-backed metadata registry: the initial state
 *              is seeded and reloaded on restart, runtime configuration
 *              updates (null = inherit-all defaults), portal upsert/delete
 *              (same id overwrites, deleting detaches pointing releases) and
 *              per-scenario release upsert/delete all bump the version stamp
 *              and persist atomically.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.meta;
import com.oli.orchestrator_demo.meta.infra.FileMetaStore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class MetaRegistryTest {

  @TempDir
  Path tempDir;

  private Path metaFile;

  @BeforeEach
  void setUp() {
    metaFile = tempDir.resolve("meta-plane.json");
  }

  private MetaRegistry freshRegistry() {
    return new MetaRegistry(new FileMetaStore(metaFile, JsonMapper.builder().build()));
  }

  @Test
  void seedsTheInitialStateAndReloadsItOnRestart() {
    MetaRegistry first = freshRegistry();
    assertThat(first.state().version()).isZero();
    assertThat(first.state().runtime()).isEqualTo(MetaState.RuntimeConfig.defaults());
    assertThat(first.state().portals()).isEmpty();
    assertThat(first.state().releases()).isEmpty();

    first.savePortal(new MetaState.PortalConfig("team-portal", "团队门户", "WEB_PORTAL",
        "https://portal.example.com", true));

    MetaRegistry restarted = freshRegistry();
    assertThat(restarted.state().version()).isEqualTo(first.state().version());
    assertThat(restarted.state().portals()).hasSize(1);
    assertThat(restarted.state().portals().get(0).name()).isEqualTo("团队门户");
  }

  @Test
  void updateRuntimeReplacesTheGlobalConfigAndBumpsTheVersion() {
    MetaRegistry registry = freshRegistry();

    MetaState updated = registry.updateRuntime(
        new MetaState.RuntimeConfig("qwen3:8b", 120, 4, 0.3, 0.9));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.runtime().modelBackend()).isEqualTo("qwen3:8b");
    assertThat(updated.runtime().requestTimeoutSeconds()).isEqualTo(120);
    assertThat(updated.runtime().maxWaveConcurrency()).isEqualTo(4);
    assertThat(registry.state().runtime().topP()).isEqualTo(0.9);

    // A null runtime resets to the inherit-all defaults.
    MetaState reset = registry.updateRuntime(null);
    assertThat(reset.version()).isEqualTo(2);
    assertThat(reset.runtime()).isEqualTo(MetaState.RuntimeConfig.defaults());
  }

  @Test
  void portalsAreUpsertedByIdAndDeletesDetachReleases() {
    MetaRegistry registry = freshRegistry();
    registry.savePortal(new MetaState.PortalConfig("portal-a", "门户 A", "WEB_PORTAL", "https://a", true));
    registry.savePortal(new MetaState.PortalConfig("portal-b", "门户 B", "DOWNLOAD", "https://b", true));
    registry.saveRelease(new MetaState.ReleaseConfig("scn-1", "portal-b", "v1.0.0", "initial", "HTML"));

    // Same id overwrites instead of duplicating.
    registry.savePortal(new MetaState.PortalConfig("portal-b", "门户 B 改", "STATIC_SITE", "https://b2", true));
    assertThat(registry.state().portals()).hasSize(2);
    assertThat(registry.state().portals().get(1).name()).isEqualTo("门户 B 改");

    assertThat(registry.portalAvailable("portal-a")).isTrue();
    assertThat(registry.portalAvailable("portal-b")).isTrue();

    // Deleting a portal detaches the releases pointing at it.
    registry.deletePortal("portal-b");
    assertThat(registry.state().portals()).hasSize(1);
    assertThat(registry.portalAvailable("portal-b")).isFalse();
    assertThat(registry.state().releases().get(0).portalId()).isNull();
  }

  @Test
  void portalAvailabilityRequiresAnEnabledPortal() {
    MetaRegistry registry = freshRegistry();
    registry.savePortal(new MetaState.PortalConfig("off", "停用门户", "WEB_PORTAL", "https://off", false));
    assertThat(registry.portalAvailable("off")).isFalse();
    assertThat(registry.portalAvailable("ghost")).isFalse();
  }

  @Test
  void releasesAreUpsertedPerScenario() {
    MetaRegistry registry = freshRegistry();
    registry.savePortal(new MetaState.PortalConfig("portal-a", "门户 A", "WEB_PORTAL", "https://a", true));
    registry.saveRelease(new MetaState.ReleaseConfig("scn-1", "portal-a", "v1.0.0", "initial", "HTML"));

    registry.saveRelease(new MetaState.ReleaseConfig("scn-1", "portal-a", "v1.1.0", "second", "HTML"));
    registry.saveRelease(new MetaState.ReleaseConfig("scn-2", "portal-a", "v0.1.0", "", "JAR"));

    assertThat(registry.state().releases()).hasSize(2);
    assertThat(registry.state().releases().get(0).version()).isEqualTo("v1.1.0");
    assertThat(registry.state().releases().get(1).artifactType()).isEqualTo("JAR");

    registry.deleteRelease("scn-1");
    assertThat(registry.state().releases()).hasSize(1);
    assertThat(registry.state().releases().get(0).scenarioId()).isEqualTo("scn-2");
  }
}
