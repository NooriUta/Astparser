package com.hound.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Утилиты для работы с файлами
 */
public class FileUtils {
    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

    private static final long DEFAULT_MAX_FILE_SIZE = 100 * 1024 * 1024; // 100 MB

    /**
     * Рекурсивный поиск файлов по расширениям
     */
    public static List<Path> findFiles(Path startPath, List<String> extensions, long maxSize) throws IOException {
        List<Path> files = new ArrayList<>();

        if (!Files.exists(startPath)) {
            throw new IllegalArgumentException("Path does not exist: " + startPath);
        }

        try (Stream<Path> stream = Files.walk(startPath)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesExtension(path, extensions))
                    .filter(path -> isWithinSizeLimit(path, maxSize))
                    .forEach(files::add);
        }

        logger.info("Found {} files in {}", files.size(), startPath);
        return files;
    }

    /**
     * Проверка размера файла
     */
    public static boolean isWithinSizeLimit(Path path, long maxSize) {
        try {
            long size = Files.size(path);
            if (size > maxSize) {
                logger.warn("File {} exceeds size limit: {} > {}", path, size, maxSize);
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to get file size: {}", path, e);
            return false;
        }
    }

    /**
     * Проверка расширения файла
     */
    public static boolean matchesExtension(Path path, List<String> extensions) {
        String fileName = path.toString().toLowerCase();
        return extensions.stream().anyMatch(fileName::endsWith);
    }

    /**
     * Чтение файла с обработкой кодировки
     */
    public static String readFile(Path path, String encoding) throws IOException {
        return Files.readString(path, java.nio.charset.Charset.forName(encoding));
    }

    /**
     * Безопасное чтение файла с ограничением размера
     */
    public static String readFileSafe(Path path, String encoding, long maxSize) throws IOException {
        long size = Files.size(path);
        if (size > maxSize) {
            throw new IOException(String.format("File too large: %d > %d", size, maxSize));
        }
        return readFile(path, encoding);
    }

    /**
     * Создание временной директории
     */
    public static Path createTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /**
     * Удаление директории рекурсивно
     */
    public static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Получение относительного пути
     */
    public static String getRelativePath(Path file, Path baseDir) {
        try {
            return baseDir.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}