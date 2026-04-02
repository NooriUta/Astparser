// src/main/java/com/hound/semantic/dialect/plsql/PlSqlSemanticListener.java
package com.hound.semantic.dialect.plsql;

import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParserBaseListener;
import com.hound.semantic.engine.UniversalSemanticEngine;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Улучшенный PlSqlSemanticListener.
 * Обрабатывает: SELECT, FROM, JOIN, CTE, subqueries, column references, atoms.
 */
public class PlSqlSemanticListener extends PlSqlParserBaseListener {

    private final UniversalSemanticEngine engine;

    public PlSqlSemanticListener(UniversalSemanticEngine engine) {
        this.engine = engine;
    }

    // ====================== TOP-LEVEL ======================

    @Override
    public void enterSelect_statement(PlSqlParser.Select_statementContext ctx) {
        if (ctx == null) return;
        engine.onStatementEnter("SELECT", extractSnippet(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitSelect_statement(PlSqlParser.Select_statementContext ctx) {
        engine.onStatementExit();
    }

    // ====================== FROM & TABLE REFERENCES ======================

    @Override
    public void enterFrom_clause(PlSqlParser.From_clauseContext ctx) {
        if (ctx == null) return;
        // Можно отметить начало FROM-блока
        engine.onStatementEnter("FROM", extractSnippet(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        engine.onStatementExit();
    }

    @Override
    public void enterTable_ref_aux_internal(PlSqlParser.Table_ref_aux_internalContext ctx) {
        if (ctx == null || ctx.getText() == null) return;
        engine.onColumnRef(ctx.getText()); // временно используем как table ref
    }

    // ====================== JOIN ======================

    @Override
    public void enterJoin_clause(PlSqlParser.Join_clauseContext ctx) {
        if (ctx == null) return;
        engine.onStatementEnter("JOIN", extractSnippet(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitJoin_clause(PlSqlParser.Join_clauseContext ctx) {
        engine.onStatementExit();
    }

    // ====================== WITH / CTE ======================

    @Override
    public void enterWith_clause(PlSqlParser.With_clauseContext ctx) {
        if (ctx == null) return;
        engine.onStatementEnter("WITH", extractSnippet(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitWith_clause(PlSqlParser.With_clauseContext ctx) {
        engine.onStatementExit();
    }

    // ====================== SUBQUERY ======================

    @Override
    public void enterSubquery(PlSqlParser.SubqueryContext ctx) {
        if (ctx == null) return;
        engine.onStatementEnter("SUBQUERY", extractSnippet(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitSubquery(PlSqlParser.SubqueryContext ctx) {
        engine.onStatementExit();
    }

    // ====================== COLUMN & SELECT LIST ======================

    @Override
    public void enterColumn_name(PlSqlParser.Column_nameContext ctx) {
        if (ctx != null && ctx.getText() != null) {
            engine.onColumnRef(ctx.getText());
        }
    }

    @Override
    public void enterSelect_list_element(PlSqlParser.Select_list_elementContext ctx) {
        if (ctx != null && ctx.getText() != null) {
            // Можно добавить onSelectItem или расширить позже
            engine.onColumnRef(ctx.getText());
        }
    }

    // ====================== ATOM ======================

    @Override
    public void enterAtom(PlSqlParser.AtomContext ctx) {
        if (ctx == null) return;

        String text = ctx.getText() != null ? ctx.getText() : "";
        int startLine = getStartLine(ctx);
        int startCol = getStartColumn(ctx);
        int endLine = getEndLine(ctx);
        int endCol = getEndColumn(ctx);

        engine.onAtom(text, startLine, startCol, endLine, endCol, "ATOM");
    }

    // ====================== HELPERS ======================

    private String extractSnippet(ParserRuleContext ctx) {
        if (ctx == null) return "";
        try {
            return ctx.getText();
        } catch (Exception e) {
            return "";
        }
    }

    private int getStartLine(ParserRuleContext ctx) {
        return (ctx != null && ctx.getStart() != null) ? ctx.getStart().getLine() : 0;
    }

    private int getEndLine(ParserRuleContext ctx) {
        return (ctx != null && ctx.getStop() != null) ? ctx.getStop().getLine() : getStartLine(ctx);
    }

    private int getStartColumn(ParserRuleContext ctx) {
        return (ctx != null && ctx.getStart() != null) ? ctx.getStart().getCharPositionInLine() : 0;
    }

    private int getEndColumn(ParserRuleContext ctx) {
        return (ctx != null && ctx.getStop() != null) ? ctx.getStop().getCharPositionInLine() : getStartColumn(ctx);
    }
}