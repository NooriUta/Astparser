package com.hound.graph;

import java.util.*;

/**
 * Универсальный узел AST.
 * Root-узел содержит информацию о файле (для Варианта A — отдельный граф на файл).
 */
public class UniversalAstNode {

    private final String id;
    private final String type;
    private final String nodeType;
    private final Map<String, Object> properties;
    private final List<UniversalAstNode> children;
    private UniversalAstNode parent;
    private Position position;

    public UniversalAstNode(String type, String nodeType) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.nodeType = nodeType;
        this.properties = new HashMap<>();
        this.children = new ArrayList<>();
    }

    public UniversalAstNode addProperty(String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
        return this;
    }

    public UniversalAstNode addChild(UniversalAstNode child) {
        children.add(child);
        child.setParent(this);
        return this;
    }

    public void setPosition(int line, int column, int startIndex, int stopIndex) {
        this.position = new Position(line, column, startIndex, stopIndex);
    }

    /**
     * Преобразование в GraphNode.
     * Root-узел явно содержит информацию о файле.
     */
    public GraphNode toGraphNode() {
        GraphNode node = new GraphNode(type);
        node.addProperty("id", id);
        node.addProperty("nodeType", nodeType);

        // Для корневого узла добавляем метаданные файла
        if ("Program".equals(type) || "ROOT".equals(nodeType)) {
            node.addProperty("fileName", properties.get("fileName"));
            node.addProperty("filePath", properties.get("fileName"));
            node.addProperty("fileHash", properties.get("fileHash"));
        }

        node.addProperty("properties", properties);

        if (position != null) {
            node.addProperty("line", position.line);
            node.addProperty("column", position.column);
            node.addProperty("startIndex", position.startIndex);
            node.addProperty("stopIndex", position.stopIndex);
        }

        return node;
    }

    /**
     * Генерирует связи только к прямым детям.
     */
    public List<GraphRelationship> toRelationships() {
        List<GraphRelationship> list = new ArrayList<>();
        for (UniversalAstNode child : children) {
            list.add(new GraphRelationship(id, child.id, "CONTAINS"));
        }
        return list;
    }

    /**
     * Возвращает все узлы дерева (включая текущий)
     */
    public List<UniversalAstNode> getAllNodes() {
        List<UniversalAstNode> allNodes = new ArrayList<>();
        collectAllNodes(this, allNodes);
        return allNodes;
    }

    private void collectAllNodes(UniversalAstNode node, List<UniversalAstNode> result) {
        if (node == null) return;
        result.add(node);
        for (UniversalAstNode child : node.children) {
            collectAllNodes(child, result);
        }
    }

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public String getType() { return type; }
    public String getNodeType() { return nodeType; }
    public Map<String, Object> getProperties() { return properties; }
    public List<UniversalAstNode> getChildren() { return children; }
    public UniversalAstNode getParent() { return parent; }
    public void setParent(UniversalAstNode parent) { this.parent = parent; }

    public Optional<Position> getPosition() {
        return Optional.ofNullable(position);
    }

    /**
     * Внутренний класс позиции
     */
    private static class Position {
        final int line, column, startIndex, stopIndex;

        Position(int line, int column, int startIndex, int stopIndex) {
            this.line = line;
            this.column = column;
            this.startIndex = startIndex;
            this.stopIndex = stopIndex;
        }
    }
}