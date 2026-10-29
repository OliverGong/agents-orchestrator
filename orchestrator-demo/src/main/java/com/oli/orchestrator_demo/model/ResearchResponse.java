/**
 * @ClassName ResearchResponse
 * @Description Step 1 output: attractions, travel tips and food options.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

import java.util.List;

public record ResearchResponse(
  List<String> attractions,
  List<String> travelTips,
  List<String> foodOptions
) {

}
