package com.hound.parser;

/**
 * Фабрика парсеров.
 * Возвращает универсальный парсер, который умеет работать со всеми сгенерированными грамматиками.
 */
public class ParserFactory {

    private static final LanguageParser universalParser = new com.hound.parser.UniversalParser();

    /**
     * Основной метод получения парсера для файла
     */
    public static LanguageParser getParser(String fileName) {
        // Пока используем один универсальный парсер для всех файлов
        // В будущем здесь можно добавить логику выбора конкретного парсера по расширению/языку
        return universalParser;
    }

    /**
     * Получение парсера по имени языка (для прямого использования)
     */
    public static LanguageParser getParserForLanguage(String language) {
        return universalParser;
    }

    /**
     * Регистрация парсера (оставляем для совместимости, пока не используется)
     */
    public static void registerParser(String language, LanguageParser parser) {
        // В текущей архитектуре не требуется, так как используется один универсальный парсер
        logger.warn("Регистрация отдельных парсеров пока не поддерживается. Используется UniversalParser.");
    }
}