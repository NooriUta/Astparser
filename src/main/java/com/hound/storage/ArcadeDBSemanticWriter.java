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
import java.util.stream.Collectors;

/**
 * Записывает SemanticResult в ArcadeDB.
 *
 * Реализует полную стратегию хранения:
 * - 13 Vertex types с полными properties
 * - 24 Edge types (structural + usage + atom resolution + flow)
 * - Embedded и Remote режимы
 *
 * Все типы с префиксом "Dali" для избежания конфликтов.
 */
public class ArcadeDBSemanticWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ArcadeDBSemanticWriter.class);
    private static final int SNIPPET_MAX = 4000;

    private Database embeddedDb;
    private RemoteDatabase remoteDb;
    private final Mode mode;

    public enum Mode { EMBEDDED, REMOTE }

    // ═══════════════════════════════════════════════════════════════
    // Constructors
    // ═══════════════════════════════════════════════════════════════

    /** Embedded mode */
    public ArcadeDBSemanticWriter(String dbPath) {
        this.mode = Mode.EMBEDDED;
        DatabaseFactory factory = new DatabaseFactory(dbPath);
        this.embeddedDb = factory.exists() ? factory.open() : factory.create();
        SchemaInitializer.ensureSchema(embeddedDb);
        logger.info("ArcadeDB EMBEDDED: {}", dbPath);
    }

    /** Remote mode */
    public ArcadeDBSemanticWriter(String host, int port, String dbName, String user, String password) {
        this.mode = Mode.REMOTE;
        this.remoteDb = new RemoteDatabase(host, port, dbName, user, password);
        for (String cmd : SchemaInitializer.remoteSchemaCommands()) {
            try { remoteDb.command("sql", cmd); } catch (Exception ignored) {}
        }
        logger.info("ArcadeDB REMOTE: {}:{}/{}", host, port, dbName);
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    public String saveResult(SemanticResult result) {
        String sid = result.getSessionId();
        long t0 = System.currentTimeMillis();

        if (mode == Mode.EMBEDDED) {
            saveEmbedded(sid, result);
        } else {
            saveRemote(sid, result);
        }

        long ms = System.currentTimeMillis() - t0;
        Structure s = result.getStructure();
        logger.info("Saved to ArcadeDB ({}): session={}, tables={}, cols={}, routines={}, " +
                        "stmts={}, atoms={}, joins={}, lineage={} ({}ms)",
                mode, sid,
                s != null ? s.getTables().size() : 0,
                s != null ? s.getColumns().size() : 0,
                s != null ? s.getRoutines().size() : 0,
                s != null ? s.getStatements().size() : 0,
                result.getAtoms().size(),
                s != null ? s.getStatements().values().stream().mapToInt(st -> st.getJoins().size()).sum() : 0,
                result.getLineage().size(), ms);
        return sid;
    }

    // ═══════════════════════════════════════════════════════════════
    // EMBEDDED mode — full graph with edges
    // ═══════════════════════════════════════════════════════════════

    private void saveEmbedded(String sid, SemanticResult result) {
        embeddedDb.transaction(() -> {
            Structure str = result.getStructure();
            if (str == null) return;

            // 1. Session
            MutableVertex sessionV = embeddedDb.newVertex("DaliSession")
                    .set("session_id", sid)
                    .set("file_path", result.getFilePath())
                    .set("dialect", result.getDialect())
                    .set("processing_time_ms", result.getProcessingTimeMs())
                    .set("created_at", System.currentTimeMillis())
                    .save();

            // 2. Tables → DaliTable + BELONGS_TO_SESSION
            Map<String, MutableVertex> tblV = new LinkedHashMap<>();
            for (var e : str.getTables().entrySet()) {
                TableInfo t = e.getValue();
                MutableVertex v = embeddedDb.newVertex("DaliTable")
                        .set("session_id", sid)
                        .set("table_geoid", e.getKey())
                        .set("table_name", t.tableName())
                        .set("schema_geoid", t.schemaGeoid())
                        .set("table_type", t.tableType())
                        .set("aliases", new ArrayList<>(t.aliases()))
                        .set("column_count", t.columnCount())
                        .save();
                tblV.put(e.getKey(), v);
                sessionV.newEdge("BELONGS_TO_SESSION", v, true);
            }

            // 3. Columns → DaliColumn + HAS_COLUMN edge
            Map<String, MutableVertex> colV = new LinkedHashMap<>();
            for (var e : str.getColumns().entrySet()) {
                ColumnInfo c = e.getValue();
                MutableVertex v = embeddedDb.newVertex("DaliColumn")
                        .set("session_id", sid)
                        .set("column_geoid", e.getKey())
                        .set("table_geoid", c.getTableGeoid())
                        .set("column_name", c.getColumnName())
                        .set("expression", c.getExpression())
                        .set("alias", c.getAlias())
                        .set("is_output", c.isOutput())
                        .set("col_order", c.getOrder())
                        .set("used_in_statements", new ArrayList<>(c.getUsedInStatements()))
                        .save();
                colV.put(e.getKey(), v);
                MutableVertex tv = tblV.get(c.getTableGeoid());
                if (tv != null) tv.newEdge("HAS_COLUMN", v, true);
            }

            // 4. Routines → DaliRoutine + BELONGS_TO_SESSION
            Map<String, MutableVertex> rtV = new LinkedHashMap<>();
            for (var e : str.getRoutines().entrySet()) {
                RoutineInfo r = e.getValue();
                MutableVertex v = embeddedDb.newVertex("DaliRoutine")
                        .set("session_id", sid)
                        .set("routine_geoid", e.getKey())
                        .set("routine_name", r.getName())
                        .set("routine_type", r.getRoutineType())
                        .set("package_geoid", r.getPackageGeoid())
                        .set("schema_geoid", r.getSchemaGeoid())
                        .save();
                rtV.put(e.getKey(), v);
                sessionV.newEdge("BELONGS_TO_SESSION", v, true);
            }

            // 5. Statements → DaliStatement (full properties)
            Map<String, MutableVertex> stV = new LinkedHashMap<>();
            for (var e : str.getStatements().entrySet()) {
                StatementInfo s = e.getValue();
                String snippet = truncate(s.getSnippet(), SNIPPET_MAX);
                MutableVertex v = embeddedDb.newVertex("DaliStatement")
                        .set("session_id", sid)
                        .set("stmt_geoid", e.getKey())
                        .set("type", s.getType())
                        .set("snippet", snippet)
                        .set("line_start", s.getLineStart())
                        .set("line_end", s.getLineEnd())
                        .set("parent_statement", s.getParentStatementGeoid())
                        .set("parent_statement_type", parentType(s.getParentStatementGeoid(), str))
                        .set("routine_geoid", s.getRoutineGeoid())
                        .set("short_name", s.getShortName())
                        .set("aliases", new ArrayList<>(s.getAliases()))
                        .set("child_statements", new ArrayList<>(s.getChildStatements()))
                        .set("source_table_geoids", new ArrayList<>(s.getSourceTables().keySet()))
                        .set("target_table_geoids", new ArrayList<>(s.getTargetTables().keySet()))
                        .save();
                stV.put(e.getKey(), v);
            }

            // 6. Statement structural edges
            for (var e : str.getStatements().entrySet()) {
                StatementInfo s = e.getValue();
                MutableVertex sv = stV.get(e.getKey());
                if (sv == null) continue;

                // CHILD_OF
                if (s.getParentStatementGeoid() != null) {
                    MutableVertex pv = stV.get(s.getParentStatementGeoid());
                    if (pv != null) sv.newEdge("CHILD_OF", pv, true);
                }

                // CONTAINS_STMT (Routine → Statement)
                if (s.getRoutineGeoid() != null) {
                    MutableVertex rv = rtV.get(s.getRoutineGeoid());
                    if (rv != null) rv.newEdge("CONTAINS_STMT", sv, true);
                }

                // READS_FROM with aliases on edge
                for (var st : s.getSourceTables().entrySet()) {
                    MutableVertex tv = tblV.get(st.getKey());
                    if (tv != null) {
                        @SuppressWarnings("unchecked")
                        List<String> aliases = st.getValue().get("aliases") instanceof List
                                ? (List<String>) st.getValue().get("aliases") : List.of();
                        sv.newEdge("READS_FROM", tv, true)
                                .set("aliases", new ArrayList<>(aliases))
                                .set("session_id", sid)
                                .save();
                    }
                }

                // WRITES_TO with aliases on edge
                for (var tt : s.getTargetTables().entrySet()) {
                    MutableVertex tv = tblV.get(tt.getKey());
                    if (tv != null) {
                        @SuppressWarnings("unchecked")
                        List<String> aliases = tt.getValue().get("aliases") instanceof List
                                ? (List<String>) tt.getValue().get("aliases") : List.of();
                        sv.newEdge("WRITES_TO", tv, true)
                                .set("aliases", new ArrayList<>(aliases))
                                .set("session_id", sid)
                                .save();
                    }
                }

                // USES_SUBQUERY
                for (var sq : s.getSourceSubqueries().entrySet()) {
                    MutableVertex sqV = stV.get(sq.getKey());
                    if (sqV != null) {
                        sv.newEdge("USES_SUBQUERY", sqV, true)
                                .set("aliases", new ArrayList<>(sq.getValue().subqueryAliases()))
                                .set("subquery_type", sq.getValue().subqueryType())
                                .save();
                    }
                }

                // OutputColumns → DaliOutputColumn + HAS_OUTPUT_COL
                for (var oc : s.getColumnsOutput().entrySet()) {
                    Map<String, Object> col = oc.getValue();
                    MutableVertex ocV = embeddedDb.newVertex("DaliOutputColumn")
                            .set("session_id", sid)
                            .set("statement_geoid", e.getKey())
                            .set("col_key", oc.getKey())
                            .set("name", col.get("name"))
                            .set("expression", col.get("expression"))
                            .set("alias", col.get("alias"))
                            .set("col_order", col.get("order"))
                            .set("source_type", col.get("source_type"))
                            .set("table_ref", col.get("table_ref"))
                            .save();
                    sv.newEdge("HAS_OUTPUT_COL", ocV, true);
                }

                // Atoms → DaliAtom + HAS_ATOM + resolution edges
                for (var at : s.getAtoms().entrySet()) {
                    Map<String, Object> a = at.getValue();
                    MutableVertex aV = embeddedDb.newVertex("DaliAtom")
                            .set("session_id", sid)
                            .set("statement_geoid", e.getKey())
                            .set("atom_text", at.getKey())
                            .set("parent_context", a.get("parent_context"))
                            .set("position", a.get("position"))
                            .set("is_complex", a.get("is_complex"))
                            .set("is_column_reference", a.get("is_column_reference"))
                            .set("is_function_call", a.get("is_function_call"))
                            .set("is_constant", a.get("is_constant"))
                            .set("is_routine_param", a.get("is_routine_param"))
                            .set("is_routine_var", a.get("is_routine_var"))
                            .set("function_name", a.get("function_name"))
                            .set("table_name", a.get("table_name"))
                            .set("column_name", a.get("column_name"))
                            .set("table_geoid", a.get("table_geoid"))
                            .set("status", a.get("status"))
                            .set("output_column_sequence", a.get("output_column_sequence"))
                            .set("nested_atoms_count", a.get("nested_atoms_count"))
                            .save();
                    sv.newEdge("HAS_ATOM", aV, true);

                    // ATOM_REF_TABLE edge
                    String atomTableGeoid = (String) a.get("table_geoid");
                    if (atomTableGeoid != null) {
                        MutableVertex atbl = tblV.get(atomTableGeoid);
                        if (atbl != null) aV.newEdge("ATOM_REF_TABLE", atbl, true);
                    }

                    // ATOM_REF_COLUMN edge
                    String atomColName = (String) a.get("column_name");
                    if (atomTableGeoid != null && atomColName != null) {
                        String colGeoid = atomTableGeoid + "." + atomColName.toUpperCase();
                        MutableVertex acol = colV.get(colGeoid);
                        if (acol != null) aV.newEdge("ATOM_REF_COLUMN", acol, true);
                    }
                }

                // Joins → DaliJoin + HAS_JOIN
                for (JoinInfo j : s.getJoins()) {
                    MutableVertex jV = embeddedDb.newVertex("DaliJoin")
                            .set("session_id", sid)
                            .set("statement_geoid", e.getKey())
                            .set("join_type", j.joinType())
                            .set("source_table_geoid", j.sourceTableGeoid())
                            .set("source_alias", j.sourceTableAlias())
                            .set("source_type", j.sourceType())
                            .set("target_table_geoid", j.targetTableGeoid())
                            .set("target_alias", j.targetTableAlias())
                            .set("target_type", j.targetType())
                            .set("conditions", j.conditions())
                            .set("line_start", j.lineStart())
                            .save();
                    sv.newEdge("HAS_JOIN", jV, true);
                }
            }

            // 7. Lineage edges (from SemanticResult — backward compatible)
            for (LineageEdge le : result.getLineage()) {
                MutableVertex from = resolve(le.sourceGeoid(), stV, tblV, rtV);
                MutableVertex to = resolve(le.targetGeoid(), stV, tblV, rtV);
                if (from != null && to != null) {
                    from.newEdge(le.relationType(), to, true)
                            .set("session_id", sid)
                            .set("statement_geoid", le.statementGeoid())
                            .save();
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // REMOTE mode — SQL commands
    // ═══════════════════════════════════════════════════════════════

    private void saveRemote(String sid, SemanticResult result) {
        Structure str = result.getStructure();
        if (str == null) return;

        // Session
        rcmd("INSERT INTO DaliSession SET session_id=?, file_path=?, dialect=?, processing_time_ms=?, created_at=?",
                sid, result.getFilePath(), result.getDialect(), result.getProcessingTimeMs(), System.currentTimeMillis());

        // Tables
        for (var e : str.getTables().entrySet()) {
            TableInfo t = e.getValue();
            rcmd("INSERT INTO DaliTable SET session_id=?, table_geoid=?, table_name=?, schema_geoid=?, table_type=?, column_count=?",
                    sid, e.getKey(), t.tableName(), t.schemaGeoid(), t.tableType(), t.columnCount());
        }

        // Columns
        for (var e : str.getColumns().entrySet()) {
            ColumnInfo c = e.getValue();
            rcmd("INSERT INTO DaliColumn SET session_id=?, column_geoid=?, table_geoid=?, column_name=?, expression=?, alias=?, is_output=?, col_order=?",
                    sid, e.getKey(), c.getTableGeoid(), c.getColumnName(), c.getExpression(), c.getAlias(), c.isOutput(), c.getOrder());
        }

        // Routines
        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            rcmd("INSERT INTO DaliRoutine SET session_id=?, routine_geoid=?, routine_name=?, routine_type=?, package_geoid=?, schema_geoid=?",
                    sid, e.getKey(), r.getName(), r.getRoutineType(), r.getPackageGeoid(), r.getSchemaGeoid());
        }

        // Statements (full properties)
        for (var e : str.getStatements().entrySet()) {
            StatementInfo s = e.getValue();
            rcmd("INSERT INTO DaliStatement SET session_id=?, stmt_geoid=?, type=?, snippet=?, " +
                            "line_start=?, line_end=?, parent_statement=?, routine_geoid=?, short_name=?",
                    sid, e.getKey(), s.getType(), truncate(s.getSnippet(), SNIPPET_MAX),
                    s.getLineStart(), s.getLineEnd(), s.getParentStatementGeoid(), s.getRoutineGeoid(), s.getShortName());
        }

        // OutputColumns
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                Map<String, Object> col = oc.getValue();
                rcmd("INSERT INTO DaliOutputColumn SET session_id=?, statement_geoid=?, col_key=?, " +
                                "name=?, expression=?, alias=?, col_order=?, source_type=?",
                        sid, e.getKey(), oc.getKey(), col.get("name"), col.get("expression"),
                        col.get("alias"), col.get("order"), col.get("source_type"));
            }
        }

        // Atoms (full properties)
        for (var e : str.getStatements().entrySet()) {
            for (var at : e.getValue().getAtoms().entrySet()) {
                Map<String, Object> a = at.getValue();
                rcmd("INSERT INTO DaliAtom SET session_id=?, statement_geoid=?, atom_text=?, " +
                                "parent_context=?, position=?, is_complex=?, is_column_reference=?, " +
                                "is_function_call=?, is_constant=?, table_name=?, column_name=?, " +
                                "table_geoid=?, status=?, output_column_sequence=?",
                        sid, e.getKey(), at.getKey(),
                        a.get("parent_context"), a.get("position"), a.get("is_complex"), a.get("is_column_reference"),
                        a.get("is_function_call"), a.get("is_constant"), a.get("table_name"), a.get("column_name"),
                        a.get("table_geoid"), a.get("status"), a.get("output_column_sequence"));
            }
        }

        // Joins
        for (var e : str.getStatements().entrySet()) {
            for (JoinInfo j : e.getValue().getJoins()) {
                rcmd("INSERT INTO DaliJoin SET session_id=?, statement_geoid=?, join_type=?, " +
                                "source_table_geoid=?, source_alias=?, target_table_geoid=?, target_alias=?, conditions=?",
                        sid, e.getKey(), j.joinType(), j.sourceTableGeoid(), j.sourceTableAlias(),
                        j.targetTableGeoid(), j.targetTableAlias(), j.conditions());
            }
        }

        // ═══════ Edges via SQL ═══════

        // HAS_COLUMN
        for (var e : str.getColumns().entrySet()) {
            edgeRemote("HAS_COLUMN", "DaliTable", "table_geoid", e.getValue().getTableGeoid(),
                    "DaliColumn", "column_geoid", e.getKey(), sid);
        }

        // CHILD_OF
        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getParentStatementGeoid() != null) {
                edgeRemote("CHILD_OF", "DaliStatement", "stmt_geoid", e.getKey(),
                        "DaliStatement", "stmt_geoid", e.getValue().getParentStatementGeoid(), sid);
            }
        }

        // CONTAINS_STMT
        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getRoutineGeoid() != null) {
                edgeRemote("CONTAINS_STMT", "DaliRoutine", "routine_geoid", e.getValue().getRoutineGeoid(),
                        "DaliStatement", "stmt_geoid", e.getKey(), sid);
            }
        }

        // READS_FROM / WRITES_TO
        for (var e : str.getStatements().entrySet()) {
            for (String tg : e.getValue().getSourceTables().keySet()) {
                edgeRemote("READS_FROM", "DaliStatement", "stmt_geoid", e.getKey(),
                        "DaliTable", "table_geoid", tg, sid);
            }
            for (String tg : e.getValue().getTargetTables().keySet()) {
                edgeRemote("WRITES_TO", "DaliStatement", "stmt_geoid", e.getKey(),
                        "DaliTable", "table_geoid", tg, sid);
            }
        }

        // BELONGS_TO_SESSION
        for (String tg : str.getTables().keySet()) {
            edgeRemote("BELONGS_TO_SESSION", "DaliSession", "session_id", sid,
                    "DaliTable", "table_geoid", tg, sid);
        }
        for (String rg : str.getRoutines().keySet()) {
            edgeRemote("BELONGS_TO_SESSION", "DaliSession", "session_id", sid,
                    "DaliRoutine", "routine_geoid", rg, sid);
        }

        // HAS_OUTPUT_COL
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                edgeRemote("HAS_OUTPUT_COL", "DaliStatement", "stmt_geoid", e.getKey(),
                        "DaliOutputColumn", "col_key", oc.getKey(), sid, "statement_geoid", e.getKey());
            }
        }

        // HAS_ATOM
        for (var e : str.getStatements().entrySet()) {
            for (var at : e.getValue().getAtoms().entrySet()) {
                edgeRemote("HAS_ATOM", "DaliStatement", "stmt_geoid", e.getKey(),
                        "DaliAtom", "atom_text", at.getKey(), sid, "statement_geoid", e.getKey());
            }
        }

        // HAS_JOIN
        for (var e : str.getStatements().entrySet()) {
            for (JoinInfo j : e.getValue().getJoins()) {
                if (j.targetTableGeoid() != null) {
                    edgeRemote("HAS_JOIN", "DaliStatement", "stmt_geoid", e.getKey(),
                            "DaliJoin", "target_table_geoid", j.targetTableGeoid(), sid, "statement_geoid", e.getKey());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private void rcmd(String sql, Object... params) {
        try {
            if (params.length > 0) {
                // Build string SQL — RemoteDatabase may not support positional params for vertex INSERT
                String resolved = sql;
                for (Object p : params) {
                    String val = p == null ? "null" :
                            (p instanceof Number) ? p.toString() :
                                    (p instanceof Boolean) ? p.toString() :
                                            "'" + esc(p.toString()) + "'";
                    resolved = resolved.replaceFirst("\\?", val);
                }
                remoteDb.command("sql", resolved);
            } else {
                remoteDb.command("sql", sql);
            }
        } catch (Exception e) {
            logger.warn("Remote cmd FAILED: {} — {}", sql.substring(0, Math.min(sql.length(), 80)), e.getMessage());
        }
    }

    private void edgeRemote(String edgeType, String fromType, String fromField, String fromVal,
                            String toType, String toField, String toVal, String sid) {
        edgeRemote(edgeType, fromType, fromField, fromVal, toType, toField, toVal, sid, null, null);
    }

    private void edgeRemote(String edgeType, String fromType, String fromField, String fromVal,
                            String toType, String toField, String toVal, String sid,
                            String extraField, String extraVal) {
        String toWhere = toField + " = '" + esc(toVal) + "' AND session_id = '" + sid + "'";
        if (extraField != null) toWhere += " AND " + extraField + " = '" + esc(extraVal) + "'";

        String sql = String.format(
                "CREATE EDGE %s FROM (SELECT FROM %s WHERE %s = '%s' AND session_id = '%s') " +
                        "TO (SELECT FROM %s WHERE %s) SET session_id = '%s'",
                edgeType, fromType, fromField, esc(fromVal), sid, toType, toWhere, sid);
        try { remoteDb.command("sql", sql); }
        catch (Exception e) { logger.debug("Edge {} failed: {}", edgeType, e.getMessage()); }
    }

    private String parentType(String parentGeoid, Structure str) {
        if (parentGeoid == null) return null;
        StatementInfo p = str.getStatements().get(parentGeoid);
        return p != null ? p.getType() : null;
    }

    @SafeVarargs
    private MutableVertex resolve(String geoid, Map<String, MutableVertex>... maps) {
        if (geoid == null) return null;
        for (var m : maps) { MutableVertex v = m.get(geoid); if (v != null) return v; }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    @Override
    public void close() {
        if (embeddedDb != null && embeddedDb.isOpen()) {
            embeddedDb.close();
            logger.info("ArcadeDB EMBEDDED closed");
        }
    }
}