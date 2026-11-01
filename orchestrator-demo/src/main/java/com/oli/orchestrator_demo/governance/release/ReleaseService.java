/**
 * @ClassName ReleaseService
 * @Description The release pipeline (v2.2 M4): publishes a scenario by
 *              resolving its release-stage configuration from the metadata
 *              plane (portal must exist and be enabled), building the portable
 *              {@link ReleasePackage} (scenario snapshot + token-free dry-run
 *              plan as evidence + portal/release metadata), hashing it
 *              (SHA-256) and appending it to the {@link ReleaseStore}. The
 *              publish is audited through the team log; every recorded
 *              version stays re-exportable.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release;
import com.oli.orchestrator_demo.governance.release.model.ReleaseRecord;
import com.oli.orchestrator_demo.governance.release.model.ReleasePackage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.governance.meta.MetaRegistry;
import com.oli.orchestrator_demo.governance.meta.MetaState;
import com.oli.orchestrator_demo.governance.scenario.dryrun.DryRunReport;
import com.oli.orchestrator_demo.governance.scenario.model.Scenario;
import com.oli.orchestrator_demo.governance.scenario.store.ScenarioRegistry;
import com.oli.orchestrator_demo.governance.teamlog.TeamLogRecorder;

import tools.jackson.databind.ObjectMapper;

@Component
public class ReleaseService {

  private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

  private final ScenarioRegistry scenarioRegistry;
  private final MetaRegistry metaRegistry;
  private final ReleaseStore store;
  private final ObjectMapper objectMapper;
  private final TeamLogRecorder teamLog;

  public ReleaseService(ScenarioRegistry scenarioRegistry, MetaRegistry metaRegistry,
      ReleaseStore store, ObjectMapper objectMapper, TeamLogRecorder teamLog) {
    this.scenarioRegistry = scenarioRegistry;
    this.metaRegistry = metaRegistry;
    this.store = store;
    this.objectMapper = objectMapper;
    this.teamLog = teamLog;
  }

  /** Resolves the release config, builds the portable package and records it. */
  public ReleaseRecord publish(String scenarioId) {
    Scenario scenario = scenarioRegistry.scenario(scenarioId);
    if (scenario == null) {
      throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
    }
    MetaState.ReleaseConfig config = metaRegistry.state().releases().stream()
        .filter(r -> scenarioId.equals(r.scenarioId()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "scenario '%s' has no release configuration; add one on the meta page first"
                .formatted(scenarioId)));
    if (config.portalId() == null || !metaRegistry.portalAvailable(config.portalId())) {
      throw new IllegalArgumentException(
          "release portal '%s' is not available; register and enable it first"
              .formatted(config.portalId()));
    }
    MetaState.PortalConfig portal = metaRegistry.state().portals().stream()
        .filter(p -> p.id().equals(config.portalId()))
        .findFirst()
        .orElseThrow();

    DryRunReport plan = scenarioRegistry.dryRun(scenarioId);
    ReleasePackage pkg = new ReleasePackage(
        ReleasePackage.FORMAT, ReleasePackage.FORMAT_VERSION, scenarioId,
        config.version(), config.changelog(),
        config.artifactType() != null ? config.artifactType() : scenario.artifactType().name(),
        portal.id(), portal.name(), portal.endpoint(), Instant.now(), ReleasePackage.TOOL,
        scenario, plan);
    String json = objectMapper.writeValueAsString(pkg);
    String hash = sha256(json);

    ReleaseRecord record = store.append(new ReleaseRecord(null, scenarioId, portal.id(),
        config.version(), config.changelog(), pkg.artifactType(), Instant.now(), hash, json));
    teamLog.audit("release-pipeline", "SCENARIO_PUBLISHED", "release:" + record.id(),
        Map.of("scenario", scenarioId, "portal", portal.id(),
            "version", String.valueOf(config.version()), "packageHash", hash));
    log.info("Release pipeline: published scenario '{}' as release {} -> portal '{}' ({} bytes, sha256 {})",
        scenarioId, config.version(), portal.id(), json.length(), hash.substring(0, 12));
    return record;
  }

  /** Newest-first registry view, optionally scoped to one scenario. */
  public java.util.List<ReleaseRecord> list(String scenarioId) {
    return store.list(scenarioId);
  }

  /** Re-exports the recorded package of one publish. */
  public ReleasePackage export(long id) {
    ReleaseRecord record = store.get(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown release: " + id));
    return objectMapper.readValue(record.packageJson(), ReleasePackage.class);
  }

  private static String sha256(String json) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
    }
    catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
