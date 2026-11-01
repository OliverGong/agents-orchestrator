/**
 * @ClassName FileInstanceStore
 * @Description The JSON file backend of the {@link InstanceStore} seam
 *              (default/seed backend, no DataSource required): an in-memory
 *              insertion-ordered map lazily seeded from the configured file
 *              (default {@code config/run-instances.json}) and rewritten
 *              atomically (temp file + move) on every mutation under a lock,
 *              exactly like the other file-backed planes. Insertion order
 *              doubles as recency order for eviction: the oldest instances
 *              are pruned beyond {@link #MAX_INSTANCES} so the file stays
 *              bounded while the recent correlation history survives
 *              restarts. Query facets are applied in memory, newest first.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance.infra;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;
import com.oli.orchestrator_demo.kernel.contract.RunInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import tools.jackson.databind.ObjectMapper;

public class FileInstanceStore implements InstanceStore {

  static final int MAX_INSTANCES = 1_000;

  private final Path file;
  private final ObjectMapper objectMapper;
  private final ReentrantLock lock = new ReentrantLock();
  /** Lazily seeded insertion-ordered map; insertion order = recency order. */
  private Map<String, RunInstance> instances;

  public FileInstanceStore(Path file, ObjectMapper objectMapper) {
    this.file = file;
    this.objectMapper = objectMapper;
  }

  @Override
  public void start(RunInstance instance) {
    lock.lock();
    try {
      load();
      instances.put(instance.runId(), instance);
      while (instances.size() > MAX_INSTANCES) {
        String oldest = instances.keySet().iterator().next();
        instances.remove(oldest);
      }
      flush();
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public void complete(String runId, Instant completedAt, Long durationMs) {
    lock.lock();
    try {
      load();
      RunInstance current = instances.get(runId);
      if (current != null) {
        instances.put(runId, current.completed(completedAt, durationMs));
        flush();
      }
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public void fail(String runId, Instant completedAt, String errorMessage) {
    lock.lock();
    try {
      load();
      RunInstance current = instances.get(runId);
      if (current != null) {
        instances.put(runId, current.failed(completedAt, errorMessage));
        flush();
      }
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<RunInstance> find(String runId) {
    lock.lock();
    try {
      load();
      return Optional.ofNullable(instances.get(runId));
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public List<RunInstance> query(InstanceQuery query) {
    lock.lock();
    try {
      load();
      return instances.values().stream()
          .filter(i -> InstanceStore.matches(query, i))
          .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
          .limit(Math.max(1, query.limit()))
          .toList();
    }
    finally {
      lock.unlock();
    }
  }

  /** Loads the file once; a torn or missing file yields an empty registry. */
  private void load() {
    if (instances != null) {
      return;
    }
    instances = new LinkedHashMap<>();
    if (!Files.exists(file)) {
      return;
    }
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        try {
          RunInstance instance = objectMapper.readValue(line, RunInstance.class);
          instances.put(instance.runId(), instance);
        }
        catch (RuntimeException ignored) {
          // skip malformed line
        }
      }
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to read run instances from " + file, ex);
    }
  }

  /** Atomic rewrite: JSONL temp file + move, so a crash never tears the registry. */
  private void flush() {
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      StringBuilder out = new StringBuilder();
      for (RunInstance instance : instances.values()) {
        out.append(objectMapper.writeValueAsString(instance)).append('\n');
      }
      Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to write run instances to " + file, ex);
    }
  }

  @Override
  public String name() {
    return "file:" + file;
  }
}
