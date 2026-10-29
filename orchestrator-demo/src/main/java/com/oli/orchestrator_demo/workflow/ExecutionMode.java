/**
 * @ClassName ExecutionMode
 * @Description Execution strategy of a {@link WorkflowTemplate collaboration
 *              template}, selected through `orchestrator.workflow.mode`.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.workflow;

public enum ExecutionMode {

  /** Stages run one after another in template definition order. */
  SEQUENTIAL,

  /**
   * Stages of the same dependency wave run in parallel (virtual threads).
   * Parallel stages start from the same context snapshot and their
   * contributions are merged afterwards; they should therefore write
   * disjoint slots of the blackboard.
   */
  CONCURRENT
}
