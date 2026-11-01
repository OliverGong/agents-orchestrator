/**
 * @ClassName NoopTeamLogStore
 * @Description The discard backend used when the team log is explicitly
 *              disabled or in lightweight test setups: appends are dropped
 *              and searches answer empty — same role as
 *              {@link com.oli.orchestrator_demo.observability.infra.NoopRunMetricsStore}.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog.infra;
import com.oli.orchestrator_demo.observability.infra.NoopRunMetricsStore;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;

import java.util.List;

public class NoopTeamLogStore implements TeamLogStore {

  @Override
  public void append(TeamLogEntry entry) {
    // discard
  }

  @Override
  public List<TeamLogEntry> search(TeamLogQuery query) {
    return List.of();
  }

  @Override
  public String name() {
    return "noop";
  }
}
