/**
 * @ClassName InstrumentedMcpTool
 * @Description Wraps the plain {@link SyncMcpToolCallback} of one MCP tool
 *              with the cross-cutting concerns the workflow needs: the tool is
 *              renamed to {@code <server>_<tool>} (so tools from different
 *              servers can never collide in the LLM's tool registry and the
 *              server stays auditable from the name), every invocation is
 *              timed into Micrometer
 *              ({@code mcp.tool.call.duration}, tagged server/tool/outcome)
 *              and reported to the {@link ObservabilityActor} as a
 *              {@code TOOL_CALLED} event attributed to the currently executing
 *              stage via the {@link RunContext} thread-local (calls outside a
 *              workflow run are simply not attributed). Failures are recorded
 *              and rethrown — the model sees the tool error and can react.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import com.oli.orchestrator_demo.control.McpServerConfig;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.RunContext;

class InstrumentedMcpTool implements ToolCallback {

  /** Tool results are summarized into events up to this length. */
  static final int MAX_RESULT_SUMMARY = 4_000;

  private final SyncMcpToolCallback delegate;
  private final String serverName;
  private final ObservabilityActor observability;
  private final MeterRegistry meterRegistry;

  InstrumentedMcpTool(SyncMcpToolCallback delegate, McpServerConfig server,
      ObservabilityActor observability, MeterRegistry meterRegistry) {
    this.delegate = delegate;
    this.serverName = server.name();
    this.observability = observability;
    this.meterRegistry = meterRegistry;
  }

  /** The original (un-prefixed) tool name as registered by the MCP server. */
  String originalName() {
    return delegate.getOriginalToolName();
  }

  /**
   * {@code <server>_<tool>}, sanitized to identifier-safe characters: the
   * composite name is unique across servers and stable for the model.
   */
  String compositeName() {
    return (serverName + "_" + delegate.getOriginalToolName()).replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  @Override
  public ToolDefinition getToolDefinition() {
    ToolDefinition original = delegate.getToolDefinition();
    return new DefaultToolDefinition(compositeName(), original.description(), original.inputSchema());
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    long start = System.nanoTime();
    String outcome = "OK";
    try {
      String result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
      report(start, outcome, result, null);
      return result;
    }
    catch (RuntimeException ex) {
      outcome = "ERROR";
      report(start, outcome, null, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
      throw ex;
    }
  }

  private void report(long startNanos, String outcome, String result, String errorMessage) {
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
    Timer.builder("mcp.tool.call.duration")
        .description("MCP tool invocation duration")
        .tag("server", serverName)
        .tag("tool", originalName())
        .tag("outcome", outcome)
        .register(meterRegistry)
        .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    RunContext.Attribution attribution = RunContext.current();
    String summary = result == null ? null
        : result.length() <= MAX_RESULT_SUMMARY ? result : result.substring(0, MAX_RESULT_SUMMARY) + "...[truncated]";
    observability.tell(AgentObservabilityEvent.toolCalled(
        attribution == null ? null : attribution.runId(),
        attribution == null ? null : attribution.stageName(),
        attribution == null ? null : attribution.agentName(),
        attribution == null ? null : attribution.waveIndex(),
        serverName, originalName(), durationMs, summary, errorMessage));
  }
}
