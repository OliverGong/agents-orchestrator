/**
 * @ClassName ControlPlaneStoreConfig
 * @Description Wires the {@link ControlPlaneStore} backend selected by
 *              {@code orchestrator.control.store}: {@code file} (default) —
 *              the JSON {@link FileControlPlaneStore} at
 *              {@code orchestrator.control.config-file}; {@code db} — the
 *              PostgreSQL {@code DbControlPlaneStore} behind a version-stamp
 *              {@code CachedControlPlaneStore} (Caffeine), with a one-time
 *              import of an existing JSON state file. The registry consumes
 *              whichever store this configuration returns.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class ControlPlaneStoreConfig {

  @Bean
  public ControlPlaneStore controlPlaneStore(ObjectMapper objectMapper,
      @Value("${orchestrator.control.config-file:config/control-plane.json}") Path configFile) {
    return new FileControlPlaneStore(configFile, objectMapper);
  }
}
