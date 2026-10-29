/**
 * @ClassName BudgetResponse
 * @Description Step 2 output: cost breakdown, total, budget check and
 *              recommendations.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

import java.math.BigDecimal;
import java.util.List;

public record BudgetResponse(
  BigDecimal accommodation,
  BigDecimal transportCost,
  BigDecimal foodCost,
  BigDecimal activityCost,
  BigDecimal totalCost,
  boolean isBudgetMet, // Whether the budget was met
  List<String> recommendations // Recommendations for the budget

) {

}
