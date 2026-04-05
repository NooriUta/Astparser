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
 *
 * Schema v13 additions (S1.SCH):
 *   DaliSchemaLog — document type for suspicious schema name registrations.
 *   Fields: session_id, schema_name, reason, backtrace (com.hound.* call stack).
 *   Always active (not only --diag mode). Triggers on: quotes, $, :, (), space, dot
 *   in schema name — helps trace where incorrect schema names originate.
 *
 * Schema v19 additions (subquery atom resolution):
 *   ATOM_REF_STMT — DaliAtom → DaliStatement (when atom resolves to subquery/CTE source).
 *   ATOM_REF_OUTPUT_COL — DaliAtom → DaliOutputColumn (specific output column of that subquery).
 *   DaliResolutionLog enriched: file_path, table_name, column_name, position added to all entries.
 *   Previously these fields were silently dropped when table_geoid pointed to a statement geoid
 *   rather than a physical DaliTable, causing atom resolution edges to be missing for subquery sources.
 *
 * Schema v18 additions (G1/G2 — affected_columns as graph nodes):
 *   DaliAffectedColumn — vertex type, one node per column reference in a statement.
 *   Connected to DaliStatement via HAS_AFFECTED_COL edge.
 *   Fields: session_id, statement_geoid, column_ref, column_name, table_geoid,
 *   dataset_alias, source_type, resolution_status;
 *   DML target columns additionally carry type_affect (INSERT|UPDATE|DELETE)
 *   and order_affect (1-based ordinal within that DML type in the statement).
 *
 * Schema v17 additions (DaliDatabase field name fix):
 *   The ad-hoc (pool==null) write path was inserting DaliDatabase with wrong field names
 *   "database_geoid"/"database_name" instead of canonical "db_geoid"/"db_name" (v7).
 *   Migration: drops legacy DaliDatabase[database_name_ft] FULLTEXT index and creates
 *   DaliDatabase[db_name_ft] on the canonical db_name field.
 *   Also fixes ad-hoc inserts to use db_geoid/db_name with find-or-create deduplication.
 *
 * Schema v16 additions (HASH index overflow fix):
 *   session_id indexes switched from NOTUNIQUE_HASH → NOTUNIQUE (LSM_TREE).
 *   NOTUNIQUE_HASH uses fixed-size buckets per key; with large files (hundreds of rows
 *   per session) the bucket overflows. LSM_TREE has no per-key size limit.
 *   Also drops legacy DaliSession[db_name,dialect] UNIQUE index that blocked re-runs.
 *
 * Schema v15 additions (FULLTEXT analyzer fix):
 *   All FULLTEXT indexes rebuilt with KeywordAnalyzer instead of StandardAnalyzer.
 *   StandardAnalyzer splits on '_', breaking CONTAINSTEXT lookups for identifiers
 *   like "some_table_name". KeywordAnalyzer treats the entire value as one token.
 *   Migration: drops all TypeName[fieldName] FULLTEXT indexes and recreates them
 *   with METADATA {"analyzer": "org.apache.lucene.analysis.core.KeywordAnalyzer"}.
 *
 * Schema v14 additions (FULLTEXT gaps):
 *   DaliApplication(app_name), DaliOutputColumn(name),
 *   DaliSession(file_path), DaliStatement(stmt_geoid)
 *
 * Schema v13 additions (HASH indexes):
 *   UNIQUE → UNIQUE_HASH, NOTUNIQUE → NOTUNIQUE_HASH for all point-lookup indexes.
 *   SQL syntax confirmed: UNIQUE_HASH / NOTUNIQUE_HASH (not "HASH" standalone).
 *   Compound UNIQUE also supports UNIQUE_HASH. NULL_STRATEGY SKIP works with all HASH variants.
 *   Added session_id NOTUNIQUE_HASH for all 10 vertex types that carry session_id.
 *   Added DaliStatement(short_name) NOTUNIQUE_HASH (was created manually, now managed).
 *
 * Schema v12 additions (NULL_STRATEGY SKIP):
 *   All UNIQUE and NOTUNIQUE (LSM_TREE) indexes rebuilt with NULL_STRATEGY SKIP.
 *
 * Schema v11 additions (S1.FT):
 *   FULLTEXT indexes: table_name_ft, column_name_ft, routine_name_ft, package_name_ft,
 *                     schema_name_ft, database_name_ft, snippet_ft, atom_text_ft,
 *                     param_name_ft, var_name_ft
 *
 * Schema v10 additions (S1.IDX):
 *   NOTUNIQUE indexes on session_id for DaliStatement, DaliRoutine, DaliAtom, DaliJoin
 *
 * Schema v7 additions (ADR-014 — Namespace / Folder-as-Database):
 *   DaliTable.canonical_geoid  — DB_NAME::SCHEMA.TABLE (globally unique)
 *   DaliTable.db_name          — database name (folder name)
 *   DaliColumn.canonical_geoid — DB_NAME::SCHEMA.TABLE.COL
 *   DaliColumn.db_name         — database name
 *   DaliSession.db_name        — database name for session grouping
 *   DaliDatabase.db_geoid      — canonical database identifier
 *   DaliDatabase.db_name       — human-readable database name
 *   DaliPerfStats.db_name      — database name for per-DB analytics
 *   Index: DaliTable(canonical_geoid)  — for fast canonical lookup
 *   Index: DaliColumn(canonical_geoid) — for fast canonical lookup
 *   Index: DaliDatabase(db_name)       — for fast namespace queries
 *
 * Schema v8 additions (LOOM-024-BE — Schema hierarchy for SHUTTLE):
 *   DaliApplication             — top-level application vertex (above DaliDatabase)
 *   BELONGS_TO_APP              — edge DaliDatabase → DaliApplication
 *   CONTAINS_SCHEMA             — edge DaliDatabase → DaliSchema (namespace graph link)
 *   DaliSchema.db_name          — database name (for direct SHUTTLE queries without joins)
 *   DaliSchema.db_geoid         — canonical database identifier on DaliSchema
 *   Index: DaliSchema(db_name)  — for fast per-DB schema queries
 *
 * Schema v9 additions (LOOM-024-BE — compound UNIQUE indexes, canonical_geoid removal):
 *   DROP: DaliTable[canonical_geoid], DaliColumn[canonical_geoid],
 *         DaliSchema[canonical_geoid], DaliSchema[db_name]  (all NOTUNIQUE, redundant)
 *   DROP property: DaliTable.canonical_geoid, DaliColumn.canonical_geoid,
 *                  DaliSchema.canonical_geoid  (derived field, not stored)
 *   CREATE UNIQUE: DaliApplication[app_geoid]           — (app_geoid)
 *   CREATE UNIQUE: DaliDatabase[db_geoid]               — (db_geoid)
 *   CREATE UNIQUE: DaliSchema[db_name+schema_geoid]     — (db_name, schema_geoid)
 *   CREATE UNIQUE: DaliTable[db_name+table_geoid]       — (db_name, table_geoid)
 *   CREATE UNIQUE: DaliColumn[db_name+column_geoid]     — (db_name, column_geoid)
 */
public final class SchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final int SCHEMA_VERSION = 19;

    /**
     * Lucene analyzer applied to all FULL_TEXT indexes.
     * KeywordAnalyzer treats the entire field value as one token, so SQL identifiers
     * like "some_table_name" are not split on underscores (unlike the default
     * StandardAnalyzer). Query with CONTAINSTEXT('some_table_name') works correctly.
     */
    private static final String FT_ANALYZER =
            "org.apache.lucene.analysis.core.KeywordAnalyzer";
    private static final String FT_METADATA =
            " METADATA {\"analyzer\": \"" + FT_ANALYZER + "\"}";

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

        // 14 Vertex types (v8: +DaliApplication)
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
        // v8: application-level grouping (above DaliDatabase)
        c += vtx(schema, "DaliApplication");
        // v18: one vertex per column reference in a statement (G1/G2)
        c += vtx(schema, "DaliAffectedColumn");

        // Structural edges (v8: +CONTAINS_SCHEMA, +BELONGS_TO_APP; v18: +HAS_AFFECTED_COL)
        for (String e : new String[]{
                "BELONGS_TO_SESSION", "CONTAINS_TABLE", "CONTAINS_ROUTINE",
                "HAS_COLUMN", "HAS_PARAMETER", "HAS_VARIABLE",
                "CHILD_OF", "CONTAINS_STMT", "HAS_OUTPUT_COL", "HAS_ATOM", "HAS_JOIN",
                "READS_FROM", "WRITES_TO", "USES_SUBQUERY", "ROUTINE_USES_TABLE", "CALLS",
                "ATOM_REF_TABLE", "ATOM_REF_COLUMN", "ATOM_REF_STMT", "ATOM_REF_OUTPUT_COL", "ATOM_PRODUCES",
                "DATA_FLOW", "FILTER_FLOW", "JOIN_FLOW", "UNION_FLOW",
                "NESTED_IN",
                "CONTAINS_SCHEMA", "BELONGS_TO_APP",
                "HAS_AFFECTED_COL", "AFFECTED_COL_REF_TABLE",
                "JOIN_SOURCE_TABLE", "JOIN_TARGET_TABLE"
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

        // DaliResolutionLog — диагностический лог попыток resolution (STAB-1)
        // Используется только в режиме --diag. Не vertex — не участвует в граф-навигации.
        if (!schema.existsType("DaliResolutionLog")) schema.createDocumentType("DaliResolutionLog");

        // DaliSchemaLog — лог подозрительных имён схем с backtrace (S1.SCH)
        // Фиксируется всегда (не только в --diag): кавычки, $, :, скобки, пробелы в именах.
        if (!schema.existsType("DaliSchemaLog")) schema.createDocumentType("DaliSchemaLog");

        // ── Schema v7 additions: namespace / canonical properties (ADR-014) ──

        // DaliTable: db_name (canonical_geoid removed in v9 — derived field)
        if (schema.existsType("DaliTable")) {
            var tblType = schema.getType("DaliTable");
            if (!tblType.existsProperty("db_name"))
                tblType.createProperty("db_name", com.arcadedb.schema.Type.STRING);
        }

        // DaliColumn: db_name (canonical_geoid removed in v9 — derived field)
        if (schema.existsType("DaliColumn")) {
            var colType = schema.getType("DaliColumn");
            if (!colType.existsProperty("db_name"))
                colType.createProperty("db_name", com.arcadedb.schema.Type.STRING);
        }

        // DaliSession: db_name
        if (schema.existsType("DaliSession") && !schema.getType("DaliSession").existsProperty("db_name"))
            schema.getType("DaliSession").createProperty("db_name", com.arcadedb.schema.Type.STRING);

        // DaliDatabase: db_geoid + db_name + created_at (canonical namespace vertex)
        if (schema.existsType("DaliDatabase")) {
            var dbType = schema.getType("DaliDatabase");
            if (!dbType.existsProperty("db_geoid"))
                dbType.createProperty("db_geoid", com.arcadedb.schema.Type.STRING);
            if (!dbType.existsProperty("db_name"))
                dbType.createProperty("db_name", com.arcadedb.schema.Type.STRING);
            if (!dbType.existsProperty("created_at"))
                dbType.createProperty("created_at", com.arcadedb.schema.Type.LONG);
        }

        // DaliPerfStats: db_name
        if (schema.existsType("DaliPerfStats") && !schema.getType("DaliPerfStats").existsProperty("db_name"))
            schema.getType("DaliPerfStats").createProperty("db_name", com.arcadedb.schema.Type.STRING);

        // ── Schema v8 additions: schema hierarchy for SHUTTLE (LOOM-024-BE) ──

        // DaliApplication: application-level vertex
        if (schema.existsType("DaliApplication")) {
            var appType = schema.getType("DaliApplication");
            if (!appType.existsProperty("app_name"))
                appType.createProperty("app_name", com.arcadedb.schema.Type.STRING);
            if (!appType.existsProperty("app_geoid"))
                appType.createProperty("app_geoid", com.arcadedb.schema.Type.STRING);
        }

        // DaliSchema: db_name + db_geoid (canonical_geoid removed in v9 — derived field)
        if (schema.existsType("DaliSchema")) {
            var schType = schema.getType("DaliSchema");
            if (!schType.existsProperty("db_name"))
                schType.createProperty("db_name", com.arcadedb.schema.Type.STRING);
            if (!schType.existsProperty("db_geoid"))
                schType.createProperty("db_geoid", com.arcadedb.schema.Type.STRING);
        }

        // ── Schema v9: drop old NOTUNIQUE single-field indexes, add compound UNIQUE ──

        // Drop legacy single-field indexes (redundant after compound UNIQUE)
        tryDropIndex(db, "DaliTable[canonical_geoid]");
        tryDropIndex(db, "DaliColumn[canonical_geoid]");
        tryDropIndex(db, "DaliSchema[canonical_geoid]");
        tryDropIndex(db, "DaliSchema[db_name]");
        tryDropIndex(db, "DaliDatabase[db_name]");

        // Compound UNIQUE indexes for canonical vertex deduplication
        tryCreateUniqueIndex(db, schema, "DaliApplication", "DaliApplication[app_geoid]",         "app_geoid");
        tryCreateUniqueIndex(db, schema, "DaliDatabase",    "DaliDatabase[db_geoid]",              "db_geoid");
        tryCreateCompoundUniqueIndex(db, schema, "DaliSchema",  "DaliSchema[db_name+schema_geoid]",  "db_name", "schema_geoid");
        tryCreateCompoundUniqueIndex(db, schema, "DaliTable",   "DaliTable[db_name+table_geoid]",    "db_name", "table_geoid");
        tryCreateCompoundUniqueIndex(db, schema, "DaliColumn",  "DaliColumn[db_name+column_geoid]",  "db_name", "column_geoid");

        // ── Schema v10: declare STRING properties for full-text search + reliable indexing ──
        // Name/search fields — required for CREATE INDEX ... FULL_TEXT
        declareStringProp(schema, "DaliTable",       "table_name");
        declareStringProp(schema, "DaliTable",       "table_geoid");
        declareStringProp(schema, "DaliTable",       "table_type");
        declareStringProp(schema, "DaliTable",       "session_id");
        declareStringProp(schema, "DaliColumn",      "column_name");
        declareStringProp(schema, "DaliColumn",      "column_geoid");
        declareStringProp(schema, "DaliColumn",      "expression");
        declareStringProp(schema, "DaliColumn",      "alias");
        declareStringProp(schema, "DaliColumn",      "session_id");
        declareStringProp(schema, "DaliRoutine",     "routine_name");
        declareStringProp(schema, "DaliRoutine",     "routine_geoid");
        declareStringProp(schema, "DaliRoutine",     "routine_type");
        declareStringProp(schema, "DaliRoutine",     "session_id");
        declareStringProp(schema, "DaliSchema",      "schema_name");
        declareStringProp(schema, "DaliSchema",      "schema_geoid");
        declareStringProp(schema, "DaliDatabase",    "database_name");
        declareStringProp(schema, "DaliDatabase",    "database_geoid");
        declareStringProp(schema, "DaliStatement",   "snippet");
        declareStringProp(schema, "DaliStatement",   "stmt_geoid");
        declareStringProp(schema, "DaliStatement",   "statement_geoid");
        declareStringProp(schema, "DaliStatement",   "type");
        declareStringProp(schema, "DaliStatement",   "short_name");
        declareStringProp(schema, "DaliStatement",   "session_id");
        declareStringProp(schema, "DaliAtom",        "atom_text");
        declareStringProp(schema, "DaliAtom",        "atom_id");
        declareStringProp(schema, "DaliAtom",        "atom_context");
        declareStringProp(schema, "DaliAtom",        "parent_context");
        declareStringProp(schema, "DaliAtom",        "status");
        declareStringProp(schema, "DaliAtom",        "session_id");
        declareStringProp(schema, "DaliJoin",        "join_type");
        declareStringProp(schema, "DaliJoin",        "session_id");
        declareStringProp(schema, "DaliParameter",   "param_name");
        declareStringProp(schema, "DaliParameter",   "param_type");
        declareStringProp(schema, "DaliParameter",   "param_mode");
        declareStringProp(schema, "DaliParameter",   "session_id");
        declareStringProp(schema, "DaliVariable",    "var_name");
        declareStringProp(schema, "DaliVariable",    "var_type");
        declareStringProp(schema, "DaliVariable",    "session_id");
        declareStringProp(schema, "DaliOutputColumn","name");
        declareStringProp(schema, "DaliOutputColumn","expression");
        declareStringProp(schema, "DaliOutputColumn","alias");
        declareStringProp(schema, "DaliOutputColumn","session_id");
        declareStringProp(schema, "DaliApplication", "app_name");
        declareStringProp(schema, "DaliPackage",     "package_name");
        declareStringProp(schema, "DaliSession",     "session_id");
        declareStringProp(schema, "DaliSession",     "file_path");
        declareStringProp(schema, "DaliSession",     "dialect");

        // ── Schema v10: FULLTEXT indexes for object name search ──
        tryCreateFullTextIndex(db, schema, "DaliTable",     "DaliTable[table_name_ft]",     "table_name");
        tryCreateFullTextIndex(db, schema, "DaliColumn",    "DaliColumn[column_name_ft]",   "column_name");
        tryCreateFullTextIndex(db, schema, "DaliRoutine",   "DaliRoutine[routine_name_ft]", "routine_name");
        tryCreateFullTextIndex(db, schema, "DaliSchema",    "DaliSchema[schema_name_ft]",   "schema_name");
        tryCreateFullTextIndex(db, schema, "DaliDatabase",  "DaliDatabase[database_name_ft]","database_name");
        tryCreateFullTextIndex(db, schema, "DaliStatement", "DaliStatement[snippet_ft]",    "snippet");
        tryCreateFullTextIndex(db, schema, "DaliAtom",      "DaliAtom[atom_text_ft]",       "atom_text");
        tryCreateFullTextIndex(db, schema, "DaliPackage",       "DaliPackage[package_name_ft]",      "package_name");
        tryCreateFullTextIndex(db, schema, "DaliParameter",     "DaliParameter[param_name_ft]",      "param_name");
        tryCreateFullTextIndex(db, schema, "DaliVariable",      "DaliVariable[var_name_ft]",         "var_name");
        tryCreateFullTextIndex(db, schema, "DaliApplication",   "DaliApplication[app_name_ft]",      "app_name");
        tryCreateFullTextIndex(db, schema, "DaliOutputColumn",  "DaliOutputColumn[name_ft]",         "name");
        tryCreateFullTextIndex(db, schema, "DaliSession",       "DaliSession[file_path_ft]",         "file_path");
        tryCreateFullTextIndex(db, schema, "DaliStatement",     "DaliStatement[stmt_geoid_ft]",      "stmt_geoid");

        // ── Schema v10→v13: NOTUNIQUE_HASH session_id indexes — all vertex types with session_id ──
        tryCreateNonUniqueIndex(db, schema, "DaliStatement",   "DaliStatement[session_id]",   "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliRoutine",     "DaliRoutine[session_id]",     "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliAtom",        "DaliAtom[session_id]",        "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliJoin",        "DaliJoin[session_id]",        "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliTable",       "DaliTable[session_id]",       "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliColumn",      "DaliColumn[session_id]",      "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliParameter",   "DaliParameter[session_id]",   "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliVariable",    "DaliVariable[session_id]",    "session_id");
        tryCreateNonUniqueIndex(db, schema, "DaliOutputColumn","DaliOutputColumn[session_id]","session_id");

        // ── Schema v13: NOTUNIQUE_HASH for point-lookup fields ──
        tryCreateNonUniqueIndex(db, schema, "DaliStatement",   "DaliStatement[short_name]",   "short_name");

        // ── Schema v16: fix HASH index overflow + drop legacy unique constraint ──
        // a) DaliSession[db_name,dialect] was a UNIQUE index created outside SchemaInitializer.
        //    It blocks re-running the parser against the same (db_name, dialect) pair.
        //    Drop it so multiple sessions per DB/dialect are allowed (history of runs).
        tryDropIndex(db, "DaliSession[db_name,dialect]");
        // b) session_id NOTUNIQUE_HASH indexes overflow with large files (hundreds of rows
        //    per session). Drop the HASH variants and replace with LSM_TREE (tryCreateSessionIndex).
        for (String t : new String[]{
                "DaliStatement", "DaliRoutine", "DaliAtom", "DaliJoin",
                "DaliTable", "DaliColumn", "DaliParameter", "DaliVariable", "DaliOutputColumn"
        }) {
            tryDropIndex(db, t + "[session_id]");
            tryCreateSessionIndex(db, schema, t);
        }

        // ── Schema v17: fix DaliDatabase FULLTEXT index field name ──
        // The v10-v16 FULLTEXT index was created on "database_name" (wrong alias).
        // The canonical field written by ensureCanonicalPool is "db_name" (v7).
        // Drop the old index and create the correct one.
        tryDropIndex(db, "DaliDatabase[database_name_ft]");
        tryDropIndex(db, "DaliDatabase[database_name]");
        tryCreateFullTextIndex(db, schema, "DaliDatabase", "DaliDatabase[db_name_ft]", "db_name");

        // ── Schema v18: G1/G2 — DaliAffectedColumn vertex + HAS_AFFECTED_COL edge ──
        // DaliAffectedColumn vertex type and HAS_AFFECTED_COL edge created above in vertex/edge lists.
        // No extra property declarations needed — ArcadeDB schemaless mode handles the fields.
        // session_id index for DaliAffectedColumn (for fast per-session queries)
        for (String t : new String[]{ "DaliAffectedColumn" }) {
            tryDropIndex(db, t + "[session_id]");
            tryCreateSessionIndex(db, schema, t);
        }

        // ── Schema v19: ATOM_REF_STMT + ATOM_REF_OUTPUT_COL ──
        // Edge types created above in the edge list. No extra properties or indexes needed —
        // these edges are traversed by graph navigation, not by field-level queries.

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
                "CREATE EDGE TYPE ATOM_REF_STMT IF NOT EXISTS",
                "CREATE EDGE TYPE ATOM_REF_OUTPUT_COL IF NOT EXISTS",
                "CREATE EDGE TYPE ATOM_PRODUCES IF NOT EXISTS",
                "CREATE EDGE TYPE DATA_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE FILTER_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE JOIN_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE UNION_FLOW IF NOT EXISTS",
                "CREATE EDGE TYPE NESTED_IN IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliSnippet IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliMeta IF NOT EXISTS",
                "CREATE DOCUMENT TYPE DaliPerfStats IF NOT EXISTS",
                // STAB-1: resolution diagnostics log (diag mode only)
                "CREATE DOCUMENT TYPE DaliResolutionLog IF NOT EXISTS",
                // S1.SCH: suspicious schema name log with backtrace (always active)
                "CREATE DOCUMENT TYPE DaliSchemaLog IF NOT EXISTS",
                // v7: namespace / canonical properties (ADR-014)
                "CREATE PROPERTY DaliTable.db_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliColumn.db_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSession.db_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliDatabase.db_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliDatabase.db_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliDatabase.created_at IF NOT EXISTS LONG",
                "CREATE PROPERTY DaliPerfStats.db_name IF NOT EXISTS STRING",
                // v8: schema hierarchy for SHUTTLE (LOOM-024-BE)
                "CREATE VERTEX TYPE DaliApplication IF NOT EXISTS",
                "CREATE EDGE TYPE CONTAINS_SCHEMA IF NOT EXISTS",
                "CREATE EDGE TYPE BELONGS_TO_APP IF NOT EXISTS",
                "CREATE PROPERTY DaliApplication.app_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliApplication.app_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSchema.db_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSchema.db_geoid IF NOT EXISTS STRING",
                // v9: drop old NOTUNIQUE single-field indexes
                "DROP INDEX IF EXISTS `DaliTable[canonical_geoid]`",
                "DROP INDEX IF EXISTS `DaliColumn[canonical_geoid]`",
                "DROP INDEX IF EXISTS `DaliSchema[canonical_geoid]`",
                "DROP INDEX IF EXISTS `DaliSchema[db_name]`",
                "DROP INDEX IF EXISTS `DaliDatabase[db_name]`",
                // v9: compound UNIQUE_HASH indexes for canonical vertex deduplication (v13: LSM→HASH)
                "CREATE INDEX IF NOT EXISTS ON DaliApplication (app_geoid) UNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliDatabase (db_geoid) UNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliSchema (db_name, schema_geoid) UNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliTable (db_name, table_geoid) UNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliColumn (db_name, column_geoid) UNIQUE_HASH NULL_STRATEGY SKIP",
                // v10: STRING property declarations for full-text search + reliable indexing
                "CREATE PROPERTY DaliTable.table_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliTable.table_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliTable.table_type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliTable.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliColumn.column_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliColumn.column_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliColumn.expression IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliColumn.alias IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliColumn.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliRoutine.routine_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliRoutine.routine_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliRoutine.routine_type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliRoutine.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSchema.schema_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSchema.schema_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliDatabase.database_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliDatabase.database_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliStatement.snippet IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliStatement.stmt_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliStatement.statement_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliStatement.type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliStatement.short_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliStatement.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAtom.atom_text IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAtom.atom_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAtom.atom_context IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAtom.parent_context IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAtom.status IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAtom.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliJoin.join_type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliJoin.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliParameter.param_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliParameter.param_type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliParameter.param_mode IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliParameter.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliVariable.var_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliVariable.var_type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliVariable.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliOutputColumn.name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliOutputColumn.expression IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliOutputColumn.alias IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliOutputColumn.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliApplication.app_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliPackage.package_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSession.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSession.file_path IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliSession.dialect IF NOT EXISTS STRING",
                // v10: FULLTEXT indexes for object name search
                "CREATE INDEX IF NOT EXISTS ON DaliTable (table_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliColumn (column_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliRoutine (routine_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliSchema (schema_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliDatabase (database_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliStatement (snippet) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliAtom (atom_text) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliPackage (package_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliParameter (param_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliVariable (var_name) FULL_TEXT",
                // v14: FULLTEXT gaps — app_name, output column name, session file_path, stmt_geoid
                "CREATE INDEX IF NOT EXISTS ON DaliApplication (app_name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliOutputColumn (name) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliSession (file_path) FULL_TEXT",
                "CREATE INDEX IF NOT EXISTS ON DaliStatement (stmt_geoid) FULL_TEXT",
                // v10→v13: NOTUNIQUE_HASH session_id — all vertex types carrying session_id
                "CREATE INDEX IF NOT EXISTS ON DaliStatement (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliRoutine (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliAtom (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliJoin (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliTable (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliColumn (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliParameter (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliVariable (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                "CREATE INDEX IF NOT EXISTS ON DaliOutputColumn (session_id) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                // v13: point-lookup HASH indexes
                "CREATE INDEX IF NOT EXISTS ON DaliStatement (short_name) NOTUNIQUE_HASH NULL_STRATEGY SKIP",
                // v18: DaliAffectedColumn vertex + HAS_AFFECTED_COL edge (G1/G2)
                "CREATE VERTEX TYPE DaliAffectedColumn IF NOT EXISTS",
                "CREATE EDGE TYPE HAS_AFFECTED_COL IF NOT EXISTS",
                "CREATE PROPERTY DaliAffectedColumn.session_id IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.statement_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.column_ref IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.column_name IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.table_geoid IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.dataset_alias IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.source_type IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.resolution_status IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.type_affect IF NOT EXISTS STRING",
                "CREATE PROPERTY DaliAffectedColumn.order_affect IF NOT EXISTS INTEGER",
                "CREATE INDEX IF NOT EXISTS ON DaliAffectedColumn (session_id) NOTUNIQUE NULL_STRATEGY SKIP",
                "CREATE EDGE TYPE AFFECTED_COL_REF_TABLE IF NOT EXISTS",
                "CREATE EDGE TYPE JOIN_SOURCE_TABLE IF NOT EXISTS",
                "CREATE EDGE TYPE JOIN_TARGET_TABLE IF NOT EXISTS",
        };
    }

    /** Drops an index by name if it exists (safe on schema upgrade). */
    private static void tryDropIndex(Database db, String indexName) {
        try {
            db.command("sql", "DROP INDEX IF EXISTS `" + indexName + "`");
            logger.debug("Index dropped (or absent): {}", indexName);
        } catch (Exception e) {
            logger.debug("Index drop skipped for {}: {}", indexName, e.getMessage());
        }
    }

    /** Creates a single-field UNIQUE_HASH index with NULL_STRATEGY SKIP. */
    private static void tryCreateUniqueIndex(Database db, Schema schema,
                                              String typeName, String indexName, String propName) {
        if (!schema.existsType(typeName)) return;
        try {
            db.command("sql",
                    "CREATE INDEX IF NOT EXISTS ON " + typeName + " (" + propName + ") UNIQUE_HASH NULL_STRATEGY SKIP");
            logger.debug("UNIQUE_HASH index ensured: {}", indexName);
        } catch (Exception e) {
            logger.debug("UNIQUE_HASH index {} skipped: {}", indexName, e.getMessage());
        }
    }

    /** Declares a STRING property on a type if it doesn't exist yet.
     *  Required before CREATE INDEX ... FULL_TEXT can be applied. */
    private static void declareStringProp(Schema schema, String typeName, String propName) {
        if (!schema.existsType(typeName)) return;
        var t = schema.getType(typeName);
        if (!t.existsProperty(propName))
            t.createProperty(propName, com.arcadedb.schema.Type.STRING);
    }

    /** Creates a FULL_TEXT index. Property must be declared as STRING first. */
    private static void tryCreateFullTextIndex(Database db, Schema schema,
                                               String typeName, String indexName, String propName) {
        if (!schema.existsType(typeName)) return;
        try {
            db.command("sql",
                    "CREATE INDEX IF NOT EXISTS ON " + typeName + " (" + propName + ") FULL_TEXT");
            logger.debug("FULL_TEXT index ensured: {}", indexName);
        } catch (Exception e) {
            logger.debug("FULL_TEXT index {} skipped: {}", indexName, e.getMessage());
        }
    }

    /** Creates a single-field NOTUNIQUE_HASH index with NULL_STRATEGY SKIP. */
    private static void tryCreateNonUniqueIndex(Database db, Schema schema,
                                                String typeName, String indexName, String propName) {
        if (!schema.existsType(typeName)) return;
        try {
            db.command("sql",
                    "CREATE INDEX IF NOT EXISTS ON " + typeName + " (" + propName + ") NOTUNIQUE_HASH NULL_STRATEGY SKIP");
            logger.debug("NOTUNIQUE_HASH index ensured: {}", indexName);
        } catch (Exception e) {
            logger.debug("NOTUNIQUE_HASH index {} skipped: {}", indexName, e.getMessage());
        }
    }

    /** Creates a compound (two-field) UNIQUE_HASH index with NULL_STRATEGY SKIP. */
    private static void tryCreateCompoundUniqueIndex(Database db, Schema schema,
                                                      String typeName, String indexName,
                                                      String field1, String field2) {
        if (!schema.existsType(typeName)) return;
        try {
            db.command("sql",
                    "CREATE INDEX IF NOT EXISTS ON " + typeName + " (" + field1 + ", " + field2 + ") UNIQUE_HASH NULL_STRATEGY SKIP");
            logger.debug("Compound UNIQUE_HASH index ensured: {}", indexName);
        } catch (Exception e) {
            logger.debug("Compound UNIQUE_HASH index {} skipped: {}", indexName, e.getMessage());
        }
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