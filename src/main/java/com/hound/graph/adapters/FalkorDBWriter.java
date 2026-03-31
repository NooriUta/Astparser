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
 * Адаптер FalkorDB для HOUND.
 *
 * Исправления:
 * 1. "Invalid input at end of input: expected '" — посимвольное экранирование
 *    \r, \n, \t, ', \ и прочих управляющих символов в Cypher string literal.
 * 2. Двойное экранирование для вложенных Map/List — toJson теперь НЕ экранирует
 *    управляющие символы; всё экранирование делается единожды в escapeCypherString.
 * 3. executeQuery больше не разбивает по ";", т.к. точка с запятой может быть
 *    внутри строковых литералов (например, в PL/SQL snippet).
 */
public class FalkorDBWriter implements GraphDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(FalkorDBWriter.class);

    private Driver driver;
    private Graph graph;
    private String currentGraphName;

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
        logger.debug("Switched to graph: {}", graphName);
    }

    // ========================= Запись узлов =========================

    @Override
    public void writeNode(GraphNode node) {
        if (node == null) return;

        String label = sanitizeLabel(node.getLabel());
        String propsStr = buildPropertiesString(node.getProperties());
        String cypher = "CREATE (n:" + label + " " + propsStr + ")";

        executeQuery(cypher);
    }

    @Override
    public void writeNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        for (GraphNode node : nodes) {
            writeNode(node);
        }
    }

    // ========================= Запись связей =========================

    @Override
    public void writeRelationship(GraphRelationship rel) {
        if (rel == null) return;

        String propsStr = buildPropertiesString(rel.getProperties());

        // fromNodeId и toNodeId — это UUID, безопасны для вставки,
        // но на всякий случай экранируем
        String cypher = "MATCH (a), (b) WHERE a.id = "
                + escapeCypherString(rel.getFromNodeId())
                + " AND b.id = "
                + escapeCypherString(rel.getToNodeId())
                + " CREATE (a)-[r:" + sanitizeLabel(rel.getType()) + " " + propsStr + "]->(b)";

        executeQuery(cypher);
    }

    @Override
    public void writeRelationships(List<GraphRelationship> rels) {
        if (rels == null || rels.isEmpty()) return;
        for (GraphRelationship rel : rels) {
            writeRelationship(rel);
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
     * <p>
     * Строка         → экранированный строковый литерал в одинарных кавычках
     * Number/Boolean  → как есть
     * Map/List        → сериализуется в плоскую JSON-строку, затем один раз экранируется
     * null            → null
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
            // Сериализуем в «сырую» JSON-строку (без экранирования управляющих символов),
            // затем один раз экранируем всё через escapeCypherString.
            String rawJson = toRawJson(value);
            return escapeCypherString(rawJson);
        }
        // Fallback: toString → escape
        return escapeCypherString(value.toString());
    }

    // ========================= Экранирование для Cypher =========================

    /**
     * Единственная точка экранирования для Cypher string literal (одинарные кавычки).
     * Обрабатывает все управляющие символы посимвольно.
     * <p>
     * Вход: сырая Java-строка (НЕ пре-экранированная).
     * Выход: '<escaped_content>'
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
     * <p>
     * КЛЮЧЕВОЕ ОТЛИЧИЕ от предыдущей версии:
     * Строковые значения оборачиваются только в двойные кавычки JSON,
     * но управляющие символы (\n, \r, \t и т.д.) НЕ экранируются —
     * они остаются как сырые char. Экранирование происходит один раз,
     * когда вся JSON-строка передаётся в escapeCypherString().
     * <p>
     * Единственное, что экранируется здесь — двойная кавычка внутри строки,
     * чтобы не сломать JSON-структуру: " → \"
     */
    private String toRawJson(Object obj) {
        if (obj == null) return "null";

        if (obj instanceof String str) {
            // Экранируем только " и обратный слеш (для корректности JSON-структуры),
            // но НЕ трогаем \n, \r, \t — они останутся сырыми байтами
            // и будут обработаны единожды в escapeCypherString.
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

        // Fallback
        return toRawJson(obj.toString());
    }

    // ========================= Санитизация ключей и меток =========================

    /**
     * Очищает имя метки/типа связи — оставляет только допустимые символы для Cypher.
     */
    private String sanitizeLabel(String label) {
        if (label == null || label.isEmpty()) return "Unknown";
        // Допускаем буквы, цифры, _ ; убираем всё остальное
        return label.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Очищает ключ свойства — оставляет только допустимые символы.
     */
    private String sanitizeKey(String key) {
        if (key == null || key.isEmpty()) return "_unknown";
        return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // ========================= Выполнение запросов =========================

    /**
     * Выполняет один Cypher-запрос.
     * <p>
     * НЕ разбивает по ";", т.к. точка с запятой может встречаться внутри
     * строковых литералов (PL/SQL snippet, комментарии и т.д.).
     */
    @Override
    public void executeQuery(String query) {
        if (query == null || query.isBlank()) return;

        try {
            graph.query(query);
            logger.trace("Executed query successfully");
        } catch (Exception e) {
            // Логируем первые 500 символов запроса для диагностики
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