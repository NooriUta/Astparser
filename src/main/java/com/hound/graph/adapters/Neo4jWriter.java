package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Адаптер для записи данных в Neo4j (Driver 5.x)
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
    public void writeNode(GraphNode node) {
        String cypher = "CREATE (n:" + node.getLabel() + " $props)";
        Map<String, Object> params = Map.of("props", node.getProperties());
        executeCypher(cypher, params);
    }

    @Override
    public void writeRelationship(GraphRelationship rel) {
        String cypher = """
            MATCH (a), (b)
            WHERE a.id = $fromId AND b.id = $toId
            CREATE (a)-[r:%s]->(b)
            SET r += $props
            """.formatted(rel.getType());

        Map<String, Object> params = Map.of(
                "fromId", rel.getFromNodeId(),
                "toId", rel.getToNodeId(),
                "props", rel.getProperties()
        );

        executeCypher(cypher, params);
    }

    private void executeCypher(String cypher, Map<String, Object> params) {
        try {
            // В Neo4j Driver 5.x Result больше не AutoCloseable
            Result result = session.run(cypher, params);
            result.consume(); // Освобождаем результат
        } catch (Exception e) {
            logger.error("Ошибка выполнения Cypher-запроса", e);
            throw new RuntimeException("Neo4j query failed", e);
        }
    }

    @Override
    public void executeQuery(String cypher) {
        executeCypher(cypher, Map.of());
    }

    @Override
    public void beginTransaction() {
        // Для простоты пока не используем явные транзакции
    }

    @Override
    public void commitTransaction() {
        // Реализовано через executeWrite при необходимости
    }

    @Override
    public void rollbackTransaction() {
        // Реализовано через executeWrite при необходимости
    }

    @Override
    public boolean isConnected() {
        // isClosed() удалён в 5.x → используем простой и надёжный чек
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