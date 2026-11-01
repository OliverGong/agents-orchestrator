/**
 * @ClassName ReleaseRecord
 * @Description One immutable entry of the release registry (v2.2 M4): every
 *              publish of a scenario to its configured portal appends a row
 *              with the resolved release metadata, the SHA-256 hash of the
 *              portable {@link ReleasePackage} JSON and the package itself
 *              (so any published version stays re-exportable).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release.model;

import java.time.Instant;

public record ReleaseRecord(
  Long id,
  String scenarioId,
  String portalId,
  String releaseVersion,
  String changelog,
  String artifactType,
  Instant publishedAt,
  String packageHash,
  String packageJson
) {
}
