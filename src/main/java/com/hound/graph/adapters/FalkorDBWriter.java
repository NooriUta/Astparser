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
 * Адаптер для FalkorDB с поддержкой отдельного графа на каждый файл.
 */
public class FalkorDBWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(FalkorDBWriter.class);

    private Driver driver;
    private Graph graph;
    private String currentGraphName;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        this.driver = FalkorDB.driver(host, port);
        this.graph = this.driver.graph(database);   // начальный граф
        this.currentGraphName = database;
        logger.info("Успешно подключено к FalkorDB: {}:{}/граф:{}", host, port, database);
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

    @Override
    public void executeQuery(String query) {
        try {
            graph.query(query);
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
            if (entry.getValue() instanceof String str) {
                String escaped = str.replace("\\", "\\\\").replace("'", "\\'");
                sb.append("'").append(escaped).append("'");
            } else {
                sb.append(entry.getValue());
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public void beginTransaction() { logger.warn("Транзакции в FalkorDB пока не реализованы"); }
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
        if (driver != null) driver.close();
    }
}