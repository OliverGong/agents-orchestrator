/**
 * @ClassName AgentDescriptor
 * @Description Self-declared slot contract of an agent: which blackboard slots
 *              it writes ({@code outputSlots}), which it reads
 *              ({@code inputSlots}) and which missing inputs it explicitly
 *              tolerates ({@code pendingSafeSlots} — the agent states the
 *              absence in its prompt instead of failing or inventing content).
 *              The contracts are collected from the agent beans at startup
 *              (see {@link AgentDescriptorRegistry}), seeded into the
 *              control-plane state and used by
 *              {@link com.oli.orchestrator_demo.workflow.WorkflowTemplate}
 *              validation to prove that a collaboration template is logically
 *              closed: every consumed slot is either produced by a stage that
 *              completes before the consumer starts, or declared pending-safe.
 *
 *              {@code defaultModel}/{@code defaultTemperature} document the
 *              built-in model defaults (`orchestrator.agents.*`); they are
 *              informational for the console (the effective runtime values
 *              live in {@link AgentRuntimeConfig}).
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.List;

public record AgentDescriptor(
  String name,
  String title,
  String description,
  List<String> outputSlots,
  List<String> inputSlots,
  List<String> pendingSafeSlots,
  String defaultModel,
  Double defaultTemperature
) {

  public AgentDescriptor {
    title = title == null || title.isBlank() ? name : title;
    description = description == null ? "" : description;
    outputSlots = outputSlots == null ? List.of() : List.copyOf(outputSlots);
    inputSlots = inputSlots == null ? List.of() : List.copyOf(inputSlots);
    pendingSafeSlots = pendingSafeSlots == null ? List.of() : List.copyOf(pendingSafeSlots);
  }
}
