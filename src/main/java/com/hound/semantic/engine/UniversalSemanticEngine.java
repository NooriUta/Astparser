// src/main/java/com/hound/semantic/engine/UniversalSemanticEngine.java
package com.hound.semantic.engine;

import com.hound.semantic.model.SemanticResult;
import com.hound.semantic.model.Structure;

/**
 * UniversalSemanticEngine — главный оркестратор семантического анализа
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

    public void onColumnRef(String fullRef) {
        // Пока заглушка — будет расширена
        System.out.println("[Semantic] Column ref: " + fullRef);
    }

    public void onAtom(String text, int line, int col, int endLine, int endCol, String context) {
        String currentStmt = scopeManager.currentStatement();
        atomProcessor.registerAtom(text, line, col, endLine, endCol, context, currentStmt, scopeManager.getActiveClause());
    }

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
        scopeManager = new ScopeManager(); // пересоздаём
        nameResolver.clearCache();
        builder.clear();
        atomProcessor.clear();
    }
}