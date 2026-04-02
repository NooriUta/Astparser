package com.hound.semantic.engine;

import com.hound.semantic.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * StructureAndLineageBuilder — builds structure (tables, columns, statements, routines)
 * and lineage edges.
 * Портирование Python: _init_table, _init_schema, _init_database, _add_column_to_table,
 * _mark_table_as_target/source, и т.д.
 */
public class StructureAndLineageBuilder {

    private static final Logger logger = LoggerFactory.getLogger(StructureAndLineageBuilder.class);

    private final Map<String, Object> databases = new LinkedHashMap<>();
    private final Map<String, Object> schemas = new LinkedHashMap<>();
    private final Map<String, TableInfo> tables = new LinkedHashMap<>();
    private final Map<String, ColumnInfo> columns = new LinkedHashMap<>();
    private final Map<String, StatementInfo> statements = new LinkedHashMap<>();
    private final Map<String, RoutineInfo> routines = new LinkedHashMap<>();
    private final List<LineageEdge> lineageEdges = new ArrayList<>();

    // ═══════ Tables ═══════

    /**
     * Создаёт таблицу если не существует. Возвращает geoid.
     * Аналог Python: _init_table / ensureTable
     */
    public String ensureTable(String tableName, String schemaGeoid) {
        String upperName = tableName.toUpperCase();
        String geoid = (schemaGeoid != null && !schemaGeoid.isBlank())
                ? schemaGeoid.toUpperCase() + "." + upperName
                : upperName;

        tables.computeIfAbsent(geoid, k -> {
            logger.debug("New table registered: {}", geoid);
            return new TableInfo(geoid, upperName, schemaGeoid, "TABLE");
        });
        return geoid;
    }

    public void addTableAlias(String tableGeoid, String alias) {
        TableInfo t = tables.get(tableGeoid);
        if (t != null && alias != null) {
            t.addAlias(alias.toUpperCase());
        }
    }

    public Map<String, TableInfo> getTables() {
        return tables;
    }

    // ═══════ Columns ═══════

    public void addColumn(String tableGeoid, String columnName, String expression, String alias) {
        String upperCol = columnName.toUpperCase();
        String geoid = tableGeoid + "." + upperCol;
        columns.computeIfAbsent(geoid, k -> {
            TableInfo t = tables.get(tableGeoid);
            if (t != null) t.incrementColumnCount();
            return new ColumnInfo(geoid, tableGeoid, upperCol, expression, alias, false, 0);
        });
    }

    // ═══════ Statements ═══════

    public void addStatement(String geoid, String type, String snippet, int lineStart, int lineEnd,
                             String parentStatementGeoid, String routineGeoid) {
        statements.computeIfAbsent(geoid, k -> {
            logger.debug("New statement registered: {} [{}]", type, geoid);
            return new StatementInfo(geoid, type, snippet, lineStart, lineEnd,
                    parentStatementGeoid, routineGeoid);
        });
    }

    public Map<String, StatementInfo> getStatements() {
        return statements;
    }

    // ═══════ Routines ═══════

    public String addRoutine(String name, String routineType, String schemaGeoid,
                             String packageGeoid, int lineStart) {
        String upperName = name != null ? name.toUpperCase() : "UNKNOWN";
        String geoid = (packageGeoid != null ? packageGeoid + "." : "")
                + (schemaGeoid != null ? schemaGeoid + "." : "")
                + upperName;

        routines.computeIfAbsent(geoid, k -> {
            logger.debug("New routine registered: {} {} [{}]", routineType, upperName, geoid);
            return new RoutineInfo(geoid, upperName, routineType, packageGeoid, schemaGeoid);
        });
        return geoid;
    }

    // ═══════ Lineage ═══════

    public void addLineageEdge(String source, String target, String type, String statementGeoid) {
        lineageEdges.add(new LineageEdge(source, target, type, statementGeoid, null));
    }

    public List<LineageEdge> getLineageEdges() {
        return new ArrayList<>(lineageEdges);
    }

    // ═══════ Structure ═══════

    public Structure getStructure() {
        return new Structure(databases, schemas, tables, columns, routines, statements);
    }

    // ═══════ Schemas / Databases ═══════

    public void ensureDatabase(String name) {
        if (name != null && !name.isBlank()) {
            databases.putIfAbsent(name.toUpperCase(), Map.of("name", name.toUpperCase()));
        }
    }

    public void ensureSchema(String name, String dbGeoid) {
        if (name != null && !name.isBlank()) {
            schemas.putIfAbsent(name.toUpperCase(), Map.of("name", name.toUpperCase(), "db", dbGeoid));
        }
    }

    // ═══════ Clear ═══════

    public void clear() {
        databases.clear();
        schemas.clear();
        tables.clear();
        columns.clear();
        statements.clear();
        routines.clear();
        lineageEdges.clear();
    }
}