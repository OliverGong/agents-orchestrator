/**
 * @ClassName InstanceStoreConfig
 * @Description Wires the {@link InstanceStore} backend of the run-instance
 *              registry; it follows the control-plane storage decision
 *              ({@code orchestrator.control.store}): {@code file} (default) —
 *              the JSON {@link FileInstanceStore} at
 *              {@code orchestrator.instance.file} (bounded, atomically
 *              rewritten, survives restarts without a database); {@code db} —
 *              the {@link DbInstanceStore} ({@code obs_run_instance} table,
 *              same database as the control plane). The observability actor
 *              consumes whichever store this configuration returns and drives
 *              its lifecycle transitions in write-ahead order.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance.infra;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class InstanceStoreConfig {

  @Bean
  public InstanceStore instanceStore(ObjectMapper objectMapper, ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode,
      @Value("${orchestrator.instance.file:config/run-instances.json}") Path file) {
    if (!"db".equals(mode)) {
      return new FileInstanceStore(file, objectMapper);
    }
    DbInstanceStore dbStore = new DbInstanceStore(jdbc.getObject());
    dbStore.initialize();
    return dbStore;
  }
}
