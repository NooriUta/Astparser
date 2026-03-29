package com.hound.parser;

import com.hound.parser.registry.ParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Фабрика парсеров с поддержкой принудительного указания диалекта.
 */
public class ParserFactory {

    private static final Logger logger = LoggerFactory.getLogger(ParserFactory.class);

    /**
     * Основной метод получения парсера.
     * Поддерживает принудительное указание языка через аргумент --language=plsql
     */
    public static LanguageParser getParser(String fileName, String forcedLanguage) {
        String language = (forcedLanguage != null && !forcedLanguage.isBlank())
                ? forcedLanguage.trim().toLowerCase()
                : detectLanguage(fileName);

        logger.debug("Файл '{}' → язык: {} (forced: {})",
                fileName, language.toUpperCase(), forcedLanguage != null);

        return ParserRegistry.getParser(language);
    }

    /**
     * Перегруженный метод для обратной совместимости
     */
    public static LanguageParser getParser(String fileName) {
        return getParser(fileName, null);
    }

    public static LanguageParser getParserForLanguage(String language) {
        return ParserRegistry.getParser(language);
    }

    /**
     * Определение языка по имени файла (fallback)
     */
    private static String detectLanguage(String fileName) {
        if (fileName == null || fileName.isBlank()) return "sql";

        String lower = fileName.toLowerCase();

        if (lower.endsWith(".plsql") || lower.endsWith(".pks") || lower.endsWith(".pkb")) {
            return "plsql";
        }
        if (lower.endsWith(".sql")) {
            if (lower.contains("plsql") || lower.contains("oracle") || lower.contains("package")) return "plsql";
            if (lower.contains("athena")) return "athena";
            if (lower.contains("hive")) return "hive";
            if (lower.contains("postgres") || lower.contains("postgresql")) return "postgresql";
            if (lower.contains("tsql") || lower.contains("mssql")) return "tsql";
            if (lower.contains("snowflake")) return "snowflake";
            if (lower.contains("starrocks")) return "starrocks";
            if (lower.contains("trino")) return "trino";
            if (lower.contains("clickhouse")) return "clickhouse";
            if (lower.contains("databricks")) return "databricks";
        }

        if (lower.contains("plsql") || lower.contains("oracle") || lower.contains("package")) return "plsql";

        return "sql";
    }

    public static void registerParser(String language, LanguageParser parser) {
        ParserRegistry.register(language, parser);
        logger.info("Зарегистрирован парсер для языка: {}", language.toUpperCase());
    }
}