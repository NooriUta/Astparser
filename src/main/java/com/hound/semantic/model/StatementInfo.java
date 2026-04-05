package com.hound.semantic.model;

import java.util.*;

/**
 * Информация об SQL statement.
 * Аналог Python: self.statements[geoid] dict.
 */
public class StatementInfo {

    private final String geoid;
    private final String type;           // SELECT, INSERT, UPDATE, DELETE, MERGE, CTE, SUBQUERY, CURSOR, USUBQUERY
    private final String snippet;
    private final int lineStart;
    private final int lineEnd;
    private final String parentStatementGeoid;
    private final String routineGeoid;

    // Source / Target tables (geoid → usage info)
    private final Map<String, Map<String, Object>> sourceTables = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> targetTables = new LinkedHashMap<>();

    // Source subqueries (geoid → SubqueryUsage)
    private final Map<String, SubqueryUsage> sourceSubqueries = new LinkedHashMap<>();

    // Child statements (subqueries, CTEs)
    private final List<String> childStatements = new ArrayList<>();

    // Output columns
    private final LinkedHashMap<String, Map<String, Object>> columnsOutput = new LinkedHashMap<>();

    // Atoms
    private final Map<String, Map<String, Object>> atoms = new LinkedHashMap<>();

    // Joins
    private final List<JoinInfo> joins = new ArrayList<>();

    // H1.4 — statement-level semantic flags
    private boolean hasAggregation = false;   // GROUP BY present
    private boolean hasWindow      = false;   // OVER() / analytic function present
    private boolean isUnion        = false;   // UNION / INTERSECT / MINUS
    private String  subtype        = null;    // INSERT_SELECT, SELECT_INTO, …

    // Aliases and naming
    private final Set<String> aliases = new LinkedHashSet<>();
    private String shortName;
    private String alias;  // primary alias

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

    // ═══════ Getters ═══════

    public String getGeoid() { return geoid; }
    public String getType() { return type; }
    public String getSnippet() { return snippet; }
    public int getLineStart() { return lineStart; }
    public int getLineEnd() { return lineEnd; }
    public String getParentStatementGeoid() { return parentStatementGeoid; }
    public String getRoutineGeoid() { return routineGeoid; }
    public String getShortName() { return shortName; }
    public String getAlias() { return alias; }
    public Set<String> getAliases() { return aliases; }
    public boolean isHasAggregation() { return hasAggregation; }
    public boolean isHasWindow()      { return hasWindow; }
    public boolean isUnion()          { return isUnion; }
    public String  getSubtype()       { return subtype; }
    public List<String> getChildStatements() { return childStatements; }
    public Map<String, SubqueryUsage> getSourceSubqueries() { return sourceSubqueries; }
    public LinkedHashMap<String, Map<String, Object>> getColumnsOutput() { return columnsOutput; }
    public Map<String, Map<String, Object>> getAtoms() { return atoms; }
    public List<JoinInfo> getJoins() { return joins; }

    // ═══════ Source / Target tables ═══════

    public void addSourceTable(String tableGeoid, String tableAlias) {
        addSourceTable(tableGeoid, tableAlias, null, null);
    }

    public void addSourceTable(String tableGeoid, String tableAlias, String tableName, String tableType) {
        Map<String, Object> entry = sourceTables.computeIfAbsent(tableGeoid, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table_stmt", tableGeoid);
            m.put("table_name", tableName != null ? tableName : tableGeoid);
            m.put("table_alias", new ArrayList<String>());
            m.put("table_type", tableType != null ? tableType : "table");
            return m;
        });
        if (tableAlias != null) {
            @SuppressWarnings("unchecked")
            var aliases = (List<String>) entry.get("table_alias");
            if (aliases == null) { aliases = new ArrayList<>(); entry.put("table_alias", aliases); }
            String upper = tableAlias.toUpperCase();
            if (!aliases.contains(upper)) aliases.add(upper);
        }
    }

    public void addTargetTable(String tableGeoid, String tableAlias) {
        addTargetTable(tableGeoid, tableAlias, null, null);
    }

    public void addTargetTable(String tableGeoid, String tableAlias, String tableName, String tableType) {
        Map<String, Object> entry = targetTables.computeIfAbsent(tableGeoid, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table_stmt", tableGeoid);
            m.put("table_name", tableName != null ? tableName : tableGeoid);
            m.put("table_alias", new ArrayList<String>());
            m.put("table_type", tableType != null ? tableType : "table");
            return m;
        });
        if (tableAlias != null) {
            @SuppressWarnings("unchecked")
            var aliases = (List<String>) entry.get("table_alias");
            if (aliases == null) { aliases = new ArrayList<>(); entry.put("table_alias", aliases); }
            String upper = tableAlias.toUpperCase();
            if (!aliases.contains(upper)) aliases.add(upper);
        }
    }

    /** Список geoid'ов source-таблиц (для NameResolver) */
    public List<String> getSourceTableGeoids() {
        return new ArrayList<>(sourceTables.keySet());
    }

    /** Список geoid'ов target-таблиц (для NameResolver) */
    public List<String> getTargetTableGeoids() {
        return new ArrayList<>(targetTables.keySet());
    }

    public Map<String, Map<String, Object>> getSourceTables() { return sourceTables; }
    public Map<String, Map<String, Object>> getTargetTables() { return targetTables; }

    // ═══════ Subqueries ═══════

    public void addSourceSubquery(String subqueryGeoid, String alias, String subqueryStmt) {
        SubqueryUsage existing = sourceSubqueries.get(subqueryGeoid);
        if (existing != null) {
            existing.addAlias(alias);
        } else {
            sourceSubqueries.put(subqueryGeoid, new SubqueryUsage(subqueryStmt, alias, "SUBQUERY"));
        }
    }

    public void addChildStatement(String childGeoid) {
        if (!childStatements.contains(childGeoid)) {
            childStatements.add(childGeoid);
        }
    }

    // ═══════ Aliases ═══════

    public void setHasAggregation(boolean v) { this.hasAggregation = v; }
    public void setHasWindow(boolean v)      { this.hasWindow = v; }
    public void setIsUnion(boolean v)        { this.isUnion = v; }
    public void setSubtype(String v)         { this.subtype = v; }

    public void setShortName(String shortName) { this.shortName = shortName; }
    public void setAlias(String alias) {
        this.alias = alias;
        if (alias != null) this.aliases.add(alias.toUpperCase());
    }
    public void addAlias(String alias) {
        if (alias != null) this.aliases.add(alias.toUpperCase());
    }

    // ═══════ Columns output ═══════

    public void addColumnOutput(String name, Map<String, Object> columnInfo) {
        columnsOutput.put(name, columnInfo);
    }

    // ═══════ Atoms ═══════

    public void addAtom(String key, Map<String, Object> atomData) {
        atoms.put(key, atomData);
    }

    // ═══════ Joins ═══════

    public void addJoin(JoinInfo join) {
        joins.add(join);
    }

    /** STAB-5: replaces join list after postProcessJoins() */
    public void setJoins(java.util.List<JoinInfo> updatedJoins) {
        joins.clear();
        joins.addAll(updatedJoins);
    }
}
 