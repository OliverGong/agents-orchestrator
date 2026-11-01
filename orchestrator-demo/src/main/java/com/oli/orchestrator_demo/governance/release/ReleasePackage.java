/**
 * @ClassName ReleasePackage
 * @Description The portable JSON bundle produced by the release pipeline
 *              (v2.2 M4) when a scenario is published: the scenario snapshot
 *              (template + overrides), the resolved execution plan (token-free
 *              dry-run report as publish evidence), the target portal and the
 *              release-stage configuration (version / changelog / artifact
 *              type) from the metadata plane. Self-contained, so any portal
 *              can consume it without access to this orchestrator instance.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release;

import java.time.Instant;

import com.oli.orchestrator_demo.governance.scenario.DryRunReport;
import com.oli.orchestrator_demo.governance.scenario.Scenario;

public record ReleasePackage(
  String format,
  String formatVersion,
  String scenarioId,
  String releaseVersion,
  String changelog,
  String artifactType,
  String portalId,
  String portalName,
  String portalEndpoint,
  Instant publishedAt,
  String tool,
  Scenario scenario,
  DryRunReport resolvedPlan
) {

  public static final String FORMAT = "orchestrator-release-package";
  public static final String FORMAT_VERSION = "1.0";
  public static final String TOOL = "orchestrator";
}
