package com.hound.graph;

/**
 * Интерфейс для записи в графовую БД.
 * Добавлен switchGraph для Варианта A (отдельный граф на каждый файл).
 */
public interface GraphDatabaseWriter extends AutoCloseable {

    void connect(String host, int port, String database, String username, String password);

    /**
     * Переключает текущий граф (для каждого файла — свой граф)
     */
    void switchGraph(String graphName);

    void writeNode(GraphNode node);
    void writeRelationship(GraphRelationship relationship);
    void executeQuery(String cypher);

    void beginTransaction();
    void commitTransaction();
    void rollbackTransaction();

    boolean isConnected();
    void close();
}