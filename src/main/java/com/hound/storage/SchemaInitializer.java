// File: src/main/java/com/hound/storage/SchemaInitializer.java
package com.hound.storage;

import com.arcadedb.database.Database;
import com.arcadedb.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Создаёт схему ArcadeDB — все Dali vertex/edge/document types.
 *
 * 13 Vertex types (все сущности — vertex, участвуют в граф-навигации):
 *   DaliSession, DaliDatabase, DaliSchema, DaliTable, DaliColumn,
 *   DaliRoutine (base), DaliPackage (EXTENDS DaliRoutine),
 *   DaliStatement, DaliAtom, DaliOutputColumn, DaliJoin,
 *   DaliParameter, DaliVariable
 *
 * 2 Document types (аналитика + вспомогательный контент):
 *   DaliSnippet  — SQL-текст statement, хранится отдельно от DaliStatement
 *   DaliPerfStats — per-session timing/count snapshot для построения графиков
 *
 * 23 Edge types (structural + usage + atom resolution + flow)
 *   Note: CONTAINS_PACKAGE removed in v6 — packages use CONTAINS_ROUTINE (IS-A)
 */
public final class SchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final int SCHEMA_VERSION = 6;

    private SchemaInitializer() {}

    public static void ensureSchema(Database db) {
        Schema schema = db.getSchema();

        // Quick check: if meta exists and version matches, skip
        if (schema.existsType("DaliMeta")) {
            try {
                var rs = db.query("sql", "SELECT schema_version FROM DaliMeta LIMIT 1");
                if (rs.hasNext()) {
                    int v = ((Number) rs.next().toMap().get("schema_version")).intValue();
                    if (v >= SCHEMA_VERSION) {
                        logger.debug("ArcadeDB schema v{} up to date", v);
                        return;
                    }
                    logger.info("ArcadeDB schema upgrade v{} → v{}", v, SCHEMA_VERSION);
                }
            } catch (Exception e) {
                logger.debug("DaliMeta read failed, reinitializing");
            }
        }

        int c = 0;

        // 13 Vertex types
        c += vtx(schema, "DaliSession");
        c += vtx(schema, "DaliDatabase");
        c += vtx(schema, "DaliSchema");
        c += vtx(schema, "DaliTable");
        c += vtx(schema, "DaliColumn");
        c += vtx(schema, "DaliRoutine");
        // DaliPackage EXTENDS DaliRoutine (ADR-012: IS-A relationship)
        c += vtxExtends(schema, "DaliPackage", "DaliRoutine");
        c += vtx(schema, "DaliStatement");
        c += vtx(schema, "DaliAtom");
        c += vtx(schema, "DaliOutputColumn");
        c += vtx(schema, "DaliJoin");
        c += vtx(schema, "DaliParameter");
        c += vtx(schema, "DaliVariable");

        // Structural edges
        for (String e : new String[]{
                "BELONGS_TO_SESSION", "CONTAINS_TABLE", "CONTAINS_ROUTINE",
                "HAS_COLUMN", "HAS_PARAMETER", "HAS_VARIABLE",
                "CHILD_OF", "CONTAINS_STMT", "HAS_OUTPUT_COL", "HAS_ATOM", "HAS_JOIN",
                "READS_FROM", "WRITES_TO", "USES_SUBQUERY", "ROUTINE_USES_TABLE", "CALLS",
                "ATOM_REF_TABLE", "ATOM_REF_COLUMN", "ATOM_PRODUCES",
                "DATA_FLOW", "FILTER_FLOW", "JOIN_FLOW", "UNION_FLOW",
                "NESTED_IN"
        }) { c += edg(schema, e); }

        // DaliPerfStats — per-session pipeline timing & structural counts (analytics/graphing)
        if (!schema.existsType("DaliPerfStats")) schema.createDocumentType("DaliPerfStats");

        // DaliJoin: conditions_b64 property (legacy, kept for backward compat with older DBs)
        if (schema.existsType("DaliJoin")
                && !schema.getType("DaliJoin").existsProperty("conditions_b64")) {
            schema.getType("DaliJoin").createProperty("conditions_b64", com.arcadedb.schema.Type.STRING);
        }

        // DaliSnippet — Document type for SQL text (avoids quoting issues in remote INSERT)
        if (!schema.existsType("DaliSnippet")) schema.createDocumentType("DaliSnippet");

        // Meta version
        if (!schema.existsType("DaliMeta")) schema.createDocumentType("DaliMeta");
        db.transaction(() -> {
            db.command("sql", "DELETE FROM DaliMeta");
            db.newDocument("DaliMeta")
                    .set("schema_version", SCHEMA_VERSION)
                    .set("created_at", System.currentTimeMillis())
                    .save();
        });

        logger.info("ArcadeDB schema v{}: {} types created", SCHEMA_VERSION, c);
    }

    /** SQL commands for remote mode schema init */
    public static String[] remoteSchemaCommands() {
        return new String[]{
                "CREATE VERTEX TYPE DaliSession IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliDatabase IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliSchema IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliTable IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliColumn IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliRoutine IF NOT EXISTS",
                // DaliPackage EXTENDS DaliRoutine — must come AFTER DaliRoutine
                "CREATE VERTEX TYPE DaliPackage IF NOT EXISTS EXTENDS DaliRoutine",
                "CREATE VERTEX TYPE DaliStatement IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliAtom IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliOutputColumn IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliJoin IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliParameter IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliVariable IF NOT EXISTS",
                "CREATE EDGE TYPE BELONGS_TO_SESSION IF NOT EXISTS",
                "CREATE EDGE TYPE CONTAINS_TABLE IF NOT EXISTS",
                "CREATE EDGE TYPE CONTAINS_ROUTINE IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_COLUMN IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_PARAMETER IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_VARIABLE IF NOT EXISTS",
                "CREATE EDGE TYPE CHILD_OF IF NOT EXISTS",
                "CREATE EDGE TYPE CONTAINS_STMT IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_OUTPUT_COL IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_ATOM IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_JOIN IF NOT EXISTS",
                "CREATE EDGE TYPE READS_FROM IF NOT EXISTS",
                "CREATE EDGE TYPE WRITES_TO IF NOT EXISTS",
                "CREATE EDGE TYPE USES_SUBQUERY IF NOT EXISTS",
                "CREATE EDGE TYPE ROUTINE_USES_TABLE IF NOT EXISTS",
                "CREATE EDGE TYPE CALLS IF NOT EXISTS",
                "CREATE EDGE TYPE ATOM_REF_TABLE IF NOT EXISTS",
                "CREATE EDGE TYPE ATOM_REF_COLUMN IF NOT EXISTS",
                "CREATE EDGE TYPE ATOM_PRODUCES IF NOT EXISTS",
                "CREATE EDGE TYPE DATA_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE FILTER_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE JOIN_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE UNION_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE NESTED_IN IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliSnippet IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliMeta IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliPerfStats IF NOT EXISTS",
        };
    }

    private static int vtx(Schema s, String n) {
        if (!s.existsType(n)) { s.createVertexType(n); return 1; } return 0;
    }
    /** Creates vertex type with inheritance (EXTENDS parent). Adds supertype if type already exists. */
    private static int vtxExtends(Schema s, String n, String parent) {
        if (!s.existsType(n)) {
            s.createVertexType(n).addSuperType(s.getType(parent));
            return 1;
        }
        // Existing type — ensure supertype is set (schema upgrade v5→v6)
        var type = s.getType(n);
        if (!s.getType(parent).isSuperTypeOf(n)) {
            try { type.addSuperType(s.getType(parent)); } catch (Exception ignored) {}
        }
        return 0;
    }
    private static int edg(Schema s, String n) {
        if (!s.existsType(n)) { s.createEdgeType(n); return 1; } return 0;
    }
}