/**
 * @ClassName ScenarioController
 * @Description HTTP surface of the scenario plane under
 *              {@code /api/v1/scenarios}: CRUD for the scenario meta-model
 *              (request template, workflow/agent overrides, MCP mapping,
 *              artifact type), the token-free dry-run rehearsal
 *              ({@code POST /{id}/dry-run}) and the dynamic instantiation
 *              path ({@code POST /{id}/activate} — hot-applies the scenario's
 *              logic group onto the control plane). Validation failures
 *              surface as 400 via the global exception handler.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.ControlPlaneState;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

  private final ScenarioRegistry registry;
  private final AgentConfigRegistry controlPlane;

  public ScenarioController(ScenarioRegistry registry, AgentConfigRegistry controlPlane) {
    this.registry = registry;
    this.controlPlane = controlPlane;
  }

  @GetMapping
  public ScenarioState scenarios() {
    return registry.state();
  }

  @GetMapping("/{id}")
  public ResponseEntity<Scenario> scenario(@PathVariable String id) {
    Scenario scenario = registry.scenario(id);
    return scenario == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(scenario);
  }

  @PostMapping
  public ScenarioState create(@RequestBody Scenario scenario) {
    return registry.create(scenario);
  }

  @PutMapping("/{id}")
  public ScenarioState update(@PathVariable String id, @RequestBody Scenario scenario) {
    return registry.update(id, scenario);
  }

  @DeleteMapping("/{id}")
  public ScenarioState delete(@PathVariable String id) {
    return registry.delete(id);
  }

  /** Token-free rehearsal: validation + wave expansion + slot flows. */
  @PostMapping("/{id}/dry-run")
  public DryRunReport dryRun(@PathVariable String id) {
    return registry.dryRun(id);
  }

  /** Dynamic instantiation: hot-applies the scenario onto the control plane. */
  @PostMapping("/{id}/activate")
  public ControlPlaneState activate(@PathVariable String id) {
    registry.activate(id);
    return controlPlane.maskedState();
  }
}
