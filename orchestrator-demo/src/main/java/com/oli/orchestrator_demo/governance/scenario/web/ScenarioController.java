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
package com.oli.orchestrator_demo.governance.scenario.web;
import com.oli.orchestrator_demo.governance.scenario.dryrun.DryRunReport;
import com.oli.orchestrator_demo.governance.scenario.model.Scenario;
import com.oli.orchestrator_demo.governance.scenario.store.ScenarioRegistry;
import com.oli.orchestrator_demo.governance.scenario.model.ScenarioState;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oli.orchestrator_demo.governance.control.registry.AgentConfigRegistry;
import com.oli.orchestrator_demo.governance.control.state.ControlPlaneState;
import com.oli.orchestrator_demo.governance.teamlog.TeamLogRecorder;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

  private final ScenarioRegistry registry;
  private final AgentConfigRegistry controlPlane;
  private final TeamLogRecorder teamLog;

  public ScenarioController(ScenarioRegistry registry, AgentConfigRegistry controlPlane,
      TeamLogRecorder teamLog) {
    this.registry = registry;
    this.controlPlane = controlPlane;
    this.teamLog = teamLog;
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
    ScenarioState state = registry.create(scenario);
    teamLog.audit("scenario-plane", "SCENARIO_CREATED", "scenario:" + scenario.id(), scenario);
    return state;
  }

  @PutMapping("/{id}")
  public ScenarioState update(@PathVariable String id, @RequestBody Scenario scenario) {
    ScenarioState state = registry.update(id, scenario);
    teamLog.audit("scenario-plane", "SCENARIO_UPDATED", "scenario:" + id, scenario);
    return state;
  }

  @DeleteMapping("/{id}")
  public ScenarioState delete(@PathVariable String id) {
    ScenarioState state = registry.delete(id);
    teamLog.audit("scenario-plane", "SCENARIO_DELETED", "scenario:" + id, null);
    return state;
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
    teamLog.audit("scenario-plane", "SCENARIO_ACTIVATED", "scenario:" + id, null);
    return controlPlane.maskedState();
  }
}
