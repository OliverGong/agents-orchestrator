/**
 * @ClassName MetaState
 * @Description The versioned state of the metadata maintenance plane (v2.2
 *              元信息补录): everything the control and scenario planes need
 *              recorded about the runtime and the release stage but that does
 *              not belong to a single agent or scenario.
 *
 *              <ul>
 *              <li>{@code runtime} — the global runtime configuration the
 *              operations team maintains: default model backend, request
 *              timeout, wave concurrency and the sampling defaults (null
 *              values inherit the agent/back-end defaults);</li>
 *              <li>{@code portals} — the release targets the team publishes
 *              to (portal id, kind, endpoint, enabled flag) — the M4 release
 *              pipeline resolves a scenario's publish against them;</li>
 *              <li>{@code releases} — the release-stage configuration per
 *              scenario (target portal, version, changelog, artifact type),
 *              the "补录" half of publishing.</li>
 *              </ul>
 *
 *              Persisted behind {@link MetaStore} (file seed / database), the
 *              {@link MetaRegistry} is its single writer.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.meta;

import java.time.Instant;
import java.util.List;

public record MetaState(
  long version,
  Instant updatedAt,
  RuntimeConfig runtime,
  List<PortalConfig> portals,
  List<ReleaseConfig> releases
) {

  /** Global runtime knobs maintained by the runtime team (补录, not enforced). */
  public record RuntimeConfig(
    String modelBackend,
    Integer requestTimeoutSeconds,
    Integer maxWaveConcurrency,
    Double temperature,
    Double topP
  ) {

    public static RuntimeConfig defaults() {
      return new RuntimeConfig(null, null, null, null, null);
    }
  }

  /** One release target (portal) the publish pipeline can ship artifacts to. */
  public record PortalConfig(
    String id,
    String name,
    String kind,
    String endpoint,
    boolean enabled
  ) {
  }

  /** Release-stage configuration recorded per scenario. */
  public record ReleaseConfig(
    String scenarioId,
    String portalId,
    String version,
    String changelog,
    String artifactType
  ) {
  }

  public MetaState {
    portals = portals == null ? List.of() : List.copyOf(portals);
    releases = releases == null ? List.of() : List.copyOf(releases);
  }

  /** The boot-time default (nothing persisted yet): inherit-all runtime. */
  public static MetaState initial() {
    return new MetaState(0, Instant.now(), RuntimeConfig.defaults(), List.of(), List.of());
  }

  public MetaState withVersion(long newVersion) {
    return new MetaState(newVersion, Instant.now(), runtime, portals, releases);
  }
}
