package com.hound.graph;

import com.hound.parser.core.AstCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Вариант A: Каждый файл — свой граф. switchGraph вызывается один раз.
 */
public class GraphWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GraphWriter.class);

    private final GraphDatabaseWriter dbWriter;
    private final String graphName;

    public GraphWriter(GraphDatabaseWriter dbWriter, String graphName) {
        this.dbWriter = dbWriter;
        this.graphName = graphName;
        this.dbWriter.switchGraph(graphName);   // ← только один раз
    }

    public void writeTree(UniversalAstNode root, AstCollector.CollectionResult result) {
        if (root == null || result == null) return;

        long startTime = System.currentTimeMillis();
        try {
            dbWriter.beginTransaction();

            if (dbWriter instanceof com.hound.graph.adapters.FalkorDBWriter falkor) {
                falkor.writeNodes(result.nodes().stream()
                        .map(UniversalAstNode::toGraphNode)
                        .toList());
                falkor.writeRelationships(result.relationships());
            } else {
                for (UniversalAstNode node : result.nodes()) {
                    dbWriter.writeNode(node.toGraphNode());
                }
                for (GraphRelationship rel : result.relationships()) {
                    dbWriter.writeRelationship(rel);
                }
            }

            dbWriter.commitTransaction();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Written to graph '{}' → {} nodes, {} relationships in {} ms",
                    graphName, result.nodes().size(), result.relationships().size(), duration);

        } catch (Exception e) {
            dbWriter.rollbackTransaction();
            logger.error("Error writing to graph '{}'", graphName, e);
            throw new RuntimeException("Failed to write AST tree to graph " + graphName, e);
        }
    }

    @Override
    public void close() {}
}