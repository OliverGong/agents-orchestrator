/**
 * @ClassName TripPlanningController
 * @Description REST entry point of the demo: accepts a {@link TripRequest},
 *              triggers the trip planning workflow and returns the final
 *              {@link OrchestratorContext} with every agent's contribution.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.TripRequest;
import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;

@RestController
@RequestMapping("/api/trips")
public class TripPlanningController {

  private final TripPlanningOrchestrator orchestrator;

  public TripPlanningController(TripPlanningOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @PostMapping("/plan")
  public OrchestratorContext plan(@RequestBody TripRequest request) {
    validate(request);
    return orchestrator.planTrip(request);
  }

  private static void validate(TripRequest request) {
    if (request == null || request.destination() == null || request.destination().isBlank()
        || request.numberOfDays() <= 0
        || request.budget() == null || request.budget().signum() <= 0) {
      throw new IllegalArgumentException(
          "destination must not be blank, numberOfDays must be positive and budget must be positive");
    }
  }
}
