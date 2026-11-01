/**
 * @ClassName TeamLogRecorder
 * @Description The single write facade of the runtime-team log: audit records
 *              for maintenance mutations (control/meta/scenario planes), a
 *              system channel for boot notices — and nothing else. Run events
 *              are appended by the {@code ObservabilityActor} directly through
 *              the {@link TeamLogStore} seam (write-ahead, same ordering as
 *              the metrics rows). Every append degrades store failures to a
 *              warning: logging must never break the logged operation.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog;
import com.oli.orchestrator_demo.observability.ObservabilityActor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class TeamLogRecorder {

  private static final Logger log = LoggerFactory.getLogger(TeamLogRecorder.class);

  private final TeamLogStore store;
  private final ObjectMapper objectMapper;

  public TeamLogRecorder(TeamLogStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  /**
   * Records one maintenance mutation. {@code target} names what changed
   * (e.g. {@code meta.portal:team-portal}), {@code detail} is serialized to
   * JSON (any serialization problem degrades to {@code String.valueOf}).
   */
  public void audit(String source, String action, String target, Object detail) {
    append(TeamLogEntry.audit(source, action, target, toJson(detail)));
  }

  /** Records a system-level notice (boot, wiring, health anomalies). */
  public void system(String action, String message, Object detail) {
    append(TeamLogEntry.system(action, message, toJson(detail)));
  }

  private void append(TeamLogEntry entry) {
    try {
      store.append(entry);
    }
    catch (RuntimeException ex) {
      log.warn("Team log append failed ({}): {}", entry.action(), ex.getMessage());
    }
  }

  private String toJson(Object detail) {
    if (detail == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(detail);
    }
    catch (RuntimeException ex) {
      return String.valueOf(detail);
    }
  }
}
