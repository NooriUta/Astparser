// src/main/java/com/hound/semantic/dialect/plsql/PlSqlSemanticListener.java
package com.hound.semantic.dialect.plsql;

import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParserBaseListener;
import com.hound.semantic.engine.UniversalSemanticEngine;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

/**
 * PlSqlSemanticListener — маппит события ANTLR парсера PL/SQL в UniversalSemanticEngine.
 */
public class PlSqlSemanticListener extends PlSqlParserBaseListener {

    private final UniversalSemanticEngine engine;

    public PlSqlSemanticListener(UniversalSemanticEngine engine) {
        this.engine = engine;
    }

    // ====================== DML Statements ======================

    @Override
    public void enterData_manipulation_language_statements(PlSqlParser.Data_manipulation_language_statementsContext ctx) {
        if (ctx == null) return;

        String type = detectDmlType(ctx);
        String snippet = extractSnippet(ctx);

        int startLine = getStartLine(ctx);
        int endLine = getEndLine(ctx);

        engine.onStatementEnter(type, snippet, startLine, endLine);
    }

    @Override
    public void exitData_manipulation_language_statements(PlSqlParser.Data_manipulation_language_statementsContext ctx) {
        engine.onStatementExit();
    }

    // ====================== Column References ======================

    @Override
    public void enterColumn_name(PlSqlParser.Column_nameContext ctx) {
        if (ctx == null || ctx.getText() == null) return;
        engine.onColumnRef(ctx.getText());
    }

    // ====================== Atoms ======================

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

    // ====================== Helpers ======================

    private String detectDmlType(PlSqlParser.Data_manipulation_language_statementsContext ctx) {
        if (ctx == null) return "UNKNOWN";

        if (ctx.select_statement() != null) return "SELECT";
        if (ctx.insert_statement() != null) return "INSERT";
        if (ctx.update_statement() != null) return "UPDATE";
        if (ctx.delete_statement() != null) return "DELETE";
        if (ctx.merge_statement() != null) return "MERGE";

        return "UNKNOWN";
    }

    private String extractSnippet(ParserRuleContext ctx) {
        if (ctx == null) return "";
        try {
            return ctx.getText();
        } catch (Exception e) {
            return "";
        }
    }

    private int getStartLine(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) return 0;
        return ctx.getStart().getLine();
    }

    private int getEndLine(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStop() == null) return getStartLine(ctx);
        return ctx.getStop().getLine();
    }

    private int getStartColumn(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) return 0;
        return ctx.getStart().getCharPositionInLine();
    }

    private int getEndColumn(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStop() == null) return getStartColumn(ctx);
        return ctx.getStop().getCharPositionInLine();
    }
}