package com.hound.graph;

import java.util.*;

/**
 * Универсальный узел AST.
 * Root-узел теперь содержит информацию о файле.
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

    // ... (остальные методы addProperty, addChild, setPosition остаются без изменений)

    public GraphNode toGraphNode() {
        GraphNode node = new GraphNode(type);
        node.addProperty("id", id);
        node.addProperty("nodeType", nodeType);
        node.addProperty("properties", properties);

        // Если это корневой узел — добавляем информацию о файле
        if ("Program".equals(type) || "ROOT".equals(nodeType)) {
            node.addProperty("fileName", properties.get("fileName"));
            node.addProperty("filePath", properties.get("fileName"));
        }

        if (position != null) {
            node.addProperty("line", position.line);
            node.addProperty("column", position.column);
            node.addProperty("startIndex", position.startIndex);
            node.addProperty("stopIndex", position.stopIndex);
        }

        return node;
    }

    // toRelationships(), getAllNodes(), collectAllNodes() и остальные методы остаются без изменений
    // (можно оставить как были)

    // Getters и Position класс — без изменений
}