package com.hound.graph;

import java.util.*;

/**
 * Универсальный узел AST (полностью восстановлен + BUG-1 исправлен).
 */
public class UniversalAstNode {

    private final String id;
    private final String type;
    private final String nodeType;
    private final Map<String, Object> properties = new HashMap<>();
    private final List<UniversalAstNode> children = new ArrayList<>();
    private UniversalAstNode parent;
    private Position position;

    public UniversalAstNode(String type, String nodeType) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.nodeType = nodeType;
    }

    public UniversalAstNode addProperty(String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
        return this;
    }

    public UniversalAstNode addChild(UniversalAstNode child) {
        if (child != null) {
            children.add(child);
            child.setParent(this);
        }
        return this;
    }

    public void setPosition(int line, int column, int startIndex, int stopIndex) {
        this.position = new Position(line, column, startIndex, stopIndex);
    }

    /**
     * BUG-1 FIX: передаём свой id в GraphNode
     */
    public GraphNode toGraphNode() {
        GraphNode node = new GraphNode(this.id, type);   // используем id из UniversalAstNode
        node.addProperty("nodeType", nodeType);

        if ("Program".equals(type) || "ROOT".equals(nodeType)) {
            node.addProperty("fileName", properties.get("fileName"));
            node.addProperty("filePath", properties.get("fileName"));
            node.addProperty("fileHash", properties.get("fileHash"));
        }

        // Добавляем все свойства как плоские
        for (var entry : properties.entrySet()) {
            node.addProperty(entry.getKey(), entry.getValue());
        }

        if (position != null) {
            node.addProperty("line", position.line);
            node.addProperty("column", position.column);
            node.addProperty("startIndex", position.startIndex);
            node.addProperty("stopIndex", position.stopIndex);
        }

        return node;
    }

    public List<GraphRelationship> toRelationships() {
        List<GraphRelationship> list = new ArrayList<>();
        for (UniversalAstNode child : children) {
            list.add(new GraphRelationship(id, child.id, "CONTAINS"));
        }
        return list;
    }

    // ==================== Getters ====================
    public String getId() { return id; }
    public String getType() { return type; }
    public String getNodeType() { return nodeType; }
    public Map<String, Object> getProperties() { return properties; }
    public List<UniversalAstNode> getChildren() { return children; }
    public UniversalAstNode getParent() { return parent; }
    public void setParent(UniversalAstNode parent) { this.parent = parent; }

    private static class Position {
        final int line, column, startIndex, stopIndex;
        Position(int line, int column, int startIndex, int stopIndex) {
            this.line = line; this.column = column;
            this.startIndex = startIndex; this.stopIndex = stopIndex;
        }
    }
}