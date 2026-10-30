/**
 * @ClassName CalibrationRule
 * @Description One user-defined output calibration rule of an agent: a
 *              regular expression that is replaced in the raw model output
 *              (after the {@link com.oli.orchestrator_demo.agent.ResponseSanitizerAdvisor}
 *              has normalized it) before the structured-output conversion.
 *              Rules let operators 规范化/格式化/校准 a model's response without
 *              touching code, e.g. normalizing date formats or stripping
 *              recurring preamble phrases. {@code replacement} supports
 *              regex capture groups ({@code $1}); leave it empty to delete
 *              matches. An invalid pattern is skipped with a warning instead
 *              of failing the workflow.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

public record CalibrationRule(String pattern, String replacement, String description) {

  public CalibrationRule {
    pattern = pattern == null ? "" : pattern;
    replacement = replacement == null ? "" : replacement;
    description = description == null ? "" : description;
  }
}
