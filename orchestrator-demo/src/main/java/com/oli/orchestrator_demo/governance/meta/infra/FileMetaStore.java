/**
 * @ClassName FileMetaStore
 * @Description The JSON file backend of the {@link MetaStore} seam (default
 *              and seed backend): one pretty-printed document at
 *              {@code config/meta-plane.json}, replaced atomically (temp file
 *              + move) on every save. A corrupt file fails the boot loudly
 *              instead of being silently ignored.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.meta.infra;
import com.oli.orchestrator_demo.governance.meta.MetaState;
import com.oli.orchestrator_demo.governance.meta.MetaStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

public class FileMetaStore implements MetaStore {

  private final Path configFile;
  private final ObjectMapper objectMapper;

  public FileMetaStore(Path configFile, ObjectMapper objectMapper) {
    this.configFile = configFile;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<MetaState> load() {
    if (!Files.exists(configFile)) {
      return Optional.empty();
    }
    // Jackson 3 signals parse failures with (unchecked) JacksonException.
    return Optional.of(objectMapper.readValue(configFile, MetaState.class));
  }

  @Override
  public void save(MetaState state) {
    try {
      if (configFile.getParent() != null) {
        Files.createDirectories(configFile.getParent());
      }
      Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
      Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to persist meta state to " + configFile, ex);
    }
  }

  @Override
  public String name() {
    return "file:" + configFile;
  }
}
