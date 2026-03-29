package com.hound.graph;

import java.util.*;

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

    public GraphNode toGraphNode() {
        GraphNode node = new GraphNode(type);
        node.addProperty("id", id);
        node.addProperty("nodeType", nodeType);
        node.addProperty("properties", properties);

        if (position != null) {
            node.addProperty("line", position.line);
            node.addProperty("column", position.column);
            node.addProperty("startIndex", position.startIndex);
            node.addProperty("stopIndex", position.stopIndex);
        }

        return node;
    }

    public List<GraphRelationship> toRelationships() {
        List<GraphRelationship> relationships = new ArrayList<>();
        for (UniversalAstNode child : children) {
            relationships.add(new GraphRelationship(id, child.id, "CONTAINS"));
            relationships.addAll(child.toRelationships());
        }
        return relationships;
    }

    // Getters
    public String getId() { return id; }
    public String getType() { return type; }
    public String getNodeType() { return nodeType; }
    public Map<String, Object> getProperties() { return properties; }
    public List<UniversalAstNode> getChildren() { return children; }
    public UniversalAstNode getParent() { return parent; }
    public void setParent(UniversalAstNode parent) { this.parent = parent; }
    public Optional<Position> getPosition() { return Optional.ofNullable(position); }

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