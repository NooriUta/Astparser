package com.hound.processor;

import com.hound.cache.FileCache;
import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphWriter;
import com.hound.graph.UniversalAstNode;
import com.hound.metrics.MetricsCollector;
import com.hound.parser.LanguageParser;
import com.hound.parser.ParserFactory;
import com.hound.parser.core.UniversalParser;        // ← правильный импорт (core)
import com.hound.parser.core.AstCollector;
import com.hound.util.FileUtils;
import com.hound.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Обработчик одного файла.
 * Исправлены все ошибки компиляции:
 *  - правильный импорт UniversalParser
 *  - вызов публичного detectLanguage()
 *  - проверка instanceof с правильным пакетом
 *  - вызов writeTree с двумя параметрами
 */
public class FileProcessor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessor.class);

    private final Path filePath;
    private final GraphWriter graphWriter;
    private final FileCache cache;
    private final MetricsCollector metrics;
    private final String forcedLanguage;

    public FileProcessor(Path filePath,
                         GraphDatabaseWriter dbWriter,
                         FileCache cache,
                         MetricsCollector metrics,
                         String forcedLanguage) {

        this.filePath = filePath;
        this.cache = cache;
        this.metrics = metrics;
        this.forcedLanguage = forcedLanguage;

        String graphName = "hound_file_" + HashUtils.sha256(filePath.toString());
        this.graphWriter = new GraphWriter(dbWriter, graphName);
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("Processing file: {}", filePath.getFileName());

            String content = FileUtils.readFileSafe(filePath, "UTF-8", 100 * 1024 * 1024);
            if (content == null || content.isBlank()) {
                logger.warn("File is empty or could not be read: {}", filePath);
                metrics.incrementSkipped();
                return;
            }

            String fileHash = HashUtils.sha256(content);

            if (cache.isProcessed(filePath.toString(), fileHash)) {
                logger.info("File already processed, skipping: {}", filePath.getFileName());
                metrics.incrementSkipped();
                return;
            }

            // Определяем язык один раз
            String language = (forcedLanguage != null && !forcedLanguage.isBlank())
                    ? forcedLanguage.trim().toLowerCase()
                    : ParserFactory.detectLanguage(filePath.toString());

            // Получаем парсер
            LanguageParser parser = ParserFactory.getParser(filePath.toString(), forcedLanguage);

            // Парсим с явной передачей языка
            UniversalAstNode ast;
            if (parser instanceof UniversalParser universalParser) {
                ast = universalParser.parse(content, filePath.toString(), language);
            } else {
                ast = parser.parse(content, filePath.toString());
            }

            // Собираем результат один раз
            AstCollector.CollectionResult result = AstCollector.collect(ast);

            // Записываем в граф (два параметра!)
            graphWriter.writeTree(ast, result);

            cache.markProcessed(filePath.toString(), fileHash);

            long duration = System.currentTimeMillis() - startTime;

            metrics.recordFileProcessed(duration, result.nodes().size(), result.relationships().size());

            logger.info("File processed successfully: {} | Language: {} | Nodes: {} | Relationships: {} | Time: {} ms",
                    filePath.getFileName(),
                    language.toUpperCase(),
                    result.nodes().size(),
                    result.relationships().size(),
                    duration);

        } catch (Exception e) {
            logger.error("Error processing file: {}", filePath, e);
            metrics.incrementErrors();
        } finally {
            graphWriter.close();
        }
    }
}