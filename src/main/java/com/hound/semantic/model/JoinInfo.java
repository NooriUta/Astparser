package com.hound.semantic.model;

import java.util.Map;

/**
 * Информация о JOIN-операции.
 * Аналог Python: join_info dict в PlSqlAnalyzerListener.
 */
public record JoinInfo(
        String joinType,         // INNER JOIN, LEFT OUTER JOIN, RIGHT JOIN, FULL JOIN, CROSS JOIN, NATURAL JOIN
        String targetTableGeoid,
        String targetTableAlias,
        String targetType,       // "table" или "subquery"
        String sourceTableGeoid,
        String sourceTableAlias,
        String sourceType,
        String conditions,       // текст ON-условия
        String statementGeoid,
        int lineStart
) {}