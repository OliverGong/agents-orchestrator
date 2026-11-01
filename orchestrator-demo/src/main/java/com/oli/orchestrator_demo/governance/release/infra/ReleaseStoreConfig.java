/**
 * @ClassName ReleaseStoreConfig
 * @Description Wires the {@link ReleaseStore} backend of the release
 *              registry; it follows the control-plane storage decision
 *              ({@code orchestrator.control.store}): {@code file} (default) —
 *              the JSON {@link FileReleaseStore} at
 *              {@code orchestrator.release.file}; {@code db} — the
 *              {@link DbReleaseStore} ({@code rl_release} table, same
 *              database as the control plane).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release.infra;
import com.oli.orchestrator_demo.governance.release.ReleaseStore;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class ReleaseStoreConfig {

  @Bean
  public ReleaseStore releaseStore(ObjectMapper objectMapper, ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode,
      @Value("${orchestrator.release.file:config/release-registry.json}") Path configFile) {
    if (!"db".equals(mode)) {
      return new FileReleaseStore(configFile, objectMapper);
    }
    DbReleaseStore dbStore = new DbReleaseStore(jdbc.getObject());
    dbStore.initialize();
    return dbStore;
  }
}
