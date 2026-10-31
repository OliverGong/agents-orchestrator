/**
 * @ClassName ScenarioStoreConfig
 * @Description Wires the {@link ScenarioStore} backend; the scenario plane
 *              follows the control-plane storage decision
 *              ({@code orchestrator.control.store}): {@code file} (default) —
 *              the JSON {@link FileScenarioStore} at
 *              {@code orchestrator.scenarios.file}; {@code db} — the
 *              {@link DbScenarioStore} (same database as the control plane),
 *              including a one-time import of an existing JSON scenario file.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class ScenarioStoreConfig {

  @Bean
  public ScenarioStore scenarioStore(ObjectMapper objectMapper, ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode,
      @Value("${orchestrator.scenarios.file:config/scenarios.json}") Path configFile) {
    FileScenarioStore fileStore = new FileScenarioStore(configFile, objectMapper);
    if (!"db".equals(mode)) {
      return fileStore;
    }
    DbScenarioStore dbStore = new DbScenarioStore(jdbc.getObject(), objectMapper);
    dbStore.initialize();
    dbStore.importFrom(fileStore);
    return dbStore;
  }
}
