package com.hound.graph.adapters;

import com.hound.graph.GraphDatabaseWriter;
import com.hound.graph.GraphNode;
import com.hound.graph.GraphRelationship;
import com.falkordb.FalkorDB;
import com.falkordb.Driver;
import com.falkordb.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Адаптер FalkorDB для HOUND.
 *
 * FIX: Edges теряются при записи.
 *
 * Корневые причины:
 * 1. MATCH (a), (b) WHERE a.id = '...' — Cartesian product всех узлов.
 *    При 1000 узлов = 1M пар → timeout/0 результатов.
 * 2. Нет label в MATCH → индекс не используется → full scan.
 * 3. Синтаксис индекса Neo4j-формата → FalkorDB отвергает → индекс не создаётся.
 *
 * Решение:
 * - Храним маппинг nodeId → label (заполняется при writeNode)
 * - В writeRelationship подставляем label в MATCH:
 *   MATCH (a:select_statement {id: '...'}), (b:identifier {id: '...'})
 *   Это даёт: 1) индекс используется, 2) нет Cartesian product
 * - Индекс создаётся с FalkorDB-синтаксисом: CREATE INDEX ON :Label(id)
 */
public class FalkorDBWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(FalkorDBWriter.class);

    private Driver driver;
    private Graph graph;
    private String currentGraphName;

    /**
     * Маппинг nodeId → sanitized label.
     * Заполняется при writeNode(), используется при writeRelationship()
     * для подстановки label в MATCH-клауз.
     */
    private final Map<String, String> nodeIdToLabel = new HashMap<>();

    /**
     * Множество label-ов, для которых уже создан индекс на id.
     */
    private final Set<String> indexedLabels = new HashSet<>();

    // ========================= Подключение =========================

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
        // Сбрасываем маппинг — новый граф, новые узлы
        nodeIdToLabel.clear();
        indexedLabels.clear();
        logger.debug("Switched to graph: {}", graphName);
    }

    // ========================= Запись узлов =========================

    @Override
    public void writeNode(GraphNode node) {
        if (node == null) return;

        String label = sanitizeLabel(node.getLabel());

        // Создаём индекс на id для этого label (один раз)
        ensureIndex(label);

        String propsStr = buildPropertiesString(node.getProperties());
        String cypher = "CREATE (n:" + label + " " + propsStr + ")";

        executeQuery(cypher);

        // Запоминаем id → label для использования в writeRelationship
        String nodeId = node.getId();
        if (nodeId != null) {
            nodeIdToLabel.put(nodeId, label);
        }
    }

    @Override
    public void writeNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        for (GraphNode node : nodes) {
            writeNode(node);
        }
    }

    // ========================= Запись связей =========================

    /**
     * Записывает связь между двумя узлами.
     *
     * КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: MATCH использует label из nodeIdToLabel.
     *
     * Было (Cartesian product, без индекса):
     *   MATCH (a), (b) WHERE a.id = '...' AND b.id = '...'
     *
     * Стало (с label, с индексом):
     *   MATCH (a:select_statement {id: '...'}), (b:identifier {id: '...'})
     *
     * Разница в производительности: O(N²) → O(1) с индексом.
     */
    @Override
    public void writeRelationship(GraphRelationship rel) {
        if (rel == null) return;

        String fromId = rel.getFromNodeId();
        String toId = rel.getToNodeId();

        // Получаем label из маппинга — заполнен при writeNode()
        String fromLabel = nodeIdToLabel.get(fromId);
        String toLabel = nodeIdToLabel.get(toId);

        String propsStr = buildPropertiesString(rel.getProperties());

        String cypher;
        if (fromLabel != null && toLabel != null) {
            // Оптимальный путь: MATCH с label → индекс используется, нет Cartesian product
            cypher = "MATCH (a:" + fromLabel + " {id: " + escapeCypherString(fromId)
                    + "}), (b:" + toLabel + " {id: " + escapeCypherString(toId)
                    + "}) CREATE (a)-[r:" + sanitizeLabel(rel.getType()) + " " + propsStr + "]->(b)";
        } else {
            // Fallback: без label (если writeNode не был вызван или id не найден)
            logger.warn("Label not found for relationship {}->{}, falling back to label-less MATCH",
                    fromId, toId);
            cypher = "MATCH (a {id: " + escapeCypherString(fromId)
                    + "}), (b {id: " + escapeCypherString(toId)
                    + "}) CREATE (a)-[r:" + sanitizeLabel(rel.getType()) + " " + propsStr + "]->(b)";
        }

        executeQuery(cypher);
    }

    @Override
    public void writeRelationships(List<GraphRelationship> rels) {
        if (rels == null || rels.isEmpty()) return;
        for (GraphRelationship rel : rels) {
            writeRelationship(rel);
        }
    }

    // ========================= Индексы =========================

    /**
     * Создаёт индекс на property "id" для указанного label.
     *
     * FalkorDB синтаксис: CREATE INDEX ON :Label(property)
     * (НЕ Neo4j-формат "CREATE INDEX FOR (n:Label) ON (n.property)")
     *
     * Без индекса MATCH (a:Label {id: ...}) делает full scan внутри label.
     * С индексом — O(1) lookup.
     */
    private void ensureIndex(String label) {
        if (label == null || label.isEmpty()) return;
        if (!indexedLabels.add(label)) return; // уже создан

        try {
            // FalkorDB-совместимый синтаксис
            String cypher = "CREATE INDEX ON :" + label + "(id)";
            graph.query(cypher);
            logger.debug("Created index on :{}(id)", label);
        } catch (Exception e) {
            // Индекс уже существует — не ошибка
            logger.debug("Index on :{}(id) already exists or not supported: {}",
                    label, e.getMessage());
        }
    }

    // ========================= Построение Cypher-свойств =========================

    /**
     * Строит строку свойств для Cypher: {key1: 'val1', key2: 42, ...}
     */
    private String buildPropertiesString(Map<String, Object> props) {
        if (props == null || props.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : props.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(sanitizeKey(entry.getKey())).append(": ");
            sb.append(toCypherLiteral(entry.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Конвертирует Java-значение в Cypher literal.
     */
    private String toCypherLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String str) {
            return escapeCypherString(str);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            String rawJson = toRawJson(value);
            return escapeCypherString(rawJson);
        }
        return escapeCypherString(value.toString());
    }

    // ========================= Экранирование для Cypher =========================

    /**
     * Единственная точка экранирования для Cypher string literal.
     * Вход: сырая Java-строка. Выход: '&lt;escaped_content&gt;'
     */
    private String escapeCypherString(String value) {
        if (value == null) return "null";

        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'' -> sb.append("\\'");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\0' -> { /* skip null bytes */ }
                default   -> sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    // ========================= «Сырая» JSON-сериализация =========================

    /**
     * Сериализует Map/List/примитив в JSON-подобную строку.
     * Управляющие символы НЕ экранируются — это делает escapeCypherString().
     */
    private String toRawJson(Object obj) {
        if (obj == null) return "null";

        if (obj instanceof String str) {
            StringBuilder sb = new StringBuilder(str.length() + 8);
            sb.append('"');
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c == '"') {
                    sb.append("\\\"");
                } else if (c == '\\') {
                    sb.append("\\\\");
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
            return sb.toString();
        }

        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }

        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(',');
                sb.append(toRawJson(entry.getKey().toString()));
                sb.append(':');
                sb.append(toRawJson(entry.getValue()));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        }

        if (obj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                sb.append(toRawJson(item));
                first = false;
            }
            sb.append(']');
            return sb.toString();
        }

        return toRawJson(obj.toString());
    }

    // ========================= Санитизация =========================

    private String sanitizeLabel(String label) {
        if (label == null || label.isEmpty()) return "Unknown";
        return label.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String sanitizeKey(String key) {
        if (key == null || key.isEmpty()) return "_unknown";
        return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // ========================= Выполнение запросов =========================

    @Override
    public void executeQuery(String query) {
        if (query == null || query.isBlank()) return;

        try {
            graph.query(query);
            logger.trace("Executed query successfully");
        } catch (Exception e) {
            String preview = query.length() > 500
                    ? query.substring(0, 500) + "...(truncated)"
                    : query;
            logger.error("FalkorDB query failed:\n{}", preview, e);
            throw new RuntimeException("FalkorDB query failed", e);
        }
    }

    // ========================= Транзакции =========================

    @Override
    public void beginTransaction() {
        logger.debug("Transactions not fully supported in FalkorDB adapter");
    }

    @Override
    public void commitTransaction() { }

    @Override
    public void rollbackTransaction() { }

    // ========================= Состояние =========================

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