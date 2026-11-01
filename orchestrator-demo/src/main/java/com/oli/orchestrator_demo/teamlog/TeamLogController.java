/**
 * @ClassName TeamLogController
 * @Description Read side of the runtime-team log under
 *              {@code /api/v1/logs}: facet search over the append-only store
 *              (time window, category, level, run/scenario attribution, free
 *              text) for the console Log Explorer — covering run lifecycle,
 *              tool calls, maintenance audits and system notices alike.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logs")
public class TeamLogController {

  private final TeamLogStore store;

  public TeamLogController(TeamLogStore store) {
    this.store = store;
  }

  @GetMapping
  public List<TeamLogEntry> search(
      @RequestParam(defaultValue = "7d") String window,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) String scenarioId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "200") int limit) {
    Instant since = parseWindow(window);
    return store.search(new TeamLogStore.TeamLogQuery(since, blankToNull(category),
        blankToNull(level), blankToNull(runId), blankToNull(scenarioId), blankToNull(q),
        Math.min(Math.max(limit, 1), 1000)));
  }

  /** Window like {@code 30m}, {@code 12h} or {@code 7d}; "all" disables the time facet. */
  private static Instant parseWindow(String window) {
    if (window == null || window.isBlank() || "all".equalsIgnoreCase(window)) {
      return null;
    }
    String value = window.substring(0, window.length() - 1);
    return switch (window.charAt(window.length() - 1)) {
      case 'm' -> Instant.now().minus(Duration.ofMinutes(parseLong(value, window)));
      case 'h' -> Instant.now().minus(Duration.ofHours(parseLong(value, window)));
      case 'd' -> Instant.now().minus(Duration.ofDays(parseLong(value, window)));
      default -> throw new IllegalArgumentException("window must be a duration like 30m, 12h or 7d");
    };
  }

  private static long parseLong(String value, String window) {
    try {
      return Long.parseLong(value);
    }
    catch (NumberFormatException ex) {
      throw new IllegalArgumentException("window must be a duration like 30m, 12h or 7d");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
