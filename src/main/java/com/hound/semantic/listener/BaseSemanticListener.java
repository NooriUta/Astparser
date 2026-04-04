// src/main/java/com/hound/semantic/listener/BaseSemanticListener.java
package com.hound.semantic.listener;

import com.hound.semantic.engine.UniversalSemanticEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * BaseSemanticListener — Java-аналог Python BaseSQLListener.
 *
 * ВСЕ поля и методы объявлены public — это необходимо потому что
 * PlSqlSemanticListener находится в другом пакете (dialect.plsql) и
 * использует composition (держит экземпляр BaseSemanticListener как поле base).
 *
 * В Java protected-члены недоступны из другого пакета через ссылку на объект —
 * только через наследование. Поэтому единственное решение при composition — public.
 */
public abstract class BaseSemanticListener {

    public final Logger logger = LoggerFactory.getLogger(getClass());
    public final UniversalSemanticEngine engine;

    // ====================== Структуры данных ======================
    public final Map<String, Map<String, Object>> databases  = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> schemas    = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> packages   = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> tables     = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> columns    = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> routines   = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> statements = new LinkedHashMap<>();

    public final Map<String, Map<String, Object>> lineageDatabases  = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> lineageSchemas    = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> lineagePackages   = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> lineageTables     = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> lineageRoutines   = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> lineageStatements = new LinkedHashMap<>();

    // ====================== current-контекст ======================
    public final Map<String, Object> current = new HashMap<>();
    // Routine stack: каждый элемент = {parentRoutine, parentRoutineType}
    public final Deque<String[]> routineStack = new ArrayDeque<>();

    // ====================== Конструктор ======================
    public BaseSemanticListener(UniversalSemanticEngine engine) {
        this.engine = engine;
        initCurrentContext();
    }

    // =========================================================================
    // РАЗДЕЛ 1: ИНИЦИАЛИЗАЦИЯ КОНТЕКСТА
    // =========================================================================

    public void initCurrentContext() {
        current.put("database", null);
        current.put("schema", null);
        current.put("package", null);
        current.put("routine", null);
        current.put("routine_type", null);
        current.put("statement", null);
        current.put("statement_type", null);
        current.put("selected_list", null);
        current.put("from", null);
        current.put("join", null);
        current.put("where", null);
        current.put("order", null);
        current.put("group_by", null);
        current.put("having", null);
        current.put("subquery_alias", null);
        current.put("atom_context", null);
        current.put("q_block", null);
        current.put("is_first_subq", null);
        current.put("is_first_subqp", null);
        current.put("general_table", null);
        current.put("column_alias", null);
        current.put("table", null);
        current.put("target_table", null);
        current.put("alias", null);
        current.put("in_dml_target", false);
        current.put("is_union", false);
        current.put("Merge_insert_part", false);
        current.put("Merge_update_part", false);
        current.put("Values_clause", false);
        current.put("Values_clause_cnt", 0);
        current.put("in_join_context", false);
        current.put("parent_is_union", false);
        current.put("parent_statement", null);
        current.put("parent_statement_type", null);
        current.put("parent_s_selected_list", null);
        current.put("parent_s_where", null);
        current.put("parent_s_from", null);
        current.put("parent_s_join", null);
        current.put("parent_s_order", null);
        current.put("parent_s_group_by", null);
        current.put("parent_s_having", null);
        current.put("parent_s_Merge_insert_part", false);
        current.put("parent_s_Merge_update_part", false);
        current.put("parent_s_operation_part", false);
        current.put("parent_column_output", null);
        current.put("unattached_atoms", new HashMap<String, Object>());
        current.put("subquery_alias_stack", new ArrayList<String>());
        current.put("s_operation_part", false);
        current.put("column_output", null);
        current.put("column_affected", null);
    }

    // =========================================================================
    // РАЗДЕЛ 2: ГЕТТЕРЫ И СЕТТЕРЫ current
    // =========================================================================

    public String currentStatement()     { return (String) current.get("statement"); }
    public String currentStatementType() { return (String) current.get("statement_type"); }
    public String currentRoutine()       { return (String) current.get("routine"); }
    public String currentRoutineType()   { return (String) current.get("routine_type"); }
    public String currentSchema()        { return (String) current.get("schema"); }
    public String currentPackage()       { return (String) current.get("package"); }
    public String parentStatement()      { return (String) current.get("parent_statement"); }
    public String currentTable()         { return (String) current.get("table"); }
    public String currentTableAlias()    { return (String) current.get("table_alias"); }

    public void setCurrentTableAlias(String alias) { current.put("table_alias", alias); }

    public boolean isInDmlTarget()   { return Boolean.TRUE.equals(current.get("in_dml_target")); }
    public boolean isInJoinContext() { return Boolean.TRUE.equals(current.get("in_join_context")); }
    public boolean isMergeInsert()   { return Boolean.TRUE.equals(current.get("Merge_insert_part")); }
    public boolean isMergeUpdate()   { return Boolean.TRUE.equals(current.get("Merge_update_part")); }
    public boolean isValuesClause()  { return Boolean.TRUE.equals(current.get("Values_clause")); }

    // Методы-сеттеры для PlSqlSemanticListener (вместо прямого current.put)
    public void setCurrentTable(String tableName)          { current.put("table", tableName); }
    public void setSubqueryAlias(String alias)             { current.put("subquery_alias", alias); }
    public void setMergeIntoSubqueryAlias(String alias)    { current.put("merge_into_subquery_alias", alias); }
    public String getMergeIntoSubqueryAlias()              { return (String) current.get("merge_into_subquery_alias"); }
    public void clearMergeIntoSubqueryAlias()              { current.remove("merge_into_subquery_alias"); }
    public void setGeneralTable(Object val)                { current.put("general_table", val); }
    public void setIsFirstSubq(Integer line)               { current.put("is_first_subq", line); }
    public void setIsFirstSubqp(Integer col)               { current.put("is_first_subqp", col); }
    public void setIsUnion(boolean val)                    { current.put("is_union", val); }
    public void setPackage(String pkgGeoid)                { current.put("package", pkgGeoid); }

    public Integer getIsFirstSubq()  { return (Integer) current.get("is_first_subq"); }
    public Integer getIsFirstSubqp() { return (Integer) current.get("is_first_subqp"); }

    @SuppressWarnings("unchecked")
    public List<String> subqueryAliasStack() {
        return (List<String>) current.get("subquery_alias_stack");
    }

    public String getCurrentParentContext() {
        if (current.get("selected_list") != null) return "SELECT";
        if (current.get("from") != null)          return "FROM";
        if (current.get("where") != null)         return "WHERE";
        if (current.get("order") != null)         return "ORDER";
        if (current.get("having") != null)        return "HAVING";
        if (current.get("group_by") != null)      return "GROUP BY";
        if (isMergeInsert())                       return "INSERT";
        if (isMergeUpdate())                       return "UPDATE";
        if (currentStatement() != null)            return currentStatementType();
        if (currentRoutine() != null)              return currentRoutineType();
        return null;
    }

    // =========================================================================
    // РАЗДЕЛ 3: ИНИЦИАЛИЗАЦИЯ SQL-ОБЪЕКТОВ
    // =========================================================================

    public void initDatabase(String dbName) {
        if (dbName == null) return;
        databases.computeIfAbsent(dbName, k -> {
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("db_name", dbName);
            db.put("schemas", new HashSet<String>());
            lineageDatabases.put(dbName, db);
            return db;
        });
    }

    public String initSchema(String schemaName, String databaseGeoid) {
        if (schemaName == null || schemaName.isBlank()) return null;
        String geoid = (databaseGeoid != null && !databaseGeoid.isBlank())
                ? databaseGeoid + "." + schemaName : schemaName;
        schemas.computeIfAbsent(geoid, k -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("database_geoid", databaseGeoid);
            s.put("schema_name", schemaName);
            s.put("tables", new HashSet<String>());
            s.put("routines", new HashSet<String>());
            s.put("packages", new HashSet<String>());
            lineageSchemas.put(geoid, s);
            if (databaseGeoid != null && databases.containsKey(databaseGeoid)) {
                @SuppressWarnings("unchecked")
                Set<String> dbSchemas = (Set<String>) databases.get(databaseGeoid).get("schemas");
                if (dbSchemas != null) dbSchemas.add(geoid);
            }
            return s;
        });
        return geoid;
    }

    public String initPackage(String packageName, String schemaGeoid) {
        if (packageName == null) return null;
        String geoid = (schemaGeoid != null && !schemaGeoid.isBlank())
                ? schemaGeoid + "." + packageName : packageName;
        packages.computeIfAbsent(geoid, k -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("schema_geoid", schemaGeoid);
            p.put("package_name", packageName);
            p.put("routines", new HashSet<String>());
            lineagePackages.put(geoid, p);
            return p;
        });
        if (schemaGeoid != null && schemas.containsKey(schemaGeoid)) {
            @SuppressWarnings("unchecked")
            Set<String> pkgs = (Set<String>) schemas.get(schemaGeoid).get("packages");
            if (pkgs != null) pkgs.add(geoid);
        }
        return geoid;
    }

    public String initTable(String tableName, String tableAlias,
                            String schemaGeoid, String tableType) {
        if (tableName == null || tableName.isBlank()) return null;
        String cleanName = cleanIdentifier(tableName);
        String resolvedSchema = schemaGeoid != null ? schemaGeoid : currentSchema();

        String geoid;
        if (cleanName.contains(".")) {
            geoid = cleanName;
        } else if (resolvedSchema != null && !resolvedSchema.isBlank()) {
            geoid = resolvedSchema + "." + cleanName;
        } else {
            geoid = cleanName;
        }

        tables.computeIfAbsent(geoid, k -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table_name", cleanName);
            t.put("schema_geoid", resolvedSchema);
            t.put("table_type", tableType != null ? tableType : "TABLE");
            t.put("aliases", new HashSet<String>());
            t.put("columns", new HashSet<String>());
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("routines", new HashSet<String>());
            usage.put("statements", new HashSet<String>());
            t.put("usage", usage);
            lineageTables.put(geoid, t);
            return t;
        });

        if (tableAlias != null && !tableAlias.isBlank()) {
            @SuppressWarnings("unchecked")
            Set<String> aliases = (Set<String>) tables.get(geoid).get("aliases");
            if (aliases != null) aliases.add(cleanIdentifier(tableAlias));
        }
        return geoid;
    }

    public String initRoutine(String routineName, String routineType,
                              String schemaGeoid, String packageGeoid, int sourceLine) {
        if (routineName == null || routineName.isBlank()) return null;
        String cleanName = cleanIdentifier(routineName);

        String geoid;
        if (packageGeoid != null && !packageGeoid.isBlank())
            geoid = packageGeoid + ":" + routineType + ":" + cleanName;
        else if (schemaGeoid != null && !schemaGeoid.isBlank())
            geoid = schemaGeoid + ":" + routineType + ":" + cleanName;
        else
            geoid = routineType + ":" + cleanName;

        // Save parent routine for restore on exit (аналог parent_statement)
        routineStack.push(new String[]{
                (String) current.get("routine"),
                (String) current.get("routine_type")
        });

        current.put("routine", geoid);
        current.put("routine_type", routineType);

        routines.computeIfAbsent(geoid, k -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("schema_geoid", schemaGeoid);
            r.put("package_geoid", packageGeoid);
            r.put("routine_type", routineType);
            r.put("routine_name", cleanName);
            r.put("source_line", sourceLine);
            r.put("columns_used", new LinkedHashMap<>());
            r.put("statements_used", new HashSet<String>());
            r.put("source_tables", new HashSet<String>());
            r.put("parameters", new ArrayList<>());
            r.put("variables", new ArrayList<>());
            r.put("return_type", null);
            r.put("called_routines", new ArrayList<>());
            lineageRoutines.put(geoid, r);
            return r;
        });

        if (packageGeoid != null && packages.containsKey(packageGeoid)) {
            @SuppressWarnings("unchecked")
            Set<String> rts = (Set<String>) packages.get(packageGeoid).get("routines");
            if (rts != null) rts.add(geoid);
        } else if (schemaGeoid != null && schemas.containsKey(schemaGeoid)) {
            @SuppressWarnings("unchecked")
            Set<String> rts = (Set<String>) schemas.get(schemaGeoid).get("routines");
            if (rts != null) rts.add(geoid);
        }
        return geoid;
    }

    public String initStatement(String stmtType, String snippet,
                                int lineStart, int lineEnd, String stmtAlias) {
        String stmtName = stmtType + ":" + lineStart + ":" + lineEnd + ":" + System.nanoTime();

        Map<String, Object> stmtInfo = new LinkedHashMap<>();
        stmtInfo.put("type", stmtType);
        stmtInfo.put("snippet", snippet != null && snippet.length() > 200
                ? snippet.substring(0, 200) + "..." : snippet);
        stmtInfo.put("line_start", lineStart);
        stmtInfo.put("line_end", lineEnd);
        Set<String> aliasSet = new HashSet<>();
        if (stmtAlias != null) aliasSet.add(stmtAlias);
        stmtInfo.put("alias", aliasSet);

        stmtInfo.put("parent_statement",           current.get("statement"));
        stmtInfo.put("parent_statement_type",      current.get("statement_type"));
        stmtInfo.put("parent_s_where",             current.get("where"));
        stmtInfo.put("parent_is_union",            current.get("is_union"));
        stmtInfo.put("parent_s_from",              current.get("from"));
        stmtInfo.put("parent_s_selected_list",     current.get("selected_list"));
        stmtInfo.put("parent_s_order",             current.get("order"));
        stmtInfo.put("parent_s_group_by",          current.get("group_by"));
        stmtInfo.put("parent_s_having",            current.get("having"));
        stmtInfo.put("parent_s_operation_part",    current.get("s_operation_part"));
        stmtInfo.put("parent_s_Merge_insert_part", current.get("Merge_insert_part"));
        stmtInfo.put("parent_s_Merge_update_part", current.get("Merge_update_part"));
        stmtInfo.put("parent_column_output",       current.get("column_output"));

        stmtInfo.put("child_statements",  new HashSet<String>());
        stmtInfo.put("used_in_routines",  new HashSet<String>());
        stmtInfo.put("source_tables",     new LinkedHashMap<>());
        stmtInfo.put("target_tables",     new LinkedHashMap<>());
        stmtInfo.put("columns_affected",  new HashSet<String>());
        stmtInfo.put("columns_output",    new LinkedHashMap<>());
        stmtInfo.put("joins",             new ArrayList<>());
        stmtInfo.put("atoms",             new LinkedHashMap<>());
        stmtInfo.put("source_subqueries", new LinkedHashMap<>());

        String parentStmt = currentStatement();
        if (parentStmt != null && statements.containsKey(parentStmt)) {
            @SuppressWarnings("unchecked")
            Set<String> children = (Set<String>) statements.get(parentStmt).get("child_statements");
            if (children != null) children.add(stmtName);

            current.put("parent_statement",           parentStmt);
            current.put("parent_statement_type",      current.get("statement_type"));
            current.put("parent_s_where",             current.get("where"));
            current.put("parent_is_union",            current.get("is_union"));
            current.put("parent_s_from",              current.get("from"));
            current.put("parent_s_selected_list",     current.get("selected_list"));
            current.put("parent_s_order",             current.get("order"));
            current.put("parent_s_group_by",          current.get("group_by"));
            current.put("parent_s_having",            current.get("having"));
            current.put("parent_s_operation_part",    current.get("s_operation_part"));
            current.put("parent_s_Merge_insert_part", current.get("Merge_insert_part"));
            current.put("parent_s_Merge_update_part", current.get("Merge_update_part"));
        }

        current.put("statement",      stmtName);
        current.put("statement_type", stmtType);

        statements.put(stmtName, stmtInfo);
        lineageStatements.put(stmtName, stmtInfo);

        String routine = currentRoutine();
        if (routine != null && parentStmt == null && routines.containsKey(routine)) {
            @SuppressWarnings("unchecked")
            Set<String> stmtsUsed = (Set<String>) routines.get(routine).get("statements_used");
            if (stmtsUsed != null) stmtsUsed.add(stmtName);
        }
        return stmtName;
    }

    public void exitStatement() {
        String parentStmt = (String) current.get("parent_statement");
        String currentStmt = currentStatement();
        if (currentStmt == null) return;

        if (parentStmt != null && statements.containsKey(parentStmt)) {
            Map<String, Object> pra = statements.get(parentStmt);
            current.put("from",              pra.get("parent_s_from"));
            current.put("selected_list",     pra.get("parent_s_selected_list"));
            current.put("order",             pra.get("parent_s_order"));
            current.put("group_by",          pra.get("parent_s_group_by"));
            current.put("having",            pra.get("parent_s_having"));
            current.put("where",             pra.get("parent_s_where"));
            current.put("statement_type",    pra.get("parent_statement_type"));
            current.put("s_operation_part",  pra.get("parent_s_operation_part"));
            current.put("Merge_insert_part", pra.get("parent_s_Merge_insert_part"));
            current.put("Merge_update_part", pra.get("parent_s_Merge_update_part"));
            current.put("parent_statement",      pra.get("parent_statement"));
            current.put("parent_statement_type", pra.get("parent_statement_type"));
            current.put("statement", parentStmt);
        } else {
            subqueryAliasStack().clear();
            current.put("subquery_alias",          null);
            current.put("statement",               null);
            current.put("statement_type",          null);
            current.put("where",                   null);
            current.put("is_union",                false);
            current.put("from",                    null);
            current.put("selected_list",           null);
            current.put("order",                   null);
            current.put("group_by",                null);
            current.put("having",                  null);
            current.put("in_dml_target",           false);
            current.put("parent_statement",        null);
            current.put("parent_statement_type",   null);
            current.put("parent_s_where",          null);
            current.put("parent_s_from",           null);
            current.put("parent_s_selected_list",  null);
            current.put("parent_s_order",          null);
            current.put("parent_s_group_by",       null);
            current.put("parent_s_having",         null);
            current.put("parent_is_union",         false);
            current.put("parent_s_operation_part", false);
            current.put("parent_column_output",    null);
            current.put("Merge_insert_part",       false);
            current.put("Merge_update_part",       false);
        }
    }

    public void exitRoutine() {
        // Restore parent routine from stack (аналог exitStatement → parent_statement)
        if (!routineStack.isEmpty()) {
            String[] parent = routineStack.pop();
            current.put("routine",      parent[0]);
            current.put("routine_type", parent[1]);
        } else {
            current.put("routine",      null);
            current.put("routine_type", null);
        }
    }

    // =========================================================================
    // РАЗДЕЛ 4: ТАБЛИЦЫ
    // =========================================================================

    @SuppressWarnings("unchecked")
    public void markTableAsTarget(String tableGeoid, String statementGeoid, String tableAlias) {
        if (tableGeoid == null || statementGeoid == null) return;
        if (!statements.containsKey(statementGeoid)) return;
        Map<String, Object> stmt = statements.get(statementGeoid);
        Map<String, Object> targets = (Map<String, Object>) stmt.computeIfAbsent(
                "target_tables", k -> new LinkedHashMap<>());
        if (!targets.containsKey(tableGeoid)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table_stmt", tableGeoid);
            entry.put("table_name", tableGeoid);
            List<String> al = new ArrayList<>();
            if (tableAlias != null) al.add(tableAlias.toUpperCase());
            entry.put("table_alias", al);
            targets.put(tableGeoid, entry);
        } else if (tableAlias != null) {
            List<String> al = (List<String>) ((Map<?, ?>) targets.get(tableGeoid)).get("table_alias");
            if (al != null && !al.contains(tableAlias.toUpperCase())) al.add(tableAlias.toUpperCase());
        }
        current.put("target_table", tableGeoid);
    }

    @SuppressWarnings("unchecked")
    public void markDatasetAsSource(String tableGeoid, String statementGeoid, String tableAlias) {
        if (tableGeoid == null || statementGeoid == null) return;
        if (!statements.containsKey(statementGeoid)) return;
        Map<String, Object> stmt = statements.get(statementGeoid);
        Map<String, Object> sources = (Map<String, Object>) stmt.computeIfAbsent(
                "source_tables", k -> new LinkedHashMap<>());
        if (!sources.containsKey(tableGeoid)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table_stmt", tableGeoid);
            entry.put("table_name", tableGeoid);
            List<String> al = new ArrayList<>();
            if (tableAlias != null) al.add(tableAlias.toUpperCase());
            entry.put("table_alias", al);
            sources.put(tableGeoid, entry);
        } else if (tableAlias != null) {
            List<String> al = (List<String>) ((Map<?, ?>) sources.get(tableGeoid)).get("table_alias");
            if (al != null && !al.contains(tableAlias.toUpperCase())) al.add(tableAlias.toUpperCase());
        }
    }

    public void processTableReference(String tableName, String tableAlias) {
        if (tableName == null || tableName.isBlank()) return;
        String tableGeoid = initTable(tableName, tableAlias, currentSchema(), "TABLE");
        if (tableGeoid == null) return;
        String stmt = currentStatement();
        if (stmt == null) return;

        List<String> dmlTypes = List.of("UPDATE", "INSERT", "DELETE", "MERGE");
        if (isInDmlTarget() && dmlTypes.contains(currentStatementType())) {
            markTableAsTarget(tableGeoid, stmt, tableAlias);
        } else {
            markDatasetAsSource(tableGeoid, stmt, tableAlias);
        }
    }

    // =========================================================================
    // РАЗДЕЛ 5: АТОМЫ
    // =========================================================================

    @SuppressWarnings("unchecked")
    public void addAtom(String text, int line, int col, int endLine, int endCol, boolean isComplex,
                        List<String> tokens, List<Map<String, String>> tokenDetails, int nestedAtomCount) {
        String parentCtx = getCurrentParentContext();
        Map<String, Object> atomContext = new LinkedHashMap<>();
        atomContext.put("text", text);
        atomContext.put("position", line + ":" + col);
        atomContext.put("sposition", endLine + ":" + endCol);
        atomContext.put("parent_context", parentCtx);
        atomContext.put("is_complex", isComplex);
        atomContext.put("is_column_reference", false);
        atomContext.put("status", null);
        atomContext.put("output_column_sequence", null);
        atomContext.put("tokens", tokens);
        atomContext.put("token_details", tokenDetails);
        atomContext.put("nested_atoms_count", nestedAtomCount);

        String atomKey = text + "~" + line + ":" + col;
        String stmt = currentStatement();
        if (stmt != null && statements.containsKey(stmt)) {
            Map<String, Object> atoms = (Map<String, Object>) statements.get(stmt).get("atoms");
            if (atoms != null) atoms.put(atomKey, atomContext);
        } else {
            Map<String, Object> unattached = (Map<String, Object>) current.get("unattached_atoms");
            if (unattached != null) unattached.put(atomKey, atomContext);
        }
        current.put("atom_context", atomContext);
        engine.onAtom(text, line, col, endLine, endCol, parentCtx != null ? parentCtx : "UNKNOWN",
                      isComplex, tokens, tokenDetails, nestedAtomCount);
    }

    // =========================================================================
    // РАЗДЕЛ 6: УТИЛИТЫ
    // =========================================================================

    public static String cleanIdentifier(String name) {
        if (name == null) return null;
        return name.replaceAll("[\"'`\\[\\]]", "").trim().toUpperCase();
    }

    // =========================================================================
    // РАЗДЕЛ 7: СОБЫТИЯ (вызываются из диалект-листенеров)
    // =========================================================================

    public void onStatementEnter(String type, String snippet, int lineStart, int lineEnd) {
        initStatement(type, snippet, lineStart, lineEnd, null);
        engine.onStatementEnter(type, snippet, lineStart, lineEnd, null);
    }

    /** STAB-13 Part B: variant with explicit alias (for inline FROM subqueries). */
    public void onStatementEnter(String type, String snippet, int lineStart, int lineEnd, String alias) {
        initStatement(type, snippet, lineStart, lineEnd, alias);
        engine.onStatementEnter(type, snippet, lineStart, lineEnd, alias);
    }

    public void onStatementExit() {
        exitStatement();
        engine.onStatementExit();
    }

    public void onRoutineEnter(String name, String type, String schemaGeoid,
                               String packageGeoid, int sourceLine) {
        initRoutine(name, type, schemaGeoid, packageGeoid, sourceLine);
        engine.onRoutineEnter(name, type, schemaGeoid, packageGeoid, sourceLine);
    }

    public void onRoutineExit() {
        exitRoutine();
        engine.onRoutineExit();
    }

    public void onRoutineParameter(String name, String type, String mode) {
        engine.onRoutineParameter(name, type, mode);
    }

    public void onRoutineVariable(String name, String type) {
        engine.onRoutineVariable(name, type);
    }

    public void onRoutineReturnType(String returnType) {
        engine.onRoutineReturnType(returnType);
    }

    /**
     * STAB-9: регистрирует вызов процедуры/функции из текущей routine.
     * Вызывается из PlSqlSemanticListener.enterCall_statement().
     */
    public void onCallStatement(String calledName, int line) {
        String callerGeoid = currentRoutine();
        if (callerGeoid == null || calledName == null || calledName.isBlank()) return;
        engine.onCallStatement(callerGeoid, calledName, line);
    }

    public void onTableReference(String tableName, String tableAlias, int line, int endLine) {
        String role = isInDmlTarget() ? "TARGET" : "SOURCE";
        processTableReference(tableName, tableAlias);
        engine.onTableReference(tableName, tableAlias, role, line, endLine);

        // MERGE INTO (subquery) alias — propagate subquery alias → target table geoid on parent scope
        if ("TARGET".equals(role)) {
            String mergeSubqAlias = getMergeIntoSubqueryAlias();
            if (mergeSubqAlias != null) {
                engine.registerMergeIntoSubqueryAlias(mergeSubqAlias, tableName);
                clearMergeIntoSubqueryAlias();
            }
        }
    }

    public void onColumnRef(String columnName, int line, int endLine) {
        engine.onColumnRef(columnName, line, endLine);
    }

    public void onJoinEnter(String joinText, int line, int endLine) {
        current.put("in_join_context", true);
        engine.onJoinStart(joinText, line, endLine);
    }

    public void onJoinExit() {
        current.put("in_join_context", false);
        engine.onJoinExit();
    }

    // CTE — alias = имя CTE (из ANTLR ctx.query_name()):
    public void onCTEEnter(String cteText, int line, int endLine) {
        String cteName = (String) current.get("subquery_alias");  // ← имя CTE уже в current
        initStatement("CTE", cteText, line, endLine, null);
        subqueryAliasStack().add(" ");
        engine.onCTEStart(cteName, cteText, line, endLine);       // ← передаём имя
    }

    public void onCTEExit() {
        List<String> stack = subqueryAliasStack();
        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
        exitStatement();
        engine.onCTEExit();
    }

    // Subquery — alias = alias подзапроса (если есть):
    public void onSubqueryEnter(String subqueryText, int line, int endLine) {
        // Сохраняем in_dml_target — обычный subquery всегда читает (SOURCE)
        // Исключение: MERGE INTO (subquery) — там in_dml_target остаётся true
        current.put("saved_dml_target_for_subquery", isInDmlTarget());
        if (getMergeIntoSubqueryAlias() == null) {
            current.put("in_dml_target", false);
        }

        List<String> stack = subqueryAliasStack();
        String alias = !stack.isEmpty() ? stack.get(stack.size() - 1) : null;
        initStatement("SUBQUERY", subqueryText, line, endLine, null);
        engine.onSubqueryStart(alias, subqueryText, line, endLine);  // ← передаём alias
    }

    public void onSubqueryExit() {
        exitStatement();
        engine.onSubqueryExit();

        // Восстанавливаем in_dml_target
        Boolean saved = (Boolean) current.get("saved_dml_target_for_subquery");
        if (saved != null) {
            current.put("in_dml_target", saved);
            current.remove("saved_dml_target_for_subquery");
        }
    }

    public void onFromEnter(int line)    { current.put("from", line);           engine.onFromStart(); }
    public void onFromExit()             { current.put("from", null);            engine.onFromExit(); }

    public void onSelectedListEnter(int line) { current.put("selected_list", line); }
    public void onSelectedListExit()          { current.put("selected_list", null); }

    /**
     * STAB-11: голый SELECT * (без table prefix) — нет select_list_elements событий.
     * Регистрируем wildcard output column "*" с order=1.
     * Аналог Python: exitSelected_list when ctx.getText() == '*'.
     */
    public void onBareStar(int line, int col) {
        String stmt = engine.getScopeManager().currentStatement();
        if (stmt == null) return;
        var stmtInfo = engine.getBuilder().getStatements().get(stmt);
        if (stmtInfo == null) return;
        if (stmtInfo.getColumnsOutput().containsKey("*")) return; // уже есть

        Map<String, Object> starCol = new java.util.LinkedHashMap<>();
        starCol.put("order",       1);
        starCol.put("name",        "*");
        starCol.put("expression",  "*");
        starCol.put("source_type", "star");
        starCol.put("alias",       null);
        stmtInfo.addColumnOutput("*", starCol);
        logger.debug("Bare SELECT * registered for stmt '{}'", stmt);
    }

    public void onWhereEnter(int line)   { current.put("where", line); }
    public void onWhereExit()            { current.put("where", null); }
    public void onHavingEnter(int line)  { current.put("having", line); }
    public void onHavingExit()           { current.put("having", null); }
    public void onOrderByEnter(int line) { current.put("order", line); }
    public void onOrderByExit()          { current.put("order", null); }
    public void onGroupByEnter(int line) { current.put("group_by", line); }
    public void onGroupByExit()          { current.put("group_by", null); }

    public void onMergeInsertEnter() { current.put("Merge_insert_part", true); }
    public void onMergeInsertExit()  { current.put("Merge_insert_part", false); }
    public void onMergeUpdateEnter() { current.put("Merge_update_part", true); }
    public void onMergeUpdateExit()  { current.put("Merge_update_part", false); }

    public void onValuesClauseEnter() {
        current.put("Values_clause", true);
        current.put("Values_clause_cnt", 0);
    }
    public void onValuesClauseExit() { current.put("Values_clause", false); }

    /**
     * STAB-6: вызывается из exitExpression когда expression является дочерним
     * элементом values_clause. Инкрементирует позиционный счётчик и привязывает
     * атомы в диапазоне позиции к output_column_sequence = cnt.
     *
     * Аналог Python: exitExpression VALUES position counter + atom binding.
     */
    public void onValuesExpressionExit(int startLine, int startCol,
                                       int endLine,   int endCol) {
        int cnt = (int) current.getOrDefault("Values_clause_cnt", 0) + 1;
        current.put("Values_clause_cnt", cnt);

        String stmt = engine.getScopeManager().currentStatement();
        if (stmt == null) return;

        engine.getAtomProcessor().bindAtomsToOutputColumn(
                stmt, startLine, startCol, endLine, endCol, cnt);
        logger.debug("VALUES expr #{}: lines {}-{} bound in stmt '{}'",
                cnt, startLine, endLine, stmt);
    }

    public void onDmlTargetEnter() { current.put("in_dml_target", true); }
    public void onDmlTargetExit()  { current.put("in_dml_target", false); }

    public void onSchemaEnter(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return;
        initDatabase("");
        current.put("schema", initSchema(schemaName, null));
    }
    public void onSchemaExit() { current.put("schema", null); }

    /**
     * STAB-8: вызывается из enterCreate_view до onStatementEnter.
     * Регистрирует view как DaliTable и помечает как текущий view-target
     * для копирования output columns внутреннего SELECT.
     */
    public void onViewDeclaration(String viewName, String schemaGeoid, int line) {
        engine.onViewDeclaration(viewName, schemaGeoid, line);
    }

    public void onColumnAlias(String alias) {
        current.put("column_alias", alias != null ? cleanIdentifier(alias) : null);
    }

    public void onAtom(String text, int line, int col, int endLine, int endCol, boolean isComplex,
                       List<String> tokens, List<Map<String, String>> tokenDetails, int nestedAtomCount) {
        addAtom(text, line, col, endLine, endCol, isComplex, tokens, tokenDetails, nestedAtomCount);
    }

    // ═══ Output column binding (port from Python exitSelect_list_elements) ═══

    public void onOutputColumnEnter(int line, int col) {
        current.put("column_output_start_line", line);
        current.put("column_output_start_col", col);
    }

    public void onOutputColumnExit(int startLine, int startCol, int endLine, int endCol,
                                    String expressionText) {
        onOutputColumnExit(startLine, startCol, endLine, endCol, expressionText, false);
    }

    @SuppressWarnings("unchecked")
    public void onOutputColumnExit(int startLine, int startCol, int endLine, int endCol,
                                    String expressionText, boolean isTableStar) {
        String stmt = currentStatement();
        if (stmt == null) return;
        String engineStmt = engine.getScopeManager().currentStatement();
        if (engineStmt == null) return;

        String alias = (String) current.get("column_alias");

        var stmtInfo = engine.getBuilder().getStatements().get(engineStmt);
        if (stmtInfo == null) return;

        String colName = alias != null ? alias : expressionText;
        int order = stmtInfo.getColumnsOutput().size() + 1;
        Map<String, Object> columnInfo = new LinkedHashMap<>();
        columnInfo.put("order", order);
        columnInfo.put("name", colName);
        columnInfo.put("expression", expressionText);
        columnInfo.put("alias", alias);
        columnInfo.put("source_type", isTableStar ? "table_star" : "expression");

        stmtInfo.addColumnOutput(colName, columnInfo);

        // Position-based atom binding
        engine.getAtomProcessor().bindAtomsToOutputColumn(
                engineStmt, startLine, startCol, endLine, endCol, order);

        current.put("column_alias", null);
        current.put("column_output", columnInfo);
    }

    // ═══ JOIN complete (port from Python exitJoin_clause) ═══

    public void onJoinComplete(String joinType, List<String> conditions, String sourceAlias, int lineStart) {
        String currentStmt = currentStatement();
        if (currentStmt == null) return;

        // Target = table being joined (set by enterTable_ref_aux within join)
        String targetName = currentTable();
        String targetAlias = currentTableAlias();

        // Resolve source
        String sourceGeoid = null;
        String sourceType = "table";
        if (sourceAlias != null && !sourceAlias.isBlank()) {
            var resolved = engine.getNameResolver().resolve(sourceAlias, "any", currentStmt);
            if (resolved.isResolved()) {
                sourceGeoid = resolved.getGeoid();
                sourceType = resolved.getType().toLowerCase();
            }
        }

        // Resolve target
        String targetGeoid = null;
        String targetType = "table";
        if (targetName != null && !targetName.isBlank()) {
            var resolved = engine.getNameResolver().resolve(targetName, "any", currentStmt);
            if (resolved.isResolved()) {
                targetGeoid = resolved.getGeoid();
                targetType = resolved.getType().toLowerCase();
            }
        }

        String conditionText = conditions != null ? String.join(" AND ", conditions) : "";
        engine.onJoinComplete(joinType, targetGeoid, targetAlias, targetType,
                sourceGeoid, sourceAlias, sourceType, conditionText, lineStart);
    }

    // =========================================================================
    // РАЗДЕЛ 8: РЕЗУЛЬТАТ
    // =========================================================================

    public Map<String, Map<String, Object>> getStatements() { return Collections.unmodifiableMap(statements); }
    public Map<String, Map<String, Object>> getRoutines()   { return Collections.unmodifiableMap(routines); }
    public Map<String, Map<String, Object>> getTables()     { return Collections.unmodifiableMap(tables); }
    public Map<String, Map<String, Object>> getSchemas()    { return Collections.unmodifiableMap(schemas); }
    public Map<String, Map<String, Object>> getDatabases()  { return Collections.unmodifiableMap(databases); }
}