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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.model.OrchestratorContext;
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

  protected AbstractContextAgent(String name, ChatModel chatModel, AgentRuntimeStore runtimeStore) {
    this.name = name;
    this.runtimeStore = runtimeStore;
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
      }
    }
    throw lastFailure;
  }

  /**
   * The runtime configuration of this agent as edited through the control
   * plane (system prompt, model, temperature, calibration rules).
   */
  protected AgentRuntimeConfig runtime() {
    return runtimeStore.agent(name);
  }

  /** Chat options builder from the runtime configuration; null values inherit the backend defaults. */
  protected ChatOptions.Builder<?> chatOptions(AgentRuntimeConfig config) {
    ChatOptions.Builder<?> options = ChatOptions.builder();
    if (config.model() != null && !config.model().isBlank()) {
      options = options.model(config.model());
    }
    if (config.temperature() != null) {
      options = options.temperature(config.temperature());
    }
    return options;
  }

  /** Produces the agent specific structured response from the current workflow state. */
  protected abstract R respond(OrchestratorContext context);

  /** Returns a new context carrying this agent's response. */
  protected abstract OrchestratorContext merge(OrchestratorContext context, R response);
}
