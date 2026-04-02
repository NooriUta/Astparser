// src/main/java/com/hound/semantic/listener/BaseSemanticListener.java
package com.hound.semantic.listener;

import com.hound.semantic.engine.UniversalSemanticEngine;

/**
 * BaseSemanticListener — универсальная база (аналог BaseSQLListener в Python).
 * Содержит все общие методы, которые будут вызываться из диалект-специфичных listener'ов.
 */
public abstract class BaseSemanticListener {

    protected final UniversalSemanticEngine engine;

    protected BaseSemanticListener(UniversalSemanticEngine engine) {
        this.engine = engine;
    }

    // ====================== Общие события (вызываются из всех диалектов) ======================

    protected void onStatementEnter(String type, String snippet, int lineStart, int lineEnd) {
        engine.onStatementEnter(type, snippet, lineStart, lineEnd);
    }

    protected void onStatementExit() {
        engine.onStatementExit();
    }

    protected void onTableReference(String tableName, int line, int endLine) {
        engine.onTableReference(tableName, line, endLine);
    }

    protected void onColumnRef(String columnName, int line, int endLine) {
        engine.onColumnRef(columnName, line, endLine);
    }

    protected void onJoinStart(String joinText, int line, int endLine) {
        engine.onJoinStart(joinText, line, endLine);
    }

    protected void onJoinExit() {
        engine.onJoinExit();
    }

    protected void onCTEStart(String cteText, int line, int endLine) {
        engine.onCTEStart(cteText, line, endLine);
    }

    protected void onCTEExit() {
        engine.onCTEExit();
    }

    protected void onSubqueryStart(String subqueryText, int line, int endLine) {
        engine.onSubqueryStart(subqueryText, line, endLine);
    }

    protected void onSubqueryExit() {
        engine.onSubqueryExit();
    }

    protected void onFromStart() {
        engine.onFromStart();
    }

    protected void onFromExit() {
        engine.onFromExit();
    }

    protected void onSelectListItem(String itemText, int line, int endLine) {
        engine.onSelectListItem(itemText, line, endLine);
    }

    protected void onAtom(String text, int line, int col, int endLine, int endCol, String context) {
        engine.onAtom(text, line, col, endLine, endCol, context);
    }
}