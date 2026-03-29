package com.hound.parser;

import com.hound.graph.UniversalAstNode;

/**
 * Интерфейс для парсера языка
 */
public interface LanguageParser {

    /**
     * Парсит код и возвращает AST дерево
     */
    UniversalAstNode parse(String code, String fileName);

    /**
     * Возвращает имя языка
     */
    String getLanguageName();

    /**
     * Проверяет, поддерживает ли парсер данный код
     */
    boolean supports(String code);

    /**
     * Устанавливает слушателя событий
     */
    void setListener(AstListener listener);
}