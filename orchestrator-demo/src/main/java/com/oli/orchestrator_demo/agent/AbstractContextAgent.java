/**
 * @ClassName AbstractContextAgent
 * @Description Base class for the LLM backed agents of the trip planning
 *              workflow. It fixes the common contract: read the current
 *              {@link OrchestratorContext}, ask the agent's ChatClient for its
 *              own structured response type, and return an updated context.
 *              Subclasses only define their prompt ({@link #respond}) and how
 *              their response is merged back into the context ({@link #merge}).
 *
 *              Since control-plane v1 the system prompt, model selection and
 *              temperature are no longer baked into compile-time ChatClient
 *              beans: the client is built once per agent on the shared
 *              {@link ChatModel} (with the cross-cutting advisors), while the
 *              persona and options are read from the
 *              {@link AgentRuntimeStore} on every call — so control-plane
 *              edits hot-apply to the very next model call. The advisor chain
 *              is CalibrationAdvisor (outermost, applies the user-defined
 *              校准 rules) -> ResponseSanitizerAdvisor (normalizes the model
 *              output for the structured conversion) -> model call.
 *
 *              Cross-cutting instrumentation (execution timing) is applied
 *              around {@link #execute} by {@code AgentExecutionAspect}, which
 *              is why {@code execute} must stay overridable (no final).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.1
 */
package com.oli.orchestrator_demo.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.oli.orchestrator_demo.control.AgentDescriptor;
import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.mcp.McpConnectionRegistry;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityEventBus;
import com.oli.orchestrator_demo.observability.RunContext;
import tools.jackson.core.JacksonException;

public abstract class AbstractContextAgent<R> implements Agent<OrchestratorContext, OrchestratorContext> {

  private static final Logger log = LoggerFactory.getLogger(AbstractContextAgent.class);

  /**
   * Small local models occasionally emit syntactically broken JSON even after
   * the {@link ResponseSanitizerAdvisor} has stripped Markdown fences. A single
   * retry (the model re-samples) resolves most of those transient failures;
   * exhausted retries surface as 502 via the global exception handler.
   */
  static final int MAX_ATTEMPTS = 2;

  private final String name;
  protected final ChatClient chatClient;
  private final AgentRuntimeStore runtimeStore;
  /**
   * Null only in template-method tests: production agents get the registry
   * injected by Spring and expose the tools of their mapped MCP servers.
   */
  private final McpConnectionRegistry mcpRegistry;

  protected AbstractContextAgent(String name, ChatModel chatModel, AgentRuntimeStore runtimeStore) {
    this(name, chatModel, runtimeStore, null);
  }

  protected AbstractContextAgent(String name, ChatModel chatModel, AgentRuntimeStore runtimeStore,
      McpConnectionRegistry mcpRegistry) {
    this.name = name;
    this.runtimeStore = runtimeStore;
    this.mcpRegistry = mcpRegistry;
    // Null model only occurs in template-method tests that override respond();
    // the builder would reject it, so the client stays unused there anyway.
    this.chatClient = chatModel == null ? null : ChatClient.builder(chatModel)
        .defaultAdvisors(new SimpleLoggerAdvisor(),
            new CalibrationAdvisor(name, runtimeStore),
            new ResponseSanitizerAdvisor())
        .build();
  }

  // Deliberately not final: the CGLIB proxy created for AgentExecutionAspect
  // is instantiated without running a constructor (Objenesis), so every method
  // read by the outside world (name, execute) must go through the proxy and
  // delegate to the real target — final methods would leak the proxy's
  // uninitialized fields (e.g. name() returning null).
  @Override
  public String name() {
    return name;
  }

  /**
   * The agent's self-declared slot contract (title, human description, and
   * which {@link com.oli.orchestrator_demo.model.OrchestratorContext} slots it
   * produces, consumes and tolerates to be missing). Collected at startup by
   * the control plane and used for the logical-closure validation of
   * collaboration templates. Deliberately not final: like {@link #name()},
   * it is read through the CGLIB proxy created for {@code AgentExecutionAspect}.
   */
  public abstract AgentDescriptor descriptor();

  @Override
  public OrchestratorContext execute(OrchestratorContext context) {
    R response = respondWithRetry(context);
    return merge(context, response);
  }

  private R respondWithRetry(OrchestratorContext context) {
    JacksonException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return respond(context);
      }
      catch (JacksonException ex) {
        lastFailure = ex;
        log.warn("{}: attempt {}/{} failed to parse model output ({}), retrying",
            name, attempt, MAX_ATTEMPTS, ex.getMessage());
        publishRetry(attempt, ex.getMessage());
      }
    }
    throw lastFailure;
  }

  /**
   * Reports one parse retry into the observability stream so the console can
   * visualize the retry chain of a stage. Attribution comes from the
   * {@link RunContext} thread-local; without it (template-method tests, calls
   * outside a workflow run) the event is silently dropped.
   */
  private void publishRetry(int attempt, String errorMessage) {
    RunContext.Attribution attribution = RunContext.current();
    if (attribution == null) {
      return;
    }
    ObservabilityEventBus.publish(AgentObservabilityEvent.stageRetried(attribution.runId(),
        attribution.stageName(), attribution.agentName(), attribution.waveIndex(),
        Thread.currentThread().getName(), attempt, errorMessage));
  }

  /**
   * The runtime configuration of this agent as edited through the control
   * plane (system prompt, model, temperature, calibration rules).
   */
  protected AgentRuntimeConfig runtime() {
    return runtimeStore.agent(name);
  }

  /**
   * Chat options builder from the runtime configuration plus the tool
   * callbacks of the MCP servers this agent is mapped to (agents without MCP
   * mappings keep the plain builder, so they are unaffected); null values
   * inherit the backend defaults. The tools are resolved per call, so
   * control-plane MCP edits and reconnects hot-apply to the very next model
   * call.
   */
  protected ChatOptions.Builder<?> chatOptions(AgentRuntimeConfig config) {
    String model = config.model() != null && !config.model().isBlank() ? config.model() : null;
    Double temperature = config.temperature();
    List<ToolCallback> tools = mcpRegistry == null ? List.of() : mcpRegistry.callbacksFor(name);
    if (tools.isEmpty()) {
      ChatOptions.Builder<?> options = ChatOptions.builder();
      if (model != null) {
        options = options.model(model);
      }
      if (temperature != null) {
        options = options.temperature(temperature);
      }
      return options;
    }
    ToolCallingChatOptions.Builder<?> options = ToolCallingChatOptions.builder();
    if (model != null) {
      options = options.model(model);
    }
    if (temperature != null) {
      options = options.temperature(temperature);
    }
    return options.toolCallbacks(tools);
  }

  /** Produces the agent specific structured response from the current workflow state. */
  protected abstract R respond(OrchestratorContext context);

  /** Returns a new context carrying this agent's response. */
  protected abstract OrchestratorContext merge(OrchestratorContext context, R response);
}
