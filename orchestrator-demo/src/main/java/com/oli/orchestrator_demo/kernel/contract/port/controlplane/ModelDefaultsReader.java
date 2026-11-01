/**
 * @ClassName ModelDefaultsReader
 * @Description Read-only port over the per-agent model defaults bound from
 *              {@code orchestrator.agents.*} (see the agent module's
 *              configuration properties). The control plane consumes this port
 *              to seed and enrich descriptors without importing the agent
 *              module; the agent module provides the bridging implementation.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.kernel.contract.port.controlplane;

public interface ModelDefaultsReader {

  /** The default model settings of the given agent (nulls mean "inherit backend defaults"). */
  ModelDefaults defaults(String agent);

  /** Immutable view of one agent's default model selection. */
  record ModelDefaults(String model, Double temperature) {
  }
}
