/**
 * @ClassName McpConnectionRegistryTest
 * @Description Exercises the dynamic connection lifecycle against a fake
 *              connector backed by a real control-plane registry: initial
 *              connect, tool exposure to mapped agents (with composite tool
 *              names), version-triggered re-wiring on configuration edits,
 *              disabled/failed handling and the manual reconnect bypass.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.oli.orchestrator_demo.Await;
import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.McpServerConfig;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

class McpConnectionRegistryTest {

  @TempDir
  Path tempDir;

  private AgentConfigRegistry registry;
  private final ObservabilityActor observability = Mockito.mock(ObservabilityActor.class);
  private final List<McpSyncClient> openedClients = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    WorkflowTemplateHolder holder = new WorkflowTemplateHolder(new WorkflowProperties());
    registry = new AgentConfigRegistry(new AgentModelProperties(), new WorkflowProperties(), holder,
        JsonMapper.builder().build(), tempDir.resolve("control-plane.json"));
    registry.initialize();
  }

  /** Connector yielding a mock client that reports the given tool names. */
  private McpConnectionRegistry.Connector connector(String... toolNames) {
    return config -> {
      McpSyncClient client = Mockito.mock(McpSyncClient.class);
      stubClient(client, toolNames);
      openedClients.add(client);
      return client;
    };
  }

  private static void stubClient(McpSyncClient client, String... toolNames) {
    List<McpSchema.Tool> tools = Arrays.stream(toolNames)
        .map(name -> new McpSchema.Tool(name, null, "tool " + name, Map.of("type", "object"), null, null, null))
        .toList();
    // SyncMcpToolCallback reads the client info during construction.
    Mockito.when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("test-client", "0"));
    Mockito.when(client.initialize()).thenReturn(null);
    Mockito.when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(tools, null));
  }

  private McpConnectionRegistry startedRegistry(McpConnectionRegistry.Connector connector) {
    McpConnectionRegistry mcp = new McpConnectionRegistry(registry, observability, new SimpleMeterRegistry(),
        connector);
    mcp.start();
    return mcp;
  }

  @Test
  void connectsRecordedServersAndExposesTheirToolsToMappedAgents() {
    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "STREAMABLE_HTTP", "http://localhost:3002/mcp", true, "forecast")));
    McpConnectionRegistry mcp = startedRegistry(connector("get_weather", "get-alerts"));

    Await.until(() -> mcp.status().stream().anyMatch(status -> "CONNECTED".equals(status.state())));
    McpServerStatus status = mcp.status().get(0);
    assertThat(status.name()).isEqualTo("weather");
    assertThat(status.transport()).isEqualTo("STREAMABLE_HTTP");
    assertThat(status.toolNames()).containsExactly("get_weather", "get-alerts");
    assertThat(status.connectedAt()).isNotNull();

    registry.updateAgent("research",
        new AgentRuntimeConfig("research", "prompt", null, null, List.of(), List.of("weather")));
    assertThat(mcp.callbacksFor("research")).hasSize(2);
    assertThat(mcp.callbacksFor("research").stream().map(callback -> callback.getToolDefinition().name()))
        .containsExactly("weather_get_weather", "weather_get-alerts");
    // Unmapped agents are untouched.
    assertThat(mcp.callbacksFor("review")).isEmpty();
  }

  @Test
  void configurationChangesRewireTheBindings() {
    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "STREAMABLE_HTTP", "http://localhost:3002/mcp", true, "")));
    McpConnectionRegistry mcp = startedRegistry(connector("get_weather"));
    Await.until(() -> mcp.status().stream().anyMatch(status -> "CONNECTED".equals(status.state())));
    McpSyncClient firstClient = openedClients.get(0);

    // Moving the endpoint is a config change -> old client closed, new one opened.
    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "STREAMABLE_HTTP", "http://otherhost:3002/mcp", true, "")));
    mcp.reconcile();
    Mockito.verify(firstClient).closeGracefully();
    Await.until(() -> openedClients.size() >= 2);
    Await.until(() -> mcp.status().stream().allMatch(status -> "CONNECTED".equals(status.state())));

    // Removing the server closes its client and drops the binding.
    registry.updateMcpServers(List.of());
    mcp.reconcile();
    Mockito.verify(openedClients.get(1)).closeGracefully();
    assertThat(mcp.status()).isEmpty();
    assertThat(mcp.callbacksFor("research")).isEmpty();
  }

  @Test
  void failedServersSurfaceTheirErrorWithoutBlockingAgentCalls() {
    registry.updateMcpServers(List.of(
        new McpServerConfig("flaky", "STREAMABLE_HTTP", "http://localhost:9/mcp", true, "")));
    McpConnectionRegistry mcp = startedRegistry(config -> {
      throw new RuntimeException("connection refused");
    });

    Await.until(() -> mcp.status().stream().anyMatch(status -> "FAILED".equals(status.state())));
    McpServerStatus failed = mcp.status().get(0);
    assertThat(failed.error()).contains("connection refused");
    // No tools from a failed server, but the agent call itself is unaffected.
    registry.updateAgent("research",
        new AgentRuntimeConfig("research", "prompt", null, null, List.of(), List.of("flaky")));
    assertThat(mcp.callbacksFor("research")).isEmpty();
  }

  @Test
  void manualReconnectBypassesTheFailureCooldown() {
    registry.updateMcpServers(List.of(
        new McpServerConfig("flaky", "STREAMABLE_HTTP", "http://localhost:3002/mcp", true, "")));
    AtomicInteger attempts = new AtomicInteger();
    McpConnectionRegistry mcp = startedRegistry(config -> {
      if (attempts.incrementAndGet() == 1) {
        throw new RuntimeException("first attempt refused");
      }
      McpSyncClient client = Mockito.mock(McpSyncClient.class);
      stubClient(client, "get_weather");
      openedClients.add(client);
      return client;
    });

    Await.until(() -> mcp.status().stream().anyMatch(status -> "FAILED".equals(status.state())));
    McpServerStatus reconnected = mcp.reconnect("flaky");
    assertThat(reconnected.state()).isEqualTo("CONNECTED");
    assertThat(reconnected.toolNames()).containsExactly("get_weather");

    assertThatThrownBy(() -> mcp.reconnect("ghost")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledServersStayDisconnected() {
    registry.updateMcpServers(List.of(
        new McpServerConfig("off", "STREAMABLE_HTTP", "http://localhost:3002/mcp", false, "")));
    McpConnectionRegistry mcp = startedRegistry(connector("get_weather"));

    assertThat(mcp.status()).hasSize(1);
    assertThat(mcp.status().get(0).state()).isEqualTo("DISABLED");
    assertThat(mcp.status().get(0).enabled()).isFalse();
    registry.updateAgent("research",
        new AgentRuntimeConfig("research", "prompt", null, null, List.of(), List.of("off")));
    assertThat(mcp.callbacksFor("research")).isEmpty();
    assertThat(openedClients).isEmpty();
  }

  @Test
  void olderJsonStateWithoutStdioFieldsStillLoads() {
    McpServerConfig config = JsonMapper.builder().build().readValue(
        "{\"name\":\"weather\",\"transport\":\"SSE\",\"endpoint\":\"http://a\",\"enabled\":true,\"description\":\"d\"}",
        McpServerConfig.class);
    assertThat(config.name()).isEqualTo("weather");
    assertThat(config.command()).isEmpty();
    assertThat(config.args()).isEmpty();
    assertThat(config.env()).isEmpty();
  }
}
