// File: src/main/java/com/hound/storage/ArcadeDBSemanticWriter.java
package com.hound.storage;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.remote.RemoteDatabase;
import com.hound.metrics.PipelineTimer;
import com.hound.semantic.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Записывает SemanticResult в ArcadeDB.
 */
public class ArcadeDBSemanticWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ArcadeDBSemanticWriter.class);
    // DaliSnippet full-text cap: prevents pathological cases (e.g. 500KB anonymous blocks).
    // DaliStatement vertex no longer carries snippet inline — full text lives in DaliSnippet document.
    private static final int SNIPPET_MAX = 4000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Database embeddedDb;
    private RemoteDatabase remoteDb;
    private final Mode mode;

    public enum Mode { EMBEDDED, REMOTE }

    // ═══════════════════════════════════════════════════════════════
    // Constructors
    // ═══════════════════════════════════════════════════════════════

    public ArcadeDBSemanticWriter(String dbPath) {
        this.mode = Mode.EMBEDDED;
        DatabaseFactory factory = new DatabaseFactory(dbPath);
        this.embeddedDb = factory.exists() ? factory.open() : factory.create();
        SchemaInitializer.ensureSchema(embeddedDb);
        logger.info("ArcadeDB EMBEDDED: {}", dbPath);
    }

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

    /**
     * Ad-hoc mode (backward-compatible): no database namespace.
     * Tables/columns are per-session, no canonical deduplication.
     */
    public String saveResult(SemanticResult result, PipelineTimer timer) {
        return saveResult(result, timer, null, null);
    }

    /**
     * Namespace-aware save.
     *
     * @param pool    CanonicalPool for the database batch (null = ad-hoc mode)
     * @param dbName  Database name derived from folder name (null = ad-hoc mode)
     */
    public String saveResult(SemanticResult result, PipelineTimer timer,
                             CanonicalPool pool, String dbName) {
        String sid = result.getSessionId();
        long t0 = System.currentTimeMillis();

        if (mode == Mode.EMBEDDED) {
            saveEmbedded(sid, result, timer, pool, dbName);
        } else {
            saveRemote(sid, result, timer, pool, dbName);
        }

        long ms = System.currentTimeMillis() - t0;
        Structure s = result.getStructure();

        int oc = 0;
        if (s != null) {
            for (var e : s.getStatements().entrySet()) oc += e.getValue().getColumnsOutput().size();
        }

        int cntTables     = s != null ? s.getTables().size()     : 0;
        int cntColumns    = s != null ? s.getColumns().size()    : 0;
        int cntStatements = s != null ? s.getStatements().size() : 0;
        int cntRoutines   = s != null ? s.getRoutines().size()   : 0;
        int cntAtoms      = result.getAtoms().size();
        int cntJoins      = s != null ? s.getStatements().values().stream()
                .mapToInt(st -> st.getJoins().size()).sum() : 0;
        int cntLineage    = result.getLineage().size();

        logger.info("ArcadeDB ({}) [db={}]: T:{} C:{} S:{} R:{} A:{} J:{} OC:{} L:{} [{}]",
                mode, dbName != null ? dbName : "ad-hoc",
                cntTables, cntColumns, cntStatements, cntRoutines,
                cntAtoms, cntJoins, oc, cntLineage, formatTime(ms));

        writePerfStats(sid, result, timer, dbName,
                cntTables, cntColumns, cntStatements, cntRoutines,
                cntAtoms, cntJoins, oc, cntLineage);
        return sid;
    }

    /**
     * Creates the canonical DaliDatabase vertex for the given namespace and returns
     * an empty CanonicalPool backed by it.
     *
     * <p>Called once per database batch before Phase 2 write loop.
     * The CanonicalPool is then passed to each {@link #saveResult} call in the batch.
     *
     * @param dbName   Database name (folder name), must not be null
     * @return  A fresh {@link CanonicalPool} for this database
     */
    public CanonicalPool ensureCanonicalPool(String dbName) {
        return ensureCanonicalPool(dbName, null, null);
    }

    /**
     * Creates canonical DaliDatabase (and optionally DaliApplication) vertices and
     * wires the BELONGS_TO_APP edge between them.
     *
     * @param dbName   Database name (folder name), must not be null
     * @param appName  Application name for DaliApplication vertex; null = no application layer
     * @param appGeoid Canonical application geoid; null = use appName as geoid
     * @return  A fresh {@link CanonicalPool} for this database
     */
    public CanonicalPool ensureCanonicalPool(String dbName, String appName, String appGeoid) {
        CanonicalPool pool = new CanonicalPool(dbName);
        String resolvedAppGeoid = (appGeoid != null) ? appGeoid : appName;
        try {
            if (mode == Mode.EMBEDDED) {
                embeddedDb.transaction(() -> {
                    // ── DaliApplication (optional) — find-or-create ──
                    MutableVertex appVtx = null;
                    if (appName != null) {
                        var appRs = embeddedDb.query("sql",
                                "SELECT FROM DaliApplication WHERE app_geoid = :g LIMIT 1",
                                Map.of("g", resolvedAppGeoid));
                        if (appRs.hasNext()) {
                            appVtx = (MutableVertex) appRs.next().toElement();
                        } else {
                            appVtx = embeddedDb.newVertex("DaliApplication")
                                    .set("app_name",   appName)
                                    .set("app_geoid",  resolvedAppGeoid)
                                    .set("created_at", System.currentTimeMillis())
                                    .save();
                        }
                        pool.setApplicationVtx(appVtx);
                    }
                    // ── DaliDatabase — find-or-create ──
                    var dbRs = embeddedDb.query("sql",
                            "SELECT FROM DaliDatabase WHERE db_geoid = :g LIMIT 1",
                            Map.of("g", dbName));
                    MutableVertex dbVtx;
                    if (dbRs.hasNext()) {
                        dbVtx = (MutableVertex) dbRs.next().toElement();
                    } else {
                        dbVtx = embeddedDb.newVertex("DaliDatabase")
                                .set("db_name",    dbName)
                                .set("db_geoid",   dbName)
                                .set("created_at", System.currentTimeMillis())
                                .save();
                        // Wire BELONGS_TO_APP only when DaliDatabase is newly created
                        if (appVtx != null) {
                            dbVtx.newEdge("BELONGS_TO_APP", appVtx, true);
                        }
                    }
                    pool.setDatabaseVtx(dbVtx);
                });
            } else {
                // ── DaliApplication (optional, remote) — find-or-create ──
                if (appName != null) {
                    try {
                        var rs = remoteDb.query("sql",
                                "SELECT @rid AS rid FROM DaliApplication WHERE app_geoid = :g LIMIT 1",
                                Map.of("g", resolvedAppGeoid));
                        if (rs.hasNext()) {
                            String rid = (String) rs.next().toMap().get("rid");
                            if (rid != null) pool.setApplicationRid(rid);
                        }
                    } catch (Exception ex) {
                        logger.debug("DaliApplication lookup failed for '{}': {}", appName, ex.getMessage());
                    }
                    if (pool.getApplicationRid() == null) {
                        rcmd("INSERT INTO DaliApplication SET app_name=?, app_geoid=?, created_at=?",
                                appName, resolvedAppGeoid, System.currentTimeMillis());
                        try {
                            var rs = remoteDb.query("sql",
                                    "SELECT @rid AS rid FROM DaliApplication WHERE app_geoid = :g LIMIT 1",
                                    Map.of("g", resolvedAppGeoid));
                            if (rs.hasNext()) {
                                String rid = (String) rs.next().toMap().get("rid");
                                if (rid != null) pool.setApplicationRid(rid);
                            }
                        } catch (Exception ex) {
                            logger.debug("DaliApplication RID fetch failed for '{}': {}", appName, ex.getMessage());
                        }
                    }
                }
                // ── DaliDatabase (remote) — find-or-create ──
                boolean dbIsNew = false;
                try {
                    var rs = remoteDb.query("sql",
                            "SELECT @rid AS rid FROM DaliDatabase WHERE db_geoid = :g LIMIT 1",
                            Map.of("g", dbName));
                    if (rs.hasNext()) {
                        String rid = (String) rs.next().toMap().get("rid");
                        if (rid != null) pool.setDatabaseRid(rid);
                    }
                } catch (Exception ex) {
                    logger.debug("DaliDatabase lookup failed for '{}': {}", dbName, ex.getMessage());
                }
                if (pool.getDatabaseRid() == null) {
                    rcmd("INSERT INTO DaliDatabase SET db_name=?, db_geoid=?, created_at=?",
                            dbName, dbName, System.currentTimeMillis());
                    dbIsNew = true;
                    try {
                        var rs = remoteDb.query("sql",
                                "SELECT @rid AS rid FROM DaliDatabase WHERE db_geoid = :g LIMIT 1",
                                Map.of("g", dbName));
                        if (rs.hasNext()) {
                            String rid = (String) rs.next().toMap().get("rid");
                            if (rid != null) pool.setDatabaseRid(rid);
                        }
                    } catch (Exception ex) {
                        logger.debug("DaliDatabase RID fetch failed for '{}': {}", dbName, ex.getMessage());
                    }
                }
                // ── BELONGS_TO_APP: DaliDatabase → DaliApplication (only for new DB) ──
                if (dbIsNew && pool.getDatabaseRid() != null && pool.getApplicationRid() != null) {
                    edgeByRid("BELONGS_TO_APP", pool.getDatabaseRid(), pool.getApplicationRid(), dbName);
                }
            }
        } catch (Exception e) {
            logger.warn("ensureCanonicalPool failed for '{}': {}", dbName, e.getMessage());
        }
        logger.info("CanonicalPool created: db='{}' app='{}'", dbName, appName != null ? appName : "—");
        return pool;
    }

    // ═══════════════════════════════════════════════════════════════
    // DaliPerfStats — per-session pipeline timing & structural counts
    // ═══════════════════════════════════════════════════════════════

    private void writePerfStats(String sid, SemanticResult result, PipelineTimer timer,
                                String dbName,
                                int cntTables, int cntColumns, int cntStatements, int cntRoutines,
                                int cntAtoms, int cntJoins, int cntOutputCols, int cntLineage) {
        // Atom resolution breakdown — iterate raw atoms map
        int atomResolvedTmp = 0, atomConstTmp = 0, atomFuncTmp = 0, atomFailedTmp = 0;
        for (var container : result.getAtoms().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cont = (Map<String, Object>) container.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> atoms =
                    (Map<String, Map<String, Object>>) cont.get("atoms");
            if (atoms == null) continue;
            for (var at : atoms.entrySet()) {
                String status = (String) at.getValue().get("status");
                if ("Обработано".equals(status))      atomResolvedTmp++;
                else if ("constant".equals(status))   atomConstTmp++;
                else if ("function_call".equals(status)) atomFuncTmp++;
                else                                  atomFailedTmp++;
            }
        }
        // Capture as effectively-final for lambda capture
        final int atomResolved = atomResolvedTmp;
        final int atomConst    = atomConstTmp;
        final int atomFunc     = atomFuncTmp;
        final int atomFailed   = atomFailedTmp;

        try {
            if (mode == Mode.EMBEDDED) {
                embeddedDb.transaction(() ->
                    embeddedDb.newDocument("DaliPerfStats")
                        .set("session_id",       sid)
                        .set("db_name",          dbName)
                        .set("file_path",        result.getFilePath())
                        .set("dialect",          result.getDialect())
                        .set("created_at",       System.currentTimeMillis())
                        // ── timing (ms) ──
                        .set("ms_parse",         timer.ms("parse"))
                        .set("ms_walk",          timer.ms("walk"))
                        .set("ms_resolve",       timer.ms("resolve"))
                        .set("ms_write_vtx",     timer.ms("write.vtx"))
                        .set("ms_write_edge",    timer.ms("write.edge"))
                        .set("ms_total",         timer.totalMs())
                        // ── token/line counts ──
                        .set("count_tokens",     timer.count("tokens"))
                        // ── structural counts ──
                        .set("cnt_tables",       cntTables)
                        .set("cnt_columns",      cntColumns)
                        .set("cnt_statements",   cntStatements)
                        .set("cnt_routines",     cntRoutines)
                        .set("cnt_atoms",        cntAtoms)
                        .set("cnt_atoms_resolved", atomResolved)
                        .set("cnt_atoms_const",  atomConst)
                        .set("cnt_atoms_func",   atomFunc)
                        .set("cnt_atoms_failed", atomFailed)
                        .set("cnt_output_cols",  cntOutputCols)
                        .set("cnt_joins",        cntJoins)
                        .set("cnt_lineage",      cntLineage)
                        .save()
                );
            } else {
                rcmd("INSERT INTO DaliPerfStats SET " +
                     "session_id=?, db_name=?, file_path=?, dialect=?, created_at=?, " +
                     "ms_parse=?, ms_walk=?, ms_resolve=?, ms_write_vtx=?, ms_write_edge=?, ms_total=?, " +
                     "count_tokens=?, " +
                     "cnt_tables=?, cnt_columns=?, cnt_statements=?, cnt_routines=?, " +
                     "cnt_atoms=?, cnt_atoms_resolved=?, cnt_atoms_const=?, cnt_atoms_func=?, cnt_atoms_failed=?, " +
                     "cnt_output_cols=?, cnt_joins=?, cnt_lineage=?",
                        sid, dbName, result.getFilePath(), result.getDialect(), System.currentTimeMillis(),
                        timer.ms("parse"), timer.ms("walk"), timer.ms("resolve"),
                        timer.ms("write.vtx"), timer.ms("write.edge"), timer.totalMs(),
                        timer.count("tokens"),
                        cntTables, cntColumns, cntStatements, cntRoutines,
                        cntAtoms, atomResolved, atomConst, atomFunc, atomFailed,
                        cntOutputCols, cntJoins, cntLineage);
            }
        } catch (Exception e) {
            logger.warn("DaliPerfStats write failed for {}: {}", sid, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EMBEDDED mode
    // ═══════════════════════════════════════════════════════════════

    private void saveEmbedded(String sid, SemanticResult result, PipelineTimer timer,
                              CanonicalPool pool, String dbName) {
        timer.start("write.vtx");
        embeddedDb.transaction(() -> {
            Structure str = result.getStructure();
            if (str == null) return;

            MutableVertex sessionV = embeddedDb.newVertex("DaliSession")
                    .set("session_id", sid)
                    .set("db_name",    dbName)
                    .set("file_path", result.getFilePath())
                    .set("dialect", result.getDialect())
                    .set("processing_time_ms", result.getProcessingTimeMs())
                    .set("created_at", System.currentTimeMillis())
                    .save();

            // ── DaliDatabase: canonical (namespace) or per-session (ad-hoc) ──
            // In namespace mode the canonical DaliDatabase already exists in the pool;
            // per-session DB references from SQL text are omitted to avoid duplication.
            Map<String, MutableVertex> dbV = new LinkedHashMap<>();
            if (pool == null) {
                for (var e : str.getDatabases().entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = (Map<String, Object>) e.getValue();
                    // find-or-create: DaliDatabase[db_geoid] UNIQUE index deduplicates across runs
                    var existRs = embeddedDb.query("sql",
                            "SELECT FROM DaliDatabase WHERE db_geoid = :g LIMIT 1",
                            Map.of("g", e.getKey()));
                    MutableVertex v;
                    if (existRs.hasNext()) {
                        v = (MutableVertex) existRs.next().toElement();
                    } else {
                        v = embeddedDb.newVertex("DaliDatabase")
                                .set("db_geoid",   e.getKey())
                                .set("db_name",    d.get("name"))
                                .set("created_at", System.currentTimeMillis())
                                .save();
                    }
                    dbV.put(e.getKey(), v);
                }
            }

            // ── DaliSchema: canonical (namespace) or per-session (ad-hoc) ──
            Map<String, MutableVertex> schV = new LinkedHashMap<>();
            for (var e : str.getSchemas().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sc = (Map<String, Object>) e.getValue();
                MutableVertex v;
                if (pool != null) {
                    // Namespace mode: one vertex per unique schema per database
                    String cg = pool.canonicalSchema(e.getKey());
                    v = pool.getSchemaVtx(cg);
                    if (v == null) {
                        // Cross-run deduplication: query before create
                        var rs = embeddedDb.query("sql",
                                "SELECT FROM DaliSchema WHERE db_name = :d AND schema_geoid = :s LIMIT 1",
                                Map.of("d", dbName, "s", e.getKey()));
                        if (rs.hasNext()) {
                            v = (MutableVertex) rs.next().toElement();
                        } else {
                            v = embeddedDb.newVertex("DaliSchema")
                                    .set("db_name",        dbName)
                                    .set("db_geoid",       dbName)
                                    .set("schema_geoid",   e.getKey())
                                    .set("schema_name",    sc.get("name"))
                                    .save();
                            // CONTAINS_SCHEMA: DaliDatabase → DaliSchema (new schema only)
                            if (pool.getDatabaseVtx() != null) {
                                pool.getDatabaseVtx().newEdge("CONTAINS_SCHEMA", v, true);
                            }
                        }
                        pool.putSchemaVtx(cg, v);
                    }
                } else {
                    // Ad-hoc mode: per-session schema vertex.
                    // Resolve parent DB name from sc.get("db") so SHUTTLE OverviewService
                    // can group this schema even without a CONTAINS_SCHEMA edge.
                    String schDbGeoid = (String) sc.get("db");
                    @SuppressWarnings("unchecked")
                    String schDbName  = (schDbGeoid != null && str.getDatabases().containsKey(schDbGeoid))
                            ? (String) ((Map<String, Object>) str.getDatabases().get(schDbGeoid)).get("name")
                            : dbName;
                    v = embeddedDb.newVertex("DaliSchema")
                            .set("session_id",   sid)
                            .set("schema_geoid", e.getKey())
                            .set("schema_name",  sc.get("name"))
                            .set("db_name",      schDbName)
                            .set("db_geoid",     schDbGeoid != null ? schDbGeoid : schDbName)
                            .save();
                }
                schV.put(e.getKey(), v);
            }

            Map<String, MutableVertex> pkgV = new LinkedHashMap<>();
            for (var e : str.getPackages().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pkg = (Map<String, Object>) e.getValue();
                MutableVertex v = embeddedDb.newVertex("DaliPackage")
                        .set("session_id", sid)
                        .set("package_geoid", e.getKey())
                        .set("package_name", pkg.get("package_name"))
                        .set("schema_geoid", pkg.get("schema_geoid"))
                        .set("routine_geoid", e.getKey())
                        .set("routine_name", pkg.get("package_name"))
                        .set("routine_type", "PACKAGE")
                        .save();
                pkgV.put(e.getKey(), v);
                String sg = (String) pkg.get("schema_geoid");
                if (sg != null && !sg.isEmpty()) {
                    MutableVertex sv2 = schV.get(sg.toUpperCase());
                    // Fallback 1: schema created by a previous file in this batch (pool cache)
                    if (sv2 == null && pool != null) {
                        sv2 = pool.getSchemaVtx(pool.canonicalSchema(sg));
                    }
                    // Fallback 2: schema exists in DB from a prior run (cross-run)
                    if (sv2 == null) {
                        var rsSchema = embeddedDb.query("sql",
                                "SELECT FROM DaliSchema WHERE schema_geoid = :s LIMIT 1",
                                Map.of("s", sg));
                        if (rsSchema.hasNext()) sv2 = (MutableVertex) rsSchema.next().toElement();
                    }
                    if (sv2 != null) sv2.newEdge("CONTAINS_ROUTINE", v, true);
                }
            }

            // ── DaliTable: canonical (namespace mode) or per-session (ad-hoc) ──
            Map<String, MutableVertex> tblV = new LinkedHashMap<>();
            for (var e : str.getTables().entrySet()) {
                TableInfo t = e.getValue();
                MutableVertex v;
                if (pool != null) {
                    // Namespace mode: one vertex per unique table per database
                    String cg = pool.canonical(e.getKey());
                    v = pool.getTableVtx(cg);
                    if (v == null) {
                        // Cross-run deduplication: query before create
                        var rs = embeddedDb.query("sql",
                                "SELECT FROM DaliTable WHERE db_name = :d AND table_geoid = :t LIMIT 1",
                                Map.of("d", dbName, "t", e.getKey()));
                        if (rs.hasNext()) {
                            v = (MutableVertex) rs.next().toElement();
                        } else {
                            v = embeddedDb.newVertex("DaliTable")
                                    .set("db_name",      dbName)
                                    .set("table_geoid",  e.getKey())
                                    .set("table_name",   t.tableName())
                                    .set("schema_geoid", t.schemaGeoid())
                                    .set("table_type",   t.tableType())
                                    .set("aliases",      new ArrayList<>(t.aliases()))
                                    .set("column_count", t.columnCount())
                                    .save();
                            // CONTAINS_TABLE: DaliSchema → DaliTable (new table only)
                            if (t.schemaGeoid() != null) {
                                MutableVertex sv2 = schV.get(t.schemaGeoid().toUpperCase());
                                if (sv2 != null) sv2.newEdge("CONTAINS_TABLE", v, true);
                            }
                        }
                        pool.putTableVtx(cg, v);
                    }
                    // No BELONGS_TO_SESSION for canonical tables
                } else {
                    // Ad-hoc mode: per-session table vertex (old behavior)
                    v = embeddedDb.newVertex("DaliTable")
                            .set("session_id",  sid)
                            .set("table_geoid", e.getKey())
                            .set("table_name",  t.tableName())
                            .set("schema_geoid", t.schemaGeoid())
                            .set("table_type",  t.tableType())
                            .set("aliases",     new ArrayList<>(t.aliases()))
                            .set("column_count", t.columnCount())
                            .save();
                    sessionV.newEdge("BELONGS_TO_SESSION", v, true);
                    if (t.schemaGeoid() != null) {
                        MutableVertex sv2 = schV.get(t.schemaGeoid().toUpperCase());
                        if (sv2 != null) sv2.newEdge("CONTAINS_TABLE", v, true);
                    }
                }
                tblV.put(e.getKey(), v);
            }

            // ── DaliColumn: canonical (namespace mode) or per-session (ad-hoc) ──
            Map<String, MutableVertex> colV = new LinkedHashMap<>();
            for (var e : str.getColumns().entrySet()) {
                ColumnInfo c = e.getValue();
                MutableVertex v;
                if (pool != null) {
                    // Namespace mode: one vertex per unique column per database
                    String cg = pool.canonicalCol(c.getTableGeoid(), c.getColumnName());
                    v = pool.getColumnVtx(cg);
                    if (v == null) {
                        // Cross-run deduplication: query before create
                        var rs = embeddedDb.query("sql",
                                "SELECT FROM DaliColumn WHERE db_name = :d AND column_geoid = :c LIMIT 1",
                                Map.of("d", dbName, "c", e.getKey()));
                        if (rs.hasNext()) {
                            v = (MutableVertex) rs.next().toElement();
                        } else {
                            v = embeddedDb.newVertex("DaliColumn")
                                    .set("db_name",        dbName)
                                    .set("column_geoid",   e.getKey())
                                    .set("table_geoid",    c.getTableGeoid())
                                    .set("column_name",    c.getColumnName())
                                    .set("expression",     c.getExpression())
                                    .set("alias",          c.getAlias())
                                    .set("is_output",      c.isOutput())
                                    .set("col_order",      c.getOrder())
                                    .set("used_in_statements", new ArrayList<>(c.getUsedInStatements()))
                                    .save();
                            MutableVertex tv = tblV.get(c.getTableGeoid());
                            if (tv != null) tv.newEdge("HAS_COLUMN", v, true);
                        }
                        pool.putColumnVtx(cg, v);
                    }
                    // Existing canonical column: skip (already linked to its table)
                } else {
                    // Ad-hoc mode: per-session column (old behavior)
                    v = embeddedDb.newVertex("DaliColumn")
                            .set("session_id",   sid)
                            .set("column_geoid", e.getKey())
                            .set("table_geoid",  c.getTableGeoid())
                            .set("column_name",  c.getColumnName())
                            .set("expression",   c.getExpression())
                            .set("alias",        c.getAlias())
                            .set("is_output",    c.isOutput())
                            .set("col_order",    c.getOrder())
                            .set("used_in_statements", new ArrayList<>(c.getUsedInStatements()))
                            .save();
                    MutableVertex tv = tblV.get(c.getTableGeoid());
                    if (tv != null) tv.newEdge("HAS_COLUMN", v, true);
                }
                colV.put(e.getKey(), v);
            }

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
                if (r.getPackageGeoid() != null) {
                    MutableVertex pv = pkgV.get(r.getPackageGeoid().toUpperCase());
                    if (pv != null) pv.newEdge("CONTAINS_ROUTINE", v, true);
                } else if (r.getSchemaGeoid() != null) {
                    MutableVertex sv2 = schV.get(r.getSchemaGeoid().toUpperCase());
                    if (sv2 != null) sv2.newEdge("CONTAINS_ROUTINE", v, true);
                }
            }

            for (var e : str.getRoutines().entrySet()) {
                RoutineInfo r = e.getValue();
                if (r.getParentRoutineGeoid() != null) {
                    MutableVertex parent = rtV.get(r.getParentRoutineGeoid());
                    MutableVertex child = rtV.get(e.getKey());
                    if (parent != null && child != null) {
                        parent.newEdge("NESTED_IN", child, true)
                                .set("session_id", sid).save();
                    }
                }
            }

            for (var e : str.getRoutines().entrySet()) {
                RoutineInfo r = e.getValue();
                MutableVertex rv = rtV.get(e.getKey());
                if (rv == null) continue;
                for (RoutineInfo.ParameterInfo p : r.getTypedParameters()) {
                    MutableVertex pV = embeddedDb.newVertex("DaliParameter")
                            .set("session_id", sid)
                            .set("routine_geoid", e.getKey())
                            .set("param_name", p.name())
                            .set("param_type", p.type())
                            .set("param_mode", p.mode())
                            .save();
                    rv.newEdge("HAS_PARAMETER", pV, true);
                }
                for (RoutineInfo.VariableInfo v : r.getTypedVariables()) {
                    MutableVertex vV = embeddedDb.newVertex("DaliVariable")
                            .set("session_id", sid)
                            .set("routine_geoid", e.getKey())
                            .set("var_name", v.name())
                            .set("var_type", v.type())
                            .save();
                    rv.newEdge("HAS_VARIABLE", vV, true);
                }
            }

            Map<String, MutableVertex> stV = new LinkedHashMap<>();
            for (var e : str.getStatements().entrySet()) {
                StatementInfo s = e.getValue();
                MutableVertex v = embeddedDb.newVertex("DaliStatement")
                        .set("session_id",            sid)
                        .set("stmt_geoid",            e.getKey())
                        .set("type",                  s.getType())
                        .set("subtype",               s.getSubtype())
                        // snippet removed from vertex — full text lives in DaliSnippet document
                        .set("line_start",            s.getLineStart())
                        .set("line_end",              s.getLineEnd())
                        .set("parent_statement",      s.getParentStatementGeoid())
                        .set("parent_statement_type", parentType(s.getParentStatementGeoid(), str))
                        .set("routine_geoid",         s.getRoutineGeoid())
                        .set("short_name",            s.getShortName())
                        .set("aliases",               new ArrayList<>(s.getAliases()))
                        .set("child_statements",      new ArrayList<>(s.getChildStatements()))
                        .set("source_table_geoids",   new ArrayList<>(s.getSourceTables().keySet()))
                        .set("target_table_geoids",   new ArrayList<>(s.getTargetTables().keySet()))
                        .set("source_subquery_geoids",new ArrayList<>(s.getSourceSubqueries().keySet()))
                        .set("source_tables_json",    toJson(new ArrayList<>(s.getSourceTables().values())))
                        .set("target_tables_json",    toJson(new ArrayList<>(s.getTargetTables().values())))
                        .set("is_union",              s.isUnion())
                        .set("is_dml",                isDml(s.getType()))
                        .set("is_ddl",                isDdl(s.getType()))
                        .set("has_aggregation",       s.isHasAggregation())
                        .set("has_window",            s.isHasWindow())
                        .set("has_cte",               hasCte(s, str.getStatements()))
                        .set("join_count",            s.getJoins().size())
                        .set("col_count_output",      s.getColumnsOutput().size())
                        .set("col_count_input",       countInputColumns(s))
                        .set("depth",                 computeDepth(s.getParentStatementGeoid(), str.getStatements()))
                        .set("quality",               computeStatementQuality(s))
                        .save();
                stV.put(e.getKey(), v);
            }

            // H1.5/H1.6: output-column lookup map, keyed by "stmtGeoid:col_order"
            Map<String, MutableVertex> ocByKey = new LinkedHashMap<>();
            // v19: lookup by "stmtGeoid:COL_NAME_UPPER" for ATOM_REF_OUTPUT_COL (subquery sources)
            Map<String, MutableVertex> ocByStmtAndName = new LinkedHashMap<>();

            for (var e : str.getStatements().entrySet()) {
                StatementInfo s = e.getValue();
                MutableVertex sv = stV.get(e.getKey());
                if (sv == null) continue;

                if (s.getParentStatementGeoid() != null) {
                    MutableVertex pv = stV.get(s.getParentStatementGeoid());
                    if (pv != null) sv.newEdge("CHILD_OF", pv, true);
                }

                if (s.getRoutineGeoid() != null) {
                    MutableVertex rv = rtV.get(s.getRoutineGeoid());
                    if (rv != null) rv.newEdge("CONTAINS_STMT", sv, true);
                }

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

                for (var sq : s.getSourceSubqueries().entrySet()) {
                    MutableVertex sqV = stV.get(sq.getKey());
                    if (sqV != null) {
                        sv.newEdge("USES_SUBQUERY", sqV, true)
                                .set("aliases", new ArrayList<>(sq.getValue().subqueryAliases()))
                                .set("subquery_type", sq.getValue().subqueryType())
                                .save();
                    }
                }

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
                    // Register in ocByKey for ATOM_PRODUCES / DATA_FLOW lookups
                    Object order = col.get("order");
                    if (order != null) ocByKey.put(e.getKey() + ":" + order, ocV);
                    // Register in ocByStmtAndName for ATOM_REF_OUTPUT_COL (subquery sources)
                    String ocName = (String) col.get("name");
                    if (ocName != null) ocByStmtAndName.put(e.getKey() + ":" + ocName.toUpperCase(), ocV);
                }

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
                    if (j.sourceTableGeoid() != null) {
                        MutableVertex srcTbl = tblV.get(j.sourceTableGeoid());
                        if (srcTbl != null) jV.newEdge("JOIN_SOURCE_TABLE", srcTbl, true);
                    }
                    if (j.targetTableGeoid() != null) {
                        MutableVertex tgtTbl = tblV.get(j.targetTableGeoid());
                        if (tgtTbl != null) jV.newEdge("JOIN_TARGET_TABLE", tgtTbl, true);
                    }
                }

                // G1/G2: DaliAffectedColumn — one vertex per column reference
                for (Map<String, Object> ac : s.getAffectedColumns()) {
                    String typeAffect = null;
                    Integer orderAffect = null;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> poliage = (List<Map<String, Object>>) ac.get("poliage_update");
                    if (poliage != null && !poliage.isEmpty()) {
                        typeAffect  = (String)  poliage.get(0).get("type_affect");
                        Object oa   = poliage.get(0).get("order_affect");
                        orderAffect = oa instanceof Number n ? n.intValue() : null;
                    }
                    MutableVertex acV = embeddedDb.newVertex("DaliAffectedColumn")
                            .set("session_id",        sid)
                            .set("statement_geoid",   e.getKey())
                            .set("column_ref",        ac.get("column_ref"))
                            .set("column_name",       ac.get("column_name"))
                            .set("table_geoid",       ac.get("table_geoid"))
                            .set("dataset_alias",     ac.get("dataset_alias"))
                            .set("source_type",       ac.get("source_type"))
                            .set("resolution_status", ac.get("resolution_status"))
                            .set("type_affect",       typeAffect)
                            .set("order_affect",      orderAffect)
                            .save();
                    sv.newEdge("HAS_AFFECTED_COL", acV, true)
                            .set("session_id", sid)
                            .save();
                    String acTblGeoid = (String) ac.get("table_geoid");
                    if (acTblGeoid != null) {
                        MutableVertex tblRef = tblV.get(acTblGeoid);
                        if (tblRef != null) acV.newEdge("AFFECTED_COL_REF_TABLE", tblRef, true);
                    }
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

                MutableVertex sv = stV.get(stmtGeoid);
                if (sv == null) continue;

                for (var at : atoms.entrySet()) {
                    Map<String, Object> a = at.getValue();
                    String atomId = md5(stmtGeoid + ":" + at.getKey());
                    MutableVertex aV = embeddedDb.newVertex("DaliAtom")
                            .set("session_id", sid)
                            .set("statement_geoid", stmtGeoid)
                            .set("atom_id", atomId)
                            .set("atom_text", at.getKey())
                            .set("atom_context", a.get("atom_context"))
                            .set("parent_context", a.get("parent_context"))
                            .set("position", a.get("position"))
                            .set("sposition", a.get("sposition"))
                            .set("is_complex", a.get("is_complex"))
                            .set("is_column_reference", a.get("is_column_reference"))
                            .set("is_function_call", a.get("is_function_call"))
                            .set("is_constant", a.get("is_constant"))
                            .set("is_routine_param", a.get("is_routine_param"))
                            .set("is_routine_var", a.get("is_routine_var"))
                            .set("table_name", a.get("table_name"))
                            .set("column_name", a.get("column_name"))
                            .set("table_geoid", a.get("table_geoid"))
                            .set("status", a.get("status"))
                            .set("output_column_sequence", a.get("output_column_sequence"))
                            .set("nested_atoms_count", a.get("nested_atoms_count"))
                            .save();
                    sv.newEdge("HAS_ATOM", aV, true);

                    String atomTableGeoid = (String) a.get("table_geoid");
                    if (atomTableGeoid != null) {
                        MutableVertex atbl = tblV.get(atomTableGeoid);
                        if (atbl != null) {
                            // Physical table source
                            aV.newEdge("ATOM_REF_TABLE", atbl, true);
                            String atomColName = (String) a.get("column_name");
                            if (atomColName != null) {
                                String colGeoid = atomTableGeoid + "." + atomColName.toUpperCase();
                                MutableVertex acol = colV.get(colGeoid);
                                if (acol != null) aV.newEdge("ATOM_REF_COLUMN", acol, true);
                            }
                        } else {
                            // Subquery/CTE source: table_geoid is a statement geoid
                            MutableVertex astmt = stV.get(atomTableGeoid);
                            if (astmt != null) {
                                aV.newEdge("ATOM_REF_STMT", astmt, true);
                                String atomColName = (String) a.get("column_name");
                                if (atomColName != null) {
                                    MutableVertex aoc = ocByStmtAndName.get(
                                            atomTableGeoid + ":" + atomColName.toUpperCase());
                                    if (aoc != null) aV.newEdge("ATOM_REF_OUTPUT_COL", aoc, true);
                                }
                            }
                        }
                    }

                    // H1.6 — ATOM_PRODUCES: DaliAtom → DaliOutputColumn
                    Object outSeq = a.get("output_column_sequence");
                    if (outSeq != null) {
                        String outColKey = stmtGeoid + ":" + outSeq;
                        MutableVertex ocV2 = ocByKey.get(outColKey);
                        if (ocV2 != null) {
                            aV.newEdge("ATOM_PRODUCES", ocV2, true)
                                    .set("session_id", sid)
                                    .save();
                        }
                    }

                    // H1.5 — DATA_FLOW: DaliColumn → DaliOutputColumn (resolved atoms only)
                    if ("Обработано".equals(a.get("status")) && outSeq != null) {
                        String atomColName2 = (String) a.get("column_name");
                        if (atomTableGeoid != null && atomColName2 != null) {
                            String srcColGeoid = atomTableGeoid + "." + atomColName2.toUpperCase();
                            MutableVertex srcCol = colV.get(srcColGeoid);
                            String outColKey2 = stmtGeoid + ":" + outSeq;
                            MutableVertex tgtOcV = ocByKey.get(outColKey2);
                            if (srcCol != null && tgtOcV != null) {
                                srcCol.newEdge("DATA_FLOW", tgtOcV, true)
                                        .set("session_id",      sid)
                                        .set("statement_geoid", stmtGeoid)
                                        .set("atom_id",         atomId)
                                        .set("flow_type",       resolveFlowType(a, str.getStatements().get(stmtGeoid)))
                                        .save();
                            }
                        }
                    }

                    String parentCtx = (String) a.get("parent_context");
                    if (("WHERE".equals(parentCtx) || "HAVING".equals(parentCtx))
                            && "Обработано".equals(a.get("status"))
                            && atomTableGeoid != null) {
                        String atomColName = (String) a.get("column_name");
                        if (atomColName != null) {
                            String colGeoid = atomTableGeoid + "." + atomColName.toUpperCase();
                            MutableVertex colVt = colV.get(colGeoid);
                            if (colVt != null) {
                                colVt.newEdge("FILTER_FLOW", sv, true)
                                        .set("context", parentCtx)
                                        .set("session_id", sid)
                                        .set("statement_geoid", stmtGeoid)
                                        .set("via_atom", at.getKey())
                                        .save();
                            }
                        }
                    }
                }
            }

            // ── DaliResolutionLog: per-atom diagnostic (S1.PRE) ──
            for (Map<String, Object> logEntry : result.getResolutionLog()) {
                embeddedDb.newDocument("DaliResolutionLog")
                        .set("session_id",       sid)
                        .set("file_path",        result.getFilePath())
                        .set("statement_geoid",  logEntry.get("statement_geoid"))
                        .set("raw_input",        logEntry.get("raw_input"))
                        .set("result_kind",      logEntry.get("result_kind"))
                        .set("is_function_call", logEntry.get("is_function_call"))
                        .set("atom_context",     logEntry.get("atom_context"))
                        .set("parent_context",   logEntry.get("parent_context"))
                        .set("note",             logEntry.get("note"))
                        .set("strategy",         logEntry.get("strategy"))
                        .set("table_name",       logEntry.get("table_name"))
                        .set("column_name",      logEntry.get("column_name"))
                        .set("position",         logEntry.get("position"))
                        .save();
            }

            // ── DaliSchemaLog: suspicious schema registrations with backtrace (S1.SCH) ──
            for (Map<String, Object> schEntry : result.getSchemaRegistrationLog()) {
                embeddedDb.newDocument("DaliSchemaLog")
                        .set("session_id",   sid)
                        .set("schema_name",  schEntry.get("schema_name"))
                        .set("reason",       schEntry.get("reason"))
                        .set("backtrace",    schEntry.get("backtrace"))
                        .save();
            }

            // ── CALLS edges: intra-file routine calls (CALLS-1) ──
            for (var callerEntry : result.getCalledRoutines().entrySet()) {
                MutableVertex callerV = rtV.get(callerEntry.getKey());
                if (callerV == null) continue;
                for (Map<String, String> call : callerEntry.getValue()) {
                    String calleeName = call.get("name");
                    if (calleeName == null) continue;
                    MutableVertex calleeV = null;
                    for (var rtEntry : rtV.entrySet()) {
                        String geoid = rtEntry.getKey().toUpperCase();
                        if (geoid.endsWith(":" + calleeName.toUpperCase())
                                || geoid.equals(calleeName.toUpperCase())) {
                            calleeV = rtEntry.getValue();
                            break;
                        }
                    }
                    if (calleeV != null) {
                        callerV.newEdge("CALLS", calleeV, true)
                                .set("session_id",   sid)
                                .set("caller_geoid", callerEntry.getKey())
                                .set("callee_name",  calleeName)
                                .set("line_start",   call.get("line"))
                                .save();
                    }
                }
            }

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
        timer.stop("write.vtx");
    }

    // ═══════════════════════════════════════════════════════════════
    // REMOTE mode — с RID-кэшем
    // ═══════════════════════════════════════════════════════════════

    private void saveRemote(String sid, SemanticResult result, PipelineTimer timer,
                            CanonicalPool pool, String dbName) {
        Structure str = result.getStructure();
        if (str == null) return;

        timer.start("write.vtx");

        rcmd("INSERT INTO DaliSession SET session_id=?, db_name=?, file_path=?, dialect=?, processing_time_ms=?, created_at=?",
                sid, dbName, result.getFilePath(), result.getDialect(), result.getProcessingTimeMs(), System.currentTimeMillis());

        // ── DaliDatabase: skip per-session references in namespace mode ──
        // In namespace mode the canonical DaliDatabase was created by ensureCanonicalPool();
        // SQL-text DB references (str.getDatabases()) are omitted to avoid duplication.
        if (pool == null) {
            for (var e : str.getDatabases().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) e.getValue();
                // find-or-create: avoid duplicates across re-runs (UNIQUE_HASH on db_geoid)
                boolean dbExists = false;
                try {
                    var rs = remoteDb.query("sql",
                            "SELECT @rid FROM DaliDatabase WHERE db_geoid = :g LIMIT 1",
                            Map.of("g", e.getKey()));
                    dbExists = rs.hasNext();
                } catch (Exception ex) {
                    logger.debug("DaliDatabase lookup (ad-hoc) failed for '{}': {}", e.getKey(), ex.getMessage());
                }
                if (!dbExists) {
                    rcmd("INSERT INTO DaliDatabase SET db_geoid=?, db_name=?, created_at=?",
                            e.getKey(), d.get("name"), System.currentTimeMillis());
                }
            }
        }

        // ── DaliSchema: canonical (namespace) or per-session (ad-hoc) ──
        // Track newly created schemas/tables so we can wire hierarchy edges after RID cache build.
        Set<String> newSchemaGeoids  = new LinkedHashSet<>();
        Set<String> newTableGeoids   = new LinkedHashSet<>();
        Set<String> newColumnGeoids  = new LinkedHashSet<>();
        for (var e : str.getSchemas().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sc = (Map<String, Object>) e.getValue();
            if (pool != null) {
                String cg = pool.canonicalSchema(e.getKey());
                if (!pool.hasSchemaRid(cg)) {
                    rcmd("INSERT INTO DaliSchema SET db_name=?, db_geoid=?, schema_geoid=?, schema_name=?",
                            dbName, dbName, e.getKey(), sc.get("name"));
                    pool.putSchemaRid(cg, cg); // placeholder — real RID fetched in buildRidCache
                    newSchemaGeoids.add(e.getKey()); // will need CONTAINS_SCHEMA edge
                }
            } else {
                // Ad-hoc mode: add db_name/db_geoid so SHUTTLE can group without CONTAINS_SCHEMA
                String schDbGeoid = (String) sc.get("db");
                @SuppressWarnings("unchecked")
                String schDbName  = (schDbGeoid != null && str.getDatabases().containsKey(schDbGeoid))
                        ? (String) ((Map<String, Object>) str.getDatabases().get(schDbGeoid)).get("name")
                        : dbName;
                rcmd("INSERT INTO DaliSchema SET session_id=?, schema_geoid=?, schema_name=?, db_name=?, db_geoid=?",
                        sid, e.getKey(), sc.get("name"),
                        schDbName, schDbGeoid != null ? schDbGeoid : schDbName);
            }
        }

        for (var e : str.getPackages().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pkg = (Map<String, Object>) e.getValue();
            rcmd("INSERT INTO DaliPackage SET session_id=?, package_geoid=?, package_name=?, schema_geoid=?, " +
                            "routine_geoid=?, routine_name=?, routine_type=?",
                    sid, e.getKey(), pkg.get("package_name"), pkg.get("schema_geoid"),
                    e.getKey(), pkg.get("package_name"), "PACKAGE");
        }

        for (var e : str.getTables().entrySet()) {
            TableInfo t = e.getValue();
            if (pool != null) {
                // Namespace mode: upsert canonical table
                String cg = pool.canonical(e.getKey());
                if (!pool.hasTableRid(cg)) {
                    rcmd("INSERT INTO DaliTable SET db_name=?, table_geoid=?, table_name=?, schema_geoid=?, table_type=?, aliases=?, column_count=?",
                            dbName, e.getKey(), t.tableName(), t.schemaGeoid(), t.tableType(),
                            toJson(new ArrayList<>(t.aliases())), t.columnCount());
                    pool.putTableRid(cg, cg); // placeholder — real RID fetched in buildRidCache
                    newTableGeoids.add(e.getKey()); // will need CONTAINS_TABLE edge
                }
            } else {
                // Ad-hoc mode: per-session table
                rcmd("INSERT INTO DaliTable SET session_id=?, table_geoid=?, table_name=?, schema_geoid=?, table_type=?, aliases=?, column_count=?",
                        sid, e.getKey(), t.tableName(), t.schemaGeoid(), t.tableType(),
                        toJson(new ArrayList<>(t.aliases())), t.columnCount());
            }
        }

        for (var e : str.getColumns().entrySet()) {
            ColumnInfo c = e.getValue();
            if (pool != null) {
                // Namespace mode: upsert canonical column
                String cg = pool.canonicalCol(c.getTableGeoid(), c.getColumnName());
                if (!pool.hasColumnRid(cg)) {
                    rcmd("INSERT INTO DaliColumn SET db_name=?, column_geoid=?, table_geoid=?, column_name=?, expression=?, alias=?, is_output=?, col_order=?, used_in_statements=?",
                            dbName, e.getKey(), c.getTableGeoid(), c.getColumnName(),
                            c.getExpression(), c.getAlias(), c.isOutput(), c.getOrder(),
                            toJson(new ArrayList<>(c.getUsedInStatements())));
                    pool.putColumnRid(cg, cg); // placeholder
                    newColumnGeoids.add(e.getKey()); // will need HAS_COLUMN edge
                }
            } else {
                // Ad-hoc mode: per-session column
                rcmd("INSERT INTO DaliColumn SET session_id=?, column_geoid=?, table_geoid=?, column_name=?, expression=?, alias=?, is_output=?, col_order=?, used_in_statements=?",
                        sid, e.getKey(), c.getTableGeoid(), c.getColumnName(), c.getExpression(), c.getAlias(), c.isOutput(), c.getOrder(),
                        toJson(new ArrayList<>(c.getUsedInStatements())));
            }
        }

        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            rcmd("INSERT INTO DaliRoutine SET session_id=?, routine_geoid=?, routine_name=?, routine_type=?, package_geoid=?, schema_geoid=?",
                    sid, e.getKey(), r.getName(), r.getRoutineType(), r.getPackageGeoid(), r.getSchemaGeoid());
        }

        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            for (RoutineInfo.ParameterInfo p : r.getTypedParameters()) {
                rcmd("INSERT INTO DaliParameter SET session_id=?, routine_geoid=?, param_name=?, param_type=?, param_mode=?",
                        sid, e.getKey(), p.name(), p.type(), p.mode());
            }
            for (RoutineInfo.VariableInfo v : r.getTypedVariables()) {
                rcmd("INSERT INTO DaliVariable SET session_id=?, routine_geoid=?, var_name=?, var_type=?",
                        sid, e.getKey(), v.name(), v.type());
            }
        }

        for (var e : str.getStatements().entrySet()) {
            StatementInfo s = e.getValue();
            // H1.4: full 30-prop parity with embedded path (snippet lives in DaliSnippet document)
            rcmd("INSERT INTO DaliStatement SET " +
                            "session_id=?, stmt_geoid=?, type=?, subtype=?, " +
                            "line_start=?, line_end=?, " +
                            "parent_statement=?, parent_statement_type=?, " +
                            "routine_geoid=?, short_name=?, " +
                            "aliases=?, child_statements=?, " +
                            "source_table_geoids=?, target_table_geoids=?, source_subquery_geoids=?, " +
                            "source_tables_json=?, target_tables_json=?, " +
                            "is_union=?, is_dml=?, is_ddl=?, " +
                            "has_aggregation=?, has_window=?, has_cte=?, " +
                            "join_count=?, col_count_output=?, col_count_input=?, " +
                            "depth=?, quality=?",
                    sid, e.getKey(), s.getType(), s.getSubtype(),
                    s.getLineStart(), s.getLineEnd(),
                    s.getParentStatementGeoid(), parentType(s.getParentStatementGeoid(), str),
                    s.getRoutineGeoid(), s.getShortName(),
                    toJson(new java.util.ArrayList<>(s.getAliases())),
                    toJson(new java.util.ArrayList<>(s.getChildStatements())),
                    toJson(new java.util.ArrayList<>(s.getSourceTables().keySet())),
                    toJson(new java.util.ArrayList<>(s.getTargetTables().keySet())),
                    toJson(new java.util.ArrayList<>(s.getSourceSubqueries().keySet())),
                    toJson(new java.util.ArrayList<>(s.getSourceTables().values())),
                    toJson(new java.util.ArrayList<>(s.getTargetTables().values())),
                    s.isUnion(), isDml(s.getType()), isDdl(s.getType()),
                    s.isHasAggregation(), s.isHasWindow(), hasCte(s, str.getStatements()),
                    s.getJoins().size(), s.getColumnsOutput().size(), countInputColumns(s),
                    computeDepth(s.getParentStatementGeoid(), str.getStatements()),
                    computeStatementQuality(s));
        }

        for (var e : str.getStatements().entrySet()) {
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
                rcmd("INSERT INTO DaliAffectedColumn SET session_id=?, statement_geoid=?, " +
                                "column_ref=?, column_name=?, table_geoid=?, dataset_alias=?, " +
                                "source_type=?, resolution_status=?, type_affect=?, order_affect=?",
                        sid, e.getKey(),
                        ac.get("column_ref"), ac.get("column_name"), ac.get("table_geoid"),
                        ac.get("dataset_alias"), ac.get("source_type"), ac.get("resolution_status"),
                        typeAffect, orderAffect);
            }
        }

        for (var e : str.getStatements().entrySet()) {
            String raw = truncate(e.getValue().getSnippet(), SNIPPET_MAX);
            if (raw == null) continue;
            rcmd("INSERT INTO DaliSnippet SET session_id=?, stmt_geoid=?, snippet=?, snippet_hash=?",
                    sid, e.getKey(), raw, md5(raw));
        }

        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                Map<String, Object> col = oc.getValue();
                rcmd("INSERT INTO DaliOutputColumn SET session_id=?, statement_geoid=?, col_key=?, " +
                                "name=?, expression=?, alias=?, col_order=?, source_type=?, table_ref=?",
                        sid, e.getKey(), oc.getKey(), col.get("name"), col.get("expression"),
                        col.get("alias"), col.get("order"), col.get("source_type"), col.get("table_ref"));
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
                String atomId = md5(stmtGeoid + ":" + at.getKey());
                rcmd("INSERT INTO DaliAtom SET session_id=?, statement_geoid=?, atom_id=?, atom_text=?, " +
                                "atom_context=?, parent_context=?, position=?, sposition=?, " +
                                "is_complex=?, is_column_reference=?, is_function_call=?, is_constant=?, " +
                                "is_routine_param=?, is_routine_var=?, table_name=?, column_name=?, " +
                                "table_geoid=?, status=?, output_column_sequence=?, nested_atoms_count=?",
                        sid, stmtGeoid, atomId, at.getKey(),
                        a.get("atom_context"), a.get("parent_context"), a.get("position"), a.get("sposition"),
                        a.get("is_complex"), a.get("is_column_reference"), a.get("is_function_call"),
                        a.get("is_constant"), a.get("is_routine_param"), a.get("is_routine_var"),
                        a.get("table_name"), a.get("column_name"),
                        a.get("table_geoid"), a.get("status"),
                        a.get("output_column_sequence"), a.get("nested_atoms_count"));
            }
        }

        for (var e : str.getStatements().entrySet()) {
            for (JoinInfo j : e.getValue().getJoins()) {
                rcmd("INSERT INTO DaliJoin SET session_id=?, statement_geoid=?, join_type=?, " +
                                "source_table_geoid=?, source_alias=?, source_type=?, " +
                                "target_table_geoid=?, target_alias=?, target_type=?, " +
                                "conditions=?, line_start=?",
                        sid, e.getKey(), j.joinType(),
                        j.sourceTableGeoid(), j.sourceTableAlias(), j.sourceType(),
                        j.targetTableGeoid(), j.targetTableAlias(), j.targetType(),
                        j.conditions(), j.lineStart());
            }
        }

        timer.stop("write.vtx");
        timer.start("write.edge");

        // === RID CACHE (namespace-aware) ===
        RidCache rid = buildRidCache(sid, pool, dbName);

        // ─────── Рёбра по RID ───────

        // CONTAINS_SCHEMA: DaliDatabase → DaliSchema (namespace mode, new schemas only)
        if (pool != null && pool.getDatabaseRid() != null) {
            for (String schGeoid : newSchemaGeoids) {
                String schRid = rid.schemas.get(schGeoid.toUpperCase());
                if (schRid != null)
                    edgeByRid("CONTAINS_SCHEMA", pool.getDatabaseRid(), schRid, sid);
            }
        }

        // CONTAINS_TABLE: DaliSchema → DaliTable (namespace mode, new tables only)
        if (pool != null) {
            for (String tblGeoid : newTableGeoids) {
                TableInfo t = str.getTables().get(tblGeoid);
                if (t == null || t.schemaGeoid() == null) continue;
                String schRid = rid.schemas.get(t.schemaGeoid().toUpperCase());
                String tblRid = rid.tables.get(tblGeoid);
                if (schRid != null && tblRid != null)
                    edgeByRid("CONTAINS_TABLE", schRid, tblRid, sid);
            }
        }

        for (var e : str.getPackages().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pkg = (Map<String, Object>) e.getValue();
            String sg = (String) pkg.get("schema_geoid");
            if (sg != null && !sg.isEmpty()) {
                String fromRid = rid.schemas.get(sg.toUpperCase());
                String toRid   = rid.packages.get(e.getKey());
                if (fromRid != null && toRid != null)
                    edgeByRid("CONTAINS_ROUTINE", fromRid, toRid, sid);
            }
        }

        // Ad-hoc mode: CONTAINS_TABLE for all per-session tables.
        // Namespace mode: already handled above via newTableGeoids.
        if (pool == null) {
            for (var e : str.getTables().entrySet()) {
                String sg = e.getValue().schemaGeoid();
                if (sg != null) {
                    String fromRid = rid.schemas.get(sg.toUpperCase());
                    String toRid   = rid.tables.get(e.getKey());
                    if (fromRid != null && toRid != null)
                        edgeByRid("CONTAINS_TABLE", fromRid, toRid, sid);
                }
            }
        }

        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            String fromRid = null;
            if (r.getPackageGeoid() != null) {
                fromRid = rid.packages.get(r.getPackageGeoid().toUpperCase());
            } else if (r.getSchemaGeoid() != null) {
                fromRid = rid.schemas.get(r.getSchemaGeoid().toUpperCase());
            }
            String toRid = rid.routines.get(e.getKey());
            if (fromRid != null && toRid != null)
                edgeByRid("CONTAINS_ROUTINE", fromRid, toRid, sid);
        }

        for (var e : str.getRoutines().entrySet()) {
            RoutineInfo r = e.getValue();
            if (r.getParentRoutineGeoid() != null) {
                String fromRid = rid.routines.get(r.getParentRoutineGeoid());
                String toRid   = rid.routines.get(e.getKey());
                if (fromRid != null && toRid != null)
                    edgeByRid("NESTED_IN", fromRid, toRid, sid);
            }
        }

        // HAS_COLUMN: DaliTable → DaliColumn (namespace mode: new columns only; ad-hoc: all)
        if (pool != null) {
            for (String colGeoid : newColumnGeoids) {
                ColumnInfo c = str.getColumns().get(colGeoid);
                if (c == null) continue;
                String fromRid = rid.tables.get(c.getTableGeoid());
                String toRid   = rid.columns.get(colGeoid);
                if (fromRid != null && toRid != null)
                    edgeByRid("HAS_COLUMN", fromRid, toRid, sid);
            }
        } else {
            for (var e : str.getColumns().entrySet()) {
                String fromRid = rid.tables.get(e.getValue().getTableGeoid());
                String toRid   = rid.columns.get(e.getKey());
                if (fromRid != null && toRid != null)
                    edgeByRid("HAS_COLUMN", fromRid, toRid, sid);
            }
        }

        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getParentStatementGeoid() != null) {
                String fromRid = rid.statements.get(e.getKey());
                String toRid   = rid.statements.get(e.getValue().getParentStatementGeoid());
                if (fromRid != null && toRid != null)
                    edgeByRid("CHILD_OF", fromRid, toRid, sid);
            }
        }

        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getRoutineGeoid() != null) {
                String fromRid = rid.routines.get(e.getValue().getRoutineGeoid());
                String toRid   = rid.statements.get(e.getKey());
                if (fromRid != null && toRid != null)
                    edgeByRid("CONTAINS_STMT", fromRid, toRid, sid);
            }
        }

        for (var e : str.getStatements().entrySet()) {
            String stmtRid = rid.statements.get(e.getKey());
            if (stmtRid == null) continue;

            for (String tg : e.getValue().getSourceTables().keySet()) {
                String toRid = rid.tables.get(tg);
                if (toRid != null) edgeByRid("READS_FROM", stmtRid, toRid, sid);
            }
            for (String tg : e.getValue().getTargetTables().keySet()) {
                String toRid = rid.tables.get(tg);
                if (toRid != null) edgeByRid("WRITES_TO", stmtRid, toRid, sid);
            }
        }

        for (String tg : str.getTables().keySet()) {
            edgeRemote("BELONGS_TO_SESSION", "DaliSession", "session_id", sid,
                    "DaliTable", "table_geoid", tg, sid);
        }
        for (String rg : str.getRoutines().keySet()) {
            edgeRemote("BELONGS_TO_SESSION", "DaliSession", "session_id", sid,
                    "DaliRoutine", "routine_geoid", rg, sid);
        }

        for (var e : str.getRoutines().entrySet()) {
            if (!e.getValue().getTypedParameters().isEmpty())
                edgeRemote("HAS_PARAMETER", "DaliRoutine", "routine_geoid", e.getKey(),
                        "DaliParameter", "routine_geoid", e.getKey(), sid);
            if (!e.getValue().getTypedVariables().isEmpty())
                edgeRemote("HAS_VARIABLE", "DaliRoutine", "routine_geoid", e.getKey(),
                        "DaliVariable", "routine_geoid", e.getKey(), sid);
        }

        for (var e : str.getStatements().entrySet()) {
            String stmtRid = rid.statements.get(e.getKey());
            if (stmtRid == null) continue;
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                String ocRid = rid.outputCols.get(oc.getKey());
                if (ocRid != null)
                    edgeByRid("HAS_OUTPUT_COL", stmtRid, ocRid, sid, "statement_geoid", e.getKey());
            }
        }

        // v19: lookup by "stmtGeoid:COL_NAME_UPPER" for ATOM_REF_OUTPUT_COL (subquery sources)
        Map<String, String> ocByStmtAndName = new HashMap<>();
        for (var e : str.getStatements().entrySet()) {
            for (var oc : e.getValue().getColumnsOutput().entrySet()) {
                String ocRid = rid.outputCols.get(oc.getKey());
                if (ocRid == null) continue;
                // col_key == oc.getKey() == column name/alias used by outer queries
                ocByStmtAndName.put(e.getKey() + ":" + oc.getKey().toUpperCase(), ocRid);
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

            String stmtRid = rid.statements.get(stmtGeoid);
            if (stmtRid == null) continue;

            for (var at : atoms.entrySet()) {
                Map<String, Object> a = at.getValue();
                String atomId = md5(stmtGeoid + ":" + at.getKey());
                String atomRid = rid.atoms.get(atomId);
                if (atomRid != null)
                    edgeByRid("HAS_ATOM", stmtRid, atomRid, sid);

                String tableGeoid = (String) a.get("table_geoid");
                if (tableGeoid != null && atomRid != null) {
                    String tblRid = rid.tables.get(tableGeoid);
                    if (tblRid != null) {
                        // Physical table source
                        edgeByRid("ATOM_REF_TABLE", atomRid, tblRid, sid);
                        String colName = (String) a.get("column_name");
                        if (colName != null) {
                            String colGeoid = tableGeoid + "." + colName.toUpperCase();
                            String colRid = rid.columns.get(colGeoid);
                            if (colRid != null)
                                edgeByRid("ATOM_REF_COLUMN", atomRid, colRid, sid);
                        }
                    } else {
                        // Subquery/CTE source: table_geoid is a statement geoid
                        String sqStmtRid = rid.statements.get(tableGeoid);
                        if (sqStmtRid != null) {
                            edgeByRid("ATOM_REF_STMT", atomRid, sqStmtRid, sid);
                            String colName = (String) a.get("column_name");
                            if (colName != null) {
                                String ocRid = ocByStmtAndName.get(
                                        tableGeoid + ":" + colName.toUpperCase());
                                if (ocRid != null)
                                    edgeByRid("ATOM_REF_OUTPUT_COL", atomRid, ocRid, sid);
                            }
                        }
                    }
                }

                // H1.6 — ATOM_PRODUCES: DaliAtom → DaliOutputColumn
                Object outSeq = a.get("output_column_sequence");
                if (outSeq != null && atomRid != null) {
                    String ocRid = rid.ocByOrder.get(stmtGeoid + ":" + outSeq);
                    if (ocRid != null)
                        edgeByRid("ATOM_PRODUCES", atomRid, ocRid, sid);
                }

                // H1.5 — DATA_FLOW: DaliColumn → DaliOutputColumn (resolved atoms in SELECT only)
                if ("Обработано".equals(a.get("status")) && outSeq != null
                        && tableGeoid != null && atomRid != null) {
                    String colName = (String) a.get("column_name");
                    if (colName != null) {
                        String colRid = rid.columns.get(tableGeoid + "." + colName.toUpperCase());
                        String ocRid  = rid.ocByOrder.get(stmtGeoid + ":" + outSeq);
                        if (colRid != null && ocRid != null)
                            edgeByRid("DATA_FLOW", colRid, ocRid, sid,
                                    "statement_geoid", stmtGeoid,
                                    "atom_id",         atomId,
                                    "flow_type",       resolveFlowType(a, str.getStatements().get(stmtGeoid)));
                    }
                }

                String parentCtx = (String) a.get("parent_context");
                if (("WHERE".equals(parentCtx) || "HAVING".equals(parentCtx))
                        && "Обработано".equals(a.get("status"))
                        && tableGeoid != null && atomRid != null) {
                    String colName = (String) a.get("column_name");
                    if (colName != null) {
                        String colGeoid = tableGeoid + "." + colName.toUpperCase();
                        String colRid = rid.columns.get(colGeoid);
                        if (colRid != null)
                            edgeByRid("FILTER_FLOW", colRid, stmtRid, sid);
                    }
                }
            }
        }

        for (var e : str.getStatements().entrySet()) {
            for (JoinInfo j : e.getValue().getJoins()) {
                if (j.targetTableGeoid() != null) {
                    edgeRemote("HAS_JOIN", "DaliStatement", "stmt_geoid", e.getKey(),
                            "DaliJoin", "statement_geoid", e.getKey(), sid, "statement_geoid", e.getKey());
                }
            }
        }

        for (var e : str.getStatements().entrySet()) {
            String fromRid = rid.statements.get(e.getKey());
            if (fromRid == null) continue;
            for (String sqGeoid : e.getValue().getSourceSubqueries().keySet()) {
                String toRid = rid.statements.get(sqGeoid);
                if (toRid != null)
                    edgeByRid("USES_SUBQUERY", fromRid, toRid, sid);
            }
        }

        // HAS_AFFECTED_COL: DaliStatement → DaliAffectedColumn (all for that statement at once)
        for (var e : str.getStatements().entrySet()) {
            if (e.getValue().getAffectedColumns().isEmpty()) continue;
            String stmtRid = rid.statements.get(e.getKey());
            if (stmtRid != null)
                edgeFromRidToQuery("HAS_AFFECTED_COL", stmtRid,
                        "DaliAffectedColumn", "statement_geoid", e.getKey(), sid);
        }

        // AFFECTED_COL_REF_TABLE: DaliAffectedColumn → DaliTable, grouped by table_geoid
        {
            Set<String> seen = new LinkedHashSet<>();
            for (var e : str.getStatements().entrySet()) {
                for (Map<String, Object> ac : e.getValue().getAffectedColumns()) {
                    String tg = (String) ac.get("table_geoid");
                    if (tg != null && seen.add(tg)) {
                        String tblRid = rid.tables.get(tg);
                        if (tblRid != null)
                            edgeFromQueryToRid("AFFECTED_COL_REF_TABLE",
                                    "DaliAffectedColumn", "table_geoid", tg, sid, tblRid);
                    }
                }
            }
        }

        // JOIN_SOURCE_TABLE / JOIN_TARGET_TABLE: DaliJoin → DaliTable, grouped by table_geoid
        {
            Set<String> srcSeen = new LinkedHashSet<>();
            Set<String> tgtSeen = new LinkedHashSet<>();
            for (var e : str.getStatements().entrySet()) {
                for (JoinInfo j : e.getValue().getJoins()) {
                    String src = j.sourceTableGeoid();
                    if (src != null && srcSeen.add(src)) {
                        String tblRid = rid.tables.get(src);
                        if (tblRid != null)
                            edgeFromQueryToRid("JOIN_SOURCE_TABLE",
                                    "DaliJoin", "source_table_geoid", src, sid, tblRid);
                    }
                    String tgt = j.targetTableGeoid();
                    if (tgt != null && tgtSeen.add(tgt)) {
                        String tblRid = rid.tables.get(tgt);
                        if (tblRid != null)
                            edgeFromQueryToRid("JOIN_TARGET_TABLE",
                                    "DaliJoin", "target_table_geoid", tgt, sid, tblRid);
                    }
                }
            }
        }

        // ── DaliResolutionLog: per-atom diagnostic (S1.PRE) ──
        for (Map<String, Object> logEntry : result.getResolutionLog()) {
            rcmd("INSERT INTO DaliResolutionLog SET session_id=?, file_path=?, statement_geoid=?, " +
                 "raw_input=?, result_kind=?, is_function_call=?, atom_context=?, parent_context=?, " +
                 "note=?, `strategy`=?, table_name=?, column_name=?, position=?",
                 sid, result.getFilePath(), logEntry.get("statement_geoid"),
                 logEntry.get("raw_input"), logEntry.get("result_kind"), logEntry.get("is_function_call"),
                 logEntry.get("atom_context"), logEntry.get("parent_context"),
                 logEntry.get("note"), logEntry.get("strategy"),
                 logEntry.get("table_name"), logEntry.get("column_name"), logEntry.get("position"));
        }

        // ── DaliSchemaLog: suspicious schema registrations with backtrace (S1.SCH) ──
        for (Map<String, Object> schEntry : result.getSchemaRegistrationLog()) {
            rcmd("INSERT INTO DaliSchemaLog SET session_id=?, schema_name=?, reason=?, backtrace=?",
                 sid, schEntry.get("schema_name"), schEntry.get("reason"), schEntry.get("backtrace"));
        }

        // ── CALLS edges: intra-file routine calls (CALLS-1) ──
        for (var callerEntry : result.getCalledRoutines().entrySet()) {
            String callerRid = rid.routines.get(callerEntry.getKey());
            if (callerRid == null) continue;
            for (Map<String, String> call : callerEntry.getValue()) {
                String calleeName = call.get("name");
                if (calleeName == null) continue;
                String calleeRid = null;
                for (var rtEntry : rid.routines.entrySet()) {
                    String geoid = rtEntry.getKey().toUpperCase();
                    if (geoid.endsWith(":" + calleeName.toUpperCase())
                            || geoid.equals(calleeName.toUpperCase())) {
                        calleeRid = rtEntry.getValue();
                        break;
                    }
                }
                if (calleeRid != null) {
                    try {
                        remoteDb.command("sql",
                            "CREATE EDGE CALLS FROM " + callerRid + " TO " + calleeRid +
                            " SET session_id = :sid, caller_geoid = :caller, callee_name = :callee, line_start = :line",
                            Map.of("sid", sid, "caller", callerEntry.getKey(),
                                   "callee", calleeName, "line", call.get("line")));
                    } catch (Exception e) {
                        logger.debug("CALLS edge failed: {}", e.getMessage());
                    }
                }
            }
        }

        timer.stop("write.edge");
    }

    // ====================== RID CACHE ======================
    private static class RidCache {
        Map<String, String> tables      = new HashMap<>();
        Map<String, String> columns     = new HashMap<>();
        Map<String, String> statements  = new HashMap<>();
        Map<String, String> routines    = new HashMap<>();
        Map<String, String> packages    = new HashMap<>();
        Map<String, String> schemas     = new HashMap<>();
        Map<String, String> atoms       = new HashMap<>();
        Map<String, String> outputCols  = new HashMap<>();
        /** key = "stmtGeoid:col_order" → RID  (for ATOM_PRODUCES / DATA_FLOW lookups) */
        Map<String, String> ocByOrder   = new HashMap<>();
    }

    private RidCache buildRidCache(String sid) {
        return buildRidCache(sid, null, null);
    }

    /**
     * Builds RID cache for remote edge creation.
     *
     * <p>In namespace mode (pool != null): tables and columns are fetched by {@code db_name}
     * (canonical, shared across sessions). All other types are fetched by session_id as usual.
     *
     * <p>In ad-hoc mode (pool == null): all types fetched by session_id.
     */
    private RidCache buildRidCache(String sid, CanonicalPool pool, String dbName) {
        RidCache cache = new RidCache();

        if (pool != null && dbName != null) {
            // Namespace mode: schemas/tables/columns by db_name (canonical, no session_id)
            cache.schemas = buildRidMapByField("DaliSchema", "schema_geoid", "db_name", dbName);
            cache.tables  = buildRidMapByField("DaliTable",  "table_geoid",  "db_name", dbName);
            cache.columns = buildRidMapByField("DaliColumn", "column_geoid", "db_name", dbName);
        } else {
            // Ad-hoc mode: all by session_id
            cache.schemas = buildRidMap("DaliSchema", "schema_geoid", sid);
            cache.tables  = buildRidMap("DaliTable",  "table_geoid",  sid);
            cache.columns = buildRidMap("DaliColumn", "column_geoid", sid);
        }

        cache.statements = buildRidMap("DaliStatement", "stmt_geoid",      sid);
        cache.routines   = buildRidMap("DaliRoutine",   "routine_geoid",   sid);
        cache.packages   = buildRidMap("DaliPackage",   "package_geoid",   sid);
        cache.atoms      = buildRidMap("DaliAtom",      "atom_id",         sid);
        cache.outputCols = buildRidMap("DaliOutputColumn", "col_key",      sid);
        cache.ocByOrder  = buildOcByOrderMap(sid);

        logger.info("RID cache [db={}, sid={}]: T:{} C:{} S:{} R:{} P:{} A:{} OC:{} OCo:{}",
                dbName != null ? dbName : "ad-hoc", sid,
                cache.tables.size(), cache.columns.size(),
                cache.statements.size(), cache.routines.size(),
                cache.packages.size(), cache.atoms.size(),
                cache.outputCols.size(), cache.ocByOrder.size());
        return cache;
    }

    private Map<String, String> buildRidMap(String type, String keyField, String sid) {
        Map<String, String> map = new HashMap<>();
        try {
            var rs = remoteDb.query("sql",
                    "SELECT @rid AS rid, " + keyField + " FROM " + type + " WHERE session_id = :sid",
                    Map.of("sid", sid));

            while (rs.hasNext()) {
                var doc = rs.next().toMap();
                String key = (String) doc.get(keyField);
                String rid = (String) doc.get("rid");
                if (key != null && rid != null) map.put(key, rid);
            }
        } catch (Exception e) {
            logger.warn("RID map failed for {}: {}", type, e.getMessage());
        }
        return map;
    }

    /**
     * Builds "stmtGeoid:col_order → RID" map for all DaliOutputColumn rows of this session.
     * Used for ATOM_PRODUCES and DATA_FLOW edge creation in remote path.
     */
    private Map<String, String> buildOcByOrderMap(String sid) {
        Map<String, String> map = new HashMap<>();
        try {
            var rs = remoteDb.query("sql",
                    "SELECT @rid AS rid, statement_geoid, col_order FROM DaliOutputColumn WHERE session_id = :sid",
                    Map.of("sid", sid));
            while (rs.hasNext()) {
                var doc = rs.next().toMap();
                String stmtG   = (String) doc.get("statement_geoid");
                Object colOrd  = doc.get("col_order");
                String rid     = (String) doc.get("rid");
                if (stmtG != null && colOrd != null && rid != null)
                    map.put(stmtG + ":" + colOrd, rid);
            }
        } catch (Exception e) {
            logger.warn("ocByOrder map failed: {}", e.getMessage());
        }
        return map;
    }

    /** Fetches RID map by a filter field other than session_id (used for canonical types). */
    private Map<String, String> buildRidMapByField(String type, String keyField,
                                                    String filterField, String filterVal) {
        Map<String, String> map = new HashMap<>();
        try {
            var rs = remoteDb.query("sql",
                    "SELECT @rid AS rid, " + keyField + " FROM " + type
                    + " WHERE " + filterField + " = :val",
                    Map.of("val", filterVal));

            while (rs.hasNext()) {
                var doc = rs.next().toMap();
                String key = (String) doc.get(keyField);
                String rid = (String) doc.get("rid");
                if (key != null && rid != null) map.put(key, rid);
            }
        } catch (Exception e) {
            logger.warn("RID map by field failed for {}: {}", type, e.getMessage());
        }
        return map;
    }

    private void edgeByRid(String edgeType, String fromRid, String toRid, String sid) {
        try {
            remoteDb.command("sql",
                    "CREATE EDGE " + edgeType + " FROM " + fromRid + " TO " + toRid + " SET session_id = :sid",
                    Map.of("sid", sid));
        } catch (Exception e) {
            logger.debug("edgeByRid {} failed {} → {}: {}", edgeType, fromRid, toRid, e.getMessage());
        }
    }

    private void edgeByRid(String edgeType, String fromRid, String toRid, String sid,
                           String extraField, Object extraVal) {
        edgeByRid(edgeType, fromRid, toRid, sid, extraField, extraVal, (Object[]) null);
    }

    /**
     * Creates a remote edge with session_id + any number of additional key/value pairs.
     * extraKV must be even-length: [key1, val1, key2, val2, ...].
     */
    private void edgeByRid(String edgeType, String fromRid, String toRid, String sid,
                           String firstKey, Object firstVal, Object... extraKV) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sid", sid);
            StringBuilder sql = new StringBuilder(
                    "CREATE EDGE " + edgeType + " FROM " + fromRid + " TO " + toRid +
                    " SET session_id = :sid");

            if (firstKey != null) {
                params.put("p0", firstVal);
                sql.append(", ").append(firstKey).append(" = :p0");
            }
            if (extraKV != null) {
                for (int i = 0; i + 1 < extraKV.length; i += 2) {
                    String k = (String) extraKV[i];
                    Object v = extraKV[i + 1];
                    String pk = "p" + (i / 2 + 1);
                    params.put(pk, v);
                    sql.append(", ").append(k).append(" = :").append(pk);
                }
            }
            remoteDb.command("sql", sql.toString(), params);
        } catch (Exception e) {
            logger.debug("edgeByRid {} failed: {}", edgeType, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers (без изменений)
    // ═══════════════════════════════════════════════════════════════

    private static final int  RCMD_MAX_RETRIES  = 3;
    private static final long RCMD_RETRY_BASE_MS = 200;

    private void rcmd(String sqlTemplate, Object... params) {
        String sqlFinal;
        Map<String, Object> paramMap;

        if (params.length == 0) {
            sqlFinal = sqlTemplate;
            paramMap = Map.of();
        } else {
            StringBuilder sql = new StringBuilder();
            paramMap = new LinkedHashMap<>();
            int paramIdx = 0;
            int lastPos  = 0;
            for (int i = 0; i < sqlTemplate.length(); i++) {
                if (sqlTemplate.charAt(i) == '?') {
                    sql.append(sqlTemplate, lastPos, i);
                    String paramName = "p" + paramIdx;
                    sql.append(':').append(paramName);
                    paramMap.put(paramName, paramIdx < params.length ? params[paramIdx] : null);
                    paramIdx++;
                    lastPos = i + 1;
                }
            }
            sql.append(sqlTemplate, lastPos, sqlTemplate.length());
            sqlFinal = sql.toString();
        }

        Exception lastEx = null;
        for (int attempt = 0; attempt <= RCMD_MAX_RETRIES; attempt++) {
            try {
                if (paramMap.isEmpty()) remoteDb.command("sql", sqlFinal);
                else                    remoteDb.command("sql", sqlFinal, paramMap);
                return;
            } catch (Exception e) {
                lastEx = e;
                String msg = e.getMessage();
                boolean isTimeout = msg != null && (msg.contains("Timeout") || msg.contains("locking"));
                if (!isTimeout || attempt == RCMD_MAX_RETRIES) break;
                long delay = RCMD_RETRY_BASE_MS * (1L << attempt); // 200, 400, 800 ms
                logger.debug("Remote cmd timeout (attempt {}/{}), retrying in {}ms: {}",
                        attempt + 1, RCMD_MAX_RETRIES, delay,
                        sqlTemplate.substring(0, Math.min(sqlTemplate.length(), 80)));
                try { Thread.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.warn("Remote cmd FAILED: {} — {}",
                sqlTemplate.substring(0, Math.min(sqlTemplate.length(), 100)),
                lastEx != null ? lastEx.getMessage() : "unknown");
    }

    private void edgeRemote(String edgeType, String fromType, String fromField, String fromVal,
                            String toType, String toField, String toVal, String sid) {
        edgeRemote(edgeType, fromType, fromField, fromVal, toType, toField, toVal, sid, null, null);
    }

    private void edgeRemote(String edgeType, String fromType, String fromField, String fromVal,
                            String toType, String toField, String toVal, String sid,
                            String extraField, String extraVal) {
        try {
            StringBuilder sql = new StringBuilder();
            Map<String, Object> params = new LinkedHashMap<>();

            sql.append("CREATE EDGE ").append(edgeType)
                    .append(" FROM (SELECT FROM ").append(fromType)
                    .append(" WHERE ").append(fromField).append(" = :fromVal")
                    .append(" AND session_id = :sid)")
                    .append(" TO (SELECT FROM ").append(toType)
                    .append(" WHERE ").append(toField).append(" = :toVal")
                    .append(" AND session_id = :sid");

            params.put("fromVal", fromVal);
            params.put("toVal", toVal);
            params.put("sid", sid);

            if (extraField != null && extraVal != null) {
                sql.append(" AND ").append(extraField).append(" = :extraVal");
                params.put("extraVal", extraVal);
            }

            sql.append(") SET session_id = :sid");

            remoteDb.command("sql", sql.toString(), params);

        } catch (Exception e) {
            logger.debug("Edge {} failed: FROM {}[{}={}] TO {}[{}={}] — {}",
                    edgeType, fromType, fromField, fromVal,
                    toType, toField, toVal, e.getMessage());
        }
    }

    /** FROM single RID → TO (SELECT FROM type WHERE session_id=sid AND filterField=filterVal) */
    private void edgeFromRidToQuery(String edgeType, String fromRid,
                                    String toType, String filterField, String filterVal, String sid) {
        try {
            remoteDb.command("sql",
                    "CREATE EDGE " + edgeType + " FROM " + fromRid +
                    " TO (SELECT FROM " + toType + " WHERE session_id = :sid AND " + filterField + " = :fv)" +
                    " SET session_id = :sid",
                    Map.of("sid", sid, "fv", filterVal));
        } catch (Exception e) {
            logger.debug("edgeFromRidToQuery {} FROM {} TO {}[{}={}]: {}",
                    edgeType, fromRid, toType, filterField, filterVal, e.getMessage());
        }
    }

    /** FROM (SELECT FROM type WHERE session_id=sid AND filterField=filterVal) → TO single RID */
    private void edgeFromQueryToRid(String edgeType,
                                    String fromType, String filterField, String filterVal,
                                    String sid, String toRid) {
        try {
            remoteDb.command("sql",
                    "CREATE EDGE " + edgeType +
                    " FROM (SELECT FROM " + fromType + " WHERE session_id = :sid AND " + filterField + " = :fv)" +
                    " TO " + toRid + " SET session_id = :sid",
                    Map.of("sid", sid, "fv", filterVal));
        } catch (Exception e) {
            logger.debug("edgeFromQueryToRid {} FROM {}[{}={}] TO {}: {}",
                    edgeType, fromType, filterField, filterVal, toRid, e.getMessage());
        }
    }

    private String parentType(String parentGeoid, Structure str) {
        if (parentGeoid == null) return null;
        StatementInfo p = str.getStatements().get(parentGeoid);
        return p != null ? p.getType() : null;
    }

    private static String toJson(Object value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { return null; }
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
                && "GROUP BY".equals(atom.get("parent_context"))) return "AGGREGATE";
        if (Boolean.TRUE.equals(atom.get("is_function_call")))    return "TRANSFORM";
        return "DIRECT";
    }

    @SafeVarargs
    private MutableVertex resolve(String geoid, Map<String, MutableVertex>... maps) {
        if (geoid == null) return null;
        for (var m : maps) { MutableVertex v = m.get(geoid); if (v != null) return v; }
        return null;
    }

    private static String formatTime(long ms) {
        if (ms < 1000) return ms + "ms";
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 0) return String.format("%dm %02ds", min, sec);
        return String.format("%d.%ds", sec, (ms % 1000) / 100);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String md5(String s) {
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

    public void cleanAll() {
        String[] edgeTypes = {
                // atom resolution (v19: +ATOM_REF_STMT, +ATOM_REF_OUTPUT_COL)
                "ATOM_REF_TABLE","ATOM_REF_COLUMN","ATOM_REF_STMT","ATOM_REF_OUTPUT_COL","ATOM_PRODUCES",
                // data flow
                "DATA_FLOW","FILTER_FLOW","JOIN_FLOW","UNION_FLOW",
                // join sources (v18)
                "JOIN_SOURCE_TABLE","JOIN_TARGET_TABLE",
                // affected columns (v18/v20)
                "HAS_AFFECTED_COL","AFFECTED_COL_REF_TABLE","AFFECTED_COL_REF_COLUMN",
                // statement structure
                "HAS_ATOM","HAS_OUTPUT_COL","HAS_JOIN","READS_FROM","WRITES_TO",
                "USES_SUBQUERY","NESTED_IN","CONTAINS_STMT",
                // routine structure
                "HAS_PARAMETER","HAS_VARIABLE","CHILD_OF","CONTAINS_ROUTINE",
                "ROUTINE_USES_TABLE","CALLS",
                // schema/session structure (v8: +CONTAINS_SCHEMA, +BELONGS_TO_APP)
                "HAS_COLUMN","CONTAINS_TABLE","CONTAINS_SCHEMA","BELONGS_TO_APP","BELONGS_TO_SESSION"
        };
        String[] vtxTypes = {
                // fine-grained first (leaves before roots)
                "DaliAffectedColumn","DaliAtom","DaliOutputColumn","DaliJoin",
                "DaliParameter","DaliVariable","DaliStatement",
                "DaliColumn","DaliPackage","DaliRoutine","DaliTable",
                "DaliSchema","DaliSession","DaliDatabase",
                "DaliApplication"   // v8: root of namespace hierarchy
        };
        String[] docTypes = {
                "DaliSnippet","DaliResolutionLog","DaliSchemaLog"
                // DaliPerfStats intentionally excluded — preserved across cleanAll() for stats review
        };

        if (mode == Mode.EMBEDDED) {
            for (String t : edgeTypes) deleteType(t);
            for (String t : vtxTypes)  deleteType(t);
            for (String t : docTypes)  deleteType(t);
        } else {
            // Remote: batched DELETE LIMIT 500 to avoid transaction lock timeout on large tables.
            // Edges first so vertex deletion doesn't touch edge buckets.
            for (String t : edgeTypes) deleteTypeRemote(t);
            for (String t : vtxTypes)  deleteTypeRemote(t);
            for (String t : docTypes)  deleteTypeRemote(t);
        }
        logger.info("ArcadeDB CLEAN: all Dali records deleted ({})", mode);
    }

    private int deleteType(String typeName) {
        try {
            if (embeddedDb.getSchema().existsType(typeName)) {
                embeddedDb.transaction(() -> embeddedDb.command("sql", "DELETE FROM " + typeName));
                return 1;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Remote-mode batched DELETE: runs DELETE FROM T LIMIT 500 in a loop until the type is empty.
     * Avoids ArcadeDB transaction lock timeouts caused by deleting large vertex types
     * (each vertex deletion updates edge buckets of related vertices in one transaction).
     */
    private void deleteTypeRemote(String typeName) {
        final int BATCH = 500;
        try {
            for (int i = 0; i < 1_000_000; i++) {
                var rs = remoteDb.query("sql",
                        "SELECT count(*) as cnt FROM " + typeName + " LIMIT 1");
                long cnt = rs.hasNext()
                        ? ((Number) rs.next().toMap().getOrDefault("cnt", 0L)).longValue() : 0;
                if (cnt == 0) break;
                try {
                    remoteDb.command("sql", "DELETE FROM " + typeName + " LIMIT " + BATCH);
                } catch (Exception e) {
                    logger.warn("Batch delete failed for {} (batch {}): {}", typeName, i, e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("deleteTypeRemote failed for {}: {}", typeName, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (embeddedDb != null && embeddedDb.isOpen()) {
            embeddedDb.close();
            logger.info("ArcadeDB EMBEDDED closed");
        }
    }
}