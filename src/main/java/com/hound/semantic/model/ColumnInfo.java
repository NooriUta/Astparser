// src/main/java/com/hound/semantic/model/ColumnInfo.java
package com.hound.semantic.model;

import java.util.HashSet;
import java.util.Set;

/**
 * Информация о колонке
 */
public class ColumnInfo {

    private final String geoid;
    private final String tableGeoid;
    private final String columnName;
    private final String expression;
    private final String alias;
    private final boolean isOutput;
    private final int order;
    private final Set<String> usedInStatements = new HashSet<>();
    private final Set<String> usedInRoutines = new HashSet<>();

    public ColumnInfo(String geoid, String tableGeoid, String columnName, String expression,
                      String alias, boolean isOutput, int order) {
        this.geoid = geoid;
        this.tableGeoid = tableGeoid;
        this.columnName = columnName;
        this.expression = expression;
        this.alias = alias;
        this.isOutput = isOutput;
        this.order = order;
    }

    // Getters
    public String getGeoid() { return geoid; }
    public String getTableGeoid() { return tableGeoid; }
    public String getColumnName() { return columnName; }
    public String getExpression() { return expression; }
    public String getAlias() { return alias; }
    public boolean isOutput() { return isOutput; }
    public int getOrder() { return order; }
    public Set<String> getUsedInStatements() { return new HashSet<>(usedInStatements); }
    public Set<String> getUsedInRoutines() { return new HashSet<>(usedInRoutines); }

    public void addUsedInStatement(String statementGeoid) {
        if (statementGeoid != null) usedInStatements.add(statementGeoid);
    }
}