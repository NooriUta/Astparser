package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import com.falkordb.FalkorDB;
import com.falkordb.Driver;
import com.falkordb.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Адаптер для FalkorDB с поддержкой отдельного графа на каждый файл (Вариант A).
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
            logger.info("Успешно подключено к FalkorDB: {}:{}/граф:{}", host, port, database);
        } catch (Exception e) {
            logger.error("Ошибка подключения к FalkorDB", e);
            throw new RuntimeException("Failed to connect to FalkorDB", e);
        }
    }

    @Override
    public void switchGraph(String graphName) {
        if (graphName == null || graphName.equals(currentGraphName)) return;
        this.graph = this.driver.graph(graphName);
        this.currentGraphName = graphName;
        logger.debug("Переключён на граф: {}", graphName);
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
                rel.getFromNodeId(), rel.getToNodeId(), rel.getType(),
                propertiesToCypher(rel.getProperties()));
        executeQuery(query);
    }

    /**
     * Критически важная функция — генерирует корректный Cypher для FalkorDB
     */
    private String propertiesToCypher(Map<String, Object> props) {
        if (props.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;

        for (var entry : props.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append(": ");

            Object value = entry.getValue();

            if (value instanceof String str) {
                // Экранируем специальные символы
                String escaped = str
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\"", "\\\"");
                sb.append("'").append(escaped).append("'");
            } else if (value instanceof Map) {
                // Вложенные map преобразуем в строку
                sb.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(value);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public void executeQuery(String query) {
        try {
            graph.query(query);
            logger.debug("Запрос выполнен успешно");
        } catch (Exception e) {
            logger.error("Ошибка выполнения запроса в FalkorDB: {}", query, e);
            throw new RuntimeException("FalkorDB query failed", e);
        }
    }

    @Override
    public void beginTransaction() {
        logger.warn("Транзакции в FalkorDB пока не реализованы");
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
            logger.info("Подключение к FalkorDB закрыто");
        } catch (Exception e) {
            logger.error("Ошибка закрытия подключения к FalkorDB", e);
        }
    }
}