// File: src/main/java/com/hound/storage/ArcadeDBSemanticWriter.java
package com.hound.storage;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.remote.RemoteDatabase;
import com.hound.semantic.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Записывает SemanticResult в ArcadeDB.
 *
 * Два режима:
 *   EMBEDDED — БД в файловой системе, in-process (--arcade-db ./data/hound-db)
 *   REMOTE   — подключение к ArcadeDB Server через HTTP (--db-host, --db-port, --db-name)
 *
 * Типы с префиксом "Dali" для избежания конфликтов с зарезервированными словами.
 */
public class ArcadeDBSemanticWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ArcadeDBSemanticWriter.class);

    private Database embeddedDb;
    private RemoteDatabase remoteDb;
    private final Mode mode;

    public enum Mode { EMBEDDED, REMOTE }

    // ═══════════════════════════════════════════════════════════════
    // Constructors
    // ═══════════════════════════════════════════════════════════════

    /** Embedded mode: БД в локальной папке */
    public ArcadeDBSemanticWriter(String dbPath) {
        this.mode = Mode.EMBEDDED;
        DatabaseFactory factory = new DatabaseFactory(dbPath);
        this.embeddedDb = factory.exists() ? factory.open() : factory.create();
        initSchema();
        logger.info("ArcadeDB EMBEDDED opened: {}", dbPath);
    }

    /** Remote mode: подключение к ArcadeDB Server (Docker) */
    public ArcadeDBSemanticWriter(String host, int port, String dbName,
                                  String user, String password) {
        this.mode = Mode.REMOTE;
        this.remoteDb = new RemoteDatabase(host, port, dbName, user, password);
        initSchemaRemote();
        logger.info("ArcadeDB REMOTE connected: {}:{}/{}", host, port, dbName);
    }

    // ═══════════════════════════════════════════════════════════════
    // Schema initialization
    // ═══════════════════════════════════════════════════════════════

    private void initSchema() {
        SchemaInitializer.ensureSchema(embeddedDb);
    }

    private void initSchemaRemote() {
        // Vertex types
        for (String type : new String[]{
                "DaliSession", "DaliDatabase", "DaliSchema", "DaliPackage",
                "DaliTable", "DaliColumn", "DaliRoutine", "DaliStatement"}) {
            safeCommand("CREATE VERTEX TYPE " + type + " IF NOT EXISTS");
        }
        // Document types
        for (String type : new String[]{
                "DaliAtom", "DaliJoin", "DaliParameter", "DaliVariable"}) {
            safeCommand("CREATE DOCUMENT TYPE " + type + " IF NOT EXISTS");
        }
        // Edge types
        for (String type : new String[]{
                "READS_FROM", "WRITES_TO", "CONTAINS_STMT", "CONTAINS_ROUTINE",
                "CONTAINS_TABLE", "HAS_COLUMN", "CHILD_OF", "CALLS",
                "DEPENDS_ON", "BELONGS_TO_SESSION"}) {
            safeCommand("CREATE EDGE TYPE " + type + " IF NOT EXISTS");
        }
        logger.info("ArcadeDB remote schema initialized");
    }

    // ═══════════════════════════════════════════════════════════════
    // Save result
    // ═══════════════════════════════════════════════════════════════

    public String saveResult(SemanticResult result) {
        String sessionId = result.getSessionId();
        long start = System.currentTimeMillis();

        if (mode == Mode.EMBEDDED) {
            saveResultEmbedded(sessionId, result);
        } else {
            saveResultRemote(sessionId, result);
        }

        long elapsed = System.currentTimeMillis() - start;
        logger.info("Saved to ArcadeDB ({}): session={}, tables={}, routines={}, " +
                        "statements={}, lineage={} ({}ms)",
                mode, sessionId,
                safe(result.getStructure(), s -> s.getTables().size()),
                safe(result.getStructure(), s -> s.getRoutines().size()),
                safe(result.getStructure(), s -> s.getStatements().size()),
                result.getLineage().size(), elapsed);

        return sessionId;
    }

    // ═══════════════════════════════════════════════════════════════
    // EMBEDDED mode
    // ═══════════════════════════════════════════════════════════════

    private void saveResultEmbedded(String sessionId, SemanticResult result) {
        embeddedDb.transaction(() -> {
            MutableVertex sessionV = embeddedDb.newVertex("DaliSession")
                    .set("session_id", sessionId)
                    .set("file_path", result.getFilePath())
                    .set("dialect", result.getDialect())
                    .set("processing_time_ms", result.getProcessingTimeMs())
                    .set("created_at", System.currentTimeMillis())
                    .save();

            Structure structure = result.getStructure();
            if (structure == null) return;

            // Tables
            Map<String, MutableVertex> tableVtx = new LinkedHashMap<>();
            for (var e : structure.getTables().entrySet()) {
                MutableVertex v = embeddedDb.newVertex("DaliTable")
                        .set("session_id", sessionId)
                        .set("table_geoid", e.getKey())
                        .set("table_name", e.getValue().tableName())
                        .set("schema_geoid", e.getValue().schemaGeoid())
                        .set("table_type", e.getValue().tableType())
                        .set("aliases", listFrom(e.getValue().aliases()))
                        .set("column_count", e.getValue().columnCount())
                        .save();
                tableVtx.put(e.getKey(), v);
                sessionV.newEdge("BELONGS_TO_SESSION", v, true);
            }

            // Columns + HAS_COLUMN edges
            for (var e : structure.getColumns().entrySet()) {
                ColumnInfo col = e.getValue();
                MutableVertex colV = embeddedDb.newVertex("DaliColumn")
                        .set("session_id", sessionId)
                        .set("column_geoid", e.getKey())
                        .set("table_geoid", col.getTableGeoid())
                        .set("column_name", col.getColumnName())
                        .set("expression", col.getExpression())
                        .set("alias", col.getAlias())
                        .set("is_output", col.isOutput())
                        .set("col_order", col.getOrder())
                        .save();
                MutableVertex tableV = tableVtx.get(col.getTableGeoid());
                if (tableV != null) tableV.newEdge("HAS_COLUMN", colV, true);
            }

            // Routines
            Map<String, MutableVertex> routineVtx = new LinkedHashMap<>();
            for (var e : structure.getRoutines().entrySet()) {
                MutableVertex v = embeddedDb.newVertex("DaliRoutine")
                        .set("session_id", sessionId)
                        .set("routine_geoid", e.getKey())
                        .set("routine_name", e.getValue().getName())
                        .set("routine_type", e.getValue().getRoutineType())
                        .set("package_geoid", e.getValue().getPackageGeoid())
                        .set("schema_geoid", e.getValue().getSchemaGeoid())
                        .save();
                routineVtx.put(e.getKey(), v);
                sessionV.newEdge("BELONGS_TO_SESSION", v, true);
            }

            // Statements
            Map<String, MutableVertex> stmtVtx = new LinkedHashMap<>();
            for (var e : structure.getStatements().entrySet()) {
                StatementInfo stmt = e.getValue();
                String snippet = stmt.getSnippet();
                if (snippet != null && snippet.length() > 500)
                    snippet = snippet.substring(0, 500) + "...";

                MutableVertex v = embeddedDb.newVertex("DaliStatement")
                        .set("session_id", sessionId)
                        .set("stmt_geoid", e.getKey())
                        .set("type", stmt.getType())
                        .set("snippet", snippet)
                        .set("line_start", stmt.getLineStart())
                        .set("line_end", stmt.getLineEnd())
                        .set("parent_statement", stmt.getParentStatementGeoid())
                        .set("routine_geoid", stmt.getRoutineGeoid())
                        .save();
                stmtVtx.put(e.getKey(), v);
            }

            // Statement edges: CHILD_OF, CONTAINS_STMT
            for (var e : structure.getStatements().entrySet()) {
                StatementInfo stmt = e.getValue();
                MutableVertex stmtV = stmtVtx.get(e.getKey());
                if (stmtV == null) continue;
                if (stmt.getParentStatementGeoid() != null) {
                    MutableVertex parentV = stmtVtx.get(stmt.getParentStatementGeoid());
                    if (parentV != null) stmtV.newEdge("CHILD_OF", parentV, true);
                }
                if (stmt.getRoutineGeoid() != null) {
                    MutableVertex routineV = routineVtx.get(stmt.getRoutineGeoid());
                    if (routineV != null) routineV.newEdge("CONTAINS_STMT", stmtV, true);
                }
            }

            // Lineage edges
            for (LineageEdge edge : result.getLineage()) {
                MutableVertex fromV = resolve(edge.sourceGeoid(), stmtVtx, tableVtx, routineVtx);
                MutableVertex toV = resolve(edge.targetGeoid(), stmtVtx, tableVtx, routineVtx);
                if (fromV != null && toV != null) {
                    fromV.newEdge(edge.relationType(), toV, true)
                            .set("session_id", sessionId)
                            .set("statement_geoid", edge.statementGeoid())
                            .save();
                }
            }

            // Atoms
            saveAtomsEmbedded(sessionId, result.getAtoms());

            // Joins
            for (var e : structure.getStatements().entrySet()) {
                for (JoinInfo join : e.getValue().getJoins()) {
                    embeddedDb.newDocument("DaliJoin")
                            .set("session_id", sessionId)
                            .set("stmt_geoid", e.getKey())
                            .set("join_type", join.joinType())
                            .set("target_table", join.targetTableGeoid())
                            .set("source_table", join.sourceTableGeoid())
                            .set("conditions", join.conditions())
                            .save();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void saveAtomsEmbedded(String sessionId, Map<String, Object> allAtoms) {
        for (var groupEntry : allAtoms.entrySet()) {
            if (!(groupEntry.getValue() instanceof Map)) continue;
            Map<String, Object> group = (Map<String, Object>) groupEntry.getValue();
            Object atomsObj = group.get("atoms");
            if (!(atomsObj instanceof Map)) continue;
            Map<String, Map<String, Object>> atoms = (Map<String, Map<String, Object>>) atomsObj;
            for (var atom : atoms.values()) {
                embeddedDb.newDocument("DaliAtom")
                        .set("session_id", sessionId)
                        .set("source_geoid", group.get("source_geoid"))
                        .set("atom_text", atom.get("atom_text"))
                        .set("position", atom.get("position"))
                        .set("parent_context", atom.get("parent_context"))
                        .set("status", atom.get("status"))
                        .save();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // REMOTE mode (HTTP API к ArcadeDB Server/Docker)
    // ═══════════════════════════════════════════════════════════════

    private void saveResultRemote(String sessionId, SemanticResult result) {
        // Session
        command("INSERT INTO DaliSession SET session_id = ?, file_path = ?, dialect = ?, " +
                        "processing_time_ms = ?, created_at = ?",
                sessionId, result.getFilePath(), result.getDialect(),
                result.getProcessingTimeMs(), System.currentTimeMillis());

        Structure structure = result.getStructure();
        if (structure == null) return;

        // Tables
        for (var e : structure.getTables().entrySet()) {
            TableInfo t = e.getValue();
            command("INSERT INTO DaliTable SET session_id = ?, table_geoid = ?, " +
                            "table_name = ?, schema_geoid = ?, table_type = ?, column_count = ?",
                    sessionId, e.getKey(), t.tableName(), t.schemaGeoid(), t.tableType(), t.columnCount());
        }

        // Columns
        for (var e : structure.getColumns().entrySet()) {
            ColumnInfo c = e.getValue();
            command("INSERT INTO DaliColumn SET session_id = ?, column_geoid = ?, " +
                            "table_geoid = ?, column_name = ?, expression = ?, alias = ?",
                    sessionId, e.getKey(), c.getTableGeoid(), c.getColumnName(),
                    c.getExpression(), c.getAlias());
        }

        // Routines
        for (var e : structure.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            command("INSERT INTO DaliRoutine SET session_id = ?, routine_geoid = ?, " +
                            "routine_name = ?, routine_type = ?, package_geoid = ?, schema_geoid = ?",
                    sessionId, e.getKey(), r.getName(), r.getRoutineType(),
                    r.getPackageGeoid(), r.getSchemaGeoid());
        }

        // Statements
        for (var e : structure.getStatements().entrySet()) {
            StatementInfo s = e.getValue();
            String snippet = s.getSnippet();
            if (snippet != null && snippet.length() > 500) snippet = snippet.substring(0, 500);
            command("INSERT INTO DaliStatement SET session_id = ?, stmt_geoid = ?, " +
                            "type = ?, snippet = ?, line_start = ?, line_end = ?, " +
                            "parent_statement = ?, routine_geoid = ?",
                    sessionId, e.getKey(), s.getType(), snippet, s.getLineStart(),
                    s.getLineEnd(), s.getParentStatementGeoid(), s.getRoutineGeoid());
        }
// HAS_COLUMN edges: Table → Column
        for (var e : structure.getColumns().entrySet()) {
            ColumnInfo c = e.getValue();
            if (c.getTableGeoid() != null) {
                safeCommand(String.format(
                        "CREATE EDGE HAS_COLUMN FROM (SELECT FROM DaliTable WHERE table_geoid = '%s' AND session_id = '%s') " +
                                "TO (SELECT FROM DaliColumn WHERE column_geoid = '%s' AND session_id = '%s')",
                        esc(c.getTableGeoid()), sessionId, esc(e.getKey()), sessionId));
            }
        }

// CHILD_OF edges: Statement → Parent Statement
        for (var e : structure.getStatements().entrySet()) {
            StatementInfo s = e.getValue();
            if (s.getParentStatementGeoid() != null) {
                safeCommand(String.format(
                        "CREATE EDGE CHILD_OF FROM (SELECT FROM DaliStatement WHERE stmt_geoid = '%s' AND session_id = '%s') " +
                                "TO (SELECT FROM DaliStatement WHERE stmt_geoid = '%s' AND session_id = '%s')",
                        esc(e.getKey()), sessionId, esc(s.getParentStatementGeoid()), sessionId));
            }
        }

// CONTAINS_STMT edges: Routine → Statement
        for (var e : structure.getStatements().entrySet()) {
            StatementInfo s = e.getValue();
            if (s.getRoutineGeoid() != null) {
                safeCommand(String.format(
                        "CREATE EDGE CONTAINS_STMT FROM (SELECT FROM DaliRoutine WHERE routine_geoid = '%s' AND session_id = '%s') " +
                                "TO (SELECT FROM DaliStatement WHERE stmt_geoid = '%s' AND session_id = '%s')",
                        esc(s.getRoutineGeoid()), sessionId, esc(e.getKey()), sessionId));
            }
        }

// BELONGS_TO_SESSION edges: Session → Tables, Session → Routines
        for (String tGeoid : structure.getTables().keySet()) {
            safeCommand(String.format(
                    "CREATE EDGE BELONGS_TO_SESSION FROM (SELECT FROM DaliSession WHERE session_id = '%s') " +
                            "TO (SELECT FROM DaliTable WHERE table_geoid = '%s' AND session_id = '%s')",
                    sessionId, esc(tGeoid), sessionId));
        }
        for (String rGeoid : structure.getRoutines().keySet()) {
            safeCommand(String.format(
                    "CREATE EDGE BELONGS_TO_SESSION FROM (SELECT FROM DaliSession WHERE session_id = '%s') " +
                            "TO (SELECT FROM DaliRoutine WHERE routine_geoid = '%s' AND session_id = '%s')",
                    sessionId, esc(rGeoid), sessionId));
        }
        // Lineage edges via SQL
        for (LineageEdge edge : result.getLineage()) {
            String edgeType = edge.relationType();
            // CREATE EDGE FROM (SELECT FROM DaliX WHERE geoid=?) TO (SELECT FROM DaliY WHERE geoid=?)
            String fromType = guessType(edge.sourceGeoid(), structure);
            String toType = guessType(edge.targetGeoid(), structure);
            String fromField = geoidField(fromType);
            String toField = geoidField(toType);
            safeCommand(String.format(
                    "CREATE EDGE %s FROM (SELECT FROM %s WHERE %s = '%s' AND session_id = '%s') " +
                            "TO (SELECT FROM %s WHERE %s = '%s' AND session_id = '%s') " +
                            "SET session_id = '%s'",
                    edgeType, fromType, fromField, esc(edge.sourceGeoid()), sessionId,
                    toType, toField, esc(edge.targetGeoid()), sessionId, sessionId));
        }

        // Atoms
        saveAtomsRemote(sessionId, result.getAtoms());
    }

    @SuppressWarnings("unchecked")
    private void saveAtomsRemote(String sessionId, Map<String, Object> allAtoms) {
        for (var groupEntry : allAtoms.entrySet()) {
            if (!(groupEntry.getValue() instanceof Map)) continue;
            Map<String, Object> group = (Map<String, Object>) groupEntry.getValue();
            Object atomsObj = group.get("atoms");
            if (!(atomsObj instanceof Map)) continue;
            Map<String, Map<String, Object>> atoms = (Map<String, Map<String, Object>>) atomsObj;
            for (var atom : atoms.values()) {
                command("INSERT INTO DaliAtom SET session_id = ?, source_geoid = ?, " +
                                "atom_text = ?, position = ?, status = ?",
                        sessionId, group.get("source_geoid"),
                        atom.get("atom_text"), atom.get("position"), atom.get("status"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private void command(String sql, Object... params) {
        try {
            remoteDb.command("sql", sql, params);
        } catch (Exception e) {
            logger.error("ArcadeDB command failed: {} — {}", sql, e.getMessage());
        }
    }

    private void safeCommand(String sql) {
        try {
            if (mode == Mode.REMOTE) {
                remoteDb.command("sql", sql);
            } else {
                embeddedDb.command("sql", sql);
            }
        } catch (Exception e) {
            logger.debug("ArcadeDB command skipped: {} — {}", sql, e.getMessage());
        }
    }

    private String guessType(String geoid, Structure structure) {
        if (structure.getStatements().containsKey(geoid)) return "DaliStatement";
        if (structure.getTables().containsKey(geoid)) return "DaliTable";
        if (structure.getRoutines().containsKey(geoid)) return "DaliRoutine";
        return "DaliStatement"; // fallback
    }

    private String geoidField(String type) {
        return switch (type) {
            case "DaliTable" -> "table_geoid";
            case "DaliRoutine" -> "routine_geoid";
            default -> "stmt_geoid";
        };
    }

    private static String esc(String s) {
        return s != null ? s.replace("'", "''") : "";
    }

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

    @Override
    public void close() {
        if (embeddedDb != null && embeddedDb.isOpen()) {
            embeddedDb.close();
            logger.info("ArcadeDB EMBEDDED closed");
        }
        // RemoteDatabase не требует close()
    }
}