// src/main/java/com/hound/semantic/engine/StructureAndLineageBuilder.java
package com.hound.semantic.engine;

import com.hound.semantic.model.*;

import java.util.*;

public class StructureAndLineageBuilder {

    private final Map<String, TableInfo> tables = new LinkedHashMap<>();
    private final Map<String, ColumnInfo> columns = new LinkedHashMap<>();
    private final Map<String, StatementInfo> statements = new LinkedHashMap<>();
    private final Map<String, RoutineInfo> routines = new LinkedHashMap<>();
    private final List<LineageEdge> lineageEdges = new ArrayList<>();

    public String ensureTable(String tableName, String schemaGeoid) {
        String geoid = (schemaGeoid != null ? schemaGeoid + "." : "") + tableName;
        tables.putIfAbsent(geoid, new TableInfo(geoid, tableName, schemaGeoid, "TABLE"));
        return geoid;
    }

    public void addColumn(String tableGeoid, String columnName, String expression, String alias) {
        String geoid = tableGeoid + "." + columnName;
        columns.putIfAbsent(geoid, new ColumnInfo(geoid, tableGeoid, columnName, expression, alias, false, 0));
    }

    public void addLineageEdge(String source, String target, String type, String statementGeoid) {
        lineageEdges.add(new LineageEdge(source, target, type, statementGeoid, null));
    }

    public Structure getStructure() {
        return new Structure(
                Map.of(),           // databases
                Map.of(),           // schemas
                tables,
                columns,
                routines,
                statements
        );
    }

    public List<LineageEdge> getLineageEdges() {
        return new ArrayList<>(lineageEdges);
    }

    public void clear() {
        tables.clear();
        columns.clear();
        statements.clear();
        routines.clear();
        lineageEdges.clear();
    }
}