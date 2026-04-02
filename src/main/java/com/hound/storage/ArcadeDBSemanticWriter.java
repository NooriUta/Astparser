// File: src/main/java/com/hound/storage/ArcadeDBSemanticWriter.java
package com.hound.storage;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.MutableVertex;
import com.hound.semantic.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Записывает SemanticResult в ArcadeDB (embedded mode).
 *
 * Аналог Python ParsingResultStorage (DuckDB), но для ArcadeDB.
 * Embedded mode — нулевой сетевой overhead, БД в файловой системе.
 *
 * Все типы с префиксом "Dali" для избежания конфликтов с зарезервированными словами.
 *
 * Использование:
 *   try (var writer = new ArcadeDBSemanticWriter("./data/hound-db")) {
 *       writer.saveResult(result);
 *   }
 */
public class ArcadeDBSemanticWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ArcadeDBSemanticWriter.class);

    private final Database db;

    /**
     * Открывает или создаёт БД в embedded mode.
     * @param dbPath путь к директории БД
     */
    public ArcadeDBSemanticWriter(String dbPath) {
        DatabaseFactory factory = new DatabaseFactory(dbPath);
        this.db = factory.exists() ? factory.open() : factory.create();
        SchemaInitializer.ensureSchema(db);
        logger.info("ArcadeDB opened: {}", dbPath);
    }

    /** Конструктор для уже открытой БД (для тестов) */
    public ArcadeDBSemanticWriter(Database db) {
        this.db = db;
        SchemaInitializer.ensureSchema(db);
    }

    /**
     * Записывает полный SemanticResult в ArcadeDB одной транзакцией.
     * Аналог Python save_parsing_results().
     *
     * @return sessionId
     */
    public String saveResult(SemanticResult result) {
        String sessionId = result.getSessionId();
        long start = System.currentTimeMillis();

        db.transaction(() -> {
            // 1. Session vertex
            MutableVertex sessionV = saveSession(sessionId, result);

            Structure structure = result.getStructure();
            if (structure == null) return;

            // 2. Tables → DaliTable vertex
            Map<String, MutableVertex> tableVtx = new LinkedHashMap<>();
            for (var e : structure.getTables().entrySet()) {
                MutableVertex v = saveTable(sessionId, e.getKey(), e.getValue());
                tableVtx.put(e.getKey(), v);
                sessionV.newEdge("BELONGS_TO_SESSION", v, true);
            }

            // 3. Columns → DaliColumn vertex + HAS_COLUMN edge
            for (var e : structure.getColumns().entrySet()) {
                ColumnInfo col = e.getValue();
                MutableVertex colV = saveColumn(sessionId, e.getKey(), col);
                MutableVertex tableV = tableVtx.get(col.getTableGeoid());
                if (tableV != null) {
                    tableV.newEdge("HAS_COLUMN", colV, true);
                }
            }

            // 4. Routines → DaliRoutine vertex
            Map<String, MutableVertex> routineVtx = new LinkedHashMap<>();
            for (var e : structure.getRoutines().entrySet()) {
                MutableVertex v = saveRoutine(sessionId, e.getKey(), e.getValue());
                routineVtx.put(e.getKey(), v);
                sessionV.newEdge("BELONGS_TO_SESSION", v, true);
            }

            // 5. Statements → DaliStatement vertex
            Map<String, MutableVertex> stmtVtx = new LinkedHashMap<>();
            for (var e : structure.getStatements().entrySet()) {
                MutableVertex v = saveStatement(sessionId, e.getKey(), e.getValue());
                stmtVtx.put(e.getKey(), v);
            }

            // 6. Statement → parent CHILD_OF edges
            //    Routine → Statement CONTAINS_STMT edges
            for (var e : structure.getStatements().entrySet()) {
                StatementInfo stmt = e.getValue();
                MutableVertex stmtV = stmtVtx.get(e.getKey());
                if (stmtV == null) continue;

                // parent-child
                if (stmt.getParentStatementGeoid() != null) {
                    MutableVertex parentV = stmtVtx.get(stmt.getParentStatementGeoid());
                    if (parentV != null) {
                        stmtV.newEdge("CHILD_OF", parentV, true);
                    }
                }

                // routine contains statement
                if (stmt.getRoutineGeoid() != null) {
                    MutableVertex routineV = routineVtx.get(stmt.getRoutineGeoid());
                    if (routineV != null) {
                        routineV.newEdge("CONTAINS_STMT", stmtV, true);
                    }
                }
            }

            // 7. Lineage edges: READS_FROM, WRITES_TO
            for (LineageEdge edge : result.getLineage()) {
                MutableVertex fromV = resolve(edge.sourceGeoid(), stmtVtx, tableVtx, routineVtx);
                MutableVertex toV = resolve(edge.targetGeoid(), stmtVtx, tableVtx, routineVtx);
                if (fromV != null && toV != null) {
                    fromV.newEdge(edge.relationType(), toV, true)
                            .set("session_id", sessionId)
                            .set("statement_geoid", edge.statementGeoid())
                            .save();
                } else {
                    logger.debug("Lineage edge skipped: {} → {} (vertex not found)",
                            edge.sourceGeoid(), edge.targetGeoid());
                }
            }

            // 8. Atoms → DaliAtom documents
            saveAllAtoms(sessionId, result.getAtoms());

            // 9. Joins → DaliJoin documents
            for (var e : structure.getStatements().entrySet()) {
                for (JoinInfo join : e.getValue().getJoins()) {
                    saveJoin(sessionId, e.getKey(), join);
                }
            }
        });

        long elapsed = System.currentTimeMillis() - start;
        logger.info("Saved to ArcadeDB: session={}, tables={}, columns={}, routines={}, " +
                        "statements={}, lineage={}, atoms={} ({}ms)",
                sessionId,
                safe(result.getStructure(), s -> s.getTables().size()),
                safe(result.getStructure(), s -> s.getColumns().size()),
                safe(result.getStructure(), s -> s.getRoutines().size()),
                safe(result.getStructure(), s -> s.getStatements().size()),
                result.getLineage().size(),
                result.getAtoms().size(),
                elapsed);

        return sessionId;
    }

    // ═══════════════════════════════════════════════════════════════
    // Private: save individual entities
    // ═══════════════════════════════════════════════════════════════

    private MutableVertex saveSession(String sessionId, SemanticResult result) {
        return db.newVertex("DaliSession")
                .set("session_id", sessionId)
                .set("file_path", result.getFilePath())
                .set("dialect", result.getDialect())
                .set("processing_time_ms", result.getProcessingTimeMs())
                .set("created_at", System.currentTimeMillis())
                .save();
    }

    private MutableVertex saveTable(String sessionId, String geoid, TableInfo table) {
        return db.newVertex("DaliTable")
                .set("session_id", sessionId)
                .set("table_geoid", geoid)
                .set("table_name", table.tableName())
                .set("schema_geoid", table.schemaGeoid())
                .set("table_type", table.tableType())
                .set("aliases", listFrom(table.aliases()))
                .set("column_count", table.columnCount())
                .save();
    }

    private MutableVertex saveColumn(String sessionId, String geoid, ColumnInfo col) {
        return db.newVertex("DaliColumn")
                .set("session_id", sessionId)
                .set("column_geoid", geoid)
                .set("table_geoid", col.getTableGeoid())
                .set("column_name", col.getColumnName())
                .set("expression", col.getExpression())
                .set("alias", col.getAlias())
                .set("is_output", col.isOutput())
                .set("col_order", col.getOrder())
                .save();
    }

    private MutableVertex saveRoutine(String sessionId, String geoid, RoutineInfo routine) {
        return db.newVertex("DaliRoutine")
                .set("session_id", sessionId)
                .set("routine_geoid", geoid)
                .set("routine_name", routine.getName())
                .set("routine_type", routine.getRoutineType())
                .set("package_geoid", routine.getPackageGeoid())
                .set("schema_geoid", routine.getSchemaGeoid())
                .save();
    }

    private MutableVertex saveStatement(String sessionId, String geoid, StatementInfo stmt) {
        String snippet = stmt.getSnippet();
        if (snippet != null && snippet.length() > 500) {
            snippet = snippet.substring(0, 500) + "...";
        }
        return db.newVertex("DaliStatement")
                .set("session_id", sessionId)
                .set("stmt_geoid", geoid)
                .set("type", stmt.getType())
                .set("snippet", snippet)
                .set("line_start", stmt.getLineStart())
                .set("line_end", stmt.getLineEnd())
                .set("parent_statement", stmt.getParentStatementGeoid())
                .set("routine_geoid", stmt.getRoutineGeoid())
                .set("source_tables", listFrom(stmt.getSourceTables().keySet()))
                .set("target_tables", listFrom(stmt.getTargetTables().keySet()))
                .set("child_statements", new ArrayList<>(stmt.getChildStatements()))
                .save();
    }

    @SuppressWarnings("unchecked")
    private void saveAllAtoms(String sessionId, Map<String, Object> allAtoms) {
        for (var groupEntry : allAtoms.entrySet()) {
            String groupKey = groupEntry.getKey();
            if (!(groupEntry.getValue() instanceof Map)) continue;

            Map<String, Object> group = (Map<String, Object>) groupEntry.getValue();
            Object atomsObj = group.get("atoms");
            if (!(atomsObj instanceof Map)) continue;

            Map<String, Map<String, Object>> atoms = (Map<String, Map<String, Object>>) atomsObj;
            for (var atomEntry : atoms.entrySet()) {
                Map<String, Object> atom = atomEntry.getValue();
                db.newDocument("DaliAtom")
                        .set("session_id", sessionId)
                        .set("group_key", groupKey)
                        .set("source_geoid", group.get("source_geoid"))
                        .set("atom_text", atom.get("atom_text"))
                        .set("position", atom.get("position"))
                        .set("atom_context", atom.get("atom_context"))
                        .set("parent_context", atom.get("parent_context"))
                        .set("table_geoid", atom.get("table_geoid"))
                        .set("column_name", atom.get("column_name"))
                        .set("is_column_reference", atom.get("is_column_reference"))
                        .set("status", atom.get("status"))
                        .save();
            }
        }
    }

    private void saveJoin(String sessionId, String stmtGeoid, JoinInfo join) {
        db.newDocument("DaliJoin")
                .set("session_id", sessionId)
                .set("stmt_geoid", stmtGeoid)
                .set("join_type", join.joinType())
                .set("target_table_geoid", join.targetTableGeoid())
                .set("target_alias", join.targetTableAlias())
                .set("source_table_geoid", join.sourceTableGeoid())
                .set("source_alias", join.sourceTableAlias())
                .set("conditions", join.conditions())
                .set("line_start", join.lineStart())
                .save();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    /** Resolve geoid to vertex — ищет в statements, потом tables, потом routines */
    @SafeVarargs
    private MutableVertex resolve(String geoid, Map<String, MutableVertex>... maps) {
        if (geoid == null) return null;
        for (var map : maps) {
            MutableVertex v = map.get(geoid);
            if (v != null) return v;
        }
        return null;
    }

    private static List<String> listFrom(Collection<String> col) {
        return col != null ? new ArrayList<>(col) : List.of();
    }

    private static <T> int safe(T obj, java.util.function.ToIntFunction<T> fn) {
        return obj != null ? fn.applyAsInt(obj) : 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // Querying (для будущего использования)
    // ═══════════════════════════════════════════════════════════════

    /** Возвращает открытую БД для произвольных запросов */
    public Database getDatabase() {
        return db;
    }

    @Override
    public void close() {
        if (db != null && db.isOpen()) {
            db.close();
            logger.info("ArcadeDB closed");
        }
    }
}