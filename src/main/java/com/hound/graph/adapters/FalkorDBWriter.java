package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import redis.clients.jedis.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class FalkorDBWriter implements GraphDatabaseWriter {
    private static final Logger logger = LoggerFactory.getLogger(FalkorDBWriter.class);
    private JedisPool jedisPool;
    private Jedis jedis;
    private String graphName;
    private Transaction transaction;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        this.graphName = database;
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);

        if (username != null && !username.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, username, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port);
        }

        jedis = jedisPool.getResource();
        logger.info("Connected to FalkorDB at {}:{}/{}", host, port, database);
    }

    @Override
    public void writeNode(GraphNode node) {
        String query = String.format("GRAPH.QUERY %s \"CREATE (:%s %s)\"",
                graphName, node.getLabel(), propertiesToRedis(node.getProperties()));
        executeQuery(query);
    }

    @Override
    public void writeRelationship(GraphRelationship relationship) {
        String query = String.format(
                "GRAPH.QUERY %s \"MATCH (a), (b) WHERE id(a) = '%s' AND id(b) = '%s' CREATE (a)-[:%s %s]->(b)\"",
                graphName, relationship.getFromNodeId(), relationship.getToNodeId(),
                relationship.getType(), propertiesToRedis(relationship.getProperties())
        );
        executeQuery(query);
    }

    @Override
    public void executeQuery(String cypher) {
        try {
            jedis.sendCommand(Protocol.Command.valueOf("GRAPH.QUERY"), graphName, cypher);
        } catch (Exception e) {
            logger.error("Error executing query: {}", cypher, e);
            throw new RuntimeException("Query execution failed", e);
        }
    }

    @Override
    public void beginTransaction() {
        transaction = jedis.multi();
    }

    @Override
    public void commitTransaction() {
        if (transaction != null) {
            transaction.exec();
            transaction = null;
        }
    }

    @Override
    public void rollbackTransaction() {
        if (transaction != null) {
            transaction.discard();
            transaction = null;
        }
    }

    @Override
    public void close() {
        if (jedis != null) jedis.close();
        if (jedisPool != null) jedisPool.close();
    }

    @Override
    public boolean isConnected() {
        return jedis != null && jedis.isConnected();
    }

    private String propertiesToRedis(Map<String, Object> properties) {
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