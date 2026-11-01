/**
 * @ClassName RunMetricsConfig
 * @Description Wires the {@link RunMetricsStore} sink selected by
 *              {@code orchestrator.control.store} (the same storage decision
 *              as the control and scenario planes): {@code file} (default) —
 *              the no-op store, because no DataSource is wired in this mode;
 *              {@code db} — the {@link DbRunMetricsStore} writing stage rows
 *              into {@code obs_run_metrics}, giving the per-scenario LM
 *              history (durations, retries, token consumption) crash-safe
 *              durability. The observability actor consumes whichever store
 *              this configuration returns.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability.infra;
import com.oli.orchestrator_demo.observability.RunMetricsStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class RunMetricsConfig {

  @Bean
  public RunMetricsStore runMetricsStore(ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode) {
    if (!"db".equals(mode)) {
      return new NoopRunMetricsStore();
    }
    DbRunMetricsStore dbStore = new DbRunMetricsStore(jdbc.getObject());
    dbStore.initialize();
    return dbStore;
  }
}
