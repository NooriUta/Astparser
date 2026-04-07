// File: src/main/java/com/hound/storage/ArcadeDBSemanticWriter.java
package com.hound.storage;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.remote.RemoteDatabase;
import com.hound.metrics.PipelineTimer;
import com.hound.semantic.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Thin facade over {@link EmbeddedWriter} and {@link RemoteWriter}.
 *
 * <p>Lifecycle, public API routing, and shared count computation live here.
 * All heavy write logic is delegated to the mode-specific writer.
 *
 * <ul>
 *   <li>{@link EmbeddedWriter} — ArcadeDB embedded-mode write path</li>
 *   <li>{@link RemoteWriter}   — ArcadeDB remote-mode write path</li>
 *   <li>{@link WriteHelpers}   — pure static helpers shared by both writers</li>
 * </ul>
 */
public class ArcadeDBSemanticWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ArcadeDBSemanticWriter.class);

    private Database             embeddedDb;   // kept for close()
    private final EmbeddedWriter embedded;
    private final RemoteWriter   remote;
    private final Mode           mode;

    public enum Mode { EMBEDDED, REMOTE }

    // ═══════════════════════════════════════════════════════════════
    // Constructors
    // ═══════════════════════════════════════════════════════════════

    public ArcadeDBSemanticWriter(String dbPath) {
        this.mode = Mode.EMBEDDED;
        DatabaseFactory factory = new DatabaseFactory(dbPath);
        this.embeddedDb = factory.exists() ? factory.open() : factory.create();
        SchemaInitializer.ensureSchema(embeddedDb);
        this.embedded = new EmbeddedWriter(embeddedDb);
        this.remote   = null;
        logger.info("ArcadeDB EMBEDDED: {}", dbPath);
    }

    public ArcadeDBSemanticWriter(String host, int port, String dbName,
                                  String user, String password) {
        this.mode = Mode.REMOTE;
        RemoteDatabase rdb = new RemoteDatabase(host, port, dbName, user, password);
        for (String cmd : SchemaInitializer.remoteSchemaCommands()) {
            try { rdb.command("sql", cmd); } catch (Exception ignored) {}
        }
        this.embedded = null;
        this.remote   = new RemoteWriter(rdb);
        logger.info("ArcadeDB REMOTE: {}:{}/{}", host, port, dbName);
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ad-hoc mode (backward-compatible): no database namespace.
     * Tables/columns are per-session; no canonical deduplication.
     */
    public String saveResult(SemanticResult result, PipelineTimer timer) {
        return saveResult(result, timer, null, null);
    }

    /**
     * Namespace-aware save.
     *
     * @param pool   CanonicalPool for the database batch (null = ad-hoc mode)
     * @param dbName Database name derived from folder name (null = ad-hoc mode)
     */
    public String saveResult(SemanticResult result, PipelineTimer timer,
                             CanonicalPool pool, String dbName) {
        String sid = result.getSessionId();
        long t0 = System.currentTimeMillis();

        if (mode == Mode.EMBEDDED) {
            embedded.write(sid, result, timer, pool, dbName);
        } else {
            remote.write(sid, result, timer, pool, dbName);
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
                cntAtoms, cntJoins, oc, cntLineage, WriteHelpers.formatTime(ms));

        writePerfStats(sid, result, timer, dbName,
                cntTables, cntColumns, cntStatements, cntRoutines,
                cntAtoms, cntJoins, oc, cntLineage);
        return sid;
    }

    // ═══════════════════════════════════════════════════════════════
    // Canonical pool
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates the canonical DaliDatabase vertex and returns an empty CanonicalPool.
     * Called once per database batch before the Phase-2 write loop.
     */
    public CanonicalPool ensureCanonicalPool(String dbName) {
        return ensureCanonicalPool(dbName, null, null);
    }

    /**
     * Creates canonical DaliDatabase (and optionally DaliApplication) vertices
     * and wires the BELONGS_TO_APP edge between them.
     *
     * @param dbName   Database name (folder name), must not be null
     * @param appName  Application name for DaliApplication vertex; null = no application layer
     * @param appGeoid Canonical application geoid; null = use appName as geoid
     */
    public CanonicalPool ensureCanonicalPool(String dbName, String appName, String appGeoid) {
        return mode == Mode.EMBEDDED
                ? embedded.ensurePool(dbName, appName, appGeoid)
                : remote.ensurePool(dbName, appName, appGeoid);
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
                if ("Обработано".equals(status))         atomResolvedTmp++;
                else if ("constant".equals(status))      atomConstTmp++;
                else if ("function_call".equals(status)) atomFuncTmp++;
                else                                     atomFailedTmp++;
            }
        }
        final int atomResolved = atomResolvedTmp;
        final int atomConst    = atomConstTmp;
        final int atomFunc     = atomFuncTmp;
        final int atomFailed   = atomFailedTmp;

        if (mode == Mode.EMBEDDED) {
            embedded.writePerfStats(sid, result, timer, dbName,
                    cntTables, cntColumns, cntStatements, cntRoutines,
                    cntAtoms, cntJoins, cntOutputCols, cntLineage,
                    atomResolved, atomConst, atomFunc, atomFailed);
        } else {
            remote.writePerfStats(sid, result, timer, dbName,
                    cntTables, cntColumns, cntStatements, cntRoutines,
                    cntAtoms, cntJoins, cntOutputCols, cntLineage,
                    atomResolved, atomConst, atomFunc, atomFailed);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // cleanAll — wipe all Dali records from the database
    // ═══════════════════════════════════════════════════════════════

    public void cleanAll() {
        String[] edgeTypes = {
                // atom resolution
                "ATOM_REF_TABLE","ATOM_REF_COLUMN","ATOM_REF_STMT","ATOM_REF_OUTPUT_COL","ATOM_PRODUCES",
                // data flow
                "DATA_FLOW","FILTER_FLOW","JOIN_FLOW","UNION_FLOW",
                // join sources
                "JOIN_SOURCE_TABLE","JOIN_TARGET_TABLE",
                // affected columns
                "HAS_AFFECTED_COL","AFFECTED_COL_REF_TABLE",
                // statement structure
                "HAS_ATOM","HAS_OUTPUT_COL","HAS_JOIN","READS_FROM","WRITES_TO",
                "USES_SUBQUERY","NESTED_IN","CONTAINS_STMT",
                // routine structure
                "HAS_PARAMETER","HAS_VARIABLE","CHILD_OF","CONTAINS_ROUTINE",
                "ROUTINE_USES_TABLE","CALLS",
                // schema/session structure
                "HAS_COLUMN","CONTAINS_TABLE","CONTAINS_SCHEMA","BELONGS_TO_APP","BELONGS_TO_SESSION"
        };
        String[] vtxTypes = {
                // leaves before roots
                "DaliAffectedColumn","DaliAtom","DaliOutputColumn","DaliJoin",
                "DaliParameter","DaliVariable","DaliStatement",
                "DaliColumn","DaliPackage","DaliRoutine","DaliTable",
                "DaliSchema","DaliSession","DaliDatabase",
                "DaliApplication"
        };
        String[] docTypes = {
                "DaliSnippet","DaliSnippetScript","DaliResolutionLog","DaliSchemaLog"
                // DaliPerfStats intentionally excluded — preserved across cleanAll() for stats review
        };

        if (mode == Mode.EMBEDDED) {
            embedded.cleanAll(edgeTypes, vtxTypes, docTypes);
        } else {
            remote.cleanAll(edgeTypes, vtxTypes, docTypes);
        }
        logger.info("ArcadeDB CLEAN: all Dali records deleted ({})", mode);
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void close() {
        if (embeddedDb != null && embeddedDb.isOpen()) {
            embeddedDb.close();
            logger.info("ArcadeDB EMBEDDED closed");
        }
    }
}
