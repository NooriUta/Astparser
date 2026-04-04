// src/main/java/com/hound/semantic/dialect/plsql/PlSqlSemanticListener.java
package com.hound.semantic.dialect.plsql;

import com.hound.parser.base.grammars.sql.plsql.PlSqlLexer;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParserBaseListener;
import com.hound.semantic.engine.CanonicalTokenType;
import com.hound.semantic.listener.BaseSemanticListener;
import com.hound.semantic.engine.UniversalSemanticEngine;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlSqlSemanticListener — Java-аналог Python PlSqlAnalyzerListener.
 *
 * ПРАВИЛА:
 * 1. Никаких ctx.tableview_name() — этого метода нет в Table_ref_auxContext и Create_viewContext
 *    данной версии грамматики. Используем рефлексию или getText().
 * 2. Никаких прямых base.current.put / base.protected — только публичные методы base.
 * 3. Никаких BaseSemanticListener.cleanIdentifier() без static — метод public static.
 */
public class PlSqlSemanticListener extends PlSqlParserBaseListener {

    private final BaseSemanticListener base;

    public PlSqlSemanticListener(UniversalSemanticEngine engine) {
        this.base = new BaseSemanticListener(engine) {};
    }

    // =========================================================================
    // Процедуры и функции
    // =========================================================================

    // ═══════ Create procedure (toplevel) ═══════
    @Override
    public void enterCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
        String name = ctx.procedure_name() != null
                ? BaseSemanticListener.cleanIdentifier(ctx.procedure_name().getText())
                : "UNKNOWN";
        base.onRoutineEnter(name, "PROCEDURE", base.currentSchema(), null, getStartLine(ctx));
        extractParameters(ctx.parameter());
    }

    @Override
    public void exitCreate_procedure_body(PlSqlParser.Create_procedure_bodyContext ctx) {
        base.onRoutineExit();
    }

    // ═══════ Procedure body (в package) ═══════
    @Override
    public void enterProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
        String name = ctx.identifier() != null
                ? BaseSemanticListener.cleanIdentifier(ctx.identifier().getText())
                : "UNKNOWN";
        base.onRoutineEnter(name, "PROCEDURE", base.currentSchema(), base.currentPackage(), getStartLine(ctx));
        extractParameters(ctx.parameter());
    }

    @Override
    public void exitProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
        base.onRoutineExit();
    }

    // ═══════ Create function (toplevel) ═══════
    @Override
    public void enterCreate_function_body(PlSqlParser.Create_function_bodyContext ctx) {
        String name = ctx.function_name() != null
                ? BaseSemanticListener.cleanIdentifier(ctx.function_name().getText())
                : "UNKNOWN";
        base.onRoutineEnter(name, "FUNCTION", base.currentSchema(), null, getStartLine(ctx));
        extractParameters(ctx.parameter());
        if (ctx.type_spec() != null) {
            base.onRoutineReturnType(ctx.type_spec().getText());
        }
    }

    @Override
    public void exitCreate_function_body(PlSqlParser.Create_function_bodyContext ctx) {
        base.onRoutineExit();
    }

    // ═══════ Function body (в package) ═══════
    @Override
    public void enterFunction_body(PlSqlParser.Function_bodyContext ctx) {
        String name = ctx.identifier() != null
                ? BaseSemanticListener.cleanIdentifier(ctx.identifier().getText())
                : "UNKNOWN";
        base.onRoutineEnter(name, "FUNCTION", base.currentSchema(), base.currentPackage(), getStartLine(ctx));
        extractParameters(ctx.parameter());
        if (ctx.type_spec() != null) {
            base.onRoutineReturnType(ctx.type_spec().getText());
        }
    }

    @Override
    public void exitFunction_body(PlSqlParser.Function_bodyContext ctx) {
        base.onRoutineExit();
    }

    // ═══════ Variable declarations ═══════
    @Override
    public void enterVariable_declaration(PlSqlParser.Variable_declarationContext ctx) {
        if (ctx == null || ctx.identifier() == null) return;
        String varName = BaseSemanticListener.cleanIdentifier(ctx.identifier().getText());
        String varType = ctx.type_spec() != null ? ctx.type_spec().getText() : "UNKNOWN";
        base.onRoutineVariable(varName, varType);
    }

    // =========================================================================
    // Пакеты
    // =========================================================================

    @Override
    public void enterCreate_package_body(PlSqlParser.Create_package_bodyContext ctx) {
        if (ctx.package_name() == null || ctx.package_name().isEmpty()) return;

        // STAB-12 Step 1: читаем схему напрямую из ctx, не через currentSchema().
        // Причина: schema — дочерний узел (schema_object_name), enterSchema_name ещё не сработал.
        // Грамматика: CREATE PACKAGE BODY (schema_object_name PERIOD)? package_name IS ...
        String schemaName = null;
        if (ctx.schema_object_name() != null && ctx.PERIOD() != null) {
            schemaName = BaseSemanticListener.cleanIdentifier(ctx.schema_object_name().getText());
            // Немедленно регистрируем схему, чтобы initPackage её нашёл
            if (schemaName != null && !schemaName.isBlank()) {
                base.onSchemaEnter(schemaName);
            }
        }

        // Берём последний package_name (грамматика может давать список)
        var pkgCtxList = ctx.package_name();
        PlSqlParser.Package_nameContext pkgCtx = pkgCtxList.get(pkgCtxList.size() - 1);
        String packageName = BaseSemanticListener.cleanIdentifier(pkgCtx.getText());

        // Guard: если packageName содержит "." — распарсить (HR.TEST_PKG без schema_name узла)
        if (packageName.contains(".")) {
            String[] parts = packageName.split("\\.", 2);
            if (schemaName == null) schemaName = parts[0];
            packageName = parts[1];
        }

        String effectiveSchema = (schemaName != null && !schemaName.isBlank())
                ? schemaName
                : base.currentSchema();  // fallback: PACKAGE BODY без schema-prefix

        String pkgGeoid = base.initPackage(packageName, effectiveSchema);
        base.setPackage(pkgGeoid);
    }

    @Override
    public void exitCreate_package_body(PlSqlParser.Create_package_bodyContext ctx) {
        base.setPackage(null);
    }

    // =========================================================================
    // Схема
    // =========================================================================

    @Override
    public void enterSchema_name(PlSqlParser.Schema_nameContext ctx) {
        if (ctx == null || ctx.identifier() == null) return;
        base.onSchemaEnter(BaseSemanticListener.cleanIdentifier(ctx.identifier().getText()));
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
        // Mark the inner subquery position so enterSubquery skips double-scope push.
        // Grammar: select_statement → select_only_statement → with_clause? subquery
        if (ctx.select_only_statement() != null && ctx.select_only_statement().subquery() != null) {
            var sq = ctx.select_only_statement().subquery();
            base.setIsFirstSubq(sq.start.getLine());
            base.setIsFirstSubqp(sq.start.getCharPositionInLine());
        }
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
        base.onStatementEnter("MERGE", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        base.onDmlTargetEnter();

        // Case A: MERGE INTO table_name alias (прямая таблица)
        if (ctx.tableview_name() != null) {
            String targetTable = BaseSemanticListener.cleanIdentifier(ctx.tableview_name().getText());
            String targetAlias = extractAlias(ctx.table_alias());
            if (targetTable != null) {
                base.onTableReference(targetTable, targetAlias, getStartLine(ctx), getEndLine(ctx));
            }
            // Прямая таблица — сразу сбрасываем target-флаг
            base.onDmlTargetExit();
        }
        // Case B: MERGE INTO (SELECT ... FROM target_table) msubquery (updatable subquery)
        // НЕ сбрасываем in_dml_target — он пропагируется в подзапрос,
        // target_table внутри зарегистрируется как TARGET при walk.
        // Сброс произойдёт в enterSelected_tableview (граница INTO → USING).
        else {
            String subqAlias = extractAlias(ctx.table_alias());
            if (subqAlias != null) {
                base.setMergeIntoSubqueryAlias(subqAlias);
            }
        }
    }

    /**
     * Граница MERGE INTO → USING.
     * Сбрасываем in_dml_target: всё что после USING — это SOURCE.
     * Также регистрируем USING source таблицу/подзапрос.
     */
    @Override
    public void enterSelected_tableview(PlSqlParser.Selected_tableviewContext ctx) {
        // Сброс target-флага для Case B (updatable subquery в MERGE INTO)
        if (base.isInDmlTarget()) {
            base.onDmlTargetExit();
        }
        // Регистрируем USING source
        String sourceAlias = extractAlias(ctx.table_alias());
        if (ctx.tableview_name() != null) {
            String sourceName = BaseSemanticListener.cleanIdentifier(ctx.tableview_name().getText());
            if (sourceName != null) {
                base.onTableReference(sourceName, sourceAlias, getStartLine(ctx), getEndLine(ctx));
            }
        }
        if (sourceAlias != null) {
            base.setSubqueryAlias(sourceAlias);
            base.subqueryAliasStack().add(sourceAlias);
        }
    }

    @Override
    public void exitMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        base.clearMergeIntoSubqueryAlias();
        base.onStatementExit();
    }

    // =========================================================================
    // Курсоры
    // =========================================================================

    @Override
    public void enterCursor_declaration(PlSqlParser.Cursor_declarationContext ctx) {
        base.onStatementEnter("CURSOR", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        if (ctx.select_statement() != null) {
            base.setIsFirstSubq(ctx.select_statement().start.getLine());
            base.setIsFirstSubqp(ctx.select_statement().start.getCharPositionInLine());
        }
    }

    @Override
    public void exitCursor_declaration(PlSqlParser.Cursor_declarationContext ctx) {
        base.onStatementExit();
        base.setIsFirstSubq(null);
        base.setIsFirstSubqp(null);
    }

    // ═══════ OPEN FOR (ref cursor) ═══════

    @Override
    public void enterOpen_for_statement(PlSqlParser.Open_for_statementContext ctx) {
        if (ctx == null) return;
        PlSqlParser.Select_statementContext selectCtx = ctx.select_statement();
        if (selectCtx != null) {
            base.setIsFirstSubq(selectCtx.getStart().getLine());
            base.setIsFirstSubqp(selectCtx.getStart().getCharPositionInLine());
            base.onStatementEnter("REF CURSOR", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        }
    }

    @Override
    public void exitOpen_for_statement(PlSqlParser.Open_for_statementContext ctx) {
        if (ctx != null && ctx.select_statement() != null) {
            base.onStatementExit();
            base.setIsFirstSubq(null);
            base.setIsFirstSubqp(null);
        }
    }

    // ═══════ LOOP с cursor (FOR rec IN SELECT) ═══════

    @Override
    public void enterLoop_statement(PlSqlParser.Loop_statementContext ctx) {
        if (ctx == null || ctx.cursor_loop_param() == null) return;
        PlSqlParser.Cursor_loop_paramContext cursorParam = ctx.cursor_loop_param();
        PlSqlParser.Select_statementContext selectCtx = cursorParam.select_statement();
        if (selectCtx != null) {
            base.setIsFirstSubq(selectCtx.getStart().getLine());
            base.setIsFirstSubqp(selectCtx.getStart().getCharPositionInLine());
            base.onStatementEnter("DINAMIC_CURSOR", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        }
    }

    @Override
    public void exitLoop_statement(PlSqlParser.Loop_statementContext ctx) {
        if (ctx != null && ctx.cursor_loop_param() != null
                && ctx.cursor_loop_param().select_statement() != null) {
            base.onStatementExit();
            base.setIsFirstSubq(null);
            base.setIsFirstSubqp(null);
        }
    }

    // =========================================================================
    // CTE / WITH clause
    // =========================================================================

    @Override
    public void enterWith_clause(PlSqlParser.With_clauseContext ctx) {
        base.onCTEEnter(extract(ctx), getStartLine(ctx), getEndLine(ctx));
        base.setIsFirstSubq(null);
        base.setIsFirstSubqp(null);
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

        Integer firstLine = base.getIsFirstSubq();
        Integer firstCol  = base.getIsFirstSubqp();
        int ctxLine = ctx.start.getLine();
        int ctxCol  = ctx.start.getCharPositionInLine();

        if (firstLine != null && firstLine == ctxLine
                && firstCol != null && firstCol == ctxCol) {
            base.setIsFirstSubq(ctxLine);
            base.setIsFirstSubqp(ctxCol);
            return;
        }

        base.setIsFirstSubq(ctxLine);
        base.setIsFirstSubqp(ctxCol);
        base.setIsUnion(ctx.subquery_operation_part() != null
                && !ctx.subquery_operation_part().isEmpty());

        if (!base.isInDmlTarget()) {
            base.onSubqueryEnter(extract(ctx), getStartLine(ctx), getEndLine(ctx));
        } else {
            base.onStatementEnter("USUBQUERY", extract(ctx), getStartLine(ctx), getEndLine(ctx));
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
    // Таблицы — enterTable_ref_aux
    //
    // ctx.tableview_name() НЕ существует в Table_ref_auxContext этой грамматики.
    // Имя таблицы извлекается через рефлексию из table_ref_aux_internal,
    // либо как getText() всего контекста минус алиас.
    // =========================================================================

    @Override
    public void enterTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
        if (ctx == null) return;

        String tableAlias = extractAlias(ctx.table_alias());
        String tableName  = extractTableNameViaReflection(ctx, tableAlias);

        if (tableName != null && !tableName.isBlank()) {
            base.onTableReference(tableName, tableAlias, getStartLine(ctx), getEndLine(ctx));
            base.setCurrentTable(tableName);
            base.setCurrentTableAlias(tableAlias);
        }

        base.setSubqueryAlias(tableAlias);
        base.subqueryAliasStack().add(tableAlias != null ? tableAlias : "");
    }

    @Override
    public void exitTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
        List<String> stack = base.subqueryAliasStack();
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
            base.setCurrentTable(tableName);
        }
    }

    @Override
    public void exitGeneral_table_ref(PlSqlParser.General_table_refContext ctx) {
        base.setGeneralTable(null);
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
        if (ctx == null) {
            base.onJoinExit();
            return;
        }

        // Порт Python: exitJoin_clause — полная обработка

        // 1. Join type
        String joinType = extractJoinType(ctx);

        // 2. Conditions
        List<String> conditions = new ArrayList<>();
        try {
            var onParts = ctx.join_on_part();
            if (onParts != null) {
                for (var onPart : onParts) {
                    if (onPart.condition() != null) {
                        conditions.add(extract(onPart.condition()));
                    }
                }
            }
        } catch (Exception e) {
            // Grammar may not have join_on_part, fallback
        }

        // 3. Source table determination через regex условий
        String targetName = base.currentTable();
        String targetAlias = base.currentTableAlias();
        String filterAlias = targetAlias != null ? targetAlias : targetName;
        String sourceAlias = determineJoinSourceFromConditions(conditions, filterAlias);

        // 4. Complete join registration
        base.onJoinComplete(joinType, conditions, sourceAlias, getStartLine(ctx));
        base.onJoinExit();
    }

    /**
     * Определяет тип JOIN из контекста.
     * Порт Python: _get_join_type()
     */
    private String extractJoinType(PlSqlParser.Join_clauseContext ctx) {
        String text = extract(ctx).toUpperCase();
        int joinIdx = text.indexOf("JOIN");
        if (joinIdx < 0) {
            if (text.contains("CROSS APPLY")) return "CROSS APPLY";
            if (text.contains("OUTER APPLY")) return "OUTER APPLY";
            return "JOIN";
        }
        String prefix = text.substring(0, joinIdx);
        if (prefix.contains("NATURAL")) return "NATURAL JOIN";
        if (prefix.contains("CROSS")) return "CROSS JOIN";
        StringBuilder type = new StringBuilder();
        if (prefix.contains("FULL"))   type.append("FULL ");
        if (prefix.contains("LEFT"))   type.append("LEFT ");
        if (prefix.contains("RIGHT"))  type.append("RIGHT ");
        if (prefix.contains("OUTER"))  type.append("OUTER ");
        if (prefix.contains("INNER"))  type.append("INNER ");
        type.append("JOIN");
        return type.toString().trim();
    }

    /**
     * Порт Python: _determine_join_source_table()
     * Определяет source table из ON conditions.
     */
    private String determineJoinSourceFromConditions(List<String> conditions, String targetRef) {
        Set<String> usedAliases = new LinkedHashSet<>();
        Pattern p = Pattern.compile("\\b([a-zA-Z_]\\w*)\\.\\w+\\b");
        for (String condition : conditions) {
            Matcher m = p.matcher(condition);
            while (m.find()) {
                usedAliases.add(m.group(1).toUpperCase());
            }
        }
        // Убираем alias/name target таблицы
        if (targetRef != null) {
            usedAliases.remove(targetRef.toUpperCase());
        }
        // Оставшийся alias = source
        return usedAliases.isEmpty() ? null : usedAliases.iterator().next();
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

        // STAB-11: голый SELECT * — нет select_list_elements событий, обрабатываем здесь.
        // ASTERISK() — terminal node; select_list_elements() — список дочерних правил.
        if (ctx == null) return;
        try {
            boolean hasAsterisk = ctx.ASTERISK() != null;
            boolean hasElements = ctx.select_list_elements() != null
                    && !ctx.select_list_elements().isEmpty();
            if (hasAsterisk && !hasElements) {
                base.onBareStar(getStartLine(ctx), getStartCol(ctx));
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Output column binding (select_list_elements)
    // =========================================================================

    @Override
    public void enterSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        if (ctx == null) return;
        base.onOutputColumnEnter(getStartLine(ctx), getStartCol(ctx));
    }

    @Override
    public void exitSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        if (ctx == null) return;
        String expr = extract(ctx);
        boolean isTableStar = false;
        try {
            if (ctx.tableview_name() != null && ctx.ASTERISK() != null) {
                isTableStar = true;
                expr = ctx.tableview_name().getText() + ".*";
            }
        } catch (Exception ignored) {}

        base.onOutputColumnExit(getStartLine(ctx), getStartCol(ctx),
                getEndLine(ctx), getEndCol(ctx), expr, isTableStar);
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

    // STAB-7: перенесено с enter на exit — при enter внутри сложных выражений
    // enterSchema_name может сработать ПОСЛЕ и загрязнить state.
    // На exit все дочерние узлы уже обработаны, scope чист.
    @Override
    public void enterColumn_name(PlSqlParser.Column_nameContext ctx) { }

    @Override
    public void exitColumn_name(PlSqlParser.Column_nameContext ctx) {
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
    public void enterWhere_clause(PlSqlParser.Where_clauseContext ctx)   { base.onWhereEnter(getStartLine(ctx)); }
    @Override
    public void exitWhere_clause(PlSqlParser.Where_clauseContext ctx)    { base.onWhereExit(); }

    @Override
    public void enterHaving_clause(PlSqlParser.Having_clauseContext ctx) { base.onHavingEnter(getStartLine(ctx)); }
    @Override
    public void exitHaving_clause(PlSqlParser.Having_clauseContext ctx)  { base.onHavingExit(); }

    @Override
    public void enterOrder_by_clause(PlSqlParser.Order_by_clauseContext ctx) { base.onOrderByEnter(getStartLine(ctx)); }
    @Override
    public void exitOrder_by_clause(PlSqlParser.Order_by_clauseContext ctx)  { base.onOrderByExit(); }

    @Override
    public void enterGroup_by_clause(PlSqlParser.Group_by_clauseContext ctx) { base.onGroupByEnter(getStartLine(ctx)); }
    @Override
    public void exitGroup_by_clause(PlSqlParser.Group_by_clauseContext ctx)  { base.onGroupByExit(); }

    // =========================================================================
    // MERGE insert/update parts
    // =========================================================================

    @Override
    public void enterMerge_insert_clause(PlSqlParser.Merge_insert_clauseContext ctx) { base.onMergeInsertEnter(); }
    @Override
    public void exitMerge_insert_clause(PlSqlParser.Merge_insert_clauseContext ctx)  { base.onMergeInsertExit(); }

    @Override
    public void enterMerge_update_clause(PlSqlParser.Merge_update_clauseContext ctx) { base.onMergeUpdateEnter(); }
    @Override
    public void exitMerge_update_clause(PlSqlParser.Merge_update_clauseContext ctx)  { base.onMergeUpdateExit(); }

    // =========================================================================
    // CALLS (STAB-9): межпроцедурные зависимости
    // Аналог Python: enterCall_statement + _add_called_routine()
    // =========================================================================

    @Override
    public void enterCall_statement(PlSqlParser.Call_statementContext ctx) {
        if (ctx == null || base.currentRoutine() == null) return;

        String calledName = null;

        // Стратегия 1: routine_name() — ANTLR4 всегда возвращает List
        try {
            var routineNames = ctx.routine_name();
            if (routineNames != null && !routineNames.isEmpty()) {
                for (var rn : routineNames) {
                    if (rn == null) continue;
                    try {
                        Method gt = rn.getClass().getMethod("getText");
                        String rText = (String) gt.invoke(rn);
                        if (rText != null && !rText.isBlank())
                            base.onCallStatement(BaseSemanticListener.cleanIdentifier(rText), getStartLine(ctx));
                    } catch (Exception ignored) {}
                }
                return; // handled via list
            }
        } catch (Exception ignored) {}

        // Стратегия 2: fallback — getText() до первой '('
        if (calledName == null || calledName.isBlank()) {
            try {
                String raw = ctx.getText();
                int paren = raw.indexOf('(');
                calledName = BaseSemanticListener.cleanIdentifier(
                        paren > 0 ? raw.substring(0, paren) : raw);
            } catch (Exception ignored) {}
        }

        if (calledName != null && !calledName.isBlank()) {
            base.onCallStatement(calledName, getStartLine(ctx));
        }
    }

    // =========================================================================
    // Values clause
    // =========================================================================

    @Override
    public void enterValues_clause(PlSqlParser.Values_clauseContext ctx) { base.onValuesClauseEnter(); }
    @Override
    public void exitValues_clause(PlSqlParser.Values_clauseContext ctx)  { base.onValuesClauseExit(); }

    /**
     * STAB-6: exitExpression — VALUES counter + atom binding.
     * Обнаруживаем, когда expression находится непосредственно внутри values_clause
     * (expression → expression_list → values_clause), и вызываем onValuesExpressionExit.
     */
    @Override
    public void exitExpression(PlSqlParser.ExpressionContext ctx) {
        if (ctx == null) return;
        boolean isInValues = false;
        try {
            // Типичная иерархия: values_clause → expression_list → expression
            // Поднимаемся на 2 уровня вверх
            if (ctx.parent != null && ctx.parent.parent != null) {
                int ruleIdx = ((ParserRuleContext) ctx.parent.parent).getRuleIndex();
                if ("values_clause".equals(PlSqlParser.ruleNames[ruleIdx])) {
                    isInValues = true;
                }
            }
        } catch (Exception ignored) {}
        if (isInValues) {
            base.onValuesExpressionExit(
                    getStartLine(ctx), getStartCol(ctx),
                    getEndLine(ctx), getEndCol(ctx));
        }
    }

    // =========================================================================
    // Atom
    // =========================================================================

    @Override
    public void enterAtom(PlSqlParser.AtomContext ctx) {
        if (ctx == null) return;
        String text = ctx.getText() != null ? ctx.getText() : "";

        // Извлечение token details — ПОРТ из Python enterAtom
        List<String> tokens = new ArrayList<>();
        List<Map<String, String>> tokenDetails = new ArrayList<>();
        collectTerminalTokens(ctx, tokens, tokenDetails);

        boolean isComplex = tokens.size() > 1;
        int nestedAtomCount = countNestedAtoms(ctx);

        base.onAtom(text, getStartLine(ctx), getStartCol(ctx),
                getEndLine(ctx), getEndCol(ctx), isComplex,
                tokens, tokenDetails, nestedAtomCount);
    }

    /**
     * Собирает все terminal-токены из поддерева контекста.
     * Аналог Python: итерация по токенам от ctx.start до ctx.stop.
     */
    private void collectTerminalTokens(ParserRuleContext ctx,
                                        List<String> tokens,
                                        List<Map<String, String>> tokenDetails) {
        List<TerminalNode> terminals = new ArrayList<>();
        collectTerminals(ctx, terminals);
        for (TerminalNode tn : terminals) {
            Token token = tn.getSymbol();
            String rawName = PlSqlLexer.VOCABULARY.getSymbolicName(token.getType());
            if (rawName == null) rawName = String.valueOf(token.getType());

            CanonicalTokenType canonical = PlSqlTokenMapper.map(rawName);
            if (canonical == CanonicalTokenType.WHITESPACE) continue;

            tokens.add(token.getText());
            tokenDetails.add(Map.of(
                    "text", token.getText(),
                    "type", canonical.name(),
                    "position", token.getLine() + ":" + token.getCharPositionInLine()
            ));
        }
    }

    private void collectTerminals(ParseTree tree, List<TerminalNode> result) {
        if (tree instanceof TerminalNode) {
            result.add((TerminalNode) tree);
        } else {
            for (int i = 0; i < tree.getChildCount(); i++) {
                collectTerminals(tree.getChild(i), result);
            }
        }
    }

    /**
     * Подсчитывает вложенные AtomContext в поддереве (не считая сам ctx).
     */
    private int countNestedAtoms(PlSqlParser.AtomContext ctx) {
        int count = 0;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            count += countNestedAtomsRecursive(ctx.getChild(i));
        }
        return count;
    }

    private int countNestedAtomsRecursive(ParseTree tree) {
        int count = 0;
        if (tree instanceof PlSqlParser.AtomContext) {
            count++;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            count += countNestedAtomsRecursive(tree.getChild(i));
        }
        return count;
    }

    // =========================================================================
    // CREATE VIEW
    // ctx.tableview_name() НЕ существует в Create_viewContext — используем рефлексию
    // =========================================================================

    @Override
    public void enterCreate_view(PlSqlParser.Create_viewContext ctx) {
        if (ctx == null) return;
        String viewName = extractViaReflection(ctx, "tableview_name");
        if (viewName != null) {
            base.initTable(viewName, null, base.currentSchema(), "VIEW");
        }
        base.onStatementEnter("CREATE_VIEW", extract(ctx), getStartLine(ctx), getEndLine(ctx));
        base.setIsFirstSubq(null);
        base.setIsFirstSubqp(null);
    }

    @Override
    public void exitCreate_view(PlSqlParser.Create_viewContext ctx) {
        base.onStatementExit();
        base.setIsFirstSubq(null);
        base.setIsFirstSubqp(null);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================


       private String extract(ParserRuleContext ctx) {
           if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) return "";
           try {
               return ctx.getStart().getInputStream()
                       .getText(new org.antlr.v4.runtime.misc.Interval(
                               ctx.getStart().getStartIndex(),
                               ctx.getStop().getStopIndex()));
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

    /** Алиас из table_alias контекста */
    private String extractAlias(PlSqlParser.Table_aliasContext aliasCtx) {
        if (aliasCtx == null) return null;
        try {
            String t = aliasCtx.getText();
            return (t != null && !t.isBlank()) ? BaseSemanticListener.cleanIdentifier(t) : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Извлекает getText() дочернего правила ctx.methodName() через рефлексию,
     * возвращает cleanIdentifier от результата или null.
     */
    private String extractViaReflection(Object ctx, String methodName) {
        if (ctx == null) return null;
        try {
            Method m = ctx.getClass().getMethod(methodName);
            Object child = m.invoke(ctx);
            if (child == null) return null;
            Method gt = child.getClass().getMethod("getText");
            String text = (String) gt.invoke(child);
            return BaseSemanticListener.cleanIdentifier(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Извлекает имя таблицы из Table_ref_auxContext.
     *
     * Стратегии (по приоритету):
     * 1. table_ref_aux_internal -> dml_table_expression_clause -> tableview_name (рефлексия)
     * 2. table_ref_aux_internal -> getText() целиком (рефлексия)
     * 3. getText() всего ctx минус суффикс алиаса
     */
    private String extractTableNameViaReflection(PlSqlParser.Table_ref_auxContext ctx,
                                                 String tableAlias) {
        // Стратегия 1+2: через table_ref_aux_internal
        try {
            Method internalM = ctx.getClass().getMethod("table_ref_aux_internal");
            Object internal = internalM.invoke(ctx);
            if (internal != null) {
                // 1: dml_table_expression_clause -> tableview_name
                try {
                    Method dmlM = internal.getClass().getMethod("dml_table_expression_clause");
                    Object dml = dmlM.invoke(internal);
                    if (dml != null) {
                        String name = extractViaReflection(dml, "tableview_name");
                        if (name != null && !name.isBlank()) return name;
                    }
                } catch (Exception ignored) {}

                // 2: getText() internal целиком (но НЕ подзапросы)
                try {
                    Method gtM = internal.getClass().getMethod("getText");
                    String text = (String) gtM.invoke(internal);
                    if (text != null && !text.isBlank() && !looksLikeSubquery(text)) {
                        return BaseSemanticListener.cleanIdentifier(text);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Стратегия 3: getText() ctx минус алиас в конце (но НЕ подзапросы)
        try {
            String full = ctx.getText();
            if (full == null || full.isBlank()) return null;
            if (tableAlias != null && full.toUpperCase().endsWith(tableAlias)) {
                full = full.substring(0, full.length() - tableAlias.length());
            }
            if (looksLikeSubquery(full)) return null;
            return BaseSemanticListener.cleanIdentifier(full);
        } catch (Exception e) {
            return null;
        }
    }

    /** Проверяет, является ли текст подзапросом, а не именем таблицы */
    private static boolean looksLikeSubquery(String text) {
        if (text == null) return false;
        String upper = text.trim().toUpperCase();
        return upper.startsWith("(SELECT") || upper.startsWith("SELECT")
                || upper.startsWith("(WITH") || text.length() > 200;
    }

    /**
     * Порт Python: _init_routine_parameters()
     * Извлекает параметры из списка ParameterContext.
     */
    private void extractParameters(List<PlSqlParser.ParameterContext> params) {
        if (params == null) return;
        for (PlSqlParser.ParameterContext p : params) {
            if (p.parameter_name() == null) continue;
            String name = BaseSemanticListener.cleanIdentifier(p.parameter_name().getText());
            String type = p.type_spec() != null ? p.type_spec().getText() : "UNKNOWN";
            String mode = "IN";
            if (p.IN() != null && !p.IN().isEmpty() && p.OUT() != null && !p.OUT().isEmpty()) mode = "IN OUT";
            else if (p.OUT() != null && !p.OUT().isEmpty()) mode = "OUT";
            else if (p.INOUT() != null && !p.INOUT().isEmpty()) mode = "IN OUT";
            base.onRoutineParameter(name, type, mode);
        }
    }
}