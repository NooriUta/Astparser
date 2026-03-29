package com.hound.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Сборщик метрик производительности
 */
public class MetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);

    // Счетчики
    private final LongAdder totalFilesProcessed = new LongAdder();
    private final LongAdder totalFilesSkipped = new LongAdder();
    private final LongAdder totalFilesFailed = new LongAdder();
    private final LongAdder totalNodesCreated = new LongAdder();
    private final LongAdder totalRelationshipsCreated = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();

    // Тайминги
    private final AtomicLong totalProcessingTime = new AtomicLong();
    private final AtomicLong minProcessingTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessingTime = new AtomicLong();

    // Метрики по языкам
    private final ConcurrentHashMap<String, LanguageMetrics> languageMetrics = new ConcurrentHashMap<>();

    private final Instant startTime;

    public MetricsCollector() {
        this.startTime = Instant.now();
    }

    public void recordFileProcessed(long durationMs, int nodes, int relationships) {
        totalFilesProcessed.increment();
        totalNodesCreated.add(nodes);
        totalRelationshipsCreated.add(relationships);
        totalProcessingTime.addAndGet(durationMs);

        // Обновляем min/max
        minProcessingTime.updateAndGet(min -> Math.min(min, durationMs));
        maxProcessingTime.updateAndGet(max -> Math.max(max, durationMs));
    }

    public void recordFileSkipped(String language) {
        totalFilesSkipped.increment();
        languageMetrics.computeIfAbsent(language, k -> new LanguageMetrics()).skipped.increment();
    }

    public void recordFileFailed(String language, String error) {
        totalFilesFailed.increment();
        languageMetrics.computeIfAbsent(language, k -> new LanguageMetrics()).failed.increment();
    }

    public void incrementErrors() {
        totalErrors.increment();
    }

    public void recordLanguageMetrics(String language, int nodes, int relationships, long durationMs) {
        LanguageMetrics metrics = languageMetrics.computeIfAbsent(language, k -> new LanguageMetrics());
        metrics.filesProcessed.increment();
        metrics.nodesCreated.add(nodes);
        metrics.relationshipsCreated.add(relationships);
        metrics.processingTime.addAndGet(durationMs);
    }

    public void printReport() {
        long elapsedSeconds = java.time.Duration.between(startTime, Instant.now()).getSeconds();

        logger.info("========================================");
        logger.info("HOUND Processing Report");
        logger.info("========================================");
        logger.info("Total files processed: {}", totalFilesProcessed.sum());
        logger.info("Total files skipped: {}", totalFilesSkipped.sum());
        logger.info("Total files failed: {}", totalFilesFailed.sum());
        logger.info("Total nodes created: {}", totalNodesCreated.sum());
        logger.info("Total relationships created: {}", totalRelationshipsCreated.sum());
        logger.info("Total errors: {}", totalErrors.sum());
        logger.info("Total processing time: {} ms", totalProcessingTime.get());
        logger.info("Average processing time: {} ms", getAverageProcessingTime());
        logger.info("Min processing time: {} ms", minProcessingTime.get() == Long.MAX_VALUE ? 0 : minProcessingTime.get());
        logger.info("Max processing time: {} ms", maxProcessingTime.get());
        logger.info("Elapsed time: {} seconds", elapsedSeconds);
        logger.info("Throughput: {} files/sec", totalFilesProcessed.sum() / (double) Math.max(1, elapsedSeconds));

        if (!languageMetrics.isEmpty()) {
            logger.info("----------------------------------------");
            logger.info("Language Breakdown:");
            for (var entry : languageMetrics.entrySet()) {
                LanguageMetrics m = entry.getValue();
                logger.info("  {}: processed={}, nodes={}, relationships={}, avgTime={}ms",
                        entry.getKey(), m.filesProcessed.sum(), m.nodesCreated.sum(),
                        m.relationshipsCreated.sum(), m.getAverageTime());
            }
        }
        logger.info("========================================");
    }

    private double getAverageProcessingTime() {
        long total = totalProcessingTime.get();
        long count = totalFilesProcessed.sum();
        return count == 0 ? 0 : (double) total / count;
    }

    public ProcessingMetrics snapshot() {
        return new ProcessingMetrics(
                totalFilesProcessed.sum(),
                totalFilesSkipped.sum(),
                totalFilesFailed.sum(),
                totalNodesCreated.sum(),
                totalRelationshipsCreated.sum(),
                totalErrors.sum(),
                totalProcessingTime.get(),
                getAverageProcessingTime(),
                minProcessingTime.get() == Long.MAX_VALUE ? 0 : minProcessingTime.get(),
                maxProcessingTime.get(),
                java.time.Duration.between(startTime, Instant.now()).toMillis()
        );
    }

    public void incrementSkipped() {
        totalFilesSkipped.increment();
    }

    private static class LanguageMetrics {
        final LongAdder filesProcessed = new LongAdder();
        final LongAdder filesSkipped = new LongAdder();
        final LongAdder filesFailed = new LongAdder();
        final LongAdder nodesCreated = new LongAdder();
        final LongAdder relationshipsCreated = new LongAdder();
        final AtomicLong processingTime = new AtomicLong();

        double getAverageTime() {
            long count = filesProcessed.sum();
            return count == 0 ? 0 : (double) processingTime.get() / count;
        }
    }
}