package com.hound.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Менеджер кэша для автоматической очистки и мониторинга
 */
public class CacheManager {
    private static final Logger logger = LoggerFactory.getLogger(CacheManager.class);

    private final FileCache fileCache;
    private final ScheduledExecutorService scheduler;

    public CacheManager(FileCache fileCache) {
        this.fileCache = fileCache;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // Запускаем автоматическую очистку каждый час
        scheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.HOURS);
    }

    /**
     * Выполняет очистку устаревших записей
     */
    public void cleanup() {
        try {
            logger.debug("Running cache cleanup");
            fileCache.cleanup();
            CacheStats stats = fileCache.getStats();
            logger.info("Cache stats after cleanup: {}", stats);
        } catch (Exception e) {
            logger.error("Error during cache cleanup", e);
        }
    }

    /**
     * Останавливает менеджер кэша
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class CacheStats {
        // Вспомогательный класс для статистики
    }
}