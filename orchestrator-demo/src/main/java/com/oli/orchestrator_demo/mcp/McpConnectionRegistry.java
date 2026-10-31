/**
 * @ClassName McpConnectionRegistry
 * @Description Self-managed lifecycle owner of the MCP client connections.
 *              Nothing about MCP is hard-wired into the agents: the control
 *              plane records {@link McpServerConfig} entries (transport
 *              STREAMABLE_HTTP / deprecated SSE / STDIO), maps them to agents
 *              via {@code mcpRefs}, and this registry turns those entries into
 *              connected, initialized {@link McpSyncClient}s whose tools are
 *              handed out as instrumented {@link ToolCallback}s.
 *
 *              Reconciliation is version-triggered and lazy: every
 *              control-plane edit bumps the state version, and the registry
 *              diffs its open bindings against the desired configuration on
 *              the next touch ({@link #callbacksFor}, {@link #status}) or on
 *              its periodic tick — same-config bindings are kept untouched,
 *              changed/new ones are rebuilt, removed/disabled ones are closed.
 *              Connection attempts run asynchronously on a dedicated scheduler
 *              thread, so a slow or broken server never blocks an agent call:
 *              the tools of a not-yet-connected server are simply absent from
 *              the next request and appear once the connection lands. Failed
 *              servers retry after a cooldown (or immediately via
 *              {@link #reconnect}).
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.ControlPlaneState;
import com.oli.orchestrator_demo.control.McpServerConfig;
import com.oli.orchestrator_demo.observability.ObservabilityActor;

import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class McpConnectionRegistry {

  private static final Logger log = LoggerFactory.getLogger(McpConnectionRegistry.class);

  /** Live connection states of one binding (DISABLED servers have none). */
  enum ConnectionState { CONNECTING, CONNECTED, FAILED }

  /** Functional seam for tests: opens one configured, unconnected client. */
  @FunctionalInterface
  public interface Connector {
    McpSyncClient connect(McpServerConfig config) throws Exception;
  }

  /** One live binding between a recorded config and its (possible) client. */
  static final class ServerBinding {
    final McpServerConfig config;
    final AtomicReference<McpSyncClient> client = new AtomicReference<>();
    volatile List<ToolCallback> callbacks = List.of();
    volatile List<String> toolNames = List.of();
    volatile Instant connectedAt;
    volatile Instant lastAttemptAt = Instant.EPOCH;
    volatile String error;
    volatile ConnectionState state = ConnectionState.CONNECTING;

    ServerBinding(McpServerConfig config) {
      this.config = config;
    }
  }

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);
  /** A FAILED binding retries no faster than this (unless forced). */
  static final Duration RETRY_COOLDOWN = Duration.ofSeconds(30);
  static final long RETRY_INTERVAL_SECONDS = 15;
  private static final String CLIENT_NAME = "orchestrator";
  private static final String CLIENT_VERSION = "1.1";
  private static final String DEFAULT_SSE_PATH = "/sse";
  private static final String DEFAULT_STREAMABLE_PATH = "/mcp";

  private final AgentConfigRegistry registry;
  private final ObservabilityActor observability;
  private final MeterRegistry meterRegistry;
  private final Connector connector;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofVirtual().name("mcp-registry-", 0).factory());
  private final Map<String, ServerBinding> bindings = new ConcurrentHashMap<>();
  /** Last control-plane version this registry reconciled against. */
  private final AtomicLong lastSeenVersion = new AtomicLong(-1);

  /** Production constructor: uses the built-in three-transport connector. */
  @org.springframework.beans.factory.annotation.Autowired
  public McpConnectionRegistry(AgentConfigRegistry registry, ObservabilityActor observability,
      MeterRegistry meterRegistry) {
    this(registry, observability, meterRegistry, McpConnectionRegistry::defaultConnect);
  }

  public McpConnectionRegistry(AgentConfigRegistry registry, ObservabilityActor observability,
      MeterRegistry meterRegistry, Connector connector) {
    this.registry = registry;
    this.observability = observability;
    this.meterRegistry = meterRegistry;
    this.connector = connector;
  }

  @PostConstruct
  void start() {
    // First pass runs immediately (async, so boot never waits on MCP servers);
    // the periodic tick retries FAILED bindings after their cooldown.
    scheduler.scheduleWithFixedDelay(this::reconcileSafely, 0, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    log.info("MCP connection registry started ({} recorded server(s))", registry.state().mcpServers().size());
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
    synchronized (this) {
      bindings.values().forEach(this::closeQuietly);
      bindings.clear();
    }
  }

  /**
   * The tool callbacks of the MCP servers mapped to the agent (per its
   * {@code mcpRefs}), ready for injection into the chat options. Servers that
   * are disabled, not connected yet or failed contribute no tools — the model
   * call proceeds without them.
   */
  public List<ToolCallback> callbacksFor(String agentName) {
    reconcile();
    AgentRuntimeConfig config;
    try {
      config = registry.agent(agentName);
    }
    catch (IllegalStateException ex) {
      return List.of();
    }
    if (config.mcpRefs().isEmpty()) {
      return List.of();
    }
    List<ToolCallback> result = new ArrayList<>();
    for (String ref : config.mcpRefs()) {
      ServerBinding binding = bindings.get(ref);
      if (binding != null && binding.state == ConnectionState.CONNECTED) {
        result.addAll(binding.callbacks);
      }
    }
    return List.copyOf(result);
  }

  /** Live status of every recorded MCP server, in configuration order. */
  public List<McpServerStatus> status() {
    reconcile();
    List<McpServerStatus> result = new ArrayList<>();
    for (McpServerConfig config : registry.state().mcpServers()) {
      ServerBinding binding = bindings.get(config.name());
      if (!config.enabled()) {
        result.add(new McpServerStatus(config.name(), config.transport(), config.location(), false,
            McpServerStatus.DISABLED, null, null, List.of()));
      }
      else if (binding == null || binding.state == ConnectionState.CONNECTING) {
        result.add(new McpServerStatus(config.name(), config.transport(), config.location(), true,
            McpServerStatus.CONNECTING, binding == null ? null : binding.error, binding == null ? null : binding.connectedAt,
            binding == null ? List.of() : binding.toolNames));
      }
      else {
        result.add(new McpServerStatus(config.name(), config.transport(), config.location(), true,
            binding.state.name(), binding.error, binding.connectedAt, binding.toolNames));
      }
    }
    return result;
  }

  /**
   * Manual reconnect of one server (control-plane button): closes the current
   * binding and reconnects synchronously, bypassing the failure cooldown.
   */
  public McpServerStatus reconnect(String name) {
    McpServerConfig config = registry.state().mcpServers().stream()
        .filter(server -> server.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown MCP server '%s'".formatted(name)));
    if (!config.enabled()) {
      throw new IllegalArgumentException("MCP server '%s' is disabled".formatted(name));
    }
    ServerBinding binding = new ServerBinding(config);
    synchronized (this) {
      ServerBinding previous = bindings.put(name, binding);
      if (previous != null) {
        closeQuietly(previous);
      }
    }
    open(binding);
    return status().stream()
        .filter(status -> status.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /**
   * The version-triggered diff: cheap (volatile read) when nothing changed,
   * otherwise rebuilds the binding set and schedules connection attempts.
   */
  void reconcile() {
    reconcile(false);
  }

  private void reconcileSafely() {
    try {
      reconcile();
    }
    catch (RuntimeException ex) {
      log.warn("MCP reconcile pass failed: {}", ex.getMessage());
    }
  }

  private void reconcile(boolean retryAllFailed) {
    ControlPlaneState state = registry.state();
    List<ServerBinding> toOpen = new ArrayList<>();
    synchronized (this) {
      long version = state.version();
      boolean versionChanged = version != lastSeenVersion.get();
      if (versionChanged) {
        Map<String, McpServerConfig> desired = new LinkedHashMap<>();
        state.mcpServers().forEach(server -> desired.put(server.name(), server));
        // Close bindings whose server was removed, reconfigured or disabled.
        for (Iterator<Map.Entry<String, ServerBinding>> it = bindings.entrySet().iterator(); it.hasNext();) {
          Map.Entry<String, ServerBinding> entry = it.next();
          ServerBinding binding = entry.getValue();
          McpServerConfig want = desired.get(entry.getKey());
          if (want == null || !want.equals(binding.config) || !want.enabled()) {
            closeQuietly(binding);
            it.remove();
          }
        }
        lastSeenVersion.set(version);
      }
      // New enabled servers get a binding (and an async connect attempt).
      for (McpServerConfig config : state.mcpServers()) {
        if (config.enabled() && !bindings.containsKey(config.name())) {
          ServerBinding binding = new ServerBinding(config);
          bindings.put(config.name(), binding);
          toOpen.add(binding);
        }
      }
      // Retry failed bindings: immediately after a control-plane edit, else
      // only past the cooldown.
      for (ServerBinding binding : bindings.values()) {
        if (binding.state != ConnectionState.FAILED || toOpen.contains(binding)) {
          continue;
        }
        if (retryAllFailed || versionChanged
            || binding.lastAttemptAt.plus(RETRY_COOLDOWN).compareTo(Instant.now()) <= 0) {
          toOpen.add(binding);
        }
      }
    }
    // Connect outside the lock: initialization can block for seconds.
    for (ServerBinding binding : toOpen) {
      scheduler.execute(() -> open(binding));
    }
  }

  /** Opens, initializes and arms one binding (runs on the scheduler thread). */
  private void open(ServerBinding binding) {
    if (binding.client.get() != null) {
      return; // a reconnect already succeeded while this attempt was queued
    }
    if (bindings.get(binding.config.name()) != binding) {
      closeQuietly(binding); // replaced or removed meanwhile
      return;
    }
    binding.lastAttemptAt = Instant.now();
    try {
      McpSyncClient client = connector.connect(binding.config);
      client.initialize();
      List<McpSchema.Tool> tools = client.listTools().tools();
      List<ToolCallback> callbacks = tools.stream()
          .map(tool -> (ToolCallback) new InstrumentedMcpTool(new SyncMcpToolCallback(client, tool),
              binding.config, observability, meterRegistry))
          .toList();
      binding.client.set(client);
      binding.callbacks = List.copyOf(callbacks);
      binding.toolNames = tools.stream().map(McpSchema.Tool::name).toList();
      binding.connectedAt = Instant.now();
      binding.error = null;
      binding.state = ConnectionState.CONNECTED;
      log.info("MCP server '{}' connected via {}: {} tool(s) {}",
          binding.config.name(), binding.config.transport(), tools.size(), binding.toolNames);
    }
    catch (Exception ex) {
      binding.state = ConnectionState.FAILED;
      binding.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      closeQuietly(binding); // release a half-open transport
      log.warn("MCP server '{}' connect failed: {}", binding.config.name(), binding.error);
    }
  }

  private void closeQuietly(ServerBinding binding) {
    McpSyncClient client = binding.client.getAndSet(null);
    if (client != null) {
      try {
        client.closeGracefully();
      }
      catch (RuntimeException ex) {
        log.debug("MCP client '{}' close failed: {}", binding.config.name(), ex.getMessage());
      }
    }
  }

  /**
   * The production connector: builds the transport for the configured
   * protocol, then a sync client on top of it. The endpoint URL splits into
   * the builders' {@code baseUrl} + endpoint path (defaults {@code /sse} and
   * {@code /mcp} when the recorded URL has no path). STDIO children inherit
   * this process' environment with the configured entries as overrides, so
   * launcher-style commands ({@code npx}/{@code uvx}) resolve their binaries.
   */
  static McpSyncClient defaultConnect(McpServerConfig config) throws Exception {
    McpClientTransport transport = switch (config.transport()) {
      case McpServerConfig.TRANSPORT_STDIO -> {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.putAll(config.env());
        ServerParameters params = ServerParameters.builder(config.command())
            .args(config.args())
            .env(env)
            .build();
        yield new StdioClientTransport(params, McpJsonDefaults.getMapper());
      }
      case McpServerConfig.TRANSPORT_SSE -> HttpClientSseClientTransport.builder(baseUrl(config.endpoint()))
          .sseEndpoint(endpointPath(config.endpoint(), DEFAULT_SSE_PATH))
          .build();
      default -> HttpClientStreamableHttpTransport.builder(baseUrl(config.endpoint()))
          .endpoint(endpointPath(config.endpoint(), DEFAULT_STREAMABLE_PATH))
          .build();
    };
    return McpClient.sync(transport)
        .requestTimeout(REQUEST_TIMEOUT)
        .initializationTimeout(INITIALIZATION_TIMEOUT)
        .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
        .build();
  }

  /** "http://host:port/prefix" -> "http://host:port" (the builder owns the path). */
  static String baseUrl(String endpoint) {
    int pathStart = pathStart(endpoint);
    return pathStart < 0 ? endpoint : endpoint.substring(0, pathStart);
  }

  static String endpointPath(String endpoint, String defaultPath) {
    int pathStart = pathStart(endpoint);
    return pathStart < 0 ? defaultPath : endpoint.substring(pathStart);
  }

  private static int pathStart(String endpoint) {
    int scheme = endpoint.indexOf("://");
    return endpoint.indexOf('/', scheme < 0 ? 0 : scheme + 3);
  }
}
