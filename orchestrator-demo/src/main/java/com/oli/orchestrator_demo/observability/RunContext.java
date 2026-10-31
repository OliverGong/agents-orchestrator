/**
 * @ClassName RunContext
 * @Description Thread-local attribution context for code that fires deep
 *              inside an agent call but needs to report against the workflow
 *              run that is currently executing: the MCP tool callbacks read it
 *              to stamp every {@code TOOL_CALLED} event with the owning run,
 *              stage, agent and wave. The orchestrator sets it around each
 *              stage execution (which runs on the request thread in sequential
 *              mode and on a dedicated virtual thread per stage in concurrent
 *              mode, so the thread-local is both correct and isolated).
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

public final class RunContext {

  /** The attribution of the stage currently executing on this thread. */
  public record Attribution(String runId, String stageName, String agentName, Integer waveIndex) {
  }

  private static final ThreadLocal<Attribution> CURRENT = new ThreadLocal<>();

  private RunContext() {
  }

  public static void set(Attribution attribution) {
    CURRENT.set(attribution);
  }

  /** {@code null} when the thread is not inside a workflow stage (e.g. a test). */
  public static Attribution current() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
