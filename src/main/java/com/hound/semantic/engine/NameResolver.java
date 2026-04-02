// File: src/main/java/com/hound/semantic/engine/NameResolver.java
package com.hound.semantic.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * NameResolver — переносит логику разрешения имён из Python BaseSQLListener.
 * Содержит 8 основных стратегий разрешения.
 */
public class NameResolver {

    private final Map<String, ResolvedRef> resolutionCache = new HashMap<>();

    /**
     * Основной метод разрешения имени (аналог _resolve_name_reference в Python)
     */
    public ResolvedRef resolve(String name, String contextType, String currentStatementGeoid) {
        if (name == null || name.isBlank()) {
            return new ResolvedRef("", "UNKNOWN", null);
        }

        String cacheKey = name.toUpperCase() + "|" + contextType + "|" + currentStatementGeoid;
        return resolutionCache.computeIfAbsent(cacheKey, k -> resolveInternal(name, contextType, currentStatementGeoid));
    }

    /**
     * Внутренняя логика разрешения (8 стратегий)
     */
    private ResolvedRef resolveInternal(String name, String contextType, String currentStatementGeoid) {
        String upperName = name.toUpperCase();

        // Стратегия 1: Таблица по точному имени
        ResolvedRef tableByName = resolveTableByName(upperName);
        if (tableByName.isResolved()) return tableByName;

        // Стратегия 2: Таблица по алиасу в текущем scope
        ResolvedRef tableByAlias = resolveTableByAlias(upperName, currentStatementGeoid);
        if (tableByAlias.isResolved()) return tableByAlias;

        // Стратегия 3: CTE по имени
        ResolvedRef cte = resolveCTE(upperName, currentStatementGeoid);
        if (cte.isResolved()) return cte;

        // Стратегия 4: Подзапрос по алиасу
        ResolvedRef subquery = resolveSubqueryByAlias(upperName, currentStatementGeoid);
        if (subquery.isResolved()) return subquery;

        // Стратегия 5: Child subqueries (вложенные подзапросы)
        ResolvedRef childSubq = resolveChildSubqueries(upperName, currentStatementGeoid);
        if (childSubq.isResolved()) return childSubq;

        // Стратегия 6: Source subqueries
        ResolvedRef sourceSubq = resolveSourceSubqueries(upperName, currentStatementGeoid);
        if (sourceSubq.isResolved()) return sourceSubq;

        // Стратегия 7: Рекурсивный поиск по родительским statement'ам
        ResolvedRef parentRecursive = resolveParentRecursive(upperName, currentStatementGeoid);
        if (parentRecursive.isResolved()) return parentRecursive;

        // Стратегия 8: Неявная таблица (когда колонка без префикса и только одна таблица в FROM)
        ResolvedRef implicit = resolveImplicitTable(currentStatementGeoid);
        if (implicit.isResolved()) return implicit;

        // Если ничего не нашли
        return new ResolvedRef(name, "UNKNOWN", null);
    }

    // ====================== Стратегии ======================

    private ResolvedRef resolveTableByName(String name) {
        // TODO: Здесь можно добавить поиск в StructureAndLineageBuilder.tables
        // Пока возвращаем как таблицу
        return new ResolvedRef(name, "TABLE", name);
    }

    private ResolvedRef resolveTableByAlias(String alias, String currentStatementGeoid) {
        // TODO: Поиск алиаса в текущем scope через ScopeManager
        return new ResolvedRef(alias, "ALIAS", null);
    }

    private ResolvedRef resolveCTE(String name, String currentStatementGeoid) {
        // TODO: Поиск среди CTE в текущем и родительских statement'ах
        return new ResolvedRef(name, "CTE", "cte:" + name);
    }

    private ResolvedRef resolveSubqueryByAlias(String alias, String currentStatementGeoid) {
        // TODO: Поиск подзапроса по алиасу
        return new ResolvedRef(alias, "SUBQUERY", "subq:" + alias);
    }

    private ResolvedRef resolveChildSubqueries(String name, String currentStatementGeoid) {
        // TODO: Поиск среди child subqueries
        return new ResolvedRef(name, "SUBQUERY", null);
    }

    private ResolvedRef resolveSourceSubqueries(String name, String currentStatementGeoid) {
        // TODO: Поиск среди source_subqueries
        return new ResolvedRef(name, "SUBQUERY", null);
    }

    private ResolvedRef resolveParentRecursive(String name, String currentStatementGeoid) {
        // TODO: Рекурсивный подъём по parent_statement
        return new ResolvedRef(name, "UNKNOWN", null);
    }

    private ResolvedRef resolveImplicitTable(String currentStatementGeoid) {
        // TODO: Если в statement только одна таблица — используем её
        return new ResolvedRef("implicit", "TABLE", null);
    }

    public void clearCache() {
        resolutionCache.clear();
    }
}