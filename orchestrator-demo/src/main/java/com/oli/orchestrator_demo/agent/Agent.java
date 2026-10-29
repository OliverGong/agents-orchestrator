/**
 * @ClassName Agent
 * @Description A single step of the orchestration workflow. Every agent is a
 *              small, single-responsibility unit of work defined on the
 *              abstract {@link com.oli.orchestrator_demo.model.OrchestratorContext}.
 *              Agents are resolved by their {@link #name()} when a
 *              collaboration template references them.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

public interface Agent<I, O> {

  /** Identifier of the agent, used by templates and for logging. */
  String name();

  /** Executes the agent's unit of work and returns its contribution. */
  O execute(I input);
}
