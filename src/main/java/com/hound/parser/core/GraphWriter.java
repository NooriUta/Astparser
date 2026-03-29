package com.hound.graph;

import com.hound.parser.core.AstCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Записывает AST-дерево в ОТДЕЛЬНЫЙ граф для каждого файла.
 * Вариант A — изоляция по файлам.
 */
public class GraphWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GraphWriter.class);

    private final GraphDatabaseWriter dbWriter;
    private final String graphName;        // уникальное имя графа для этого файла

    public GraphWriter(GraphDatabaseWriter dbWriter, String graphName) {
        this.dbWriter = dbWriter;
        this.graphName = graphName;
    }

    /**
     * Основной метод записи дерева в свой собственный граф
     */
    public void writeTree(UniversalAstNode root) {
        if (root == null) return;

        long startTime = System.currentTimeMillis();

        try {
            AstCollector.CollectionResult result = AstCollector.collect(root);

            // Переключаемся на отдельный граф для этого файла
            dbWriter.switchGraph(graphName);

            dbWriter.beginTransaction();

            // Записываем все узлы
            for (UniversalAstNode node : result.nodes()) {
                dbWriter.writeNode(node.toGraphNode());
            }

            // Записываем все связи
            for (GraphRelationship rel : result.relationships()) {
                dbWriter.writeRelationship(rel);
            }

            dbWriter.commitTransaction();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Записано в граф '{}' → {} узлов, {} связей за {} мс",
                    graphName, result.nodes().size(), result.relationships().size(), duration);

        } catch (Exception e) {
            dbWriter.rollbackTransaction();
            logger.error("Ошибка записи в граф {}", graphName, e);
            throw new RuntimeException("Failed to write AST tree to graph " + graphName, e);
        }
    }

    @Override
    public void close() {
        // Граф не закрываем здесь, только соединение в dbWriter
    }
}