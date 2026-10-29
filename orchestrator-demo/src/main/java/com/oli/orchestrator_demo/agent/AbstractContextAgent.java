/**
 * @ClassName AbstractContextAgent
 * @Description Base class for the LLM backed agents of the trip planning
 *              workflow. It fixes the common contract: read the current
 *              {@link OrchestratorContext}, ask the agent's ChatClient for its
 *              own structured response type, and return an updated context.
 *              Subclasses only define their prompt ({@link #respond}) and how
 *              their response is merged back into the context ({@link #merge}).
 *              Cross-cutting instrumentation (execution timing) is applied
 *              around {@link #execute} by {@code AgentExecutionAspect}, which
 *              is why {@code execute} must stay overridable (no final).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

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

  protected AbstractContextAgent(String name, ChatClient chatClient) {
    this.name = name;
    this.chatClient = chatClient;
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

  /** Produces the agent specific structured response from the current workflow state. */
  protected abstract R respond(OrchestratorContext context);

  /** Returns a new context carrying this agent's response. */
  protected abstract OrchestratorContext merge(OrchestratorContext context, R response);
}
