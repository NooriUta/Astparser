package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Адаптер для Neo4j (Driver 5.x) с поддержкой switchGraph.
 */
public class Neo4jWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jWriter.class);

    private Driver driver;
    private Session session;

    @Override
    public void connect(String host, int port, String database, String username, String password) {
        String uri = "neo4j://" + host + ":" + port;
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        this.session = driver.session(SessionConfig.forDatabase(database));

        logger.info("Успешно подключено к Neo4j: {}:{}/{}", host, port, database);
    }

    @Override
    public void switchGraph(String graphName) {
        if (session != null) session.close();
        this.session = driver.session(SessionConfig.forDatabase(graphName));
        logger.debug("Neo4j: переключён на граф/database '{}'", graphName);
    }

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

    private void executeCypher(String cypher, Map<String, Object> params) {
        try {
            Result result = session.run(cypher, params);
            result.consume();
        } catch (Exception e) {
            logger.error("Ошибка выполнения запроса в Neo4j", e);
            throw new RuntimeException("Neo4j query failed", e);
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
            logger.info("Подключение к Neo4j закрыто");
        } catch (Exception e) {
            logger.error("Ошибка закрытия подключения к Neo4j", e);
        }
    }
}