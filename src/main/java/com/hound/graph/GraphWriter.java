package com.hound.graph;

import com.hound.parser.core.AstCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Записывает AST-дерево в отдельный граф для каждого файла.
 *
 * Порядок записи критически важен:
 * 1. Все узлы записываются ПЕРВЫМИ (writeNode вызывается для каждого)
 * 2. Только после этого записываются связи (writeRelationship для каждой)
 *
 * Это гарантирует что MATCH в writeRelationship найдёт узлы,
 * т.к. writeNode() в FalkorDB/Neo4j выполняется немедленно.
 *
 * Индексы создаются внутри каждого адаптера (FalkorDBWriter.ensureIndex).
 */
public class GraphWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GraphWriter.class);

    private final GraphDatabaseWriter dbWriter;
    private final String graphName;

    public GraphWriter(GraphDatabaseWriter dbWriter, String graphName) {
        this.dbWriter = dbWriter;
        this.graphName = graphName;
        this.dbWriter.switchGraph(graphName);
    }

    /**
     * Записывает узлы и связи в граф.
     *
     * Порядок:
     * 1. writeNode() для каждого узла — узлы сразу в БД
     *    (FalkorDBWriter: executeQuery немедленно, + запоминает id→label)
     * 2. writeRelationship() для каждой связи — MATCH найдёт узлы
     *    (FalkorDBWriter: использует label из маппинга id→label)
     */
    public void writeResult(AstCollector.CollectionResult result) {
        if (result == null || result.nodes().isEmpty()) return;

        long startTime = System.currentTimeMillis();

        try {
            // ===== Фаза 1: записываем ВСЕ узлы =====
            for (UniversalAstNode node : result.nodes()) {
                dbWriter.writeNode(node.toGraphNode());
            }

            // ===== Фаза 2: записываем связи (узлы уже в БД) =====
            for (GraphRelationship rel : result.relationships()) {
                dbWriter.writeRelationship(rel);
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Written to graph '{}': {} nodes, {} rels in {} ms",
                    graphName, result.nodes().size(), result.relationships().size(), duration);

        } catch (Exception e) {
            logger.error("Error writing to graph '{}': {}", graphName, e.getMessage(), e);
            throw new RuntimeException("Failed to write AST to graph " + graphName, e);
        }
    }

    /**
     * Для обратной совместимости.
     */
    public void writeTree(UniversalAstNode root) {
        if (root == null) return;
        AstCollector.CollectionResult result = AstCollector.collect(root);
        writeResult(result);
    }

    @Override
    public void close() {
        // Graph connection managed by dbWriter
    }
}