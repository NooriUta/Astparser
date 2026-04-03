package com.hound.semantic.engine;

import com.hound.semantic.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map;

/**
 * UniversalSemanticEngine — главный оркестратор семантического анализа.
 * Аналог Python BaseSQLListener — центральная точка всех семантических событий.
 *
 * Все компоненты связываются через wire() при конструировании.
 */
public class UniversalSemanticEngine {

    private static final Logger logger = LoggerFactory.getLogger(UniversalSemanticEngine.class);

    private final ScopeManager scopeManager;
    private final NameResolver nameResolver;
    private final StructureAndLineageBuilder builder;
    private final AtomProcessor atomProcessor;
    private final JoinProcessor joinProcessor;

    public UniversalSemanticEngine() {
        this.scopeManager = new ScopeManager();
        this.nameResolver = new NameResolver();
        this.builder = new StructureAndLineageBuilder();
        this.atomProcessor = new AtomProcessor();
        this.joinProcessor = new JoinProcessor();

        // Wire dependencies
        this.nameResolver.wire(builder, scopeManager);
        this.atomProcessor.wire(nameResolver, builder, scopeManager);
    }

    // ═══════ Accessors for BaseSemanticListener / DialectAdapter ═══════

    public ScopeManager getScopeManager() { return scopeManager; }
    public NameResolver getNameResolver() { return nameResolver; }
    public StructureAndLineageBuilder getBuilder() { return builder; }
    public AtomProcessor getAtomProcessor() { return atomProcessor; }
    public JoinProcessor getJoinProcessor() { return joinProcessor; }

    // ═══════════════════════════════════════════════════════════════
    // Statement lifecycle
    // ═══════════════════════════════════════════════════════════════

    public void onStatementEnter(String type, String snippet, int lineStart, int lineEnd, String alias) {
        String parentStmt = scopeManager.currentStatement();
        String routineGeoid = scopeManager.currentRoutine();

        ScopeContext ctx = ScopeContext.forStatement(type, snippet, lineStart, lineEnd,
                parentStmt, routineGeoid, alias);

        // Register alias on PARENT scope so atoms in the parent statement can resolve it
        // (e.g., MERGE USING subquery aliases SOURCE/TARGET)
        if (alias != null && !alias.isBlank()) {
            ScopeContext parentScope = scopeManager.peek();
            if (parentScope != null) {
                parentScope.registerAlias(alias.toUpperCase(), ctx.getStatementGeoid());
            }
        }

        scopeManager.push(ctx);

        builder.addStatement(ctx.getStatementGeoid(), type, snippet, lineStart, lineEnd,
                parentStmt, routineGeoid);

        if (parentStmt != null) {
            var parentInfo = builder.getStatements().get(parentStmt);
            if (parentInfo != null) {
                parentInfo.addChildStatement(ctx.getStatementGeoid());
            }
        }

        logger.debug("Statement ENTER: {} [{}] line {}-{}, parent={}",
                type, ctx.getStatementGeoid(), lineStart, lineEnd, parentStmt);
    }

    public void onStatementExit() {
        String stmt = scopeManager.currentStatement();
        if (stmt != null) {
            StatementInfo si = builder.getStatements().get(stmt);

            // B2.OC1 — diagnostics
            if (si != null) {
                logger.info("DIAG EXIT [{}] type={} outputCols={} atoms={}",
                        stmt, si.getType(), si.getColumnsOutput().size(),
                        atomProcessor.getAtomsForStatement(stmt).size());
            }

            // 1. Resolve star subquery columns
            resolveStarSubqueryColumnsForStmt(stmt);
            // 2. Atoms resolution
            atomProcessor.resolveAtomsOnStatementExit(stmt);
            // 3. Post-process joins (check unresolved sources)
            postProcessJoins(stmt);
            // 4. UNION column merging
            ScopeContext current = scopeManager.peek();
            if (current != null && current.isUnion()) {
                mergeUnionColumns(stmt);
            }

            // B2.SQ1+SQ2 — subquery source registration in parent
            if (si != null && !si.getSourceSubqueries().isEmpty()) {
                logger.info("DIAG SUBQUERY [{}]: {} sources", stmt, si.getSourceSubqueries().size());
            }
            if (current != null) {
                String type = current.getStatementType();
                if ("SUBQUERY".equals(type) || "CTE".equals(type) || "USUBQUERY".equals(type)) {
                    String parentStmt = current.getParentStatement();
                    if (parentStmt != null) {
                        StatementInfo parentInfo = builder.getStatements().get(parentStmt);
                        if (parentInfo != null) {
                            String alias = current.getAlias();
                            parentInfo.addSourceSubquery(stmt, alias, type);
                        }
                    }
                }
            }
        }
        ScopeContext popped = scopeManager.pop();
        logger.debug("Statement EXIT: {} [{}]",
                popped != null ? popped.getStatementType() : "?",
                popped != null ? popped.getStatementGeoid() : "?");
    }

    // ═══════════════════════════════════════════════════════════════
    // Table references
    // ═══════════════════════════════════════════════════════════════

    /**
     * Вызывается из Dialect Listener при обнаружении ссылки на таблицу.
     * @param tableName имя таблицы (может быть schema.table)
     * @param alias алиас (nullable)
     * @param role "SOURCE" или "TARGET"
     * @param line строка в SQL
     * @param endLine конечная строка
     */
    public void onTableReference(String tableName, String alias, String role,
                                 int line, int endLine) {
        if (tableName == null || tableName.isBlank()) return;

        String schemaGeoid = scopeManager.currentSchema();
        String tableGeoid = builder.ensureTable(tableName, schemaGeoid);

        // Register alias
        if (alias != null && !alias.isBlank()) {
            scopeManager.registerAlias(alias.toUpperCase(), tableGeoid);
            builder.addTableAlias(tableGeoid, alias);
        }
        // Also register table name as alias
        scopeManager.registerAlias(tableName.toUpperCase(), tableGeoid);

        // Source/Target в текущем statement
        String currentStmt = scopeManager.currentStatement();
        if (currentStmt != null) {
            var stmtInfo = builder.getStatements().get(currentStmt);
            if (stmtInfo != null) {
                TableInfo tbl = builder.getTables().get(tableGeoid);
                String tblName = tbl != null ? tbl.tableName() : tableName;
                String tblType = tbl != null ? tbl.tableType() : "table";
                if ("TARGET".equals(role)) {
                    stmtInfo.addTargetTable(tableGeoid, alias, tblName, tblType);
                    scopeManager.peek().setTargetTable(tableGeoid);
                    builder.addLineageEdge(currentStmt, tableGeoid, "WRITES_TO", currentStmt);
                } else {
                    stmtInfo.addSourceTable(tableGeoid, alias, tblName, tblType);
                    builder.addLineageEdge(tableGeoid, currentStmt, "READS_FROM", currentStmt);
                }
            }
        }

        logger.debug("Table REF: {} alias={} role={} stmt={}", tableName, alias, role, currentStmt);
    }

    /** Backward-compatible version without role */
    public void onTableReference(String tableName, int line, int endLine) {
        onTableReference(tableName, null, "SOURCE", line, endLine);
    }

    /**
     * MERGE INTO (SELECT * FROM target_table) msubquery —
     * регистрирует алиас подзапроса (msubquery) → geoid целевой таблицы
     * на РОДИТЕЛЬСКОМ scope (MERGE), чтобы msubquery.column резолвился в target_table.column.
     */
    public void registerMergeIntoSubqueryAlias(String subqueryAlias, String tableName) {
        String schemaGeoid = scopeManager.currentSchema();
        String tableGeoid = builder.ensureTable(tableName, schemaGeoid);
        scopeManager.registerAliasOnParent(subqueryAlias.toUpperCase(), tableGeoid);
        logger.debug("MERGE INTO subquery alias: {} → {} (table {})", subqueryAlias, tableGeoid, tableName);
    }

    // ═══════════════════════════════════════════════════════════════
    // Column references
    // ═══════════════════════════════════════════════════════════════

    public void onColumnRef(String fullRef, int line, int endLine) {
        if (fullRef == null || fullRef.isBlank()) return;

        String currentStmt = scopeManager.currentStatement();
        String parentContext = scopeManager.getActiveClause();

        // Parse "table.column" or "column"
        String[] parts = fullRef.split("\\.", 2);
        String tablePart = parts.length > 1 ? parts[0] : null;
        String columnPart = parts.length > 1 ? parts[1] : parts[0];

        // Resolve table
        String tableGeoid = null;
        if (tablePart != null) {
            ResolvedRef resolved = nameResolver.resolve(tablePart, "any", currentStmt);
            if (resolved.isResolved()) {
                tableGeoid = resolved.getGeoid();
            }
        } else {
            ResolvedRef implicit = nameResolver.resolveImplicitTable(currentStmt);
            if (implicit.isResolved()) {
                tableGeoid = implicit.getGeoid();
            }
        }

        // Add column to structure + usage tracking
        if (tableGeoid != null) {
            builder.addColumn(tableGeoid, columnPart, null, null);
            String colGeoid = tableGeoid + "." + columnPart.toUpperCase();
            ColumnInfo colInfo = builder.getColumns().get(colGeoid);
            if (colInfo != null) {
                colInfo.addUsedInStatement(currentStmt);
                colInfo.addUsedInRoutine(scopeManager.currentRoutine());
            }
        }

        // Register as atom
        atomProcessor.registerAtom(fullRef, line, 0, endLine, 0,
                "COLUMN", currentStmt, parentContext);
    }

    public void onColumnRef(String fullRef) {
        onColumnRef(fullRef, 0, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // JOIN
    // ═══════════════════════════════════════════════════════════════

    public void onJoinStart(String joinText, int line, int endLine) {
        ScopeContext ctx = scopeManager.peek();
        if (ctx != null) {
            ctx.setInJoinContext(true);
        }
    }

    /**
     * Регистрирует JOIN-операцию с полной информацией.
     */
    public void onJoinComplete(String joinType, String targetTableGeoid, String targetAlias,
                               String targetType, String sourceTableGeoid, String sourceAlias,
                               String sourceType, String conditions, int lineStart) {
        String currentStmt = scopeManager.currentStatement();
        JoinInfo joinInfo = new JoinInfo(
                joinType, targetTableGeoid, targetAlias, targetType,
                sourceTableGeoid, sourceAlias, sourceType,
                conditions, currentStmt, lineStart
        );
        joinProcessor.registerJoin(currentStmt, joinInfo);

        // Also register in statement info
        var stmtInfo = builder.getStatements().get(currentStmt);
        if (stmtInfo != null) {
            stmtInfo.addJoin(joinInfo);
        }
    }

    public void onJoinExit() {
        ScopeContext ctx = scopeManager.peek();
        if (ctx != null) {
            ctx.setInJoinContext(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Routine lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Вход в routine. Geoid формируется через цепочку:
     *   Package (контейнер):   "PKG_NAME"
     *   В пакете:              "PKG_NAME:PROCEDURE:HELLO"
     *   Вложенная:             "PKG_NAME:PROCEDURE:OUTER:FUNCTION:INNER"
     *   Без пакета:            "PROCEDURE:STANDALONE"
     */
    public void onRoutineEnter(String name, String routineType, String schemaGeoid,
                               String packageGeoid, int lineStart) {
        if (packageGeoid != null && !packageGeoid.isBlank()) {
            builder.ensurePackage(packageGeoid, schemaGeoid);
        }
        // currentRoutine() = geoid parent routine (для вложенности)
        String parentRoutine = scopeManager.currentRoutine();
        String geoid = builder.addRoutine(name, routineType, schemaGeoid, packageGeoid,
                lineStart, parentRoutine);

        // Push routine scope so nested statements inherit routineGeoid
        ScopeContext routineScope = ScopeContext.forRoutine(geoid);
        scopeManager.push(routineScope);

        logger.debug("Routine ENTER: {} {} [{}] parentRoutine={}",
                routineType, name, geoid, parentRoutine);
    }

    public void onRoutineExit() {
        // Pop the routine scope
        ScopeContext popped = scopeManager.pop();
        logger.debug("Routine EXIT: {}",
                popped != null ? popped.getRoutineGeoid() : "?");
    }

    public void onRoutineParameter(String name, String type, String mode) {
        String routine = scopeManager.currentRoutine();
        if (routine != null) {
            RoutineInfo ri = builder.getRoutines().get(routine);
            if (ri != null) ri.addTypedParameter(name, type, mode);
        }
    }

    public void onRoutineVariable(String name, String type) {
        String routine = scopeManager.currentRoutine();
        if (routine != null) {
            RoutineInfo ri = builder.getRoutines().get(routine);
            if (ri != null) ri.addTypedVariable(name, type);
        }
    }

    public void onRoutineReturnType(String returnType) {
        String routine = scopeManager.currentRoutine();
        if (routine != null) {
            RoutineInfo ri = builder.getRoutines().get(routine);
            if (ri != null) ri.setReturnType(returnType);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CTE
    // ═══════════════════════════════════════════════════════════════

    public void onCTEStart(String cteName, String cteText, int line, int endLine) {
        onStatementEnter("CTE", cteText, line, endLine, cteName);
    }

    public void onCTEExit() {
        onStatementExit();
    }

    // ═══════════════════════════════════════════════════════════════
    // Subquery
    // ═══════════════════════════════════════════════════════════════

    public void onSubqueryStart(String subqueryAlias, String subqueryText, int line, int endLine) {
        onStatementEnter("SUBQUERY", subqueryText, line, endLine, subqueryAlias);
    }

    public void onSubqueryExit() {
        onStatementExit();
    }

    // ═══════════════════════════════════════════════════════════════
    // Clauses (FROM, WHERE, HAVING, GROUP BY, ORDER BY)
    // ═══════════════════════════════════════════════════════════════

    public void onFromStart()  { /* marker for scope */ }
    public void onFromExit()   { /* reset marker */ }

    // ═══════════════════════════════════════════════════════════════
    // Atom
    // ═══════════════════════════════════════════════════════════════

    public void onAtom(String text, int line, int col, int endLine, int endCol, String context,
                       boolean isComplex, List<String> tokens,
                       List<Map<String, String>> tokenDetails, int nestedAtomCount) {
        String currentStmt = scopeManager.currentStatement();
        String parentContext = scopeManager.getActiveClause();
        atomProcessor.registerAtom(text, line, col, endLine, endCol, context, currentStmt, parentContext,
                isComplex, tokens, tokenDetails, nestedAtomCount);
    }

    // ═══════════════════════════════════════════════════════════════
    // Select list items
    // ═══════════════════════════════════════════════════════════════

    public void onSelectListItem(String itemText, int line, int endLine) {
        if (itemText == null || itemText.isBlank()) return;
        String currentStmt = scopeManager.currentStatement();
        atomProcessor.registerAtom(itemText, line, 0, endLine, 0,
                "SELECT_LIST", currentStmt, "SELECT");
    }

    // ═══════════════════════════════════════════════════════════════
    // DML target
    // ═══════════════════════════════════════════════════════════════

    public boolean isInDmlTarget() {
        return scopeManager.isInDmlTarget();
    }

    public String getCurrentParentContext() {
        return scopeManager.getActiveClause();
    }

    // ═══════════════════════════════════════════════════════════════
    // Result
    // ═══════════════════════════════════════════════════════════════

    public SemanticResult getResult() {
        return new SemanticResult(
                "session-" + System.currentTimeMillis(),
                "unknown-file.sql",
                "plsql",
                0,
                builder.getStructure(),
                builder.getLineageEdges(),
                atomProcessor.getAtoms()
        );
    }

    public SemanticResult getResult(String sessionId, String filePath, String dialect, long processingTimeMs) {
        return new SemanticResult(sessionId, filePath, dialect, processingTimeMs,
                builder.getStructure(), builder.getLineageEdges(), atomProcessor.getAtoms());
    }

    // ═══════════════════════════════════════════════════════════════
    // Post-walk resolution
    // ═══════════════════════════════════════════════════════════════

    /** Вызывается из onStatementExit для текущего statement */
    private void resolveStarSubqueryColumnsForStmt(String stmtGeoid) {
        StatementInfo si = builder.getStatements().get(stmtGeoid);
        if (si != null) resolveStarSubqueryColumns(stmtGeoid, si);
    }

    private void postProcessJoins(String stmtGeoid) {
        StatementInfo si = builder.getStatements().get(stmtGeoid);
        if (si == null) return;
        for (JoinInfo j : si.getJoins()) {
            if (j.sourceTableGeoid() == null && j.sourceTableAlias() != null) {
                ResolvedRef ref = nameResolver.resolve(j.sourceTableAlias(), "any", stmtGeoid);
                if (ref.isResolved()) {
                    logger.debug("Post-resolved JOIN source: {} → {}", j.sourceTableAlias(), ref.getGeoid());
                }
            }
        }
    }

    private void mergeUnionColumns(String stmtGeoid) {
        StatementInfo si = builder.getStatements().get(stmtGeoid);
        if (si == null) return;
        for (String childGeoid : si.getChildStatements()) {
            StatementInfo child = builder.getStatements().get(childGeoid);
            if (child != null && child.getColumnsOutput() != null) {
                for (var ce : child.getColumnsOutput().entrySet()) {
                    si.getColumnsOutput().putIfAbsent(ce.getKey(), ce.getValue());
                }
            }
        }
    }

    /**
     * Порт Python: resolve_pending_columns()
     * Вызывается ПОСЛЕ полного walk AST.
     * Разрешает columns, которые не могли быть resolved inline.
     */
    private final List<Map<String, String>> pendingColumns = new ArrayList<>();

    public void addPendingColumn(String columnRef, String statementGeoid) {
        pendingColumns.add(Map.of("ref", columnRef, "stmt", statementGeoid));
    }

    public void resolvePendingColumns() {
        // 1. Star subquery columns
        for (var entry : builder.getStatements().entrySet()) {
            resolveStarSubqueryColumns(entry.getKey(), entry.getValue());
        }
        // 2. Pending column refs
        logger.debug("Resolving {} pending columns", pendingColumns.size());
        var resolved = new ArrayList<Integer>();
        for (int i = 0; i < pendingColumns.size(); i++) {
            var p = pendingColumns.get(i);
            String[] parts = p.get("ref").split("\\.", 2);
            String tPart = parts.length > 1 ? parts[0] : null;
            String cPart = parts.length > 1 ? parts[1] : parts[0];
            String tGeoid = null;
            if (tPart != null) {
                var ref = nameResolver.resolve(tPart, "any", p.get("stmt"));
                if (ref.isResolved()) tGeoid = ref.getGeoid();
            } else {
                var ref = nameResolver.resolveImplicitTable(p.get("stmt"));
                if (ref.isResolved()) tGeoid = ref.getGeoid();
            }
            if (tGeoid != null) {
                builder.addColumn(tGeoid, cPart, null, null);
                resolved.add(i);
            }
        }
        for (int i = resolved.size() - 1; i >= 0; i--) pendingColumns.remove((int) resolved.get(i));
        logger.debug("Pending remaining: {}", pendingColumns.size());
    }

    /**
     * Порт Python: resolve_star_subquery_columns()
     * Восстановление columns из подзапросов при SELECT *.
     */
    private void resolveStarSubqueryColumns(String stmtGeoid, StatementInfo stmtInfo) {
        var outputCols = stmtInfo.getColumnsOutput();
        if (outputCols == null || outputCols.isEmpty()) return;

        for (var colEntry : new ArrayList<>(outputCols.entrySet())) {
            Map<String, Object> colInfo = colEntry.getValue();
            String colName = (String) colInfo.get("name");
            if (!"*".equals(colName)) continue;

            // SELECT * — берём columns из source tables/subqueries
            for (String sourceGeoid : stmtInfo.getSourceTableGeoids()) {
                var sourceStmt = builder.getStatements().get(sourceGeoid);
                if (sourceStmt != null && sourceStmt.getColumnsOutput() != null) {
                    for (var srcCol : sourceStmt.getColumnsOutput().entrySet()) {
                        String newColName = srcCol.getKey();
                        if (!outputCols.containsKey(newColName)) {
                            Map<String, Object> newCol = new LinkedHashMap<>(srcCol.getValue());
                            newCol.put("source_type", "subquery_star");
                            newCol.put("source_subquery", sourceGeoid);
                            outputCols.put(newColName, newCol);
                        }
                    }
                }
            }
        }
    }

    public void clear() {
        scopeManager.clear();
        nameResolver.clearCache();
        builder.clear();
        atomProcessor.clear();
        joinProcessor.clear();
    }
}