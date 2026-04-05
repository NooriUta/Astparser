package com.hound.semantic;

import com.hound.semantic.engine.UniversalSemanticEngine;
import com.hound.semantic.dialect.plsql.PlSqlSemanticListener;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlLexer;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1.9.Q — Regression quality threshold.
 *
 * Parses all PL/SQL fixture files under src/test/resources/plsql/ and asserts:
 *   1. Each file produces at least one statement and one atom.
 *   2. Atom resolution quality (resolved + constants + functions) / total ≥ QUALITY_THRESHOLD.
 *
 * QUALITY_THRESHOLD was calibrated against the fixture files during S2.Q.
 * Update it whenever new fixture files are added or the resolution logic changes significantly.
 */
class HoundRegressionTest {

    private static final Logger LOG = LoggerFactory.getLogger(HoundRegressionTest.class);

    /**
     * S2.Q baseline: minimum acceptable atom-resolution quality per file.
     * Calibrated on 3 fixture files (19-04-2026):
     *   pkg_dml_basic.pck   = 1.00
     *   pkg_merge_using.pck = 1.00
     *   pkg_select_cte.pck  = 0.887
     *   → threshold = min - 0.08 = 0.80
     */
    static final double QUALITY_THRESHOLD = 0.80;

    // ═══════════════════════════════════════════════════════════════
    // Test infrastructure
    // ═══════════════════════════════════════════════════════════════

    /** Loads and parses a PL/SQL file, returns the engine after resolution. */
    private static UniversalSemanticEngine parseFile(Path path) throws IOException {
        String sql = Files.readString(path);
        UniversalSemanticEngine engine = new UniversalSemanticEngine();
        PlSqlSemanticListener listener = new PlSqlSemanticListener(engine);
        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
        PlSqlParser parser = new PlSqlParser(new CommonTokenStream(lexer));
        new ParseTreeWalker().walk(listener, parser.sql_script());
        engine.resolvePendingColumns();
        return engine;
    }

    /** Computes atom quality: (resolved + constants + functions) / total. */
    static double quality(List<Map<String, Object>> log) {
        if (log.isEmpty()) return 1.0;
        long resolved  = log.stream().filter(e -> "Обработано".equals(e.get("result_kind"))).count();
        long constants = log.stream().filter(e -> "constant"  .equals(e.get("result_kind"))).count();
        long functions = log.stream().filter(e -> "function_call".equals(e.get("result_kind"))).count();
        return (resolved + constants + functions) / (double) log.size();
    }

    /** Lists all .pck / .sql / .pls fixture files in src/test/resources/plsql/. */
    static Stream<Path> testFiles() throws Exception {
        URL dir = HoundRegressionTest.class.getClassLoader().getResource("plsql");
        if (dir == null) {
            // Fallback: look relative to project root
            Path fallback = Path.of("src/test/resources/plsql");
            if (Files.isDirectory(fallback)) {
                return Files.list(fallback).filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".pck") || n.endsWith(".sql") || n.endsWith(".pls");
                }).sorted();
            }
            return Stream.empty();
        }
        return Files.list(Path.of(dir.toURI()))
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".pck") || n.endsWith(".sql") || n.endsWith(".pls");
                })
                .sorted();
    }

    // ═══════════════════════════════════════════════════════════════
    // Per-file structural assertions
    // ═══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("testFiles")
    void fileProducesStatements(Path file) throws IOException {
        UniversalSemanticEngine engine = parseFile(file);
        int stmtCount = engine.getBuilder().getStatements().size();
        assertTrue(stmtCount > 0,
                file.getFileName() + ": expected at least 1 statement, got " + stmtCount);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testFiles")
    void fileProducesAtoms(Path file) throws IOException {
        UniversalSemanticEngine engine = parseFile(file);
        int atomCount = engine.getResolutionLog().size();
        assertTrue(atomCount > 0,
                file.getFileName() + ": expected at least 1 atom in resolution log, got " + atomCount);
    }

    // ═══════════════════════════════════════════════════════════════
    // Quality threshold assertion
    // ═══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("testFiles")
    void qualityAboveThreshold(Path file) throws IOException {
        UniversalSemanticEngine engine = parseFile(file);
        List<Map<String, Object>> log = engine.getResolutionLog();

        double q = quality(log);
        LOG.info("Quality [{} atoms]: {}  file={}",
                log.size(), String.format("%.2f", q), file.getFileName());

        assertTrue(q >= QUALITY_THRESHOLD,
                "%s: quality %.2f below threshold %.2f  (total=%d, unresolved=%d)"
                        .formatted(file.getFileName(), q, QUALITY_THRESHOLD,
                                log.size(),
                                log.stream().filter(e -> "unresolved".equals(e.get("result_kind"))).count()));
    }

    // ═══════════════════════════════════════════════════════════════
    // Domain-specific regression checks
    // ═══════════════════════════════════════════════════════════════

    @Test
    void mergeUsingSubquery_noUnresolvedSourceAlias() throws Exception {
        Path f = testFiles()
                .filter(p -> p.getFileName().toString().contains("merge"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No merge fixture found"));

        UniversalSemanticEngine engine = parseFile(f);
        List<Map<String, Object>> log = engine.getResolutionLog();

        long unresolvedSourceAlias = log.stream()
                .filter(e -> {
                    String raw = (String) e.get("raw_input");
                    return raw != null && raw.toUpperCase().startsWith("SOURCE.");
                })
                .filter(e -> "unresolved".equals(e.get("result_kind")))
                .count();

        assertEquals(0, unresolvedSourceAlias,
                "SOURCE.* refs in MERGE USING (SELECT...) must be resolved — BUG S1.BUG-4 regression");
    }

    // ═══════════════════════════════════════════════════════════════
    // S2.Q — summary (informational, non-blocking)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void regressionSummary() throws Exception {
        Map<String, Double> qualities = new LinkedHashMap<>();
        for (Path file : (Iterable<Path>) testFiles()::iterator) {
            UniversalSemanticEngine engine = parseFile(file);
            qualities.put(file.getFileName().toString(), quality(engine.getResolutionLog()));
        }

        if (qualities.isEmpty()) {
            LOG.warn("regressionSummary: no fixture files found");
            return;
        }

        double avg = qualities.values().stream().mapToDouble(d -> d).average().orElse(0);
        double min = qualities.values().stream().mapToDouble(d -> d).min().orElse(0);
        double max = qualities.values().stream().mapToDouble(d -> d).max().orElse(0);

        LOG.info("=== S2.Q Quality Summary ===");
        qualities.forEach((name, q) ->
                LOG.info("  {}  quality={}",
                        String.format("%-50s", name), String.format("%.2f", q)));
        LOG.info("  avg={}  min={}  max={}  files={}",
                String.format("%.2f", avg), String.format("%.2f", min),
                String.format("%.2f", max), qualities.size());
        LOG.info("  Suggested QUALITY_THRESHOLD = {}  (min - 0.10)",
                String.format("%.2f", Math.max(0, min - 0.10)));

        // Non-blocking structural assertion
        assertTrue(avg > 0,
                "Average quality must be > 0 across all fixture files");
    }
}
