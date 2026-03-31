package com.hound.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;


public class MetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);

    // Основные счётчики
    private final LongAdder totalFilesProcessed = new LongAdder();
    private final LongAdder totalFilesSkipped = new LongAdder();
    private final LongAdder totalFilesFailed = new LongAdder();
    private final LongAdder totalNodesCreated = new LongAdder();
    private final LongAdder totalRelationshipsCreated = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();

    // Тайминги
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final AtomicLong minProcessingTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessingTime = new AtomicLong(0);

    // Метрики по языкам
    private final ConcurrentHashMap<String, LanguageMetrics> languageMetrics = new ConcurrentHashMap<>();

    private final Instant startTime = Instant.now();

    public void recordFileProcessed(long durationMs, int nodes, int relationships) {
        totalFilesProcessed.increment();
        totalNodesCreated.add(nodes);
        totalRelationshipsCreated.add(relationships);
        totalProcessingTime.addAndGet(durationMs);

        minProcessingTime.updateAndGet(min -> Math.min(min, durationMs));
        maxProcessingTime.updateAndGet(max -> Math.max(max, durationMs));
    }

    public void incrementSkipped() {
        totalFilesSkipped.increment();
    }

    public void incrementErrors() {
        totalErrors.increment();
    }

    public void printReport() {
        long elapsedSeconds = Duration.between(startTime, Instant.now()).getSeconds();
        long totalFiles = totalFilesProcessed.sum();
        double avgTime = totalFiles == 0 ? 0.0 : (double) totalProcessingTime.get() / totalFiles;
        double throughput = totalFiles == 0 ? 0.0 : totalFiles / (double) Math.max(1, elapsedSeconds);

        logger.info("========================================");
        logger.info("HOUND Processing Report");
        logger.info("========================================");
        logger.info("Total files processed : {}", totalFiles);
        logger.info("Total files skipped   : {}", totalFilesSkipped.sum());
        logger.info("Total files failed    : {}", totalFilesFailed.sum());
        logger.info("Total nodes created   : {}", totalNodesCreated.sum());
        logger.info("Total relationships   : {}", totalRelationshipsCreated.sum());
        logger.info("Total errors          : {}", totalErrors.sum());
        logger.info("Total processing time : {} ms", totalProcessingTime.get());
        logger.info("Average time per file : {} ms", String.format("%.1f", avgTime));
        logger.info("Min processing time   : {} ms", minProcessingTime.get() == Long.MAX_VALUE ? 0 : minProcessingTime.get());
        logger.info("Max processing time   : {} ms", maxProcessingTime.get());
        logger.info("Elapsed time          : {} seconds", elapsedSeconds);
        logger.info("Throughput            : {} files/sec", String.format("%.2f", throughput));

        if (!languageMetrics.isEmpty()) {
            logger.info("----------------------------------------");
            logger.info("Language Breakdown:");
            for (var entry : languageMetrics.entrySet()) {
                LanguageMetrics m = entry.getValue();
                logger.info("  {} → processed={}, nodes={}, rels={}, avgTime={} ms",
                        entry.getKey(),
                        m.filesProcessed.sum(),
                        m.nodesCreated.sum(),
                        m.relationshipsCreated.sum(),
                        String.format("%.1f", m.getAverageTime()));
            }
        }
        logger.info("========================================");
    }

    private static class LanguageMetrics {
        final LongAdder filesProcessed = new LongAdder();
        final LongAdder nodesCreated = new LongAdder();
        final LongAdder relationshipsCreated = new LongAdder();
        final AtomicLong processingTime = new AtomicLong();

        double getAverageTime() {
            long count = filesProcessed.sum();
            return count == 0 ? 0.0 : (double) processingTime.get() / count;
        }
    }
}