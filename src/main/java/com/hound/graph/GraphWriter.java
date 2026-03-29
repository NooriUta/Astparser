package com.hound.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Записывает AST дерево в графовую базу данных
 * Поддерживает пакетную запись и потокобезопасность
 */
public class GraphWriter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GraphWriter.class);

    private final GraphDatabaseWriter dbWriter;
    private final int batchSize;
    private final List<GraphNode> nodeBuffer;
    private final List<GraphRelationship> relationshipBuffer;
    private final AtomicLong nodesWritten = new AtomicLong(0);
    private final AtomicLong relationshipsWritten = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);

    public GraphWriter(GraphDatabaseWriter dbWriter, int batchSize) {
        this.dbWriter = dbWriter;
        this.batchSize = batchSize;
        this.nodeBuffer = new ArrayList<>(batchSize);
        this.relationshipBuffer = new ArrayList<>(batchSize);
    }

    /**
     * Записывает один узел
     */
    public void writeNode(UniversalAstNode astNode) {
        writeNode(astNode.toGraphNode());
    }

    /**
     * Записывает один узел
     */
    public void writeNode(GraphNode node) {
        synchronized (nodeBuffer) {
            nodeBuffer.add(node);
            if (nodeBuffer.size() >= batchSize) {
                flush();
            }
        }
    }

    /**
     * Записывает связь
     */
    public void writeRelationship(GraphRelationship relationship) {
        synchronized (relationshipBuffer) {
            relationshipBuffer.add(relationship);
            if (relationshipBuffer.size() >= batchSize) {
                flush();
            }
        }
    }

    /**
     * Записывает полное дерево
     */
    public void writeTree(UniversalAstNode root) {
        logger.debug("Writing AST tree to graph database...");

        long startTime = System.currentTimeMillis();

        try {
            // Получаем все узлы и связи
            List<UniversalAstNode> allNodes = root.getAllNodes();
            List<GraphNode> nodes = new ArrayList<>(allNodes.size());
            List<GraphRelationship> relationships = new ArrayList<>();

            for (UniversalAstNode node : allNodes) {
                nodes.add(node.toGraphNode());
                relationships.addAll(node.toRelationships());
            }

            // Записываем пакетами
            writeBatch(nodes, relationships);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Written {} nodes and {} relationships in {}ms ({} batches)",
                    nodesWritten.get(), relationshipsWritten.get(), duration, batchesProcessed.get());

        } catch (Exception e) {
            logger.error("Error writing AST tree to database", e);
            throw new RuntimeException("Failed to write AST tree", e);
        }
    }

    /**
     * Записывает пакет узлов и связей
     */
    public void writeBatch(List<GraphNode> nodes, List<GraphRelationship> relationships) {
        if (nodes.isEmpty() && relationships.isEmpty()) {
            return;
        }

        try {
            dbWriter.beginTransaction();

            // Записываем узлы
            for (GraphNode node : nodes) {
                dbWriter.writeNode(node);
                nodesWritten.incrementAndGet();
            }

            // Записываем связи
            for (GraphRelationship rel : relationships) {
                dbWriter.writeRelationship(rel);
                relationshipsWritten.incrementAndGet();
            }

            dbWriter.commitTransaction();
            batchesProcessed.incrementAndGet();

        } catch (Exception e) {
            dbWriter.rollbackTransaction();
            logger.error("Error writing batch", e);
            throw new RuntimeException("Batch write failed", e);
        }
    }

    /**
     * Сбрасывает буферы в БД
     */
    public void flush() {
        List<GraphNode> nodesToWrite;
        List<GraphRelationship> relsToWrite;

        synchronized (nodeBuffer) {
            if (nodeBuffer.isEmpty() && relationshipBuffer.isEmpty()) {
                return;
            }
            nodesToWrite = new ArrayList<>(nodeBuffer);
            relsToWrite = new ArrayList<>(relationshipBuffer);
            nodeBuffer.clear();
            relationshipBuffer.clear();
        }

        writeBatch(nodesToWrite, relsToWrite);
    }

    /**
     * Получает статистику записи
     */
    public WriteStats getStats() {
        return new WriteStats(
                nodesWritten.get(),
                relationshipsWritten.get(),
                batchesProcessed.get()
        );
    }

    /**
     * Сбрасывает статистику
     */
    public void resetStats() {
        nodesWritten.set(0);
        relationshipsWritten.set(0);
        batchesProcessed.set(0);
    }

    @Override
    public void close() {
        try {
            flush();
        } catch (Exception e) {
            logger.error("Error flushing on close", e);
        }

        if (dbWriter != null) {
            dbWriter.close();
        }
    }

    /**
     * Статистика записи
     */
    public static class WriteStats {
        private final long nodesWritten;
        private final long relationshipsWritten;
        private final long batchesProcessed;

        public WriteStats(long nodesWritten, long relationshipsWritten, long batchesProcessed) {
            this.nodesWritten = nodesWritten;
            this.relationshipsWritten = relationshipsWritten;
            this.batchesProcessed = batchesProcessed;
        }

        public long getNodesWritten() { return nodesWritten; }
        public long getRelationshipsWritten() { return relationshipsWritten; }
        public long getBatchesProcessed() { return batchesProcessed; }

        @Override
        public String toString() {
            return String.format("WriteStats{nodes=%d, relationships=%d, batches=%d}",
                    nodesWritten, relationshipsWritten, batchesProcessed);
        }
    }
}