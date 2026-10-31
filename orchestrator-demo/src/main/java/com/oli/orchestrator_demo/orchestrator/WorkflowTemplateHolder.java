/**
 * @ClassName WorkflowTemplateHolder
 * @Description Holds the currently active {@link WorkflowTemplate} of the
 *              orchestrator as a volatile reference. Initialized from
 *              {@link WorkflowProperties} at startup, it is the single
 *              hot-swap point through which the control plane replaces the
 *              collaboration template at runtime (PUT
 *              {@code /api/v1/control/workflow}): the orchestrator reads the
 *              reference once per run, so a swap takes effect on the next
 *              plan request without touching running ones. Replacement
 *              validation (known agents, dependency order) is the caller's
 *              responsibility.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.orchestrator;

import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.workflow.WorkflowProperties;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

@Component
public class WorkflowTemplateHolder {

  private volatile WorkflowTemplate current;

  public WorkflowTemplateHolder(WorkflowProperties properties) {
    this.current = properties.toTemplate();
  }

  /** The template the next plan request will execute. */
  public WorkflowTemplate get() {
    return current;
  }

  /** Hot-swaps the active template (the next run picks it up). */
  public void replace(WorkflowTemplate next) {
    this.current = next;
  }
}
