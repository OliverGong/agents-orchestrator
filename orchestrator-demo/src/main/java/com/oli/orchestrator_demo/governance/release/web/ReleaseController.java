/**
 * @ClassName ReleaseController
 * @Description HTTP surface of the release pipeline under
 *              {@code /api/v1/releases}: publish a scenario to its configured
 *              portal ({@code POST /{scenarioId}/publish}), the registry
 *              view ({@code GET ?scenarioId=}) and package re-export
 *              ({@code GET /{id}/package}, attachment download). Validation
 *              failures surface as 400 via the global exception handler.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release.web;
import com.oli.orchestrator_demo.governance.release.ReleasePackage;
import com.oli.orchestrator_demo.governance.release.ReleaseRecord;
import com.oli.orchestrator_demo.governance.release.ReleaseService;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

  private final ReleaseService service;

  public ReleaseController(ReleaseService service) {
    this.service = service;
  }

  /** The release registry, newest first (optionally scoped to one scenario). */
  @GetMapping
  public List<ReleaseRecord> list(@RequestParam(required = false) String scenarioId) {
    return service.list(scenarioId);
  }

  /** Publishes one scenario: package build + hash + registry append + audit. */
  @PostMapping("/{scenarioId}/publish")
  public ReleaseRecord publish(@PathVariable String scenarioId) {
    return service.publish(scenarioId);
  }

  /** Re-exports the recorded portable package of one publish as a download. */
  @GetMapping("/{id}/package")
  public ResponseEntity<ReleasePackage> export(@PathVariable long id) {
    ReleasePackage pkg = service.export(id);
    String filename = "%s-%s.json".formatted(pkg.scenarioId(),
        pkg.releaseVersion() == null ? "unversioned" : pkg.releaseVersion());
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(pkg);
  }
}
