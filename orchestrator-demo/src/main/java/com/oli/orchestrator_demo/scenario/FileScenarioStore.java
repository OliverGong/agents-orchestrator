/**
 * @ClassName FileScenarioStore
 * @Description The file-backed {@link ScenarioStore}: the versioned
 *              {@link ScenarioState} lives as pretty-printed JSON at the
 *              configured path (default {@code config/scenarios.json}),
 *              gitignored runtime state. Writes are atomic (temp sibling +
 *              {@code ATOMIC_MOVE}), exactly like the control-plane file
 *              backend; this store doubles as the seed source for the one-time
 *              JSON → database migration and as the fallback backend.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

public class FileScenarioStore implements ScenarioStore {

  private final Path configFile;
  private final ObjectMapper objectMapper;

  public FileScenarioStore(Path configFile, ObjectMapper objectMapper) {
    this.configFile = configFile;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<ScenarioState> load() {
    if (!Files.exists(configFile)) {
      return Optional.empty();
    }
    // Jackson 3 signals parse failures with (unchecked) JacksonException; a
    // corrupt scenario file must fail the boot loudly instead of being ignored.
    return Optional.of(objectMapper.readValue(configFile, ScenarioState.class));
  }

  @Override
  public void save(ScenarioState state) {
    try {
      if (configFile.getParent() != null) {
        Files.createDirectories(configFile.getParent());
      }
      Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
      Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to persist scenario state to " + configFile, ex);
    }
  }

  @Override
  public String name() {
    return "file:" + configFile;
  }
}
