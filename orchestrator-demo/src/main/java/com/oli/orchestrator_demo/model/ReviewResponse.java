/**
 * @ClassName ReviewResponse
 * @Description Step 4 output: final review verdict, issues and summary.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

import java.util.List;

public record ReviewResponse(
  boolean isValid, // Whether the itinerary is valid
  String summary, // Summary of the review
  List<String> issues, // List of issues found in the itinerary
  List<String> recommendations // Recommendations for improving the itinerary
) {

}
