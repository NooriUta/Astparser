package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Neo4jWriter implements GraphDatabaseWriter {
    private static final Logger logger = LoggerFactory.getLogger(Neo4jWriter.class);
    private Driver driver;
    private Session session;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        String uri = String.format("bolt://%s:%d", host, port);
        AuthToken auth = AuthTokens.basic(username, password);
        driver = GraphDatabase.driver(uri, auth);
        session = driver.session(SessionConfig.forDatabase(database));
        logger.info("Connected to Neo4j at {}", uri);
    }

    @Override
    public void writeNode(GraphNode node) {
        String cypher = String.format("CREATE (n:%s %s)", node.getLabel(), propertiesToCypher(node.getProperties()));
        executeQuery(cypher);
    }

    @Override
    public void writeRelationship(GraphRelationship relationship) {
        String cypher = String.format(
                "MATCH (a) WHERE id(a) = '%s' MATCH (b) WHERE id(b) = '%s' CREATE (a)-[r:%s %s]->(b)",
                relationship.getFromNodeId(), relationship.getToNodeId(),
                relationship.getType(), propertiesToCypher(relationship.getProperties())
        );
        executeQuery(cypher);
    }

    @Override
    public void executeQuery(String cypher) {
        try (Result result = session.run(cypher)) {
            // Consume result
        } catch (Exception e) {
            logger.error("Error executing query: {}", cypher, e);
            throw new RuntimeException("Query execution failed", e);
        }
    }

    @Override
    public void beginTransaction() {
        if (session != null && session.isOpen()) {
            session.close();
        }
        session = driver.session();
        session.beginTransaction();
    }

    @Override
    public void commitTransaction() {
        if (session != null && session.isOpen()) {
            session.commitTransaction();
        }
    }

    @Override
    public void rollbackTransaction() {
        if (session != null && session.isOpen()) {
            session.rollbackTransaction();
        }
    }

    @Override
    public void close() {
        if (session != null && session.isOpen()) {
            session.close();
        }
        if (driver != null) {
            driver.close();
        }
    }

    @Override
    public boolean isConnected() {
        return driver != null && session != null && session.isOpen();
    }

    private String propertiesToCypher(Map<String, Object> properties) {
        if (properties.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        properties.forEach((key, value) -> {
            if (sb.length() > 1) sb.append(", ");
            sb.append(key).append(": ");
            if (value instanceof String) {
                sb.append("'").append(escapeString((String) value)).append("'");
            } else {
                sb.append(value);
            }
        });
        sb.append("}");
        return sb.toString();
    }

    private String escapeString(String str) {
        return str.replace("\\", "\\\\").replace("'", "\\'");
    }
}