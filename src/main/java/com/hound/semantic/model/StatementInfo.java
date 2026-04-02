// src/main/java/com/hound/semantic/model/StatementInfo.java
package com.hound.semantic.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Информация об SQL statement
 */
public class StatementInfo {

    private final String geoid;
    private final String type;           // SELECT, INSERT, UPDATE, DELETE, MERGE, CTE, SUBQUERY
    private final String snippet;
    private final int lineStart;
    private final int lineEnd;
    private final String parentStatementGeoid;
    private final String routineGeoid;

    private final Map<String, Object> sourceTables = new LinkedHashMap<>();
    private final Map<String, Object> targetTables = new LinkedHashMap<>();
    private final Map<String, Object> columnsOutput = new LinkedHashMap<>();

    public StatementInfo(String geoid, String type, String snippet, int lineStart, int lineEnd,
                         String parentStatementGeoid, String routineGeoid) {
        this.geoid = geoid;
        this.type = type;
        this.snippet = snippet;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.parentStatementGeoid = parentStatementGeoid;
        this.routineGeoid = routineGeoid;
    }

    public String getGeoid() { return geoid; }
    public String getType() { return type; }
    public String getSnippet() { return snippet; }
    public int getLineStart() { return lineStart; }
    public int getLineEnd() { return lineEnd; }
    public String getParentStatementGeoid() { return parentStatementGeoid; }
    public String getRoutineGeoid() { return routineGeoid; }

    public void addSourceTable(String tableGeoid) {
        sourceTables.put(tableGeoid, Map.of("geoid", tableGeoid));
    }

    public void addTargetTable(String tableGeoid) {
        targetTables.put(tableGeoid, Map.of("geoid", tableGeoid));
    }
}