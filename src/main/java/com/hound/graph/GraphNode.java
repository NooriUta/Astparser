package com.hound.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GraphNode {
    private final String id;
    private final String label;
    private final Map<String, Object> properties;

    // Новый конструктор для BUG-1 (передаём id из UniversalAstNode)
    public GraphNode(String id, String label) {
        this.id = id;
        this.label = label;
        this.properties = new HashMap<>();
    }

    public GraphNode(String label) {
        this.id = UUID.randomUUID().toString();
        this.label = label;
        this.properties = new HashMap<>();
    }

    public GraphNode(String label, Map<String, Object> properties) {
        this(label);
        this.properties.putAll(properties);
    }

    public GraphNode addProperty(String key, Object value) {
        if (value != null) {
            properties.put(key, escapeValue(value));
        }
        return this;
    }

    private Object escapeValue(Object value) {
        if (value instanceof String) {
            return ((String) value).replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("'", "\\'");
        }
        return value;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public Map<String, Object> getProperties() { return properties; }
}