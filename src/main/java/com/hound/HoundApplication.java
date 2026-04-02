// src/main/java/com/hound/HoundApplication.java
package com.hound;

import com.hound.semantic.engine.UniversalSemanticEngine;
import com.hound.semantic.model.*;
import com.hound.semantic.dialect.plsql.PlSqlSemanticListener;
import com.hound.storage.ArcadeDBSemanticWriter;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlLexer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.cli.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
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
            ".pks", ".pkb", ".pkh",
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
        } finally {
            if (writer != null) writer.close();
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

        logger.info("Найдено {} SQL-файлов", sqlFiles.size());

        int success = 0, failed = 0;
        long totalTime = 0;

        for (Path file : sqlFiles) {
            try {
                totalTime += processFile(file, config, writer);
                success++;
            } catch (Exception e) {
                logger.error("Ошибка: {} — {}", file.getFileName(), e.getMessage());
                failed++;
            }
        }

        logger.info("ИТОГО: успешно={}, ошибок={}, время={}", success, failed, formatTime(totalTime));
    }

    // ═══════════════════════════════════════════════════════════════
    // File
    // ═══════════════════════════════════════════════════════════════

    private long processFile(Path file, RunConfig config, ArcadeDBSemanticWriter writer)
            throws IOException {
        String sql = Files.readString(file);
        if (sql.isBlank()) {
            logger.warn("Пустой файл: {}", file);
            return 0;
        }

        long startTime = System.currentTimeMillis();

        UniversalSemanticEngine engine = new UniversalSemanticEngine();
        Object listener = createListener(config.language, engine);
        parseAndWalk(sql, config.language, listener);

        long duration = System.currentTimeMillis() - startTime;
        SemanticResult result = engine.getResult(
                "session-" + System.currentTimeMillis(),
                file.toString(),
                config.language,
                duration
        );

        // Запись в ArcadeDB
        if (writer != null) {
            writer.saveResult(result);
        }

        printResult(file, result, duration);
        return duration;
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

    private void parseAndWalk(String sql, String language, Object listener) {
        switch (language.toLowerCase()) {
            case "plsql" -> {
                PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
                CommonTokenStream tokens = new CommonTokenStream(lexer);
                PlSqlParser parser = new PlSqlParser(tokens);
                PlSqlParser.Sql_scriptContext tree = parser.sql_script();
                ParseTreeWalker.DEFAULT.walk((PlSqlSemanticListener) listener, tree);
            }
            default -> throw new IllegalArgumentException("Парсер не реализован: " + language);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Output
    // ═══════════════════════════════════════════════════════════════

    private void printResult(Path file, SemanticResult result, long duration) {
        System.out.println("--- " + file.getFileName() + " (" + formatTime(duration) + ") ---");

        var structure = result.getStructure();
        if (structure != null) {
            System.out.println("  Таблицы  : " + size(structure.getTables()));
            System.out.println("  Колонки  : " + size(structure.getColumns()));
            System.out.println("  Statmnts : " + size(structure.getStatements()));
            System.out.println("  Routines : " + size(structure.getRoutines()));

            if (structure.getTables() != null) {
                structure.getTables().forEach((g, t) ->
                        System.out.println("    T: " + g + " [" + t.tableType() + "]"));
            }
            if (structure.getRoutines() != null) {
                structure.getRoutines().forEach((g, r) ->
                        System.out.println("    R: " + r.getRoutineType() + " " + r.getName()));
            }
            if (structure.getStatements() != null) {
                structure.getStatements().forEach((g, s) ->
                        System.out.println("    S: " + s.getType() + " line " + s.getLineStart()));
            }
        }

        var lineage = result.getLineage();
        if (lineage != null && !lineage.isEmpty()) {
            System.out.println("  Lineage  : " + lineage.size() + " edges");
            lineage.forEach(e ->
                    System.out.println("    L: " + e.sourceGeoid() + " -> " + e.targetGeoid()
                            + " [" + e.relationType() + "]"));
        }

        System.out.println("  Atoms    : " + size(result.getAtoms()) + " groups");
        System.out.println();
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
        options.addOption(null, "threads", true, "Number of threads (reserved)");
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
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private static String formatTime(long ms) {
        return ms >= 1000 ? String.format("%.1f s", ms / 1000.0) : ms + " ms";
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
    }
}