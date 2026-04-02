// src/main/java/com/hound/semantic/dialect/plsql/PlSqlSemanticListener.java
package com.hound.semantic.dialect.plsql;

import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParserBaseListener;
import com.hound.semantic.listener.BaseSemanticListener;
import com.hound.semantic.engine.UniversalSemanticEngine;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * PlSqlSemanticListener — точный перенос логики из Python PlSqlAnalyzerListener.
 */
public class PlSqlSemanticListener extends PlSqlParserBaseListener {

    private final BaseSemanticListener base;

    public PlSqlSemanticListener(UniversalSemanticEngine engine) {
        this.base = new BaseSemanticListener(engine) {};
    }

    // ====================== PL/SQL-специфичные правила ======================

    @Override
    public void enterSelect_statement(PlSqlParser.Select_statementContext ctx) {
        base.onStatementEnter("SELECT", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitSelect_statement(PlSqlParser.Select_statementContext ctx) {
        base.onStatementExit();
    }

    @Override
    public void enterInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        base.onStatementEnter("INSERT", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        base.onStatementExit();
    }

    @Override
    public void enterUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        base.onStatementEnter("UPDATE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        base.onStatementExit();
    }

    @Override
    public void enterDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        base.onStatementEnter("DELETE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        base.onStatementExit();
    }

    @Override
    public void enterMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        base.onStatementEnter("MERGE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        base.onStatementExit();
    }

    @Override
    public void enterWith_clause(PlSqlParser.With_clauseContext ctx) {
        base.onCTEStart(extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitWith_clause(PlSqlParser.With_clauseContext ctx) {
        base.onCTEExit();
    }

    @Override
    public void enterFrom_clause(PlSqlParser.From_clauseContext ctx) {
        base.onFromStart();
    }

    @Override
    public void exitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        base.onFromExit();
    }

    @Override
    public void enterTable_ref_aux_internal(PlSqlParser.Table_ref_aux_internalContext ctx) {
        if (ctx != null && ctx.getText() != null) {
            base.onTableReference(ctx.getText(), getStartLine(ctx), getEndLine(ctx));
        }
    }

    @Override
    public void enterJoin_clause(PlSqlParser.Join_clauseContext ctx) {
        base.onJoinStart(extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitJoin_clause(PlSqlParser.Join_clauseContext ctx) {
        base.onJoinExit();
    }

    @Override
    public void enterSubquery(PlSqlParser.SubqueryContext ctx) {
        base.onSubqueryStart(extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitSubquery(PlSqlParser.SubqueryContext ctx) {
        base.onSubqueryExit();
    }

    @Override
    public void enterColumn_name(PlSqlParser.Column_nameContext ctx) {
        if (ctx != null && ctx.getText() != null) {
            base.onColumnRef(ctx.getText(), getStartLine(ctx), getEndLine(ctx));
        }
    }

    @Override
    public void enterAtom(PlSqlParser.AtomContext ctx) {
        if (ctx == null) return;
        base.onAtom(
                ctx.getText() != null ? ctx.getText() : "",
                getStartLine(ctx),
                getStartColumn(ctx),
                getEndLine(ctx),
                getEndColumn(ctx),
                "ATOM"
        );
    }

    // ====================== Helpers ======================

    private String extract(ParserRuleContext ctx) {
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