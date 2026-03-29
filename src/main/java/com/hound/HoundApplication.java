package com.hound;

import com.hound.config.AppConfig;
import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.adapters.*;
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
        logger.info("Starting HOUND AST Parser v{}", getClass().getPackage().getImplementationVersion());

        // Initialize components
        GraphDatabaseWriter dbWriter = createDatabaseWriter(config);
        dbWriter.connect(config.getDbHost(), config.getDbPort(),
                config.getDbName(), config.getDbUser(), config.getDbPassword());

        FileCache cache = new FileCache(config.getCachePath());
        MetricsCollector metrics = new MetricsCollector();

        // Scan for files
        DirectoryScanner scanner = new DirectoryScanner();
        List<Path> files = scanner.scan(config.getInputPath(), config.getFileExtensions());
        logger.info("Found {} files to process", files.size());

        // Process files
        ThreadPoolManager threadPool = new ThreadPoolManager(config.getThreads());

        for (Path file : files) {
            FileProcessor processor = new FileProcessor(file, dbWriter, cache, metrics, config.getBatchSize());
            threadPool.submit(processor);
        }

        // Wait for completion
        threadPool.shutdownAndWait();

        // Output metrics
        metrics.printReport();

        // Cleanup
        dbWriter.close();
        logger.info("HOUND processing completed");
    }

    private GraphDatabaseWriter createDatabaseWriter(AppConfig config) {
        switch (config.getDbType().toLowerCase()) {
            case "neo4j":
                return new Neo4jWriter();
            case "falkordb":
                return new FalkorDBWriter();
            case "memgraph":
                return new MemgraphWriter();
            default:
                throw new IllegalArgumentException("Unsupported database type: " + config.getDbType());
        }
    }

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
        options.addOption("b", "batch-size", true, "Batch size");
        options.addOption("c", "cache", true, "Cache path");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        AppConfig config = new AppConfig();
        config.setInputPath(cmd.getOptionValue("input"));
        config.setDbType(cmd.getOptionValue("db-type", "neo4j"));
        config.setDbHost(cmd.getOptionValue("db-host", "localhost"));
        config.setDbPort(Integer.parseInt(cmd.getOptionValue("db-port", "7687")));
        config.setDbName(cmd.getOptionValue("db-name", "hound"));
        config.setDbUser(cmd.getOptionValue("db-user"));
        config.setDbPassword(cmd.getOptionValue("db-password"));
        config.setThreads(Integer.parseInt(cmd.getOptionValue("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors()))));
        config.setBatchSize(Integer.parseInt(cmd.getOptionValue("batch-size", "1000")));
        config.setCachePath(cmd.getOptionValue("cache", ".hound-cache"));
        config.setFileExtensions(List.of(".sql", ".plsql"));
        config.setIncrementalMode(false);
        config.setMaxFileSizeMB(100); // 100 МБ по умолчанию

        return config;
    }
}