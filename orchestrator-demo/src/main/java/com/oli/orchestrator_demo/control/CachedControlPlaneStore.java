/**
 * @ClassName CachedControlPlaneStore
 * @Description Read-cache decorator for the database-backed control-plane
 *              store (Caffeine). The hot paths (per model call, console
 *              polling) read the in-memory snapshot held by the registry —
 *              the cache protects the secondary read path: consumers that go
 *              through the store (v2.1 scenario matching, multi-instance
 *              setups) get the state without paying a full reassembly query
 *              on every call, while still observing external writes:
 *
 *              <ul>
 *              <li><b>version-stamp invalidation</b> — a cached snapshot is
 *              only served while the store's cheap {@link ControlPlaneStore#currentVersion()}
 *              (a single-row query) still matches its version; any other
 *              writer bumps the version and the next load reassembles;</li>
 *              <li><b>write-through</b> — {@link #save} persists and refreshes
 *              the cached snapshot in the same call;</li>
 *              <li><b>TTL bound</b> — a time-to-live caps staleness even if
 *              the version probe were skipped, keeping memory bounded.</li>
 *              </ul>
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class CachedControlPlaneStore implements ControlPlaneStore {

  private static final String KEY = "state";

  private final ControlPlaneStore delegate;
  private final Cache<String, ControlPlaneState> cache;

  public CachedControlPlaneStore(ControlPlaneStore delegate, Duration ttl) {
    this.delegate = delegate;
    this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).build();
  }

  @Override
  public Optional<ControlPlaneState> load() {
    ControlPlaneState cached = cache.getIfPresent(KEY);
    if (cached != null && cached.version() == delegate.currentVersion()) {
      return Optional.of(cached);
    }
    cache.invalidate(KEY);
    Optional<ControlPlaneState> fresh = delegate.load();
    fresh.ifPresent(state -> cache.put(KEY, state));
    return fresh;
  }

  @Override
  public void save(ControlPlaneState state) {
    delegate.save(state);
    cache.put(KEY, state);
  }

  @Override
  public long currentVersion() {
    return delegate.currentVersion();
  }

  @Override
  public String name() {
    return "cached " + delegate.name();
  }
}
