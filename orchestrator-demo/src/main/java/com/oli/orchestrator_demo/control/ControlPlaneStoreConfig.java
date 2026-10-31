/**
 * @ClassName ControlPlaneStoreConfig
 * @Description Wires the {@link ControlPlaneStore} backend selected by
 *              {@code orchestrator.control.store}: {@code file} (default) —
 *              the JSON {@link FileControlPlaneStore} at
 *              {@code orchestrator.control.config-file}; {@code db} — the
 *              PostgreSQL {@link DbControlPlaneStore} behind a version-stamp
 *              {@link CachedControlPlaneStore} (Caffeine), including a
 *              one-time import of an existing JSON state file. The registry
 *              consumes whichever store this configuration returns; the
 *              field crypto ({@link ControlPlaneCrypto}) is only engaged by
 *              the database backend (key: {@code ORCHESTRATOR_CRYPTO_KEY}).
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class ControlPlaneStoreConfig {

  @Bean
  public ControlPlaneCrypto controlPlaneCrypto(
      @Value("${orchestrator.control.crypto.key:}") String keyMaterial) {
    return new ControlPlaneCrypto(keyMaterial);
  }

  @Bean
  public ControlPlaneStore controlPlaneStore(ObjectMapper objectMapper, ControlPlaneCrypto crypto,
      ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode,
      @Value("${orchestrator.control.store.cache-ttl-seconds:30}") long cacheTtlSeconds,
      @Value("${orchestrator.control.config-file:config/control-plane.json}") Path configFile) {
    FileControlPlaneStore fileStore = new FileControlPlaneStore(configFile, objectMapper);
    if (!"db".equals(mode)) {
      return fileStore;
    }
    DbControlPlaneStore dbStore = new DbControlPlaneStore(jdbc.getObject(), objectMapper, crypto);
    dbStore.initialize();
    dbStore.importFrom(fileStore);
    return new CachedControlPlaneStore(dbStore, Duration.ofSeconds(cacheTtlSeconds));
  }
}
