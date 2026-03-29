package com.hound.processor;

import com.hound.cache.FileCache;
import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphRelationship;
import com.hound.graph.UniversalAstNode;
import com.hound.metrics.MetricsCollector;
import com.hound.parser.LanguageParser;
import com.hound.parser.ParserFactory;
import com.hound.util.FileUtils;
import com.hound.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Обработчик одного файла: читает, парсит и записывает AST в графовую БД
 */
public class FileProcessor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessor.class);

    private final Path filePath;
    private final GraphDatabaseWriter dbWriter;
    private final FileCache cache;
    private final MetricsCollector metrics;
    private final int batchSize;

    public FileProcessor(Path filePath, GraphDatabaseWriter dbWriter, FileCache cache,
                         MetricsCollector metrics, int batchSize) {
        this.filePath = filePath;
        this.dbWriter = dbWriter;
        this.cache = cache;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("Начало обработки файла: {}", filePath.getFileName());

            // Безопасное чтение файла с ограничением размера (100 МБ)
            String content = FileUtils.readFileSafe(filePath, "UTF-8", 100 * 1024 * 1024);

            String fileHash = HashUtils.md5(content);

            // Проверка кэша
            if (cache.isProcessed(filePath.toString(), fileHash)) {
                logger.info("Файл уже обработан ранее, пропускаем: {}", filePath.getFileName());
                metrics.incrementSkipped();
                return;
            }

            // Получаем парсер
            LanguageParser parser = ParserFactory.getParser(filePath.toString());

            // Парсим в универсальное AST-дерево
            UniversalAstNode ast = parser.parse(content, filePath.toString());

            // Собираем все узлы и связи
            List<UniversalAstNode> nodes = new ArrayList<>();
            List<GraphRelationship> relationships = new ArrayList<>();
            collectNodes(ast, nodes, relationships);

            // Записываем в БД пакетами
            writeInBatches(nodes, relationships);

            // Отмечаем файл как обработанный
            cache.markProcessed(filePath.toString(), fileHash);

            long duration = System.currentTimeMillis() - startTime;
            metrics.recordFileProcessed(duration, nodes.size(), relationships.size());

            logger.info("Файл успешно обработан: {} | Узлов: {} | Связей: {} | Время: {} мс",
                    filePath.getFileName(), nodes.size(), relationships.size(), duration);

        } catch (Exception e) {
            logger.error("Ошибка при обработке файла: {}", filePath, e);
            metrics.incrementErrors();
        }
    }

    /**
     * Рекурсивно собирает все узлы и связи из универсального AST
     */
    private void collectNodes(UniversalAstNode node,
                              List<UniversalAstNode> nodes,
                              List<GraphRelationship> relationships) {
        nodes.add(node);
        relationships.addAll(node.toRelationships());

        for (UniversalAstNode child : node.getChildren()) {
            collectNodes(child, nodes, relationships);
        }
    }

    /**
     * Записывает узлы и связи в БД пакетами
     */
    private void writeInBatches(List<UniversalAstNode> nodes,
                                List<GraphRelationship> relationships) {

        for (int i = 0; i < nodes.size(); i += batchSize) {
            int end = Math.min(i + batchSize, nodes.size());

            dbWriter.beginTransaction();
            try {
                // Запись узлов текущего пакета
                for (int j = i; j < end; j++) {
                    dbWriter.writeNode(nodes.get(j).toGraphNode());
                }

                // Запись связей текущего пакета
                int relStart = i;
                int relEnd = Math.min(i + batchSize, relationships.size());
                for (int j = relStart; j < relEnd; j++) {
                    dbWriter.writeRelationship(relationships.get(j));
                }

                dbWriter.commitTransaction();

            } catch (Exception e) {
                dbWriter.rollbackTransaction();
                logger.error("Ошибка записи пакета в БД для файла {}", filePath.getFileName(), e);
                throw new RuntimeException("Batch write failed", e);
            }
        }
    }
}