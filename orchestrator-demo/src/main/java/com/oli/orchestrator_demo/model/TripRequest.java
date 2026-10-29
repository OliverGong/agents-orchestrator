/**
 * @ClassName TripRequest
 * @Description User input of the trip planning workflow: destination, number
 *              of days, total budget and free-form preferences.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

import java.math.BigDecimal;

public record TripRequest(
  String destination,
  int numberOfDays,
  BigDecimal budget,
  String preferences
) {

}
