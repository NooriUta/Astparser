// src/main/java/com/hound/semantic/dialect/plsql/PlSqlSemanticListener.java
package com.hound.semantic.dialect.plsql;

import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParserBaseListener;
import com.hound.semantic.listener.BaseSemanticListener;
import com.hound.semantic.engine.UniversalSemanticEngine;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * PlSqlSemanticListener — Java-аналог Python PlSqlAnalyzerListener.
 *
 * Наследует PlSqlParserBaseListener (ANTLR) и делегирует все события
 * в BaseSemanticListener через поле base (composition).
 *
 * Ключевые принципы:
 * - Все имена методов ANTLR берутся только из реально сгенерированного PlSqlParserBaseListener.
 * - Имена правил грамматики используются как есть, без предположений о суффиксах.
 * - enterTable_ref_aux обрабатывает таблицу напрямую (без внутреннего Internal-вызова).
 */
public class PlSqlSemanticListener extends PlSqlParserBaseListener {

    private final BaseSemanticListener base;

    public PlSqlSemanticListener(UniversalSemanticEngine engine) {
        this.base = new BaseSemanticListener(engine) {};
    }

    // =========================================================================
    // Процедуры и функции
    // =========================================================================

    @Override
    public void enterCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
        String name = extractRoutineName(ctx);
        base.onRoutineEnter(name, "PROCEDURE", base.currentSchema(), null, getStartLine(ctx));
    }

    @Override
    public void exitCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
        base.onRoutineExit();
    }

    @Override
    public void enterProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
        String name = extractRoutineName(ctx);
        base.onRoutineEnter(name, "PROCEDURE",
                base.currentSchema(), base.currentPackage(), getStartLine(ctx));
    }

    @Override
    public void exitProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
        base.onRoutineExit();
    }

    @Override
    public void enterCreate_function_body(PlSqlParser.Create_function_bodyContext ctx) {
        String name = extractRoutineName(ctx);
        base.onRoutineEnter(name, "FUNCTION", base.currentSchema(), null, getStartLine(ctx));
    }

    @Override
    public void exitCreate_function_body(PlSqlParser.Create_function_bodyContext ctx) {
        base.onRoutineExit();
    }

    @Override
    public void enterFunction_body(PlSqlParser.Function_bodyContext ctx) {
        String name = extractRoutineName(ctx);
        base.onRoutineEnter(name, "FUNCTION",
                base.currentSchema(), base.currentPackage(), getStartLine(ctx));
    }

    @Override
    public void exitFunction_body(PlSqlParser.Function_bodyContext ctx) {
        base.onRoutineExit();
    }

    // =========================================================================
    // Пакеты
    // =========================================================================

    @Override
    public void enterCreate_package_body(PlSqlParser.Create_package_bodyContext ctx) {
        if (ctx.package_name() == null) return;
        for (PlSqlParser.Package_nameContext pkgCtx : ctx.package_name()) {
            String packageName = BaseSemanticListener.cleanIdentifier(pkgCtx.getText());
            String schemaGeoid = base.currentSchema();
            String pkgGeoid = base.initPackage(packageName, schemaGeoid);
            base.current.put("package", pkgGeoid);
        }
    }

    @Override
    public void exitCreate_package_body(PlSqlParser.Create_package_bodyContext ctx) {
        base.current.put("package", null);
    }

    // =========================================================================
    // Схема
    // =========================================================================

    @Override
    public void enterSchema_name(PlSqlParser.Schema_nameContext ctx) {
        if (ctx == null || ctx.identifier() == null) return;
        String schemaName = BaseSemanticListener.cleanIdentifier(ctx.identifier().getText());
        base.onSchemaEnter(schemaName);
    }

    @Override
    public void exitSchema_name(PlSqlParser.Schema_nameContext ctx) {
        base.onSchemaExit();
    }

    // =========================================================================
    // DML statements
    // =========================================================================

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
        base.onDmlTargetEnter();
        base.onStatementEnter("INSERT", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        base.onStatementExit();
        base.onDmlTargetExit();
    }

    @Override
    public void enterUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        base.onDmlTargetEnter();
        base.onStatementEnter("UPDATE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        base.onStatementExit();
        base.onDmlTargetExit();
    }

    @Override
    public void enterDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        base.onDmlTargetEnter();
        base.onStatementEnter("DELETE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        base.onStatementExit();
        base.onDmlTargetExit();
    }

    @Override
    public void enterMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        base.onDmlTargetEnter();
        base.onStatementEnter("MERGE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        base.onStatementExit();
        base.onDmlTargetExit();
    }

    // =========================================================================
    // Курсоры
    // =========================================================================

    @Override
    public void enterCursor_declaration(PlSqlParser.Cursor_declarationContext ctx) {
        base.onStatementEnter("CURSOR", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        if (ctx.select_statement() != null) {
            base.current.put("is_first_subq",  ctx.select_statement().start.getLine());
            base.current.put("is_first_subqp", ctx.select_statement().start.getCharPositionInLine());
        }
    }

    @Override
    public void exitCursor_declaration(PlSqlParser.Cursor_declarationContext ctx) {
        base.onStatementExit();
        base.current.put("is_first_subq",  null);
        base.current.put("is_first_subqp", null);
    }

    // =========================================================================
    // CTE / WITH clause
    // =========================================================================

    @Override
    public void enterWith_clause(PlSqlParser.With_clauseContext ctx) {
        base.onCTEEnter(extract(ctx), getStartLine(ctx), getEndLine(ctx));
        base.current.put("is_first_subq",  null);
        base.current.put("is_first_subqp", null);
    }

    @Override
    public void exitWith_clause(PlSqlParser.With_clauseContext ctx) {
        base.onCTEExit();
    }

    // =========================================================================
    // Подзапросы
    // =========================================================================

    @Override
    public void enterSubquery(PlSqlParser.SubqueryContext ctx) {
        if (ctx == null) return;

        Integer firstLine = (Integer) base.current.get("is_first_subq");
        Integer firstCol  = (Integer) base.current.get("is_first_subqp");
        int ctxLine = ctx.start.getLine();
        int ctxCol  = ctx.start.getCharPositionInLine();

        // Пропускаем первый подзапрос (он совпадает с DML/SELECT statement)
        if (firstLine != null && firstLine == ctxLine
                && firstCol != null && firstCol == ctxCol) {
            base.current.put("is_first_subq",  ctxLine);
            base.current.put("is_first_subqp", ctxCol);
            return;
        }

        base.current.put("is_first_subq",  ctxLine);
        base.current.put("is_first_subqp", ctxCol);

        boolean hasUnion = ctx.subquery_operation_part() != null
                && !ctx.subquery_operation_part().isEmpty();
        base.current.put("is_union", hasUnion);

        if (!base.isInDmlTarget()) {
            base.onSubqueryEnter(extract(ctx), getStartLine(ctx), getEndLine(ctx));
        } else {
            // USUBQUERY — подзапрос в DML target (Updatable subquery для MERGE)
            base.initStatement("USUBQUERY", extract(ctx), getStartLine(ctx), getEndLine(ctx), null);
        }
    }

    @Override
    public void exitSubquery(PlSqlParser.SubqueryContext ctx) {
        String stmtType = base.currentStatementType();
        if ("SUBQUERY".equals(stmtType) || "USUBQUERY".equals(stmtType)) {
            base.onSubqueryExit();
        }
    }

    // =========================================================================
    // FROM clause
    // =========================================================================

    @Override
    public void enterFrom_clause(PlSqlParser.From_clauseContext ctx) {
        base.onFromEnter(getStartLine(ctx));
    }

    @Override
    public void exitFrom_clause(PlSqlParser.From_clauseContext ctx) {
        base.onFromExit();
    }

    // =========================================================================
    // Ссылки на таблицы — enterTable_ref_aux (без Internal)
    //
    // Аналог Python enterTable_ref_aux:
    //   - Извлекаем tableview_name из дочерних правил грамматики
    //   - Сами читаем алиас из table_alias
    //   - Управляем subquery_alias_stack
    // =========================================================================

    @Override
    public void enterTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
        if (ctx == null) return;

        String tableAlias = extractAlias(ctx.table_alias());
        String tableName  = null;

        // Пытаемся извлечь имя таблицы из tableview_name через дочерние контексты
        if (ctx.tableview_name() != null) {
            tableName = BaseSemanticListener.cleanIdentifier(ctx.tableview_name().getText());
        }

        if (tableName != null && !tableName.isBlank()) {
            base.onTableReference(tableName, tableAlias, getStartLine(ctx), getEndLine(ctx));
            base.current.put("table", tableName);
        }

        base.current.put("subquery_alias", tableAlias);
        base.subqueryAliasStack().add(tableAlias != null ? tableAlias : "");

        base.logger.debug("enterTable_ref_aux: table={} alias={}", tableName, tableAlias);
    }

    @Override
    public void exitTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
        java.util.List<String> stack = base.subqueryAliasStack();
        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
    }

    // =========================================================================
    // General table ref (DML target)
    // =========================================================================

    @Override
    public void enterGeneral_table_ref(PlSqlParser.General_table_refContext ctx) {
        if (ctx == null) return;
        base.onDmlTargetEnter();

        String tableAlias = extractAlias(ctx.table_alias());
        String tableName  = null;

        if (ctx.dml_table_expression_clause() != null) {
            PlSqlParser.Dml_table_expression_clauseContext dml = ctx.dml_table_expression_clause();
            if (dml.tableview_name() != null) {
                tableName = BaseSemanticListener.cleanIdentifier(dml.tableview_name().getText());
            }
        }

        if (tableName != null && !tableName.isBlank()) {
            base.onTableReference(tableName, tableAlias, getStartLine(ctx), getEndLine(ctx));
            base.current.put("table", tableName);
        }
    }

    @Override
    public void exitGeneral_table_ref(PlSqlParser.General_table_refContext ctx) {
        base.current.put("general_table", null);
        base.onDmlTargetExit();
    }

    // =========================================================================
    // JOIN
    // =========================================================================

    @Override
    public void enterJoin_clause(PlSqlParser.Join_clauseContext ctx) {
        base.onJoinEnter(extract(ctx), getStartLine(ctx), getEndLine(ctx));
    }

    @Override
    public void exitJoin_clause(PlSqlParser.Join_clauseContext ctx) {
        base.onJoinExit();
    }

    // =========================================================================
    // Selected list
    // =========================================================================

    @Override
    public void enterSelected_list(PlSqlParser.Selected_listContext ctx) {
        base.onSelectedListEnter(getStartLine(ctx));
    }

    @Override
    public void exitSelected_list(PlSqlParser.Selected_listContext ctx) {
        base.onSelectedListExit();
    }

    // =========================================================================
    // Column alias
    // =========================================================================

    @Override
    public void enterColumn_alias(PlSqlParser.Column_aliasContext ctx) {
        if (ctx == null) return;
        if (ctx.identifier() != null) {
            base.onColumnAlias(ctx.identifier().getText());
        } else if (ctx.quoted_string() != null) {
            base.onColumnAlias(ctx.quoted_string().getText());
        }
    }

    // =========================================================================
    // Column name
    // =========================================================================

    @Override
    public void enterColumn_name(PlSqlParser.Column_nameContext ctx) {
        if (ctx == null || ctx.getText() == null) return;
        String columnRef = BaseSemanticListener.cleanIdentifier(ctx.getText());
        if (columnRef != null && !columnRef.isBlank()) {
            base.onColumnRef(columnRef, getStartLine(ctx), getEndLine(ctx));
        }
    }

    // =========================================================================
    // WHERE / HAVING / ORDER BY / GROUP BY
    // =========================================================================

    @Override
    public void enterWhere_clause(PlSqlParser.Where_clauseContext ctx) {
        base.onWhereEnter(getStartLine(ctx));
    }

    @Override
    public void exitWhere_clause(PlSqlParser.Where_clauseContext ctx) {
        base.onWhereExit();
    }

    @Override
    public void enterHaving_clause(PlSqlParser.Having_clauseContext ctx) {
        base.onHavingEnter(getStartLine(ctx));
    }

    @Override
    public void exitHaving_clause(PlSqlParser.Having_clauseContext ctx) {
        base.onHavingExit();
    }

    @Override
    public void enterOrder_by_clause(PlSqlParser.Order_by_clauseContext ctx) {
        base.onOrderByEnter(getStartLine(ctx));
    }

    @Override
    public void exitOrder_by_clause(PlSqlParser.Order_by_clauseContext ctx) {
        base.onOrderByExit();
    }

    @Override
    public void enterGroup_by_clause(PlSqlParser.Group_by_clauseContext ctx) {
        base.onGroupByEnter(getStartLine(ctx));
    }

    @Override
    public void exitGroup_by_clause(PlSqlParser.Group_by_clauseContext ctx) {
        base.onGroupByExit();
    }

    // =========================================================================
    // MERGE insert/update parts
    // =========================================================================

    @Override
    public void enterMerge_insert_clause(PlSqlParser.Merge_insert_clauseContext ctx) {
        base.onMergeInsertEnter();
    }

    @Override
    public void exitMerge_insert_clause(PlSqlParser.Merge_insert_clauseContext ctx) {
        base.onMergeInsertExit();
    }

    @Override
    public void enterMerge_update_clause(PlSqlParser.Merge_update_clauseContext ctx) {
        base.onMergeUpdateEnter();
    }

    @Override
    public void exitMerge_update_clause(PlSqlParser.Merge_update_clauseContext ctx) {
        base.onMergeUpdateExit();
    }

    // =========================================================================
    // Values clause
    // =========================================================================

    @Override
    public void enterValues_clause(PlSqlParser.Values_clauseContext ctx) {
        base.onValuesClauseEnter();
    }

    @Override
    public void exitValues_clause(PlSqlParser.Values_clauseContext ctx) {
        base.onValuesClauseExit();
    }

    // =========================================================================
    // Atom
    // =========================================================================

    @Override
    public void enterAtom(PlSqlParser.AtomContext ctx) {
        if (ctx == null) return;
        String text = ctx.getText() != null ? ctx.getText() : "";
        boolean isComplex = ctx.getStop() != null && ctx.getStart() != null
                && ctx.getStop().getTokenIndex() > ctx.getStart().getTokenIndex();
        base.onAtom(text, getStartLine(ctx), getStartCol(ctx),
                getEndLine(ctx), getEndCol(ctx), isComplex);
    }

    // =========================================================================
    // CREATE VIEW
    // =========================================================================

    @Override
    public void enterCreate_view(PlSqlParser.Create_viewContext ctx) {
        if (ctx == null) return;
        // Имя view ищем через tableview_name если есть, иначе по тексту
        String viewName = null;
        try {
            if (ctx.tableview_name() != null) {
                viewName = BaseSemanticListener.cleanIdentifier(ctx.tableview_name().getText());
            }
        } catch (Exception ignored) {
            // tableview_name может отсутствовать в данной версии грамматики
        }
        if (viewName != null) {
            base.initTable(viewName, null, base.currentSchema(), "VIEW");
        }
        base.onStatementEnter("CREATE_VIEW", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        base.current.put("is_first_subq",  null);
        base.current.put("is_first_subqp", null);
    }

    @Override
    public void exitCreate_view(PlSqlParser.Create_viewContext ctx) {
        base.onStatementExit();
        base.current.put("is_first_subq",  null);
        base.current.put("is_first_subqp", null);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private String extract(ParserRuleContext ctx) {
        if (ctx == null) return "";
        try {
            String text = ctx.getText();
            return text != null && text.length() > 300 ? text.substring(0, 300) + "..." : text;
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

    private int getStartCol(ParserRuleContext ctx) {
        return (ctx != null && ctx.getStart() != null) ? ctx.getStart().getCharPositionInLine() : 0;
    }

    private int getEndCol(ParserRuleContext ctx) {
        return (ctx != null && ctx.getStop() != null) ? ctx.getStop().getCharPositionInLine() : getStartCol(ctx);
    }

    /**
     * Извлекает алиас из table_alias контекста.
     */
    private String extractAlias(PlSqlParser.Table_aliasContext aliasCtx) {
        if (aliasCtx == null) return null;
        try {
            String text = aliasCtx.getText();
            return text != null && !text.isBlank()
                    ? BaseSemanticListener.cleanIdentifier(text) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Извлекает имя routine через рефлексию (procedure_name или function_name).
     */
    private String extractRoutineName(ParserRuleContext ctx) {
        if (ctx == null) return "UNKNOWN";
        // procedure_name
        try {
            java.lang.reflect.Method m = ctx.getClass().getMethod("procedure_name");
            Object nameCtx = m.invoke(ctx);
            if (nameCtx != null) {
                java.lang.reflect.Method id = nameCtx.getClass().getMethod("identifier");
                Object idCtx = id.invoke(nameCtx);
                if (idCtx != null) {
                    return BaseSemanticListener.cleanIdentifier(idCtx.toString());
                }
            }
        } catch (Exception ignored) {}
        // function_name
        try {
            java.lang.reflect.Method m = ctx.getClass().getMethod("function_name");
            Object nameCtx = m.invoke(ctx);
            if (nameCtx != null) {
                java.lang.reflect.Method id = nameCtx.getClass().getMethod("identifier");
                Object idCtx = id.invoke(nameCtx);
                if (idCtx != null) {
                    return BaseSemanticListener.cleanIdentifier(idCtx.toString());
                }
            }
        } catch (Exception ignored) {}
        return ctx.getStart() != null ? "ROUTINE_" + ctx.getStart().getLine() : "UNKNOWN";
    }
}