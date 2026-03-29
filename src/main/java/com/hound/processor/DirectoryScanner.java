package com.hound.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Сканер директорий для поиска файлов с кодом
 */
public class DirectoryScanner {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryScanner.class);

    /**
     * Сканирует директорию и возвращает список файлов с указанными расширениями
     *
     * @param startPath начальный путь для сканирования
     * @param extensions список расширений файлов
     * @return список найденных файлов
     * @throws IOException при ошибках ввода-вывода
     */
    public List<Path> scan(String startPath, List<String> extensions) throws IOException {
        return scan(Paths.get(startPath), extensions);
    }

    /**
     * Сканирует директорию и возвращает список файлов с указанными расширениями
     *
     * @param startPath начальный путь для сканирования
     * @param extensions список расширений файлов
     * @return список найденных файлов
     * @throws IOException при ошибках ввода-вывода
     */
    public List<Path> scan(Path startPath, List<String> extensions) throws IOException {
        List<Path> files = new ArrayList<>();

        if (!Files.exists(startPath)) {
            throw new IllegalArgumentException("Path does not exist: " + startPath);
        }

        if (Files.isRegularFile(startPath)) {
            // Если это файл, проверяем расширение
            if (matchesExtension(startPath, extensions)) {
                files.add(startPath);
            }
        } else {
            // Рекурсивный обход директории
            try (Stream<Path> stream = Files.walk(startPath)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(path -> matchesExtension(path, extensions))
                        .forEach(files::add);
            }
        }

        logger.info("Found {} files in {}", files.size(), startPath);
        return files;
    }

    /**
     * Сканирует директорию с использованием FileVisitor (более эффективно для больших директорий)
     */
    public List<Path> scanWithVisitor(Path startPath, List<String> extensions) throws IOException {
        List<Path> files = new ArrayList<>();

        Files.walkFileTree(startPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matchesExtension(file, extensions)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to access file: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });

        logger.info("Found {} files in {}", files.size(), startPath);
        return files;
    }

    /**
     * Проверяет, соответствует ли файл одному из расширений
     */
    private boolean matchesExtension(Path path, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return true;
        }

        String fileName = path.toString().toLowerCase();
        for (String ext : extensions) {
            if (fileName.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Получает список файлов с фильтрацией по размеру
     */
    public List<Path> scanWithSizeLimit(Path startPath, List<String> extensions, long maxSizeBytes) throws IOException {
        List<Path> files = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(startPath)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesExtension(path, extensions))
                    .filter(path -> {
                        try {
                            return Files.size(path) <= maxSizeBytes;
                        } catch (IOException e) {
                            logger.warn("Failed to get file size: {}", path, e);
                            return false;
                        }
                    })
                    .forEach(files::add);
        }

        logger.info("Found {} files (filtered by size limit)", files.size());
        return files;
    }
}