/**
 * @ClassName FileTeamLogStore
 * @Description The JSONL file backend of the {@link TeamLogStore} seam
 *              (default and seed backend): one JSON object per line at the
 *              configured path (default {@code config/team-log.jsonl}),
 *              appended under a lock. Search scans the file newest-last,
 *              applies the query facets in memory and returns the newest
 *              {@code limit} rows — simple, portable and gitignored runtime
 *              state, exactly like the other file-backed planes.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import tools.jackson.databind.ObjectMapper;

public class FileTeamLogStore implements TeamLogStore {

  private final Path logFile;
  private final ObjectMapper objectMapper;
  private final ReentrantLock lock = new ReentrantLock();
  /** Seeded from the existing line count on first append (ids grow monotonically). */
  private final AtomicLong nextId = new AtomicLong(-1);

  public FileTeamLogStore(Path logFile, ObjectMapper objectMapper) {
    this.logFile = logFile;
    this.objectMapper = objectMapper;
  }

  @Override
  public void append(TeamLogEntry entry) {
    lock.lock();
    try {
      if (nextId.get() < 0) {
        nextId.set(Files.exists(logFile) ? Files.lines(logFile).filter(l -> !l.isBlank()).count() : 0);
      }
      if (logFile.getParent() != null) {
        Files.createDirectories(logFile.getParent());
      }
      String line = objectMapper.writeValueAsString(entry.withId(nextId.getAndIncrement())) + "\n";
      Files.writeString(logFile, line, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to append team log entry to " + logFile, ex);
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public List<TeamLogEntry> search(TeamLogQuery query) {
    if (!Files.exists(logFile)) {
      return List.of();
    }
    List<TeamLogEntry> entries = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        // A torn trailing line (crash mid-append) must not break the search.
        try {
          entries.add(objectMapper.readValue(line, TeamLogEntry.class));
        }
        catch (RuntimeException ignored) {
          // skip malformed line
        }
      }
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to read team log from " + logFile, ex);
    }
    return entries.stream()
        .filter(e -> matches(query, e))
        .sorted(Comparator.comparing(TeamLogEntry::ts).reversed())
        .limit(Math.max(1, query.limit()))
        .toList();
  }

  static boolean matches(TeamLogQuery query, TeamLogEntry e) {
    if (query.since() != null && e.ts().isBefore(query.since())) {
      return false;
    }
    if (query.category() != null && !query.category().equalsIgnoreCase(e.category())) {
      return false;
    }
    if (query.level() != null && !query.level().equalsIgnoreCase(e.level())) {
      return false;
    }
    if (query.runId() != null && !query.runId().equals(e.runId())) {
      return false;
    }
    if (query.scenarioId() != null && !query.scenarioId().equals(e.scenarioId())) {
      return false;
    }
    if (query.text() != null && !query.text().isBlank()) {
      String needle = query.text().toLowerCase();
      boolean hit = contains(e.message(), needle) || contains(e.action(), needle)
          || contains(e.source(), needle) || contains(e.detail(), needle);
      if (!hit) {
        return false;
      }
    }
    return true;
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase().contains(needle);
  }

  @Override
  public String name() {
    return "file:" + logFile;
  }
}
