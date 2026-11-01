/**
 * @ClassName MetaRegistry
 * @Description Single writer of the metadata maintenance plane: loads the
 *              {@link MetaState} from the {@link MetaStore} at boot (falling
 *              back to {@link MetaState#initial()} when nothing was persisted
 *              yet) and owns every mutation — runtime configuration updates,
 *              portal upsert/deletes and per-scenario release configuration —
 *              bumping the version stamp and persisting atomically on each
 *              change. Readers only ever see immutable snapshots.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MetaRegistry {

  private static final Logger log = LoggerFactory.getLogger(MetaRegistry.class);

  private final MetaStore store;
  private volatile MetaState state;

  public MetaRegistry(MetaStore store) {
    this.store = store;
    this.state = store.load().orElseGet(MetaState::initial);
    log.info("Meta plane: loaded version {} from {}", state.version(), store.name());
  }

  public MetaState state() {
    return state;
  }

  /** Replaces the global runtime configuration (null fields = inherit defaults). */
  public MetaState updateRuntime(MetaState.RuntimeConfig runtime) {
    return commit(new MetaState(state.version() + 1, state.updatedAt(),
        runtime == null ? MetaState.RuntimeConfig.defaults() : runtime,
        state.portals(), state.releases()));
  }

  /** Upserts one release portal by id. */
  public MetaState savePortal(MetaState.PortalConfig portal) {
    List<MetaState.PortalConfig> portals = new ArrayList<>(state.portals().stream()
        .filter(p -> !p.id().equals(portal.id()))
        .toList());
    portals.add(portal);
    portals.sort(java.util.Comparator.comparing(MetaState.PortalConfig::id));
    return commit(new MetaState(state.version() + 1, state.updatedAt(), state.runtime(),
        List.copyOf(portals), state.releases()));
  }

  public MetaState deletePortal(String id) {
    List<MetaState.PortalConfig> portals = state.portals().stream()
        .filter(p -> !p.id().equals(id))
        .toList();
    // Releases pointing at the removed portal lose their target.
    List<MetaState.ReleaseConfig> releases = state.releases().stream()
        .map(r -> r.portalId() != null && r.portalId().equals(id)
            ? new MetaState.ReleaseConfig(r.scenarioId(), null, r.version(), r.changelog(), r.artifactType())
            : r)
        .toList();
    return commit(new MetaState(state.version() + 1, state.updatedAt(), state.runtime(),
        portals, releases));
  }

  /** Upserts the release-stage configuration of one scenario. */
  public MetaState saveRelease(MetaState.ReleaseConfig release) {
    List<MetaState.ReleaseConfig> releases = new ArrayList<>(state.releases().stream()
        .filter(r -> !r.scenarioId().equals(release.scenarioId()))
        .toList());
    releases.add(release);
    releases.sort(java.util.Comparator.comparing(MetaState.ReleaseConfig::scenarioId));
    return commit(new MetaState(state.version() + 1, state.updatedAt(), state.runtime(),
        state.portals(), List.copyOf(releases)));
  }

  public MetaState deleteRelease(String scenarioId) {
    List<MetaState.ReleaseConfig> releases = state.releases().stream()
        .filter(r -> !r.scenarioId().equals(scenarioId))
        .toList();
    return commit(new MetaState(state.version() + 1, state.updatedAt(), state.runtime(),
        state.portals(), releases));
  }

  private MetaState commit(MetaState next) {
    store.save(next);
    state = next;
    log.info("Meta plane: saved version {} to {}", next.version(), store.name());
    return next;
  }

  /** Validation helper: the portal a release points at must exist and be enabled. */
  public boolean portalAvailable(String portalId) {
    return state.portals().stream()
        .anyMatch(p -> Objects.equals(p.id(), portalId) && p.enabled());
  }
}
