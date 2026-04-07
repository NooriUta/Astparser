// File: src/main/java/com/hound/storage/JsonlBatchBuilder.java
package com.hound.storage;

import com.hound.semantic.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Собирает NDJSON payload для ArcadeDB HTTP Batch endpoint.
 *
 * <p>Правила:
 * <ol>
 *   <li>Сначала все вершины ({@link #appendVertex} / {@link #appendDocument})
 *   <li>Потом все рёбра ({@link #appendEdge})
 *   <li>{@code @id} для вершин = geoid (уникален в рамках сессии)
 *   <li>{@code @from} / {@code @to} в рёбрах = geoid соответствующих вершин
 * </ol>
 */
public class JsonlBatchBuilder {

    private final StringBuilder vertices = new StringBuilder(64 * 1024);
    private final StringBuilder edges    = new StringBuilder(32 * 1024);
    private int vertexCount;
    private int edgeCount;

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /** Adds a vertex line. extId is the geoid — used as {@code @id}. */
    public void appendVertex(String type, String extId, Map<String, Object> props) {
        vertices.append(toJsonLine("vertex", type, extId, null, null, props)).append('\n');
        vertexCount++;
    }

    /** Adds a document line (e.g. DaliSnippet). No {@code @id}, no edges. */
    public void appendDocument(String type, Map<String, Object> props) {
        vertices.append(toJsonLine("document", type, null, null, null, props)).append('\n');
        vertexCount++;
    }

    /** Adds an edge line. fromExtId / toExtId = geoid of source/target vertices. */
    public void appendEdge(String edgeType, String fromExtId, String toExtId, Map<String, Object> props) {
        edges.append(toJsonLine("edge", edgeType, null, fromExtId, toExtId, props)).append('\n');
        edgeCount++;
    }

    /** Builds final payload: all vertices then all edges (NDJSON). */
    public String build() {
        StringBuilder sb = new StringBuilder(vertices.length() + edges.length());
        sb.append(vertices);
        sb.append(edges);
        return sb.toString();
    }

    public int vertexCount() { return vertexCount; }
    public int edgeCount()   { return edgeCount; }

    // ═══════════════════════════════════════════════════════════════
    // Factory: SemanticResult → NDJSON builder
    // ═══════════════════════════════════════════════════════════════

    private static final int SNIPPET_MAX = 4000;

    /**
     * Builds a complete NDJSON payload from a {@link SemanticResult}.
     * Vertex order mirrors {@code saveRemote()} in {@link ArcadeDBSemanticWriter}.
     */
    public static JsonlBatchBuilder buildFromResult(String sid, SemanticResult result) {
        JsonlBatchBuilder b = new JsonlBatchBuilder();
        Structure str = result.getStructure();
        if (str == null) return b;

        Map<String, Object> sidProps = Map.of("session_id", sid);

        // ─────────────────────────────────────────────────────
        // Phase 1: Vertices (strict order)
        // ─────────────────────────────────────────────────────

        // 1. DaliSession
        b.appendVertex("DaliSession", sid, mapOf(
                "session_id", sid,
                "file_path", nvl(result.getFilePath()),
                "dialect", nvl(result.getDialect()),
                "processing_time_ms", result.getProcessingTimeMs(),
                "created_at", System.currentTimeMillis()
        ));

        // 2. DaliDatabase
        for (var e : str.getDatabases().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) e.getValue();
            b.appendVertex("DaliDatabase", e.getKey(), mapOf(
                    "session_id", sid,
                    "db_geoid", e.getKey(),
                    "db_name", d.get("name")
            ));
        }

        // 3. DaliSchema
        for (var e : str.getSchemas().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sc = (Map<String, Object>) e.getValue();
            b.appendVertex("DaliSchema", e.getKey(), mapOf(
                    "session_id", sid,
                    "schema_geoid", e.getKey(),
                    "schema_name", sc.get("name"),
                    "db_geoid", sc.get("db")
            ));
        }

        // 4. DaliPackage
        for (var e : str.getPackages().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pkg = (Map<String, Object>) e.getValue();
            b.appendVertex("DaliPackage", e.getKey(), mapOf(
                    "session_id", sid,
                    "package_geoid", e.getKey(),
                    "package_name", pkg.get("package_name"),
                    "schema_geoid", pkg.get("schema_geoid"),
                    "routine_geoid", e.getKey(),
                    "routine_name", pkg.get("package_name"),
                    "routine_type", "PACKAGE"
            ));
        }

        // 5. DaliTable
        for (var e : str.getTables().entrySet()) {
            TableInfo t = e.getValue();
            b.appendVertex("DaliTable", e.getKey(), mapOf(
                    "session_id", sid,
                    "table_geoid", e.getKey(),
                    "table_name", t.tableName(),
                    "schema_geoid", t.schemaGeoid(),
                    "table_type", t.tableType(),
                    "column_count", t.columnCount()
            ));
        }

        // 6. DaliColumn
        for (var e : str.getColumns().entrySet()) {
            ColumnInfo c = e.getValue();
            b.appendVertex("DaliColumn", e.getKey(), mapOf(
                    "session_id", sid,
                    "column_geoid", e.getKey(),
                    "table_geoid", c.getTableGeoid(),
                    "column_name", c.getColumnName(),
                    "expression", c.getExpression(),
                    "alias", c.getAlias(),
                    "is_output", c.isOutput(),
                    "col_order", c.getOrder()
            ));
        }

        // 7. DaliRoutine (or DaliPackage if geoid is in packages map)
        Set<String> packageGeoids = str.getPackages().keySet();
        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            // If this routine's geoid matches a package entry, skip (already emitted as DaliPackage)
            if (packageGeoids.contains(e.getKey())) continue;
            b.appendVertex("DaliRoutine", e.getKey(), mapOf(
                    "session_id", sid,
                    "routine_geoid", e.getKey(),
                    "routine_name", r.getName(),
                    "routine_type", r.getRoutineType(),
                    "package_geoid", r.getPackageGeoid(),
                    "schema_geoid", r.getSchemaGeoid()
            ));
        }

        // 7b. DaliParameter, DaliVariable
        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            int pIdx = 0;
            for (RoutineInfo.ParameterInfo p : r.getTypedParameters()) {
                b.appendVertex("DaliParameter", e.getKey() + ":PARAM:" + pIdx, mapOf(
                        "session_id", sid,
                        "routine_geoid", e.getKey(),
                        "param_name", p.name(),
                        "param_type", p.type(),
                        "param_mode", p.mode()
                ));
                pIdx++;
            }
            int vIdx = 0;
            for (RoutineInfo.VariableInfo v : r.getTypedVariables()) {
                b.appendVertex("DaliVariable", e.getKey() + ":VAR:" + vIdx, mapOf(
                        "session_id", sid,
                        "routine_geoid", e.getKey(),
                        "var_name", v.name(),
                        "var_type", v.type()
                ));
                vIdx++;
            }
        }

        // 8. DaliStatement
        for (var e : str.getStatements().entrySet()) {
            StatementInfo s = e.getValue();
            b.appendVertex("DaliStatement", e.getKey(), mapOf(
                    "session_id", sid,
                    "stmt_geoid", e.getKey(),
                    "type", s.getType(),
                    "subtype", s.getSubtype(),
                    "line_start", s.getLineStart(),
                    "line_end", s.getLineEnd(),
                    "parent_statement", s.getParentStatementGeoid(),
                    "parent_statement_type", parentType(s.getParentStatementGeoid(), str),
                    "routine_geoid", s.getRoutineGeoid(),
                    "short_name", s.getShortName(),
                    "is_union", s.isUnion(),
                    "is_dml", isDml(s.getType()),
                    "is_ddl", isDdl(s.getType()),
                    "has_aggregation", s.isHasAggregation(),
                    "has_window", s.isHasWindow(),
                    "has_cte", hasCte(s, str.getStatements()),
                    "join_count", s.getJoins().size(),
                    "col_count_output", s.getColumnsOutput().size(),
                    "col_count_input", countInputColumns(s),
                    "depth", computeDepth(s.getParentStatementGeoid(), str.getStatements()),
                    "quality", computeStatementQuality(s)
            ));
        }

        // 9. DaliOutputColumn
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                Map<String, Object> col = oc.getValue();
                String ocExtId = e.getKey() + ":OC:" + oc.getKey();
                b.appendVertex("DaliOutputColumn", ocExtId, mapOf(
                        "session_id", sid,
                        "statement_geoid", e.getKey(),
                        "col_key", oc.getKey(),
                        "name", col.get("name"),
                        "expression", col.get("expression"),
                        "alias", col.get("alias"),
                        "col_order", col.get("order"),
                        "source_type", col.get("source_type"),
                        "table_ref", col.get("table_ref")
                ));
            }
        }

        // 9b. DaliAffectedColumn
        for (var e : str.getStatements().entrySet()) {
            int acIdx = 0;
            for (Map<String, Object> ac : e.getValue().getAffectedColumns()) {
                String typeAffect = null;
                Integer orderAffect = null;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> poliage = (List<Map<String, Object>>) ac.get("poliage_update");
                if (poliage != null && !poliage.isEmpty()) {
                    typeAffect  = (String)  poliage.get(0).get("type_affect");
                    Object oa   = poliage.get(0).get("order_affect");
                    orderAffect = oa instanceof Number n ? n.intValue() : null;
                }
                String acExtId = e.getKey() + ":AC:" + acIdx;
                b.appendVertex("DaliAffectedColumn", acExtId, mapOf(
                        "session_id", sid,
                        "statement_geoid", e.getKey(),
                        "column_ref", ac.get("column_ref"),
                        "column_name", ac.get("column_name"),
                        "table_geoid", ac.get("table_geoid"),
                        "dataset_alias", ac.get("dataset_alias"),
                        "source_type", ac.get("source_type"),
                        "resolution_status", ac.get("resolution_status"),
                        "type_affect", typeAffect,
                        "order_affect", orderAffect
                ));
                acIdx++;
            }
        }

        // 10. DaliAtom
        // Build atomId map for edge creation later
        Map<String, String> atomIdMap = new LinkedHashMap<>(); // "stmtGeoid:atomText" → atomId
        for (var container : result.getAtoms().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cont = (Map<String, Object>) container.getValue();
            String stmtGeoid = (String) cont.get("source_geoid");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> atoms =
                    (Map<String, Map<String, Object>>) cont.get("atoms");
            if (atoms == null || stmtGeoid == null) continue;

            for (var at : atoms.entrySet()) {
                Map<String, Object> a = at.getValue();
                String atomId = md5(stmtGeoid + ":" + at.getKey());
                atomIdMap.put(stmtGeoid + ":" + at.getKey(), atomId);
                b.appendVertex("DaliAtom", atomId, mapOf(
                        "session_id", sid,
                        "statement_geoid", stmtGeoid,
                        "atom_id", atomId,
                        "atom_text", at.getKey(),
                        "atom_context", a.get("atom_context"),
                        "parent_context", a.get("parent_context"),
                        "position", a.get("position"),
                        "sposition", a.get("sposition"),
                        "is_complex", a.get("is_complex"),
                        "is_column_reference", a.get("is_column_reference"),
                        "is_function_call", a.get("is_function_call"),
                        "is_constant", a.get("is_constant"),
                        "is_routine_param", a.get("is_routine_param"),
                        "is_routine_var", a.get("is_routine_var"),
                        "table_name", a.get("table_name"),
                        "column_name", a.get("column_name"),
                        "table_geoid", a.get("table_geoid"),
                        "status", a.get("status"),
                        "output_column_sequence", a.get("output_column_sequence"),
                        "nested_atoms_count", a.get("nested_atoms_count")
                ));
            }
        }

        // 11. DaliJoin
        for (var e : str.getStatements().entrySet()) {
            int joinIdx = 0;
            for (JoinInfo j : e.getValue().getJoins()) {
                String joinExtId = e.getKey() + ":JOIN:" + j.lineStart() + ":" + joinIdx;
                b.appendVertex("DaliJoin", joinExtId, mapOf(
                        "session_id", sid,
                        "statement_geoid", e.getKey(),
                        "join_type", j.joinType(),
                        "source_table_geoid", j.sourceTableGeoid(),
                        "source_alias", j.sourceTableAlias(),
                        "source_type", j.sourceType(),
                        "target_table_geoid", j.targetTableGeoid(),
                        "target_alias", j.targetTableAlias(),
                        "target_type", j.targetType(),
                        "conditions", j.conditions(),
                        "line_start", j.lineStart()
                ));
                joinIdx++;
            }
        }

        // 12. DaliSnippet (document, not vertex)
        for (var e : str.getStatements().entrySet()) {
            String raw = truncate(e.getValue().getSnippet(), SNIPPET_MAX);
            if (raw == null) continue;
            b.appendDocument("DaliSnippet", mapOf(
                    "session_id", sid,
                    "stmt_geoid", e.getKey(),
                    "snippet", raw,
                    "snippet_hash", md5(raw)
            ));
        }

        // 12b. DaliResolutionLog (document)
        for (Map<String, Object> logEntry : result.getResolutionLog()) {
            b.appendDocument("DaliResolutionLog", mapOf(
                    "session_id", sid,
                    "file_path", result.getFilePath(),
                    "statement_geoid", logEntry.get("statement_geoid"),
                    "raw_input", logEntry.get("raw_input"),
                    "result_kind", logEntry.get("result_kind"),
                    "is_function_call", logEntry.get("is_function_call"),
                    "atom_context", logEntry.get("atom_context"),
                    "parent_context", logEntry.get("parent_context"),
                    "note", logEntry.get("note"),
                    "strategy", logEntry.get("strategy"),
                    "table_name", logEntry.get("table_name"),
                    "column_name", logEntry.get("column_name"),
                    "position", logEntry.get("position")
            ));
        }

        // 12c. DaliSchemaLog (document)
        for (Map<String, Object> schEntry : result.getSchemaRegistrationLog()) {
            b.appendDocument("DaliSchemaLog", mapOf(
                    "session_id", sid,
                    "schema_name", schEntry.get("schema_name"),
                    "reason", schEntry.get("reason"),
                    "backtrace", schEntry.get("backtrace")
            ));
        }

        // ─────────────────────────────────────────────────────
        // Phase 2: Edges (strictly after all vertices)
        // ─────────────────────────────────────────────────────

        // Structural: CONTAINS_TABLE
        for (var e : str.getTables().entrySet()) {
            String sg = e.getValue().schemaGeoid();
            if (sg != null)
                b.appendEdge("CONTAINS_TABLE", sg, e.getKey(), sidProps);
        }

        // Structural: CONTAINS_ROUTINE (schema → package)
        for (var e : str.getPackages().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pkg = (Map<String, Object>) e.getValue();
            String sg = (String) pkg.get("schema_geoid");
            if (sg != null && !sg.isEmpty())
                b.appendEdge("CONTAINS_ROUTINE", sg, e.getKey(), sidProps);
        }

        // Structural: CONTAINS_ROUTINE (package/schema → routine)
        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            if (packageGeoids.contains(e.getKey())) continue; // skip package-as-routine
            String fromGeoid = null;
            if (r.getPackageGeoid() != null)           fromGeoid = r.getPackageGeoid();
            else if (r.getSchemaGeoid() != null)       fromGeoid = r.getSchemaGeoid();
            if (fromGeoid != null)
                b.appendEdge("CONTAINS_ROUTINE", fromGeoid, e.getKey(), sidProps);
        }

        // Structural: NESTED_IN (parent routine → child routine)
        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            if (r.getParentRoutineGeoid() != null) {
                b.appendEdge("NESTED_IN", r.getParentRoutineGeoid(), e.getKey(), sidProps);
            }
        }

        // Structural: HAS_COLUMN
        for (var e : str.getColumns().entrySet()) {
            b.appendEdge("HAS_COLUMN", e.getValue().getTableGeoid(), e.getKey(), sidProps);
        }

        // Structural: CONTAINS_STMT (routine → statement)
        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getRoutineGeoid() != null)
                b.appendEdge("CONTAINS_STMT", e.getValue().getRoutineGeoid(), e.getKey(), sidProps);
        }

        // Structural: CHILD_OF (statement → parent statement)
        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getParentStatementGeoid() != null)
                b.appendEdge("CHILD_OF", e.getKey(), e.getValue().getParentStatementGeoid(), sidProps);
        }

        // Structural: HAS_OUTPUT_COL
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                String ocExtId = e.getKey() + ":OC:" + oc.getKey();
                b.appendEdge("HAS_OUTPUT_COL", e.getKey(), ocExtId,
                        mapOf("session_id", sid, "statement_geoid", e.getKey()));
            }
        }

        // Structural: HAS_AFFECTED_COL
        for (var e : str.getStatements().entrySet()) {
            int acIdx = 0;
            for (Map<String, Object> ac : e.getValue().getAffectedColumns()) {
                String acExtId = e.getKey() + ":AC:" + acIdx;
                b.appendEdge("HAS_AFFECTED_COL", e.getKey(), acExtId, sidProps);
                // AFFECTED_COL_REF_TABLE
                String tg = (String) ac.get("table_geoid");
                if (tg != null)
                    b.appendEdge("AFFECTED_COL_REF_TABLE", acExtId, tg, sidProps);
                acIdx++;
            }
        }

        // Structural: HAS_JOIN + JOIN_SOURCE_TABLE + JOIN_TARGET_TABLE
        for (var e : str.getStatements().entrySet()) {
            int joinIdx = 0;
            for (JoinInfo j : e.getValue().getJoins()) {
                String joinExtId = e.getKey() + ":JOIN:" + j.lineStart() + ":" + joinIdx;
                b.appendEdge("HAS_JOIN", e.getKey(), joinExtId, sidProps);
                if (j.sourceTableGeoid() != null)
                    b.appendEdge("JOIN_SOURCE_TABLE", joinExtId, j.sourceTableGeoid(), sidProps);
                if (j.targetTableGeoid() != null)
                    b.appendEdge("JOIN_TARGET_TABLE", joinExtId, j.targetTableGeoid(), sidProps);
                joinIdx++;
            }
        }

        // Structural: HAS_PARAMETER, HAS_VARIABLE
        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            int pIdx = 0;
            for (RoutineInfo.ParameterInfo ignored : r.getTypedParameters()) {
                b.appendEdge("HAS_PARAMETER", e.getKey(), e.getKey() + ":PARAM:" + pIdx, sidProps);
                pIdx++;
            }
            int vIdx = 0;
            for (RoutineInfo.VariableInfo ignored : r.getTypedVariables()) {
                b.appendEdge("HAS_VARIABLE", e.getKey(), e.getKey() + ":VAR:" + vIdx, sidProps);
                vIdx++;
            }
        }

        // Usage: BELONGS_TO_SESSION
        for (String tg : str.getTables().keySet())
            b.appendEdge("BELONGS_TO_SESSION", sid, tg, sidProps);
        for (String rg : str.getRoutines().keySet())
            b.appendEdge("BELONGS_TO_SESSION", sid, rg, sidProps);

        // Usage: READS_FROM, WRITES_TO
        for (var e : str.getStatements().entrySet()) {
            for (String tg : e.getValue().getSourceTables().keySet())
                b.appendEdge("READS_FROM", e.getKey(), tg, sidProps);
            for (String tg : e.getValue().getTargetTables().keySet())
                b.appendEdge("WRITES_TO", e.getKey(), tg, sidProps);
        }

        // Usage: USES_SUBQUERY
        for (var e : str.getStatements().entrySet()) {
            for (String sqGeoid : e.getValue().getSourceSubqueries().keySet())
                b.appendEdge("USES_SUBQUERY", e.getKey(), sqGeoid, sidProps);
        }

        // Atoms: HAS_ATOM, ATOM_REF_TABLE, ATOM_REF_COLUMN, ATOM_REF_STMT, ATOM_REF_OUTPUT_COL
        // ATOM_PRODUCES, DATA_FLOW, FILTER_FLOW
        Set<String> tableGeoids = str.getTables().keySet();
        // Build OC lookup: "stmtGeoid:COL_NAME_UPPER" → ocExtId for ATOM_REF_OUTPUT_COL
        Map<String, String> ocByStmtAndName = new HashMap<>();
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                ocByStmtAndName.put(e.getKey() + ":" + oc.getKey().toUpperCase(),
                        e.getKey() + ":OC:" + oc.getKey());
            }
        }
        // Build OC lookup by order: "stmtGeoid:order" → ocExtId for ATOM_PRODUCES / DATA_FLOW
        Map<String, String> ocByOrder = new HashMap<>();
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                Object order = oc.getValue().get("order");
                if (order != null) ocByOrder.put(e.getKey() + ":" + order, e.getKey() + ":OC:" + oc.getKey());
            }
        }
        // Build AffectedColumn lookup by ref: "stmtGeoid:column_ref" → acExtId
        Map<String, String> affColByRef = new HashMap<>();
        for (var e : str.getStatements().entrySet()) {
            int acIdx = 0;
            for (Map<String, Object> ac : e.getValue().getAffectedColumns()) {
                String colRef = (String) ac.get("column_ref");
                if (colRef != null)
                    affColByRef.put(e.getKey() + ":" + colRef, e.getKey() + ":AC:" + acIdx);
                acIdx++;
            }
        }

        for (var container : result.getAtoms().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cont = (Map<String, Object>) container.getValue();
            String stmtGeoid = (String) cont.get("source_geoid");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> atoms =
                    (Map<String, Map<String, Object>>) cont.get("atoms");
            if (atoms == null || stmtGeoid == null) continue;

            for (var at : atoms.entrySet()) {
                Map<String, Object> a = at.getValue();
                String atomId = atomIdMap.get(stmtGeoid + ":" + at.getKey());
                if (atomId == null) continue;

                // HAS_ATOM: statement → atom
                b.appendEdge("HAS_ATOM", stmtGeoid, atomId, sidProps);

                String atomTableGeoid = (String) a.get("table_geoid");
                if (atomTableGeoid != null) {
                    if (tableGeoids.contains(atomTableGeoid)) {
                        // Physical table source
                        b.appendEdge("ATOM_REF_TABLE", atomId, atomTableGeoid, sidProps);
                        String colName = (String) a.get("column_name");
                        if (colName != null) {
                            String colGeoid = atomTableGeoid + "." + colName.toUpperCase();
                            if (str.getColumns().containsKey(colGeoid))
                                b.appendEdge("ATOM_REF_COLUMN", atomId, colGeoid, sidProps);
                        }
                    } else {
                        // Subquery/CTE source: table_geoid is a statement geoid
                        if (str.getStatements().containsKey(atomTableGeoid)) {
                            b.appendEdge("ATOM_REF_STMT", atomId, atomTableGeoid, sidProps);
                            String colName = (String) a.get("column_name");
                            if (colName != null) {
                                String ocExtId = ocByStmtAndName.get(
                                        atomTableGeoid + ":" + colName.toUpperCase());
                                if (ocExtId != null)
                                    b.appendEdge("ATOM_REF_OUTPUT_COL", atomId, ocExtId, sidProps);
                            }
                        }
                    }
                }

                // ATOM_PRODUCES: atom → output column
                Object outSeq = a.get("output_column_sequence");
                if (outSeq != null) {
                    String ocExtId = ocByOrder.get(stmtGeoid + ":" + outSeq);
                    if (ocExtId != null)
                        b.appendEdge("ATOM_PRODUCES", atomId, ocExtId, sidProps);
                }

                // DATA_FLOW: column → output column (resolved atoms)
                if ("Обработано".equals(a.get("status")) && outSeq != null) {
                    String colName = (String) a.get("column_name");
                    if (atomTableGeoid != null && colName != null) {
                        String srcColGeoid = atomTableGeoid + "." + colName.toUpperCase();
                        String ocExtId = ocByOrder.get(stmtGeoid + ":" + outSeq);
                        if (str.getColumns().containsKey(srcColGeoid) && ocExtId != null) {
                            b.appendEdge("DATA_FLOW", srcColGeoid, ocExtId, mapOf(
                                    "session_id", sid,
                                    "statement_geoid", stmtGeoid,
                                    "atom_id", atomId,
                                    "flow_type", resolveFlowType(a, str.getStatements().get(stmtGeoid))
                            ));
                        }
                    }
                }

                // DATA_FLOW: column → affected column (UPDATE SET / MERGE)
                String dmlTargetRef = (String) a.get("dml_target_ref");
                if ("Обработано".equals(a.get("status")) && dmlTargetRef != null
                        && atomTableGeoid != null) {
                    String colName = (String) a.get("column_name");
                    if (colName != null) {
                        String srcColGeoid = atomTableGeoid + "." + colName.toUpperCase();
                        String acExtId = affColByRef.get(stmtGeoid + ":" + dmlTargetRef);
                        if (str.getColumns().containsKey(srcColGeoid) && acExtId != null) {
                            b.appendEdge("DATA_FLOW", srcColGeoid, acExtId, mapOf(
                                    "session_id", sid,
                                    "statement_geoid", stmtGeoid,
                                    "atom_id", atomId,
                                    "flow_type", resolveDmlFlowType(str.getStatements().get(stmtGeoid))
                            ));
                        }
                    }
                }

                // FILTER_FLOW: column → statement
                String parentCtx = (String) a.get("parent_context");
                if (("WHERE".equals(parentCtx) || "HAVING".equals(parentCtx) || "JOIN".equals(parentCtx))
                        && "Обработано".equals(a.get("status"))
                        && atomTableGeoid != null) {
                    String colName = (String) a.get("column_name");
                    if (colName != null) {
                        String colGeoid = atomTableGeoid + "." + colName.toUpperCase();
                        if (str.getColumns().containsKey(colGeoid))
                            b.appendEdge("FILTER_FLOW", colGeoid, stmtGeoid, mapOf(
                                    "filter_type", parentCtx,
                                    "session_id", sid,
                                    "statement_geoid", stmtGeoid,
                                    "via_atom", at.getKey()
                            ));
                    }
                }
            }
        }

        // CALLS: routine → routine
        for (var callerEntry : result.getCalledRoutines().entrySet()) {
            String callerGeoid = callerEntry.getKey();
            for (Map<String, String> call : callerEntry.getValue()) {
                String calleeName = call.get("name");
                if (calleeName == null) continue;
                // Find callee geoid by suffix match (same logic as saveRemote)
                String calleeGeoid = null;
                for (String rg : str.getRoutines().keySet()) {
                    String upper = rg.toUpperCase();
                    if (upper.endsWith(":" + calleeName.toUpperCase())
                            || upper.equals(calleeName.toUpperCase())) {
                        calleeGeoid = rg;
                        break;
                    }
                }
                if (calleeGeoid != null)
                    b.appendEdge("CALLS", callerGeoid, calleeGeoid, mapOf(
                            "session_id", sid,
                            "caller_geoid", callerGeoid,
                            "callee_name", calleeName,
                            "line_start", call.get("line")
                    ));
            }
        }

        // Lineage edges
        for (LineageEdge le : result.getLineage()) {
            b.appendEdge(le.relationType(), le.sourceGeoid(), le.targetGeoid(),
                    mapOf("session_id", sid, "statement_geoid", le.statementGeoid()));
        }

        return b;
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON serialization (no external dependencies)
    // ═══════════════════════════════════════════════════════════════

    private static String toJsonLine(String metaType, String className, String extId,
                                      String from, String to, Map<String, Object> props) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"@type\":\"").append(metaType)
          .append("\",\"@class\":\"").append(className).append('"');

        if (extId != null) sb.append(",\"@id\":\"").append(escapeJson(extId)).append('"');
        if (from != null)  sb.append(",\"@from\":\"").append(escapeJson(from)).append('"');
        if (to != null)    sb.append(",\"@to\":\"").append(escapeJson(to)).append('"');

        if (props != null) {
            for (var entry : props.entrySet()) {
                Object v = entry.getValue();
                if (v == null) continue; // skip null values
                sb.append(",\"").append(escapeJson(entry.getKey())).append("\":");
                appendJsonValue(sb, v);
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
        } else if (v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            // Double, Float — write as number
            sb.append(n);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else {
            // Fallback: toString() escaped as string
            sb.append('"').append(escapeJson(v.toString())).append('"');
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers (mirrored from ArcadeDBSemanticWriter)
    // ═══════════════════════════════════════════════════════════════

    static String md5(String s) {
        if (s == null) return "";
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static Object nvl(Object v) { return v != null ? v : ""; }

    private static String parentType(String parentGeoid, Structure str) {
        if (parentGeoid == null) return null;
        StatementInfo p = str.getStatements().get(parentGeoid);
        return p != null ? p.getType() : null;
    }

    private static boolean isDml(String type) {
        return type != null && switch (type) {
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> true;
            default -> false;
        };
    }

    private static boolean isDdl(String type) {
        if (type == null) return false;
        return type.startsWith("CREATE") || type.startsWith("ALTER") || type.startsWith("DROP");
    }

    private static int computeDepth(String parentGeoid, Map<String, StatementInfo> allStatements) {
        int depth = 0;
        String current = parentGeoid;
        while (current != null && depth < 50) {
            StatementInfo parent = allStatements.get(current);
            if (parent == null) break;
            depth++;
            current = parent.getParentStatementGeoid();
        }
        return depth;
    }

    private static double computeStatementQuality(StatementInfo s) {
        Map<String, Map<String, Object>> atoms = s.getAtoms();
        if (atoms.isEmpty()) return 0.0;
        int total = atoms.size();
        long resolved  = atoms.values().stream().filter(a -> "Обработано".equals(a.get("status"))).count();
        long constants = atoms.values().stream().filter(a -> Boolean.TRUE.equals(a.get("is_constant"))).count();
        long functions = atoms.values().stream().filter(a -> Boolean.TRUE.equals(a.get("is_function_call"))).count();
        return (resolved + constants + functions) / (double) total;
    }

    private static boolean hasCte(StatementInfo s, Map<String, StatementInfo> allStatements) {
        for (String childGeoid : s.getChildStatements()) {
            StatementInfo child = allStatements.get(childGeoid);
            if (child != null && "CTE".equals(child.getType())) return true;
        }
        return false;
    }

    private static int countInputColumns(StatementInfo s) {
        return (int) s.getAtoms().values().stream()
                .filter(a -> Boolean.TRUE.equals(a.get("is_column_reference")))
                .count();
    }

    private static String resolveFlowType(Map<String, Object> atom, StatementInfo stmt) {
        if (stmt != null && stmt.isHasAggregation()
                && "GROUP BY".equals(atom.get("parent_context")))
            return "AGGREGATE";
        return "DIRECT";
    }

    private static String resolveDmlFlowType(StatementInfo stmt) {
        if (stmt == null) return "DIRECT";
        return switch (stmt.getType()) {
            case "UPDATE" -> "UPDATE";
            case "MERGE"  -> "MERGE";
            case "INSERT" -> "INSERT";
            default       -> "DIRECT";
        };
    }

    /** Creates a mutable LinkedHashMap that allows null values (unlike Map.of). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
