package com.hound.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Кэш для отслеживания обработанных файлов.
 * Использует файловое хранилище для сохранения состояния между запусками.
 */
public class FileCache {
    private static final Logger logger = LoggerFactory.getLogger(FileCache.class);
    private static final String CACHE_FILE = "cache.json";

    private final Path cachePath;
    private final ConcurrentHashMap<String, CacheEntry> cache;
    private final ReentrantReadWriteLock lock;
    private final ObjectMapper objectMapper;

    public FileCache(String cacheDir) throws IOException {
        this.cachePath = Paths.get(cacheDir);
        this.cache = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // Создаем директорию для кэша если не существует
        Files.createDirectories(cachePath);

        // Загружаем существующий кэш
        loadCache();
    }

    /**
     * Проверяет, был ли файл уже обработан
     */
    public boolean isProcessed(String filePath, String fileHash) {
        lock.readLock().lock();
        try {
            CacheEntry entry = cache.get(filePath);
            if (entry == null) {
                return false;
            }

            // Проверяем хеш файла
            if (!entry.getFileHash().equals(fileHash)) {
                return false;
            }

            // Проверяем TTL
            if (entry.isExpired()) {
                return false;
            }

            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Отмечает файл как обработанный
     */
    public void markProcessed(String filePath, String fileHash) {
        lock.writeLock().lock();
        try {
            CacheEntry entry = new CacheEntry(filePath, fileHash);
            cache.put(filePath, entry);
            saveCache();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Удаляет файл из кэша
     */
    public void invalidate(String filePath) {
        lock.writeLock().lock();
        try {
            cache.remove(filePath);
            saveCache();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Очищает устаревшие записи
     */
    public void cleanup() {
        lock.writeLock().lock();
        try {
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            saveCache();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Получает статистику кэша
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            long active = cache.values().stream().filter(e -> !e.isExpired()).count();
            long expired = cache.values().stream().filter(CacheEntry::isExpired).count();
            return new CacheStats(cache.size(), active, expired);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void loadCache() {
        Path cacheFile = cachePath.resolve(CACHE_FILE);
        if (Files.exists(cacheFile)) {
            try {
                CacheEntry[] entries = objectMapper.readValue(cacheFile.toFile(), CacheEntry[].class);
                for (CacheEntry entry : entries) {
                    cache.put(entry.getFilePath(), entry);
                }
                logger.info("Loaded {} entries from cache", cache.size());
            } catch (IOException e) {
                logger.warn("Failed to load cache: {}", e.getMessage());
            }
        }
    }

    private void saveCache() {
        Path cacheFile = cachePath.resolve(CACHE_FILE);
        try {
            objectMapper.writeValue(cacheFile.toFile(), cache.values().toArray());
        } catch (IOException e) {
            logger.error("Failed to save cache: {}", e.getMessage());
        }
    }

    /**
     * Статистика кэша
     */
    public static class CacheStats {
        private final long total;
        private final long active;
        private final long expired;

        public CacheStats(long total, long active, long expired) {
            this.total = total;
            this.active = active;
            this.expired = expired;
        }

        public long getTotal() { return total; }
        public long getActive() { return active; }
        public long getExpired() { return expired; }

        @Override
        public String toString() {
            return String.format("CacheStats{total=%d, active=%d, expired=%d}", total, active, expired);
        }
    }
}