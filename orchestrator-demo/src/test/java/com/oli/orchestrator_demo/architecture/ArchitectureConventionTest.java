package com.oli.orchestrator_demo.architecture;
import com.oli.orchestrator_demo.kernel.contract.port.runtime.McpConnectionGateway;
import com.oli.orchestrator_demo.kernel.contract.port.runtime.RunStatusReader;
import com.oli.orchestrator_demo.kernel.contract.port.runtime.RunInstanceSink;
import com.oli.orchestrator_demo.kernel.contract.port.controlplane.ModelDefaultsReader;
import com.oli.orchestrator_demo.kernel.contract.port.controlplane.ControlPlaneReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture fitness functions (see ARCHITECTURE §15.3/§15.6): the
 * dependency rules of the v3.0 layout are enforced here so the structure
 * cannot silently erode. Sources are scanned from the module working
 * directory (Surefire runs with the module base dir), which keeps the test
 * dependency-free — no ArchUnit needed — and works fully offline.
 *
 * Rules:
 *  R1 shared/ (cross-cutting kernel) imports no business domain at all
 *     (business SPIs live in contract/).
 *  R2 contract/ (stable execution contract) imports nothing in-process.
 *  R3 the module dependency graph (zone-qualified modules, e.g. core.agent)
 *     is acyclic.
 *  R4 infra/ adapters are only referenced from their own module
 *     (assembly configs and tests included).
 *  R5 web/ presentation classes never touch any infra/ adapter.
 */
class ArchitectureConventionTest {

    private static final Path BASE = Paths.get("src/main/java/com/oli/orchestrator_demo");

    private static final String ROOT_PKG = "com.oli.orchestrator_demo";

    /** The four zones grouping the modules (see ARCHITECTURE §3: 4 zones,
     *  2-5 modules each — the 3-to-5 abstraction rule). */
    private static final Set<String> ZONES = Set.of("kernel", "core", "governance", "ops");

    /** Zone-qualified modules; tests and the application root are not modules. */
    private static final Set<String> MODULES = Set.of(
            "kernel.shared", "kernel.contract",
            "core.trip", "core.agent", "core.workflow", "core.mcp",
            "governance.control", "governance.scenario", "governance.meta",
            "governance.release", "governance.teamlog",
            "ops.observability", "ops.instance");

    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(" + Pattern.quote(ROOT_PKG) + "\\.[\\w.]+)\\s*;", Pattern.MULTILINE);

    record Source(String pkg, Path path, List<String> imports) {
    }

    private static List<Source> sources() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(BASE), "source tree not on the test classpath working dir");
        try (Stream<Path> walk = Files.walk(BASE)) {
            return walk.filter(p -> p.toString().endsWith(".java")).map(ArchitectureConventionTest::parse).toList();
        }
    }

    private static Source parse(Path path) {
        String rel = BASE.relativize(path).toString();
        String pkg = rel.substring(0, rel.lastIndexOf('.'))
                .replace('/', '.').replace('\\', '.');
        List<String> imports = new ArrayList<>();
        try {
            Matcher m = IMPORT.matcher(Files.readString(path));
            while (m.find()) {
                imports.add(m.group(1));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
        return new Source(pkg, path, imports);
    }

    /** R1 — shared/ kernel: zero business-domain imports (SPIs live in contract/). */
    @Test
    void sharedKernelOnlyTouchesTheSpiSeam() throws IOException {
        List<String> violations = sources().stream()
                .filter(s -> s.pkg().startsWith("kernel.shared."))
                .flatMap(s -> s.imports().stream()
                        .filter(i -> {
                            String m = moduleOf(i);
                            return MODULES.contains(m) && !m.startsWith("kernel.");
                        })
                        .map(i -> s.pkg() + " -> " + i))
                .toList();
        assertTrue(violations.isEmpty(), "shared/ must stay decoupled from business domains: " + violations);
    }

    /** R2 — contract/ is a pristine data plane: imports only within itself.
     *  The spi/model/config/port subpackages are navigation-only grouping;
     *  moduleOf() still resolves them to the single kernel.contract module,
     *  so intra-contract references count as package-internal. */
    @Test
    void contractPackageImportsNothingOutsideItself() throws IOException {
        List<String> violations = sources().stream()
                .filter(s -> s.pkg().startsWith("kernel.contract."))
                .flatMap(s -> s.imports().stream()
                        .filter(i -> !moduleOf(i).equals("kernel.contract"))
                        .map(i -> s.pkg() + " -> " + i))
                .toList();
        assertTrue(violations.isEmpty(), "contract/ must not import in-process types: " + violations);
    }

    /** R3 — the module graph (zone-qualified, e.g. core.agent) has no cycles.
     *  All four historical waivers were inverted (ARCHITECTURE §15.6.2):
     *  pure records sank into kernel/contract and narrow read/write ports
     *  (ModelDefaultsReader, ControlPlaneReader, McpConnectionGateway,
     *  RunInstanceSink, RunStatusReader) replaced the back-edges. */
    @Test
    void moduleDependencyGraphIsAcyclic() throws IOException {
        // Zero waivers — new cycles must be resolved by inversion, not by
        // extending this set (see ARCHITECTURE §15.6).
        Set<String> waived = Set.of();
        Map<String, Set<String>> graph = new HashMap<>();
        for (Source s : sources()) {
            String from = moduleOf(s.pkg());
            for (String i : s.imports()) {
                String to = moduleOf(i);
                if (!to.equals(from) && !waived.contains(pair(from, to))) {
                    graph.computeIfAbsent(from, k -> new HashSet<>()).add(to);
                }
            }
        }
        List<String> cycle = findCycle(graph);
        // lazy supplier: String.join must not run when the cycle is null (acyclic)
        assertTrue(cycle == null, () -> "module cycle detected: " + String.join(" -> ", cycle));
    }

    /** Canonical unordered "a|b" key so either direction matches the waiver. */
    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** R4 — infra/ adapters are local to their module (ports & adapters). */
    @Test
    void infraAdaptersAreOnlyUsedByTheirOwnModule() throws IOException {
        List<String> violations = sources().stream()
                .filter(s -> s.pkg().contains(".infra"))
                .filter(s -> !s.pkg().endsWith(".infra") || true) // adapters AND their configs
                .flatMap(s -> s.imports().stream()
                        .filter(i -> i.contains(".infra."))
                        .filter(i -> !sameModule(s.pkg(), i))
                        .map(i -> s.pkg() + " -> " + i))
                .toList();
        assertTrue(violations.isEmpty(), "infra/ adapters leak across modules: " + violations);
    }

    /** R5 — web/ presentation layer never reaches into any infra/ adapter. */
    @Test
    void webLayerNeverTouchesInfraAdapters() throws IOException {
        List<String> violations = sources().stream()
                .filter(s -> s.pkg().endsWith(".web"))
                .flatMap(s -> s.imports().stream()
                        .filter(i -> i.contains(".infra."))
                        .map(i -> s.pkg() + " -> " + i))
                .toList();
        assertTrue(violations.isEmpty(), "web/ must not depend on persistence adapters: " + violations);
    }

    private static String topOf(String pkg) {
        int dot = pkg.indexOf('.');
        return dot < 0 ? pkg : pkg.substring(0, dot);
    }

    /** Zone-qualified module of a package or FQCN (e.g. "kernel.shared");
     *  root-level classes (the application assembly) map to themselves. */
    private static String moduleOf(String pkgOrFqcn) {
        String rel = pkgOrFqcn.startsWith(ROOT_PKG + ".")
                ? pkgOrFqcn.substring(ROOT_PKG.length() + 1) : pkgOrFqcn;
        String[] parts = rel.split("\\.");
        return parts.length >= 2 && ZONES.contains(parts[0])
                ? parts[0] + "." + parts[1] : parts[0];
    }

    private static boolean sameModule(String consumerPkg, String importedFqcn) {
        return moduleOf(consumerPkg).equals(moduleOf(importedFqcn));
    }

    /** DFS cycle detection; returns the closed walk (e.g. [mcp, control, mcp],
     *  closing where it started), or null when the graph is acyclic. */
    private static List<String> findCycle(Map<String, Set<String>> graph) {
        for (String start : graph.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new HashSet<>();
            String closer = dfs(start, graph, path, onPath);
            if (closer != null) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(closer);
                return cycle;
            }
        }
        return null;
    }

    /** Depth-first walk; returns the first on-path node re-visited (the cycle
     *  entry), or null when this branch closes no cycle. */
    private static String dfs(String node, Map<String, Set<String>> graph,
                              Deque<String> path, Set<String> onPath) {
        path.addLast(node);
        onPath.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (onPath.contains(next)) {
                return next;
            }
            String closer = dfs(next, graph, path, onPath);
            if (closer != null) {
                return closer;
            }
        }
        path.removeLast();
        onPath.remove(node);
        return null;
    }
}
