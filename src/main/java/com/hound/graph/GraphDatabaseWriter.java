package com.hound.graph;

public interface GraphDatabaseWriter extends AutoCloseable {
    void connect(String host, int port, String database, String username, String password);
    void writeNode(GraphNode node);
    void writeRelationship(GraphRelationship relationship);
    void executeQuery(String cypher);
    void beginTransaction();
    void commitTransaction();
    void rollbackTransaction();
    void close();
    boolean isConnected();
}