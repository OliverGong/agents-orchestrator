/**
 * @ClassName FileControlPlaneStore
 * @Description The file-backed {@link ControlPlaneStore}: the versioned
 *              {@link ControlPlaneState} lives as pretty-printed JSON at the
 *              configured path (default {@code config/control-plane.json}),
 *              gitignored because it is runtime state. Every write is
 *              atomic — a sibling temp file is written first and then moved
 *              into place with {@code ATOMIC_MOVE}, so a crash can never
 *              leave a half-written state behind. Kept as the default
 *              backend, as the seed source for the one-time JSON → database
 *              migration and as the fallback backend for environments without
 *              a database.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

public class FileControlPlaneStore implements ControlPlaneStore {

  private final Path configFile;
  private final ObjectMapper objectMapper;

  public FileControlPlaneStore(Path configFile, ObjectMapper objectMapper) {
    this.configFile = configFile;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<ControlPlaneState> load() {
    if (!Files.exists(configFile)) {
      return Optional.empty();
    }
    // Jackson 3 signals parse failures with (unchecked) JacksonException; a
    // corrupt state file must fail the boot loudly instead of being ignored.
    return Optional.of(objectMapper.readValue(configFile, ControlPlaneState.class));
  }

  @Override
  public void save(ControlPlaneState state) {
    try {
      if (configFile.getParent() != null) {
        Files.createDirectories(configFile.getParent());
      }
      Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
      Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to persist control plane state to " + configFile, ex);
    }
  }

  @Override
  public long currentVersion() {
    return load().map(ControlPlaneState::version).orElse(-1L);
  }

  @Override
  public String name() {
    return "file:" + configFile;
  }
}
