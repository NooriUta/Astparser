// src/main/java/com/hound/HoundApplication.java
package com.hound;

import com.hound.semantic.engine.UniversalSemanticEngine;
import com.hound.semantic.model.*;
import com.hound.semantic.dialect.plsql.PlSqlSemanticListener;
import com.hound.storage.ArcadeDBSemanticWriter;
import com.hound.diagnostic.DiagnosticRunner;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlLexer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.cli.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hound.metrics.PipelineTimer;

import com.hound.processor.ThreadPoolManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Главный класс приложения Hound.
 *
 * Использование:
 *   hound --input path/to/dir --language plsql
 *   hound --input path/to/dir --language plsql --arcade-db ./data/hound-db
 *   hound --input path/to/dir --language plsql --db-type arcadedb --db-host localhost --db-port 2480 --db-name hound
 *   hound path/to/file.sql
 */
public class HoundApplication {

    private static final Logger logger = LoggerFactory.getLogger(HoundApplication.class);

    private static final String[] SQL_EXTENSIONS = {
            ".sql", ".plsql", ".pls",
            ".pks", ".pkb", ".pkh", ".pck" ,
            ".prc", ".fnc",
            ".trg", ".vw", ".typ",
            ".ddl", ".dml"
    };

    public static void main(String[] args) {
        try {
            RunConfig config = parseArguments(args);
            new HoundApplication().run(config);
        } catch (ParseException e) {
            System.err.println("Ошибка аргументов: " + e.getMessage());
            printHelp();
            System.exit(1);
        } catch (Exception e) {
            logger.error("Application error", e);
            System.exit(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Run
    // ═══════════════════════════════════════════════════════════════

    public void run(RunConfig config) throws Exception {
        logger.info("=== Hound Semantic Engine v1.0.0 ===");
        logger.info("Input    : {}", config.inputPath);
        logger.info("Language : {}", config.language);

        // Создаём writer (embedded или remote или null)
        ArcadeDBSemanticWriter writer = createWriter(config);

        // --clean: удалить все данные перед запуском
        if (config.clean && writer != null) {
            logger.info("CLEAN    : deleting all Dali records...");
            writer.cleanAll();
        }

        Path target = Path.of(config.inputPath);
        if (!Files.exists(target)) {
            if (writer != null) writer.close();
            throw new IllegalArgumentException("Не найдено: " + target);
        }

        try {
            if (Files.isDirectory(target)) {
                processDirectory(target, config, writer);
            } else {
                processFile(target, config, writer);
            }

            // --diag: запустить диагностику после обработки
            if (config.diag && writer != null) {
                runDiagnostics(config, writer);
            }
        } finally {
            if (writer != null) writer.close();
        }
    }

    /**
     * Запускает DiagnosticRunner после обработки файлов.
     * Для embedded — открывает БД напрямую.
     * Для remote — использует remote query API.
     */
    private void runDiagnostics(RunConfig config, ArcadeDBSemanticWriter writer) {
        System.out.println();
        if (config.arcadeDbPath != null) {
            // Embedded: close current writer, reopen read-only for diagnostics
            writer.close();
            com.arcadedb.database.DatabaseFactory factory =
                    new com.arcadedb.database.DatabaseFactory(config.arcadeDbPath);
            if (factory.exists()) {
                try (com.arcadedb.database.Database diagDb = factory.open()) {
                    new DiagnosticRunner(diagDb).runAll();
                }
            }
        } else if ("arcadedb".equalsIgnoreCase(config.dbType)) {
            // Remote: use DiagnosticRunner with RemoteDatabase
            var remoteDb = new com.arcadedb.remote.RemoteDatabase(
                    config.dbHost, config.dbPort, config.dbName,
                    config.dbUser, config.dbPassword);
            try {
                new DiagnosticRunner(remoteDb).runAll();
            } finally {
                try { remoteDb.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Создаёт writer в зависимости от параметров:
     *   --arcade-db path          → embedded mode
     *   --db-type arcadedb        → remote mode (к Docker серверу)
     *   ничего                     → null (без записи)
     */
    private ArcadeDBSemanticWriter createWriter(RunConfig config) {
        // Embedded mode
        if (config.arcadeDbPath != null) {
            logger.info("ArcadeDB : EMBEDDED {}", config.arcadeDbPath);
            return new ArcadeDBSemanticWriter(config.arcadeDbPath);
        }

        // Remote mode
        if ("arcadedb".equalsIgnoreCase(config.dbType)) {
            logger.info("ArcadeDB : REMOTE {}:{}/{} user={}",
                    config.dbHost, config.dbPort, config.dbName, config.dbUser);
            return new ArcadeDBSemanticWriter(
                    config.dbHost, config.dbPort, config.dbName,
                    config.dbUser, config.dbPassword);
        }

        // Нет записи
        logger.info("Storage  : disabled (используйте --arcade-db или --db-type arcadedb)");
        return null;
    }

    /** Результат анализа одного файла (без записи в БД). */
    private record AnalysisResult(Path file, SemanticResult semantic, PipelineTimer timer) {}

    // ═══════════════════════════════════════════════════════════════
    // Directory
    // ═══════════════════════════════════════════════════════════════

    private void processDirectory(Path dir, RunConfig config, ArcadeDBSemanticWriter writer)
            throws IOException {
        List<Path> sqlFiles;
        try (Stream<Path> stream = Files.walk(dir)) {
            sqlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(HoundApplication::isSqlFile)
                    .sorted()
                    .toList();
        }

        if (sqlFiles.isEmpty()) {
            logger.warn("SQL-файлы не найдены в: {}", dir);
            return;
        }

        int threads = config.threads;
        logger.info("Найдено {} SQL-файлов, потоков: {}", sqlFiles.size(), threads);

        // session-id counter — уникален при параллельном запуске
        AtomicLong sessionSeq = new AtomicLong(System.currentTimeMillis());

        // ── Phase 1: параллельный parse + walk + resolve ──
        List<Future<AnalysisResult>> futures = new ArrayList<>(sqlFiles.size());
        ThreadPoolManager pool = ThreadPoolManager.newFixedThreadPool(threads);
        for (Path file : sqlFiles) {
            futures.add(pool.submit(() -> analyzeFile(file, config, sessionSeq)));
        }
        pool.shutdownAndWait();

        // ── Phase 2: последовательная запись в БД + сбор статистики ──
        int success = 0, failed = 0;
        List<PipelineTimer> timers = new ArrayList<>();
        List<String>        names  = new ArrayList<>();

        for (Future<AnalysisResult> f : futures) {
            try {
                AnalysisResult ar = f.get();
                if (ar.semantic() != null) {
                    if (writer != null) {
                        writer.saveResult(ar.semantic(), ar.timer());
                    }
                    printResult(ar.file(), ar.semantic(), ar.timer().count("lines"), ar.timer());
                }
                timers.add(ar.timer());
                names.add(ar.file().getFileName().toString());
                success++;
            } catch (ExecutionException e) {
                logger.error("Ошибка: {} — {}", "<file>", e.getCause().getMessage(), e.getCause());
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Прерывание при ожидании результата", e);
                failed++;
            }
        }

        printSummary(timers, names, success, failed);
    }

    /**
     * Parse + walk + resolve для одного файла.
     * Не пишет в БД — результат собирается в Phase 2.
     * Потокобезопасен: каждый вызов создаёт свои engine/listener.
     */
    private AnalysisResult analyzeFile(Path file, RunConfig config, AtomicLong sessionSeq)
            throws IOException {
        String sql = Files.readString(file);
        if (sql.isBlank()) {
            logger.warn("Пустой файл: {}", file);
            return new AnalysisResult(file, null, new PipelineTimer());
        }

        long lineCount = sql.lines().count();
        PipelineTimer timer = new PipelineTimer();
        timer.count("lines", (int) lineCount);

        UniversalSemanticEngine engine = new UniversalSemanticEngine();
        Object listener = createListener(config.language, engine);
        parseAndWalk(sql, config.language, listener, timer);

        timer.start("resolve");
        engine.resolvePendingColumns();
        timer.stop("resolve");

        long parseWalkResolveMs = timer.ms("parse") + timer.ms("walk") + timer.ms("resolve");
        SemanticResult result = engine.getResult(
                "session-" + sessionSeq.getAndIncrement(),
                file.toString(),
                config.language,
                parseWalkResolveMs
        );

        return new AnalysisResult(file, result, timer);
    }

    // ═══════════════════════════════════════════════════════════════
    // File
    // ═══════════════════════════════════════════════════════════════

    private PipelineTimer processFile(Path file, RunConfig config, ArcadeDBSemanticWriter writer)
            throws IOException {
        String sql = Files.readString(file);
        if (sql.isBlank()) {
            logger.warn("Пустой файл: {}", file);
            return new PipelineTimer();
        }

        long lineCount = sql.lines().count();
        PipelineTimer timer = new PipelineTimer();
        timer.count("lines", (int) lineCount);

        UniversalSemanticEngine engine = new UniversalSemanticEngine();
        Object listener = createListener(config.language, engine);
        parseAndWalk(sql, config.language, listener, timer);

        timer.start("resolve");
        engine.resolvePendingColumns();
        timer.stop("resolve");

        long parseWalkResolveMs = timer.ms("parse") + timer.ms("walk") + timer.ms("resolve");
        SemanticResult result = engine.getResult(
                "session-" + System.currentTimeMillis(),
                file.toString(),
                config.language,
                parseWalkResolveMs
        );

        if (writer != null) {
            writer.saveResult(result, timer);
        }

        printResult(file, result, lineCount, timer);
        return timer;
    }

    // ═══════════════════════════════════════════════════════════════
    // Dialect
    // ═══════════════════════════════════════════════════════════════

    private Object createListener(String language, UniversalSemanticEngine engine) {
        return switch (language.toLowerCase()) {
            case "plsql" -> new PlSqlSemanticListener(engine);
            default -> throw new IllegalArgumentException("Диалект не реализован: " + language);
        };
    }

    private void parseAndWalk(String sql, String language, Object listener, PipelineTimer timer) {
        switch (language.toLowerCase()) {
            case "plsql" -> {
                timer.start("parse");
                PlSqlLexer lexer       = new PlSqlLexer(CharStreams.fromString(sql));
                CommonTokenStream tokens = new CommonTokenStream(lexer);
                PlSqlParser parser      = new PlSqlParser(tokens);
                PlSqlParser.Sql_scriptContext tree = parser.sql_script();
                timer.stop("parse");
                timer.count("tokens", tokens.getNumberOfOnChannelTokens());

                timer.start("walk");
                ParseTreeWalker.DEFAULT.walk((PlSqlSemanticListener) listener, tree);
                timer.stop("walk");
            }
            default -> throw new IllegalArgumentException("Парсер не реализован: " + language);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Output
    // ═══════════════════════════════════════════════════════════════

    private void printResult(Path file, SemanticResult result, long lineCount, PipelineTimer timer) {
        var s = result.getStructure();
        int tables  = s != null ? size(s.getTables()) : 0;
        int cols    = s != null ? size(s.getColumns()) : 0;
        int stmts   = s != null ? size(s.getStatements()) : 0;
        int routs   = s != null ? size(s.getRoutines()) : 0;
        int joins   = s != null ? s.getStatements().values().stream().mapToInt(st -> st.getJoins().size()).sum() : 0;
        int lineage = size(result.getLineage());
        int atoms   = size(result.getAtoms());

        long p  = timer.ms("parse");
        long w  = timer.ms("walk");
        long rv = timer.ms("resolve");
        long db = timer.ms("write.vtx") + timer.ms("write.edge");

        System.out.printf("  %-30s %5d lines  P:%-4d W:%-5d Rv:%-4d DB:%-5d  T:%-3d C:%-4d S:%-3d Rt:%-2d J:%-2d A:%-3d L:%-3d%n",
                file.getFileName(), lineCount,
                p, w, rv, db,
                tables, cols, stmts, routs, joins, atoms, lineage);
    }

    // ═══════════════════════════════════════════════════════════════
    // CLI
    // ═══════════════════════════════════════════════════════════════

    private static RunConfig parseArguments(String[] args) throws ParseException {
        if (args.length == 1 && !args[0].startsWith("-")) {
            RunConfig c = new RunConfig();
            c.inputPath = args[0];
            return c;
        }

        Options options = new Options();
        options.addOption("i", "input", true, "Input file or directory path");
        options.addOption("l", "language", true, "SQL dialect: plsql (default)");
        options.addOption("d", "dialect", true, "Alias for --language");
        options.addOption(null, "arcade-db", true, "ArcadeDB embedded path");
        options.addOption("t", "db-type", true, "Database type: arcadedb");
        options.addOption(null, "db-host", true, "Database host (default: localhost)");
        options.addOption("p", "db-port", true, "Database port (default: 2480)");
        options.addOption("n", "db-name", true, "Database name (default: hound)");
        options.addOption("u", "db-user", true, "Database user (default: root)");
        options.addOption(null, "db-password", true, "Database password");
        options.addOption(null, "clean", false, "Delete all Dali records before processing");
        options.addOption(null, "diag", false, "Run diagnostics after processing");
        options.addOption(null, "threads", true, "Number of parallel parse threads (default: CPU cores)");
        options.addOption(null, "help", false, "Show help");

        CommandLine cmd = new DefaultParser().parse(options, args);

        if (cmd.hasOption("help")) {
            printHelp();
            System.exit(0);
        }

        RunConfig config = new RunConfig();

        config.inputPath = cmd.getOptionValue("input");
        if (config.inputPath == null || config.inputPath.isBlank()) {
            throw new ParseException("--input обязателен");
        }

        if (cmd.hasOption("language")) config.language = cmd.getOptionValue("language");
        else if (cmd.hasOption("dialect")) config.language = cmd.getOptionValue("dialect");

        // ArcadeDB embedded
        if (cmd.hasOption("arcade-db")) {
            config.arcadeDbPath = cmd.getOptionValue("arcade-db");
        }

        // ArcadeDB remote
        if (cmd.hasOption("db-type")) config.dbType = cmd.getOptionValue("db-type");
        if (cmd.hasOption("db-host")) config.dbHost = cmd.getOptionValue("db-host");
        if (cmd.hasOption("db-port")) config.dbPort = Integer.parseInt(cmd.getOptionValue("db-port"));
        if (cmd.hasOption("db-name")) config.dbName = cmd.getOptionValue("db-name");
        if (cmd.hasOption("db-user")) config.dbUser = cmd.getOptionValue("db-user");
        if (cmd.hasOption("db-password")) config.dbPassword = cmd.getOptionValue("db-password");

        // Flags
        config.clean = cmd.hasOption("clean");
        config.diag  = cmd.hasOption("diag");
        if (cmd.hasOption("threads")) {
            config.threads = Math.max(1, Integer.parseInt(cmd.getOptionValue("threads")));
        }

        return config;
    }

    private static void printHelp() {
        System.out.println("Hound Semantic Engine v1.0.0");
        System.out.println();
        System.out.println("Использование:");
        System.out.println("  hound --input <path> [--language plsql] [storage options]");
        System.out.println("  hound <path>");
        System.out.println();
        System.out.println("Storage (выберите один):");
        System.out.println("  --arcade-db <path>           ArcadeDB embedded (файл)");
        System.out.println("  --db-type arcadedb           ArcadeDB remote (Docker/сервер)");
        System.out.println("    --db-host localhost");
        System.out.println("    --db-port 2480");
        System.out.println("    --db-name hound");
        System.out.println("    --db-user root");
        System.out.println("    --db-password playwithdata");
        System.out.println();
        System.out.println("Flags:");
        System.out.println("  --clean                      Очистить все Dali-данные перед обработкой");
        System.out.println("  --diag                       Запустить диагностику после обработки");
        System.out.println("  --threads N                  Потоков для параллельного разбора (default: CPU cores)");
    }

    // ═══════════════════════════════════════════════════════════════
    // Performance Summary
    // ═══════════════════════════════════════════════════════════════

    private void printSummary(List<PipelineTimer> timers, List<String> names, int success, int failed) {
        long totalLines = timers.stream().mapToLong(t -> t.count("lines")).sum();
        long parseTotal = timers.stream().mapToLong(t -> t.ms("parse")).sum();
        long walkTotal  = timers.stream().mapToLong(t -> t.ms("walk")).sum();
        long rvTotal    = timers.stream().mapToLong(t -> t.ms("resolve")).sum();
        long vtxTotal   = timers.stream().mapToLong(t -> t.ms("write.vtx")).sum();
        long edgeTotal  = timers.stream().mapToLong(t -> t.ms("write.edge")).sum();
        long dbTotal    = vtxTotal + edgeTotal;
        long total      = parseTotal + walkTotal + rvTotal + dbTotal;

        int n = timers.size();

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.printf ("  PERFORMANCE SUMMARY (%d files, %,d lines)%n", success, totalLines);
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  Phase          Total        Avg/file     % total");
        System.out.println("  ────────────   ──────────   ──────────   ───────");
        printPhaseLine("Parse",    parseTotal, n, total);
        printPhaseLine("Walk",     walkTotal,  n, total);
        printPhaseLine("Resolve",  rvTotal,    n, total);
        printPhaseLine("DB Write", dbTotal,    n, total);
        if (edgeTotal > 0) {
            printSubPhaseLine("vtx",  vtxTotal,  total);
            printSubPhaseLine("edge", edgeTotal, total);
        }
        System.out.println("  ────────────   ──────────   ──────────");
        printPhaseLine("TOTAL",    total,      n, total);

        if (n > 1) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < n; i++) idx.add(i);
            idx.sort((a, b) -> Long.compare(timers.get(b).totalMs(), timers.get(a).totalMs()));

            System.out.println();
            System.out.println("  Slowest files:");
            int show = Math.min(5, n);
            for (int rank = 0; rank < show; rank++) {
                int i = idx.get(rank);
                PipelineTimer t = timers.get(i);
                System.out.printf("    %d. %-35s %,6d lines  %s%n",
                        rank + 1, names.get(i), t.count("lines"), formatTime(t.totalMs()));
            }
        }

        if (failed > 0) {
            System.out.println();
            System.out.println("  Файлов с ошибками: " + failed);
        }
        System.out.println("════════════════════════════════════════════════════════════════");
    }

    private void printPhaseLine(String phase, long ms, int n, long total) {
        int pct = total == 0 ? 0 : (int) (ms * 100 / total);
        String avg = n == 0 ? "0ms" : formatTime(ms / n);
        System.out.printf("  %-13s  %-10s  %-10s  %3d%%%n", phase, formatTime(ms), avg, pct);
    }

    private void printSubPhaseLine(String sub, long ms, long total) {
        int pct = total == 0 ? 0 : (int) (ms * 100 / total);
        System.out.printf("    └─ %-7s  %-10s  %-10s  %3d%%%n", sub, formatTime(ms), "", pct);
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private static String formatTime(long ms) {
        if (ms < 1000) return ms + "ms";
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 0) return String.format("%dm %02ds", min, sec);
        return String.format("%d.%ds", sec, (ms % 1000) / 100);
    }

    private static int size(java.util.Map<?, ?> m) { return m != null ? m.size() : 0; }
    private static int size(java.util.List<?> l) { return l != null ? l.size() : 0; }

    private static boolean isSqlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : SQL_EXTENSIONS) if (name.endsWith(ext)) return true;
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Config
    // ═══════════════════════════════════════════════════════════════

    static class RunConfig {
        String inputPath;
        String language = "plsql";

        // Embedded ArcadeDB
        String arcadeDbPath = null;

        // Remote ArcadeDB (Docker)
        String dbType = null;
        String dbHost = "localhost";
        int dbPort = 2480;
        String dbName = "hound";
        String dbUser = "root";
        String dbPassword = "playwithdata";

        // Flags
        boolean clean    = false;
        boolean diag     = false;
        int     threads  = Runtime.getRuntime().availableProcessors();
    }
}