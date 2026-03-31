package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import com.falkordb.FalkorDB;
import com.falkordb.Driver;
import com.falkordb.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Стабильная версия FalkorDBWriter.
 * Использует только плоские свойства и простые строки, чтобы избежать ошибок парсинга FalkorDB.
 */
public class FalkorDBWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(FalkorDBWriter.class);

    private Driver driver;
    private Graph graph;
    private String currentGraphName;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        try {
            this.driver = FalkorDB.driver(host, port);
            this.graph = this.driver.graph(database);
            this.currentGraphName = database;
            logger.info("Connected to FalkorDB: {}:{}/graph:{}", host, port, database);
        } catch (Exception e) {
            logger.error("Failed to connect to FalkorDB", e);
            throw new RuntimeException("Failed to connect to FalkorDB", e);
        }
    }

    @Override
    public void switchGraph(String graphName) {
        if (graphName == null || graphName.equals(currentGraphName)) return;
        this.graph = this.driver.graph(graphName);
        this.currentGraphName = graphName;
        logger.debug("Switched to graph: {}", graphName);
    }

    @Override
    public void writeNode(GraphNode node) {
        if (node == null) return;

        String label = node.getLabel();
        StringBuilder props = new StringBuilder("{");
        boolean first = true;

        for (var entry : node.getProperties().entrySet()) {
            if (!first) props.append(", ");
            props.append(entry.getKey()).append(": ");

            Object value = entry.getValue();
            if (value instanceof String str) {
                String escaped = str.replace("\\", "\\\\")
                        .replace("'", "\\'");
                props.append("'").append(escaped).append("'");
            } else if (value != null) {
                props.append(value);
            } else {
                props.append("null");
            }
            first = false;
        }
        props.append("}");

        String cypher = "CREATE (n:" + label + " " + props + ")";

        executeQuery(cypher);
    }

    @Override
    public void writeNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        for (GraphNode node : nodes) {
            writeNode(node);
        }
    }

    @Override
    public void writeRelationship(GraphRelationship rel) {
        if (rel == null) return;

        String propsStr = "{}";
        if (!rel.getProperties().isEmpty()) {
            // Для связей тоже строим строку
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : rel.getProperties().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append(": ");
                Object v = entry.getValue();
                if (v instanceof String s) {
                    String escaped = s.replace("\\", "\\\\").replace("'", "\\'");
                    sb.append("'").append(escaped).append("'");
                } else if (v != null) {
                    sb.append(v);
                }
                first = false;
            }
            sb.append("}");
            propsStr = sb.toString();
        }

        String cypher = String.format(
                "MATCH (a), (b) WHERE a.id = '%s' AND b.id = '%s' " +
                        "CREATE (a)-[r:%s %s]->(b)",
                rel.getFromNodeId(), rel.getToNodeId(), rel.getType(), propsStr);

        executeQuery(cypher);
    }

    @Override
    public void writeRelationships(List<GraphRelationship> rels) {
        if (rels == null || rels.isEmpty()) return;
        for (GraphRelationship rel : rels) {
            writeRelationship(rel);
        }
    }

    private void executeQuery(String query) {
        try {
            graph.query(query);
        } catch (Exception e) {
            logger.error("FalkorDB query failed:\n{}", query, e);
            throw new RuntimeException("FalkorDB query failed", e);
        }
    }

    @Override
    public void executeQuery(String query) {
        executeQuery(query);
    }

    @Override
    public void beginTransaction() {
        logger.warn("Transactions not fully supported in FalkorDB adapter");
    }

    @Override
    public void commitTransaction() {}
    @Override
    public void rollbackTransaction() {}

    @Override
    public boolean isConnected() {
        return driver != null && graph != null;
    }

    @Override
    public void close() {
        try {
            if (driver != null) driver.close();
            logger.info("FalkorDB connection closed");
        } catch (Exception e) {
            logger.error("Error closing FalkorDB connection", e);
        }
    }
}