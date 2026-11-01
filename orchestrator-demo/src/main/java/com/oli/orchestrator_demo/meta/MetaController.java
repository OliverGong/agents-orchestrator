/**
 * @ClassName MetaController
 * @Description HTTP surface of the metadata maintenance plane (v2.2 元信息补录)
 *              under {@code /api/v1/meta}: the versioned state (GET), the
 *              global runtime configuration (PUT /runtime), the release
 *              portals (POST/DELETE /portals) and the per-scenario release
 *              configuration (PUT/DELETE /releases/{scenarioId}). Validation
 *              failures surface as 400 via the global exception handler.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.meta;

import com.oli.orchestrator_demo.teamlog.TeamLogRecorder;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

  private final MetaRegistry registry;
  private final TeamLogRecorder teamLog;

  public MetaController(MetaRegistry registry, TeamLogRecorder teamLog) {
    this.registry = registry;
    this.teamLog = teamLog;
  }

  @GetMapping
  public MetaState state() {
    return registry.state();
  }

  @PutMapping("/runtime")
  public MetaState updateRuntime(@RequestBody MetaState.RuntimeConfig runtime) {
    MetaState state = registry.updateRuntime(runtime);
    teamLog.audit("meta-plane", "RUNTIME_UPDATED", "meta.runtime", runtime);
    return state;
  }

  @PostMapping("/portals")
  public MetaState savePortal(@RequestBody MetaState.PortalConfig portal) {
    if (portal.id() == null || portal.id().isBlank()) {
      throw new IllegalArgumentException("portal id must not be blank");
    }
    MetaState state = registry.savePortal(portal);
    teamLog.audit("meta-plane", "PORTAL_SAVED", "meta.portal:" + portal.id(), portal);
    return state;
  }

  @DeleteMapping("/portals/{id}")
  public MetaState deletePortal(@PathVariable String id) {
    MetaState state = registry.deletePortal(id);
    teamLog.audit("meta-plane", "PORTAL_DELETED", "meta.portal:" + id, null);
    return state;
  }

  @PutMapping("/releases/{scenarioId}")
  public MetaState saveRelease(@PathVariable String scenarioId,
      @RequestBody MetaState.ReleaseConfig release) {
    if (release.portalId() != null && !registry.portalAvailable(release.portalId())) {
      throw new IllegalArgumentException(
          "portal '%s' does not exist or is disabled".formatted(release.portalId()));
    }
    MetaState state = registry.saveRelease(new MetaState.ReleaseConfig(scenarioId, release.portalId(),
        release.version(), release.changelog(), release.artifactType()));
    teamLog.audit("meta-plane", "RELEASE_SAVED", "meta.release:" + scenarioId, release);
    return state;
  }

  @DeleteMapping("/releases/{scenarioId}")
  public MetaState deleteRelease(@PathVariable String scenarioId) {
    MetaState state = registry.deleteRelease(scenarioId);
    teamLog.audit("meta-plane", "RELEASE_DELETED", "meta.release:" + scenarioId, null);
    return state;
  }
}
