package com.hound.semantic.engine;

import com.hound.semantic.model.SemanticResult;

/**
 * UniversalSemanticEngine — главный оркестратор семантического анализа.
 * Аналог Python BaseSQLListener — центральная точка всех семантических событий.
 */
public class UniversalSemanticEngine {

    private final ScopeManager scopeManager;
    private final NameResolver nameResolver;
    private final StructureAndLineageBuilder builder;
    private final AtomProcessor atomProcessor;

    public UniversalSemanticEngine() {
        this.scopeManager = new ScopeManager();
        this.nameResolver = new NameResolver();
        this.builder = new StructureAndLineageBuilder();
        this.atomProcessor = new AtomProcessor();
    }

    // ====================== Statement ======================

    public void onStatementEnter(String type, String snippet, int lineStart, int lineEnd) {
        ScopeContext ctx = ScopeContext.forStatement(type, snippet, lineStart, lineEnd);
        scopeManager.push(ctx);
    }

    public void onStatementExit() {
        String stmt = scopeManager.currentStatement();
        if (stmt != null) {
            atomProcessor.resolveAtomsOnStatementExit(stmt);
        }
        scopeManager.pop();
    }

    // ====================== Table ======================

    public void onTableReference(String tableName, int line, int endLine) {
        if (tableName == null || tableName.isBlank()) return;
        String schemaGeoid = scopeManager.currentSchema();
        String tableGeoid = builder.ensureTable(tableName, schemaGeoid);
        scopeManager.registerAlias(tableName, tableGeoid);
    }

    // ====================== Column ======================

    /**
     * Вызывается из BaseSemanticListener с позицией (новая сигнатура).
     */
    public void onColumnRef(String fullRef, int line, int endLine) {
        if (fullRef == null || fullRef.isBlank()) return;
        String currentStmt = scopeManager.currentStatement();
        // Регистрируем как атом с контекстом COLUMN
        atomProcessor.registerAtom(fullRef, line, 0, endLine, 0, "COLUMN", currentStmt, scopeManager.getActiveClause());
    }

    /**
     * Устаревшая версия без позиции — оставлена для обратной совместимости.
     */
    public void onColumnRef(String fullRef) {
        onColumnRef(fullRef, 0, 0);
    }

    // ====================== Join ======================

    public void onJoinStart(String joinText, int line, int endLine) {
        ScopeContext ctx = scopeManager.peek();
        if (ctx != null) {
            ctx.setInJoinContext(true);
        }
    }

    public void onJoinExit() {
        ScopeContext ctx = scopeManager.peek();
        if (ctx != null) {
            ctx.setInJoinContext(false);
        }
    }

    // ====================== CTE ======================

    public void onCTEStart(String cteText, int line, int endLine) {
        ScopeContext ctx = ScopeContext.forStatement("CTE", cteText, line, endLine);
        scopeManager.push(ctx);
    }

    public void onCTEExit() {
        scopeManager.pop();
    }

    // ====================== Subquery ======================

    public void onSubqueryStart(String subqueryText, int line, int endLine) {
        ScopeContext ctx = ScopeContext.forStatement("SUBQUERY", subqueryText, line, endLine);
        scopeManager.push(ctx);
    }

    public void onSubqueryExit() {
        String stmt = scopeManager.currentStatement();
        if (stmt != null) {
            atomProcessor.resolveAtomsOnStatementExit(stmt);
        }
        scopeManager.pop();
    }

    // ====================== From clause ======================

    public void onFromStart() {
        // Маркер — сейчас обрабатываем FROM-клозу; можно расширить через ScopeContext
    }

    public void onFromExit() {
        // Сброс маркера FROM
    }

    // ====================== Select list ======================

    public void onSelectListItem(String itemText, int line, int endLine) {
        if (itemText == null || itemText.isBlank()) return;
        String currentStmt = scopeManager.currentStatement();
        atomProcessor.registerAtom(itemText, line, 0, endLine, 0, "SELECT_LIST", currentStmt, "SELECT");
    }

    // ====================== Atom ======================

    public void onAtom(String text, int line, int col, int endLine, int endCol, String context) {
        String currentStmt = scopeManager.currentStatement();
        atomProcessor.registerAtom(text, line, col, endLine, endCol, context, currentStmt, scopeManager.getActiveClause());
    }

    // ====================== Result & Lifecycle ======================

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

    public void clear() {
        scopeManager.clear();
        nameResolver.clearCache();
        builder.clear();
        atomProcessor.clear();
    }
}
