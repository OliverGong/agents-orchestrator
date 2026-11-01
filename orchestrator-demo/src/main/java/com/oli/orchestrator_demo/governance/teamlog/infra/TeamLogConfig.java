/**
 * @ClassName TeamLogConfig
 * @Description Wires the {@link TeamLogStore} backend of the runtime-team
 *              log; it follows the control-plane storage decision
 *              ({@code orchestrator.control.store}): {@code file} (default) —
 *              the JSONL {@link FileTeamLogStore} at
 *              {@code orchestrator.teamlog.file}; {@code db} — the
 *              {@link DbTeamLogStore} ({@code team_log} table, same database
 *              as the control plane).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.teamlog.infra;
import com.oli.orchestrator_demo.governance.teamlog.TeamLogStore;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class TeamLogConfig {

  @Bean
  public TeamLogStore teamLogStore(ObjectMapper objectMapper, ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode,
      @Value("${orchestrator.teamlog.file:config/team-log.jsonl}") Path logFile) {
    if (!"db".equals(mode)) {
      return new FileTeamLogStore(logFile, objectMapper);
    }
    DbTeamLogStore dbStore = new DbTeamLogStore(jdbc.getObject());
    dbStore.initialize();
    return dbStore;
  }
}
