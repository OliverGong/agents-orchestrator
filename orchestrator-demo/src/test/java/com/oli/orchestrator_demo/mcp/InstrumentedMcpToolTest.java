/**
 * @ClassName InstrumentedMcpToolTest
 * @Description Verifies the tool wrapper: composite (server-prefixed) naming,
 *              attributed {@code TOOL_CALLED} events from the {@link RunContext},
 *              Micrometer timers, failure recording with rethrow, result
 *              truncation and the no-attribution path outside a run.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import com.oli.orchestrator_demo.control.McpServerConfig;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.RunContext;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class InstrumentedMcpToolTest {

  private final ObservabilityActor observability = Mockito.mock(ObservabilityActor.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private SyncMcpToolCallback delegateReturning(String result) {
    SyncMcpToolCallback delegate = Mockito.mock(SyncMcpToolCallback.class);
    Mockito.when(delegate.getOriginalToolName()).thenReturn("get weather!");
    Mockito.when(delegate.getToolDefinition())
        .thenReturn(new DefaultToolDefinition("get weather!", "Fetch the forecast", "{}"));
    Mockito.when(delegate.call(Mockito.anyString())).thenReturn(result);
    return delegate;
  }

  private InstrumentedMcpTool tool(SyncMcpToolCallback delegate) {
    return new InstrumentedMcpTool(delegate,
        new McpServerConfig("weather", "STREAMABLE_HTTP", "http://localhost:3002/mcp", true, ""),
        observability, meters);
  }

  @Test
  void renamesTheToolToTheSanitizedServerPrefixedComposite() {
    assertThat(tool(delegateReturning("ok")).getToolDefinition().name())
        .isEqualTo("weather_get_weather_"); // trailing underscore from the sanitized '!'
    assertThat(tool(delegateReturning("ok")).getToolDefinition().description())
        .isEqualTo("Fetch the forecast");
  }

  @Test
  void reportsAttributedEventsAndMetricsOnSuccess() {
    RunContext.set(new RunContext.Attribution("run-1", "research", "research", 0));
    try {
      assertThat(tool(delegateReturning("sunny")).call("{}")).isEqualTo("sunny");
    }
    finally {
      RunContext.clear();
    }
    ArgumentCaptor<AgentObservabilityEvent> events = ArgumentCaptor.forClass(AgentObservabilityEvent.class);
    Mockito.verify(observability).tell(events.capture());
    AgentObservabilityEvent event = events.getValue();
    assertThat(event.type()).isEqualTo(AgentObservabilityEvent.Type.TOOL_CALLED);
    assertThat(event.runId()).isEqualTo("run-1");
    assertThat(event.stageName()).isEqualTo("research");
    assertThat(event.agentName()).isEqualTo("research");
    assertThat(event.waveIndex()).isZero();
    assertThat(event.toolCall().serverName()).isEqualTo("weather");
    assertThat(event.toolCall().toolName()).isEqualTo("get weather!");
    assertThat(event.toolCall().outcome()).isEqualTo("OK");
    assertThat(event.toolCall().resultSummary()).isEqualTo("sunny");
    assertThat(meters.get("mcp.tool.call.duration").tag("server", "weather").tag("outcome", "OK")
        .timer().count()).isEqualTo(1);
  }

  @Test
  void recordsFailuresAndRethrows() {
    SyncMcpToolCallback delegate = Mockito.mock(SyncMcpToolCallback.class);
    Mockito.when(delegate.getOriginalToolName()).thenReturn("echo");
    Mockito.when(delegate.getToolDefinition()).thenReturn(new DefaultToolDefinition("echo", "d", "{}"));
    Mockito.when(delegate.call(Mockito.anyString())).thenThrow(new IllegalStateException("boom"));

    RunContext.set(new RunContext.Attribution("run-2", "plan", "itinerary", 1));
    try {
      assertThatThrownBy(() -> tool(delegate).call("{}")).isInstanceOf(IllegalStateException.class);
    }
    finally {
      RunContext.clear();
    }
    ArgumentCaptor<AgentObservabilityEvent> events = ArgumentCaptor.forClass(AgentObservabilityEvent.class);
    Mockito.verify(observability).tell(events.capture());
    assertThat(events.getValue().toolCall().outcome()).isEqualTo("ERROR");
    assertThat(events.getValue().toolCall().errorMessage()).isEqualTo("boom");
    assertThat(meters.get("mcp.tool.call.duration").tag("outcome", "ERROR").timer().count()).isEqualTo(1);
  }

  @Test
  void callsOutsideARunCarryNoAttribution() {
    assertThat(tool(delegateReturning("ok")).call("{}")).isEqualTo("ok");
    ArgumentCaptor<AgentObservabilityEvent> events = ArgumentCaptor.forClass(AgentObservabilityEvent.class);
    Mockito.verify(observability).tell(events.capture());
    assertThat(events.getValue().runId()).isNull();
    assertThat(events.getValue().stageName()).isNull();
    assertThat(events.getValue().toolCall().serverName()).isEqualTo("weather");
  }

  @Test
  void longResultsAreTruncatedOnlyInTheEventSummary() {
    String longResult = "x".repeat(InstrumentedMcpTool.MAX_RESULT_SUMMARY + 100);
    RunContext.set(new RunContext.Attribution("run-3", "research", "research", 0));
    try {
      assertThat(tool(delegateReturning(longResult)).call("{}")).isEqualTo(longResult);
    }
    finally {
      RunContext.clear();
    }
    ArgumentCaptor<AgentObservabilityEvent> events = ArgumentCaptor.forClass(AgentObservabilityEvent.class);
    Mockito.verify(observability).tell(events.capture());
    assertThat(events.getValue().toolCall().resultSummary())
        .hasSize(InstrumentedMcpTool.MAX_RESULT_SUMMARY + "...[truncated]".length())
        .endsWith("...[truncated]");
  }
}
