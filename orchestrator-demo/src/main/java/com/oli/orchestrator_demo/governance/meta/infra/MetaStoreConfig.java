/**
 * @ClassName MetaStoreConfig
 * @Description Wires the {@link MetaStore} backend of the metadata maintenance
 *              plane; it follows the control-plane storage decision
 *              ({@code orchestrator.control.store}): {@code file} (default) —
 *              the JSON {@link FileMetaStore} at
 *              {@code orchestrator.meta.file}; {@code db} — the
 *              {@link DbMetaStore} ({@code mp_*} tables, same database as the
 *              control plane), including a one-time import of an existing
 *              JSON meta file.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.meta.infra;
import com.oli.orchestrator_demo.governance.meta.MetaStore;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class MetaStoreConfig {

  @Bean
  public MetaStore metaStore(ObjectMapper objectMapper, ObjectProvider<JdbcClient> jdbc,
      @Value("${orchestrator.control.store:file}") String mode,
      @Value("${orchestrator.meta.file:config/meta-plane.json}") Path configFile) {
    FileMetaStore fileStore = new FileMetaStore(configFile, objectMapper);
    if (!"db".equals(mode)) {
      return fileStore;
    }
    DbMetaStore dbStore = new DbMetaStore(jdbc.getObject());
    dbStore.initialize();
    dbStore.importFrom(fileStore);
    return dbStore;
  }
}
