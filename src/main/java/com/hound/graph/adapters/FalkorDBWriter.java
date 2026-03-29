package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import com.falkordb.FalkorDB;
import com.falkordb.Driver;          // ← required
import com.falkordb.Graph;
import com.falkordb.ResultSet;       // can stay (unused now) or be removed
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Адаптер для FalkorDB с использованием официального Java клиента (jfalkordb) v0.7.0+
 */
public class FalkorDBWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(FalkorDBWriter.class);

    private Driver driver;
    private Graph graph;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        this.driver = FalkorDB.driver(host, port);
        this.graph = this.driver.graph(database);

        logger.info("Успешно подключено к FalkorDB: {}:{}/граф:{}", host, port, database);
    }

    @Override
    public void writeNode(GraphNode node) {
        String label = node.getLabel();
        String props = propertiesToCypher(node.getProperties());

        String query = String.format("CREATE (n:%s %s)", label, props);
        executeQuery(query);
    }

    @Override
    public void writeRelationship(GraphRelationship rel) {
        String query = String.format(
                "MATCH (a), (b) WHERE a.id = '%s' AND b.id = '%s' " +
                        "CREATE (a)-[r:%s %s]->(b)",
                rel.getFromNodeId(),
                rel.getToNodeId(),
                rel.getType(),
                propertiesToCypher(rel.getProperties())
        );
        executeQuery(query);
    }

    @Override
    public void executeQuery(String query) {
        try {
            // consume() больше не существует в текущей версии jfalkordb
            graph.query(query);
            logger.debug("Запрос к FalkorDB выполнен успешно");
        } catch (Exception e) {
            logger.error("Ошибка выполнения запроса в FalkorDB: {}", query, e);
            throw new RuntimeException("FalkorDB query failed", e);
        }
    }

    private String propertiesToCypher(Map<String, Object> props) {
        if (props.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : props.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof String) {
                sb.append("'").append(escape((String) entry.getValue())).append("'");
            } else {
                sb.append(entry.getValue());
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(String str) {
        return str.replace("\\", "\\\\").replace("'", "\\'");
    }

    @Override
    public void beginTransaction() {
        logger.warn("Транзакции в FalkorDB пока не реализованы в этом адаптере");
    }

    @Override
    public void commitTransaction() { }

    @Override
    public void rollbackTransaction() { }

    @Override
    public boolean isConnected() {
        return driver != null && graph != null;
    }

    @Override
    public void close() {
        try {
            if (driver != null) {
                driver.close();
            }
            logger.info("Подключение к FalkorDB закрыто");
        } catch (Exception e) {
            logger.error("Ошибка при закрытии подключения к FalkorDB", e);
        }
    }
}