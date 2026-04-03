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
 *   DaliSession, DaliDatabase, DaliSchema, DaliPackage, DaliTable, DaliColumn,
 *   DaliRoutine, DaliStatement, DaliAtom, DaliOutputColumn, DaliJoin,
 *   DaliParameter, DaliVariable
 *
 * 1 Document type (вспомогательный контент, не участвует в граф-навигации):
 *   DaliSnippet — SQL-текст statement, хранится отдельно от DaliStatement
 *   чтобы избежать проблем с экранированием при remote INSERT
 *
 * 24 Edge types (structural + usage + atom resolution + flow)
 */
public final class SchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final int SCHEMA_VERSION = 3;

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
        c += vtx(schema, "DaliPackage");
        c += vtx(schema, "DaliTable");
        c += vtx(schema, "DaliColumn");
        c += vtx(schema, "DaliRoutine");
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
                "DATA_FLOW", "FILTER_FLOW", "JOIN_FLOW", "UNION_FLOW"
        }) { c += edg(schema, e); }

        // DaliSnippet — Document type for SQL text (avoids quoting issues in remote INSERT)
        if (!schema.existsType("DaliSnippet")) schema.createDocumentType("DaliSnippet");

        // Meta version
        if (!schema.existsType("DaliMeta")) schema.createDocumentType("DaliMeta");
        db.command("sql", "DELETE FROM DaliMeta");
        db.newDocument("DaliMeta")
                .set("schema_version", SCHEMA_VERSION)
                .set("created_at", System.currentTimeMillis())
                .save();

        logger.info("ArcadeDB schema v{}: {} types created", SCHEMA_VERSION, c);
    }

    /** SQL commands for remote mode schema init */
    public static String[] remoteSchemaCommands() {
        return new String[]{
                "CREATE VERTEX TYPE DaliSession IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliDatabase IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliSchema IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliPackage IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliTable IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliColumn IF NOT EXISTS",
                "CREATE VERTEX TYPE DaliRoutine IF NOT EXISTS",
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
                "CREATE DOCUMENT TYPE DaliSnippet IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliMeta IF NOT EXISTS",
        };
    }

    private static int vtx(Schema s, String n) {
        if (!s.existsType(n)) { s.createVertexType(n); return 1; } return 0;
    }
    private static int edg(Schema s, String n) {
        if (!s.existsType(n)) { s.createEdgeType(n); return 1; } return 0;
    }
}