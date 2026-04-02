package com.hound.semantic.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * ScopeContext — представляет текущее состояние разбора (аналог _current dict в Python).
 */
public class ScopeContext {

    private String statementGeoid;
    private String statementType;
    private String routineGeoid;
    private String schemaGeoid;
    private String packageGeoid;
    private String parentStatement;

    private boolean isInDmlTarget = false;
    private boolean isInJoinContext = false;
    private boolean isInValuesClause = false;
    private boolean isMergeInsertPart = false;
    private boolean isMergeUpdatePart = false;
    private boolean isUnion = false;

    private final Map<String, String> aliases = new HashMap<>();

    public static ScopeContext forStatement(String type, String snippet, int lineStart, int lineEnd) {
        ScopeContext ctx = new ScopeContext();
        ctx.statementType = type;
        // Можно генерировать geoid, если нужно
        ctx.statementGeoid = "stmt:" + type + ":" + lineStart;
        return ctx;
    }

    public void registerAlias(String alias, String geoid) {
        if (alias != null && geoid != null) {
            aliases.put(alias.toUpperCase(), geoid);
        }
    }

    public String resolveAlias(String alias) {
        return alias != null ? aliases.get(alias.toUpperCase()) : null;
    }

    // Getters
    public String getStatementGeoid() { return statementGeoid; }
    public String getStatementType() { return statementType; }
    public String getRoutineGeoid() { return routineGeoid; }
    public String getSchemaGeoid() { return schemaGeoid; }
    public String getParentStatement() { return parentStatement; }

    public boolean isInDmlTarget() { return isInDmlTarget; }
    public boolean isInJoinContext() { return isInJoinContext; }
    public boolean isInValuesClause() { return isInValuesClause; }
    public boolean isMergeInsertPart() { return isMergeInsertPart; }
    public boolean isMergeUpdatePart() { return isMergeUpdatePart; }

    public String getActiveClause() {
        if (isInValuesClause) return "VALUES";
        if (isInJoinContext) return "JOIN";
        if (isMergeInsertPart) return "MERGE_INSERT";
        if (isMergeUpdatePart) return "MERGE_UPDATE";
        return "UNKNOWN";
    }

    // Setters (при необходимости)
    public void setInDmlTarget(boolean inDmlTarget) { this.isInDmlTarget = inDmlTarget; }
    public void setInJoinContext(boolean inJoinContext) { this.isInJoinContext = inJoinContext; }
}