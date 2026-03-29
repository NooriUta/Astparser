package com.hound.processor;

import com.hound.cache.FileCache;
import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphWriter;
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
 * Тонкий оркестратор обработки одного файла.
 * Только последовательность шагов, без дублирования логики.
 */
public class FileProcessor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessor.class);

    private final Path filePath;
    private final GraphWriter graphWriter;   // ← теперь используем GraphWriter
    private final FileCache cache;
    private final MetricsCollector metrics;

    public FileProcessor(Path filePath, GraphDatabaseWriter dbWriter, FileCache cache,
                         MetricsCollector metrics, int batchSize) {
        this.filePath = filePath;
        this.graphWriter = new GraphWriter(dbWriter, batchSize);  // создаём GraphWriter внутри
        this.cache = cache;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("Начало обработки файла: {}", filePath.getFileName());

            String content = FileUtils.readFileSafe(filePath, "UTF-8", 100 * 1024 * 1024);
            String fileHash = HashUtils.md5(content);

            if (cache.isProcessed(filePath.toString(), fileHash)) {
                logger.info("Файл уже обработан ранее, пропускаем: {}", filePath.getFileName());
                metrics.incrementSkipped();
                return;
            }

            LanguageParser parser = ParserFactory.getParser(filePath.toString());
            UniversalAstNode ast = parser.parse(content, filePath.toString());

            // Используем новый AstCollector
            AstCollector.CollectionResult result = AstCollector.collect(ast);

            // Записываем через GraphWriter (одним вызовом)
            graphWriter.writeTree(ast);   // ← здесь используется getAllNodes() внутри GraphWriter

            cache.markProcessed(filePath.toString(), fileHash);

            long duration = System.currentTimeMillis() - startTime;
            metrics.recordFileProcessed(duration, result.nodes().size(), result.relationships().size());

            logger.info("Файл успешно обработан: {} | Узлов: {} | Связей: {} | Время: {} мс",
                    filePath.getFileName(), result.nodes().size(), result.relationships().size(), duration);

        } catch (Exception e) {
            logger.error("Ошибка обработки файла: {}", filePath, e);
            metrics.incrementErrors();
        } finally {
            graphWriter.close();   // закрываем writer после каждого файла
        }
    }
}