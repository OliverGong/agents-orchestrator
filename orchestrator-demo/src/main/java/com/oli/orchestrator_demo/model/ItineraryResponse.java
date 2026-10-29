/**
 * @ClassName ItineraryResponse
 * @Description Step 3 output: day-by-day itinerary plan.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

import java.math.BigDecimal;
import java.util.List;

public record ItineraryResponse(
  List<DayPlan> dayPlans // List of day plans for the itinerary
) {
  public record DayPlan(
    int day,
    String morning,
    String afternoon,
    String evening,
    BigDecimal estimatedCost // Estimated cost for the day
  ) {

  }

}
