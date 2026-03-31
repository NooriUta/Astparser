package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Адаптер для Memgraph (совместим с Neo4j Driver 5.x).
 * Добавлена поддержка batch-методов writeNodes / writeRelationships.
 */
public class MemgraphWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(MemgraphWriter.class);

    private Driver driver;
    private Session session;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        String uri = "bolt://" + host + ":" + port;
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        this.session = driver.session(SessionConfig.forDatabase(database));

        logger.info("Successfully connected to Memgraph: {}:{}/{}", host, port, database);
    }

    @Override
    public void switchGraph(String graphName) {
        if (session != null) session.close();
        this.session = driver.session(SessionConfig.forDatabase(graphName));
        logger.debug("Memgraph: switched to database/graph '{}'", graphName);
    }

    // ====================== Одиночные методы ======================

    @Override
    public void writeNode(GraphNode node) {
        String cypher = "CREATE (n:" + node.getLabel() + " $props)";
        executeCypher(cypher, Map.of("props", node.getProperties()));
    }

    @Override
    public void writeRelationship(GraphRelationship rel) {
        String cypher = """
            MATCH (a), (b)
            WHERE a.id = $fromId AND b.id = $toId
            CREATE (a)-[r:%s]->(b)
            SET r += $props
            """.formatted(rel.getType());

        executeCypher(cypher, Map.of(
                "fromId", rel.getFromNodeId(),
                "toId", rel.getToNodeId(),
                "props", rel.getProperties()
        ));
    }

    // ====================== Batch методы ======================

    @Override
    public void writeNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return;

        for (GraphNode node : nodes) {
            writeNode(node);        // Memgraph хорошо работает с одиночными запросами
        }
    }

    @Override
    public void writeRelationships(List<GraphRelationship> rels) {
        if (rels == null || rels.isEmpty()) return;

        for (GraphRelationship rel : rels) {
            writeRelationship(rel);
        }
    }

    // ====================== Вспомогательные методы ======================

    private void executeCypher(String cypher, Map<String, Object> params) {
        try {
            Result result = session.run(cypher, params);
            result.consume();
        } catch (Exception e) {
            logger.error("Error executing query in Memgraph", e);
            throw new RuntimeException("Memgraph query failed", e);
        }
    }

    @Override
    public void executeQuery(String cypher) {
        executeCypher(cypher, Map.of());
    }

    @Override
    public void beginTransaction() {}
    @Override
    public void commitTransaction() {}
    @Override
    public void rollbackTransaction() {}

    @Override
    public boolean isConnected() {
        return driver != null && session != null;
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
            if (driver != null) driver.close();
            logger.info("Memgraph connection closed");
        } catch (Exception e) {
            logger.error("Error closing Memgraph connection", e);
        }
    }
}