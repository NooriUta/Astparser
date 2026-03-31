package com.hound;

import com.hound.config.AppConfig;
import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.adapters.FalkorDBWriter;
import com.hound.graph.adapters.MemgraphWriter;
import com.hound.graph.adapters.Neo4jWriter;
import com.hound.processor.DirectoryScanner;
import com.hound.processor.FileProcessor;
import com.hound.processor.ThreadPoolManager;
import com.hound.cache.FileCache;
import com.hound.metrics.MetricsCollector;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Главный класс приложения HOUND.
 * Исправлена ошибка конструктора FileProcessor (убран batchSize).
 */
public class HoundApplication {

    private static final Logger logger = LoggerFactory.getLogger(HoundApplication.class);

    public static void main(String[] args) {
        try {
            AppConfig config = parseArguments(args);
            HoundApplication app = new HoundApplication();
            app.run(config);
        } catch (Exception e) {
            logger.error("Application error", e);
            System.exit(1);
        }
    }

    public void run(AppConfig config) throws Exception {
        logger.info("Starting HOUND AST Parser v1.0.0");

        // Инициализация компонентов
        GraphDatabaseWriter dbWriter = createDatabaseWriter(config);
        dbWriter.connect(config.getDbHost(), config.getDbPort(),
                config.getDbName(), config.getDbUser(), config.getDbPassword());

        FileCache cache = new FileCache(config.getCachePath());
        MetricsCollector metrics = new MetricsCollector();

        // Сканирование файлов
        DirectoryScanner scanner = new DirectoryScanner();
        List<Path> files = scanner.scan(config.getInputPath(), config.getFileExtensions());
        logger.info("Found {} files to process", files.size());

        if (files.isEmpty()) {
            logger.warn("No files found to process. Check input path and file extensions.");
            dbWriter.close();
            return;
        }

        // Обработка файлов
        ThreadPoolManager threadPool = new ThreadPoolManager(config.getThreads());

        for (Path file : files) {
            FileProcessor processor = new FileProcessor(
                    file,
                    dbWriter,
                    cache,
                    metrics,
                    config.getForcedLanguage()   // ← только 5 параметров!
            );
            threadPool.submit(processor);
        }

        // Ожидание завершения всех задач
        threadPool.shutdownAndWait();

        // Вывод отчёта
        metrics.printReport();

        // Очистка ресурсов
        dbWriter.close();
        logger.info("HOUND processing completed successfully");
    }

    private GraphDatabaseWriter createDatabaseWriter(AppConfig config) {
        return switch (config.getDbType().toLowerCase()) {
            case "neo4j" -> new Neo4jWriter();
            case "falkordb" -> new FalkorDBWriter();
            case "memgraph" -> new MemgraphWriter();
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getDbType());
        };
    }

    /**
     * Парсинг аргументов командной строки
     */
    private static AppConfig parseArguments(String[] args) throws ParseException {
        Options options = new Options();

        options.addOption("i", "input", true, "Input file or directory path");
        options.addOption("t", "db-type", true, "Database type: neo4j|falkordb|memgraph");
        options.addOption("h", "db-host", true, "Database host");
        options.addOption("p", "db-port", true, "Database port");
        options.addOption("n", "db-name", true, "Database name");
        options.addOption("u", "db-user", true, "Database user");
        options.addOption("pw", "db-password", true, "Database password");
        options.addOption("th", "threads", true, "Number of threads");
        options.addOption("c", "cache", true, "Cache path");
        options.addOption("l", "language", true, "Force SQL dialect (plsql, mysql, postgresql, etc.)");
        options.addOption("d", "dialect", true, "Force SQL dialect (alias for --language)");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        AppConfig config = new AppConfig();

        config.setInputPath(cmd.getOptionValue("input"));
        config.setDbType(cmd.getOptionValue("db-type", "falkordb"));
        config.setDbHost(cmd.getOptionValue("db-host", "localhost"));
        config.setDbPort(Integer.parseInt(cmd.getOptionValue("db-port", "6379")));
        config.setDbName(cmd.getOptionValue("db-name", "hound"));
        config.setDbUser(cmd.getOptionValue("db-user", ""));
        config.setDbPassword(cmd.getOptionValue("db-password", ""));

        config.setThreads(Integer.parseInt(cmd.getOptionValue("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors()))));

        config.setCachePath(cmd.getOptionValue("cache", ".hound-cache"));
        config.setFileExtensions(List.of(".sql", ".plsql", ".pks", ".pkb"));
        config.setIncrementalMode(false);
        config.setMaxFileSizeMB(100);

        // Принудительный язык/диалект
        if (cmd.hasOption("language")) {
            config.setForcedLanguage(cmd.getOptionValue("language"));
        } else if (cmd.hasOption("dialect")) {
            config.setForcedLanguage(cmd.getOptionValue("dialect"));
        }

        return config;
    }
}