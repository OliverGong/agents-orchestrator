/**
 * @ClassName TeamLogEntry
 * @Description One immutable entry of the runtime-team log (v2.3 团队日志):
 *              an append-only record covering everything the team wants to
 *              review later — control/meta/scenario-plane mutations
 *              ({@code AUDIT}), run-lifecycle and stage events including tool
 *              calls ({@code RUN}) and boot/system notices ({@code SYSTEM}).
 *              Not limited to business logs: the category/level/source facets
 *              make the whole plane queryable from the Log Explorer.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.teamlog;

import java.time.Instant;

public record TeamLogEntry(
  Long id,
  Instant ts,
  String category,
  String level,
  String source,
  String runId,
  String scenarioId,
  String action,
  String message,
  String detail
) {

  public static final String AUDIT = "AUDIT";
  public static final String RUN = "RUN";
  public static final String SYSTEM = "SYSTEM";

  public static final String INFO = "INFO";
  public static final String WARN = "WARN";
  public static final String ERROR = "ERROR";

  public TeamLogEntry {
    if (ts == null) {
      ts = Instant.now();
    }
  }

  /** Store-side id assignment (the entry itself stays immutable). */
  public TeamLogEntry withId(long newId) {
    return new TeamLogEntry(newId, ts, category, level, source, runId, scenarioId, action, message, detail);
  }

  /** An audit record of one maintenance mutation (control/meta/scenario plane). */
  public static TeamLogEntry audit(String source, String action, String target, String detail) {
    return new TeamLogEntry(null, Instant.now(), AUDIT, INFO, source, null, null,
        action, target, detail);
  }

  /** A system-level notice (boot, backend wiring, health anomalies). */
  public static TeamLogEntry system(String action, String message, String detail) {
    return new TeamLogEntry(null, Instant.now(), SYSTEM, INFO, "system", null, null,
        action, message, detail);
  }
}
