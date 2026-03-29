package com.hound.processor;

import com.hound.cache.FileCache;
import com.hound.graph.GraphDatabaseWriter;
import com.hound.parser.core.GraphWriter;
import com.hound.graph.UniversalAstNode;
import com.hound.metrics.MetricsCollector;
import com.hound.parser.LanguageParser;
import com.hound.parser.ParserFactory;
import com.hound.parser.core.AstCollector;
import com.hound.util.FileUtils;
import com.hound.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Обработчик одного файла.
 * Тонкий оркестратор: читает → парсит → собирает → записывает.
 */
public class FileProcessor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessor.class);

    private final Path filePath;
    private final GraphWriter graphWriter;
    private final FileCache cache;
    private final MetricsCollector metrics;
    private final String forcedLanguage;   // ← Новый параметр

    public FileProcessor(Path filePath,
                         GraphDatabaseWriter dbWriter,
                         FileCache cache,
                         MetricsCollector metrics,
                         int batchSize,
                         String forcedLanguage) {           // ← добавлен параметр
        this.filePath = filePath;
        this.graphWriter = new GraphWriter(dbWriter, batchSize);
        this.cache = cache;
        this.metrics = metrics;
        this.forcedLanguage = forcedLanguage;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("Начало обработки файла: {}", filePath.getFileName());

            String content = FileUtils.readFileSafe(filePath, "UTF-8", 100 * 1024 * 1024);
            String fileHash = HashUtils.md5(content);

            // Проверка кэша
            if (cache.isProcessed(filePath.toString(), fileHash)) {
                logger.info("Файл уже обработан ранее, пропускаем: {}", filePath.getFileName());
                metrics.incrementSkipped();
                return;
            }

            // Получаем парсер с учётом принудительного языка
            LanguageParser parser = ParserFactory.getParser(filePath.toString(), forcedLanguage);

            // Парсинг
            UniversalAstNode ast = parser.parse(content, filePath.toString());

            // Сбор узлов и связей через AstCollector
            AstCollector.CollectionResult result = AstCollector.collect(ast);

            // Запись в граф
            graphWriter.writeTree(ast);

            // Обновление кэша
            cache.markProcessed(filePath.toString(), fileHash);

            long duration = System.currentTimeMillis() - startTime;
            metrics.recordFileProcessed(duration, result.nodes().size(), result.relationships().size());

            logger.info("Файл успешно обработан: {} | Узлов: {} | Связей: {} | Язык: {} | Время: {} мс",
                    filePath.getFileName(),
                    result.nodes().size(),
                    result.relationships().size(),
                    forcedLanguage != null ? forcedLanguage.toUpperCase() : "auto",
                    duration);

        } catch (Exception e) {
            logger.error("Ошибка обработки файла: {}", filePath, e);
            metrics.incrementErrors();
        } finally {
            graphWriter.close();
        }
    }
}