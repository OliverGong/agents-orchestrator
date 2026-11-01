/**
 * @ClassName FileReleaseStore
 * @Description The JSON file backend of the {@link ReleaseStore} seam
 *              (default and seed backend): one pretty-printed document at
 *              the configured path (default {@code config/release-registry.json}),
 *              replaced atomically on every append, exactly like the other
 *              file-backed planes.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.release;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

public class FileReleaseStore implements ReleaseStore {

  private final Path configFile;
  private final ObjectMapper objectMapper;

  public FileReleaseStore(Path configFile, ObjectMapper objectMapper) {
    this.configFile = configFile;
    this.objectMapper = objectMapper;
  }

  private State loadState() {
    if (!Files.exists(configFile)) {
      return new State(0, List.of());
    }
    return objectMapper.readValue(configFile, State.class);
  }

  @Override
  public ReleaseRecord append(ReleaseRecord record) {
    State state = loadState();
    ReleaseRecord withId = new ReleaseRecord(state.version() + 1, record.scenarioId(),
        record.portalId(), record.releaseVersion(), record.changelog(), record.artifactType(),
        record.publishedAt(), record.packageHash(), record.packageJson());
    List<ReleaseRecord> records = new ArrayList<>(state.records());
    records.add(withId);
    save(new State(state.version() + 1, records));
    return withId;
  }

  @Override
  public List<ReleaseRecord> list(String scenarioId) {
    return loadState().records().stream()
        .filter(r -> scenarioId == null || scenarioId.equals(r.scenarioId()))
        .sorted(Comparator.comparing(ReleaseRecord::id,
            Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
  }

  @Override
  public Optional<ReleaseRecord> get(long id) {
    return loadState().records().stream()
        .filter(r -> r.id() != null && r.id() == id)
        .findFirst();
  }

  private void save(State state) {
    try {
      if (configFile.getParent() != null) {
        Files.createDirectories(configFile.getParent());
      }
      Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
      Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to persist release registry to " + configFile, ex);
    }
  }

  @Override
  public String name() {
    return "file:" + configFile;
  }
}
