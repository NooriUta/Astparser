package com.hound.semantic.engine;

import com.hound.semantic.model.RoutineInfo;
import com.hound.semantic.model.StatementInfo;
import com.hound.semantic.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AtomProcessor — сбор, классификация и разрешение атомарных выражений.
 * Портирование Python: enterAtom, _analyze_atom_structure, _resolve_atom_reference,
 * _process_atoms_on_exit, get_atoms_data.
 */
public class AtomProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AtomProcessor.class);

    // ═══════ Constants — port from Python ═══════

    private static final Set<String> CONSTANT_TYPES = Set.of(
            "CHAR_STRING", "UNSIGNED_INTEGER", "NUMERIC_LITERAL",
            "DATE_LITERAL", "TIMESTAMP_LITERAL", "NULL", "SYSDATE",
            "NULL_", "TRUE", "MINVALUE", "DEFAULT", "APPROXIMATE_NUM_LIT",
            "CURRENT_DATE", "FALSE"
    );

    private static final Set<String> IDENTIFIER_TYPES = Set.of(
            "DELIMITED_ID", "REGULAR_ID", "ID", "NAME", "VALUE", "BRANCH",
            "CLIENT", "SYSTEM", "TYPE", "OTHER", "ROLE", "DESCRIPTION",
            "OWNERSHIP", "SUBTYPE", "SCALE", "USER", "OWNER", "DATA"
    );

    private static final Set<String> SYSTEM_PSEUDO_COLUMNS = Set.of(
            "LEVEL", "UNBOUNDED", "ROWID", "ROW_NUMBER", "ROWNUM"
    );

    // ═══════ State ═══════

    // statement_geoid → { atom_key → atom_data }
    private final Map<String, Map<String, Map<String, Object>>> atomsByStatement = new LinkedHashMap<>();

    // Unattached atoms (outside any statement)
    private final Map<String, Map<String, Object>> unattachedAtoms = new LinkedHashMap<>();

    // External dependencies
    private NameResolver nameResolver;
    private StructureAndLineageBuilder builder;
    private ScopeManager scopeManager;

    public void wire(NameResolver nameResolver, StructureAndLineageBuilder builder, ScopeManager scopeManager) {
        this.nameResolver = nameResolver;
        this.builder = builder;
        this.scopeManager = scopeManager;
    }

    // =========================================================================
    // ATOM REGISTRATION
    // =========================================================================

    /**
     * Регистрирует атом с полными token details.
     * Порт Python: enterAtom → сохранение в self.statements[stmt]['atoms']
     */
    public void registerAtom(String text, int line, int col, int endLine, int endCol,
                             String context, String statementGeoid, String parentContext,
                             boolean isComplex, List<String> tokens,
                             List<Map<String, String>> tokenDetails, int nestedAtomCount) {
        if (text == null || text.isBlank()) return;

        if (statementGeoid == null) {
            registerUnattachedAtom(text, buildAtomData(text, line, col, endLine, endCol,
                    context, null, parentContext, isComplex, tokens, tokenDetails, nestedAtomCount));
            return;
        }

        Map<String, Map<String, Object>> stmtAtoms = atomsByStatement
                .computeIfAbsent(statementGeoid, k -> new LinkedHashMap<>());

        String atomKey = text.toUpperCase() + "~" + line + ":" + col;
        if (stmtAtoms.containsKey(atomKey)) return;

        Map<String, Object> atomData = buildAtomData(text, line, col, endLine, endCol,
                context, statementGeoid, parentContext, isComplex, tokens, tokenDetails, nestedAtomCount);

        // Если есть вложенные атомы — сразу помечаем как обработанный
        if (nestedAtomCount > 0) {
            atomData.put("status", "Обработано");
            atomData.put("is_complex_atom", true);
        }

        // Классификация по token details
        classifyAtom(atomData);

        stmtAtoms.put(atomKey, atomData);
    }

    /**
     * Backward-compatible registerAtom без token details.
     */
    public void registerAtom(String text, int line, int col, int endLine, int endCol,
                             String context, String statementGeoid, String parentContext) {
        boolean isComplex = text != null && (text.contains(".") || text.contains("("));
        registerAtom(text, line, col, endLine, endCol, context, statementGeoid, parentContext,
                isComplex, List.of(), List.of(), 0);
    }

    private Map<String, Object> buildAtomData(String text, int line, int col, int endLine, int endCol,
                                               String context, String statementGeoid, String parentContext,
                                               boolean isComplex, List<String> tokens,
                                               List<Map<String, String>> tokenDetails, int nestedAtomCount) {
        Map<String, Object> atomData = new LinkedHashMap<>();
        atomData.put("atom_text", text);
        atomData.put("position", line + ":" + col);
        atomData.put("sposition", endLine + ":" + endCol);
        atomData.put("atom_context", context);
        atomData.put("parent_context", parentContext);
        atomData.put("is_column_reference", false);
        atomData.put("is_function_call", false);
        atomData.put("is_constant", false);
        atomData.put("is_routine_param", false);
        atomData.put("is_routine_var", false);
        atomData.put("table_name", null);
        atomData.put("column_name", null);
        atomData.put("table_geoid", null);
        atomData.put("statement_geoid", statementGeoid);
        atomData.put("status", null);
        atomData.put("resolution", null);
        atomData.put("is_complex", isComplex);
        atomData.put("nested_atoms_count", nestedAtomCount);
        atomData.put("tokens", tokens != null ? tokens : List.of());
        atomData.put("token_details", tokenDetails != null ? tokenDetails : List.of());
        atomData.put("output_column_sequence", null);
        return atomData;
    }

    public void registerUnattachedAtom(String text, Map<String, Object> atomData) {
        unattachedAtoms.put(text, atomData);
    }

    // =========================================================================
    // ATOM CLASSIFICATION — Port from Python _analyze_atom_structure
    // =========================================================================

    /**
     * Порт Python: PlSqlAnalyzerListener._analyze_atom_structure()
     * Классифицирует атом по token types.
     */
    @SuppressWarnings("unchecked")
    private void classifyAtom(Map<String, Object> atomData) {
        List<String> tokens = (List<String>) atomData.get("tokens");
        List<Map<String, String>> tokenDetails = (List<Map<String, String>>) atomData.get("token_details");

        if (tokens == null || tokenDetails == null || tokens.isEmpty()) {
            classifyByText(atomData);
            return;
        }

        // --- Простая ссылка на колонку (1 токен, тип REGULAR_ID/DELIMITED_ID/ID) ---
        if (tokens.size() == 1 && isIdentifierType(tokenDetails.get(0).get("type"))) {
            atomData.put("is_column_reference", true);
            atomData.put("column_name", tokens.get(0));
            return;
        }

        // --- Квалифицированная ссылка table.column (3 токена: ID.PERIOD.ID) ---
        if (tokens.size() == 3
                && isIdentifierType(tokenDetails.get(0).get("type"))
                && "PERIOD".equals(tokenDetails.get(1).get("type"))
                && isIdentifierType(tokenDetails.get(2).get("type"))) {
            atomData.put("is_column_reference", true);
            atomData.put("table_name", tokens.get(0));
            atomData.put("column_name", tokens.get(2));
            return;
        }

        // --- Полностью квалифицированная schema.table.column (5 токенов) ---
        if (tokens.size() == 5
                && isIdentifierType(tokenDetails.get(0).get("type"))
                && "PERIOD".equals(tokenDetails.get(1).get("type"))
                && isIdentifierType(tokenDetails.get(2).get("type"))
                && "PERIOD".equals(tokenDetails.get(3).get("type"))
                && isIdentifierType(tokenDetails.get(4).get("type"))) {
            atomData.put("is_column_reference", true);
            atomData.put("table_name", tokens.get(2));   // table — средний элемент
            atomData.put("column_name", tokens.get(4));
            return;
        }

        // --- Вызов функции: ID LEFT_PAREN ... ---
        if (tokens.size() >= 3
                && isIdentifierType(tokenDetails.get(0).get("type"))
                && "LEFT_PAREN".equals(tokenDetails.get(1).get("type"))) {
            atomData.put("is_function_call", true);
            atomData.put("function_name", tokens.get(0));
            return;
        }

        // --- Константы (1 токен) ---
        if (tokens.size() == 1 && CONSTANT_TYPES.contains(tokenDetails.get(0).get("type"))) {
            atomData.put("is_constant", true);
            return;
        }

        // --- DATE 'string' (ANSI date literal) ---
        if (tokenDetails.size() >= 2
                && "DATE".equals(tokenDetails.get(0).get("type"))
                && "CHAR_STRING".equals(tokenDetails.get(1).get("type"))) {
            atomData.put("is_constant", true);
            return;
        }

        // --- Системные псевдоколонки ---
        if (tokens.size() == 1 && SYSTEM_PSEUDO_COLUMNS.contains(tokens.get(0).toUpperCase())) {
            atomData.put("is_constant", true);
            return;
        }
    }

    /**
     * Fallback classification from text when no token_details available.
     */
    private void classifyByText(Map<String, Object> atomData) {
        String text = (String) atomData.get("atom_text");
        if (text == null) return;

        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            atomData.put("is_constant", true);
        } else if (text.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            atomData.put("is_constant", true);
        }
        if (text.contains("(") && text.contains(")")) {
            atomData.put("is_function_call", true);
        }
    }

    private boolean isIdentifierType(String type) {
        if (type == null) return false;
        return IDENTIFIER_TYPES.contains(type) || type.endsWith("_LETTER");
    }

    // =========================================================================
    // ATOM RESOLUTION — Port from Python _resolve_atom_reference
    // =========================================================================

    /**
     * Разрешает атомы при выходе из statement.
     * Аналог Python: _process_atoms_on_exit(statement_name)
     */
    public void resolveAtomsOnStatementExit(String statementGeoid) {
        Map<String, Map<String, Object>> stmtAtoms = atomsByStatement.get(statementGeoid);
        if (stmtAtoms == null || stmtAtoms.isEmpty()) return;
        if (nameResolver == null) return;

        logger.debug("Resolving {} atoms for statement {}", stmtAtoms.size(), statementGeoid);

        for (var entry : stmtAtoms.entrySet()) {
            Map<String, Object> atomData = entry.getValue();

            // Пропускаем уже обработанные
            if (atomData.get("status") != null) continue;

            // Пропускаем константы
            if (Boolean.TRUE.equals(atomData.get("is_constant"))) {
                atomData.put("status", "constant");
                continue;
            }
            // Пропускаем вызовы функций
            if (Boolean.TRUE.equals(atomData.get("is_function_call"))) {
                atomData.put("status", "function_call");
                continue;
            }

            // Попытка resolve
            Map<String, Object> resolution = resolveAtomReference(entry.getKey(), atomData, statementGeoid);

            if (resolution != null && Boolean.TRUE.equals(resolution.get("resolved"))) {
                atomData.put("status", "Обработано");
                atomData.put("resolution", resolution);
                atomData.put("table_geoid", resolution.get("table_geoid"));
                atomData.put("column_name", resolution.get("column_name"));
                atomData.put("table_name", resolution.get("table_name"));
                atomData.put("is_column_reference", Boolean.TRUE.equals(resolution.get("is_column_reference")));
                atomData.put("is_routine_param", Boolean.TRUE.equals(resolution.get("is_routine_param")));
                atomData.put("is_routine_var", Boolean.TRUE.equals(resolution.get("is_routine_var")));

                // Добавляем колонку в structure если resolved to table
                String tableGeoid = (String) resolution.get("table_geoid");
                String columnName = (String) resolution.get("column_name");
                if (tableGeoid != null && columnName != null && builder != null) {
                    builder.addColumn(tableGeoid, columnName, (String) atomData.get("atom_text"), null);
                }

                logger.debug("Atom resolved: {} → table={}, column={}", entry.getKey(), tableGeoid, columnName);
            } else {
                String parentCtx = (String) atomData.get("parent_context");
                if ("SELECT".equals(parentCtx) || "INSERT".equals(parentCtx)
                        || "UPDATE".equals(parentCtx) || "MERGE".equals(parentCtx)) {
                    logger.warn("Could not resolve atom: {} in context {}", entry.getKey(), parentCtx);
                }
                atomData.put("status", "unresolved");
                if (resolution != null) {
                    atomData.put("resolution", resolution);
                }
            }
        }
    }

    /**
     * Порт Python: PlSqlAnalyzerListener._resolve_atom_reference()
     * ПОЛНАЯ логика разрешения атомов.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveAtomReference(String atomKey, Map<String, Object> atomData,
                                                      String statementGeoid) {
        String atomText = (String) atomData.get("atom_text");
        if (atomText == null) atomText = atomKey;

        List<String> tokens = (List<String>) atomData.get("tokens");
        List<Map<String, String>> tokenDetails = (List<Map<String, String>>) atomData.get("token_details");
        boolean isComplex = Boolean.TRUE.equals(atomData.get("is_complex"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("atom_text", atomText);
        result.put("is_complex", isComplex);
        result.put("resolved", false);
        result.put("table_geoid", null);
        result.put("statement_geoid", statementGeoid);
        result.put("column_name", null);
        result.put("reference_type", null);
        result.put("reason", null);
        result.put("table_name", null);

        // Если нет token details — fallback к text-based resolution
        if (tokens == null || tokenDetails == null || tokens.isEmpty()) {
            return resolveByText(atomText, statementGeoid, result);
        }

        // ═══ 1. COMPLEX ATOMS (>= 3 tokens) ═══
        if (isComplex && tokens.size() >= 3) {
            // Проверка: последний токен == NEXTVAL → sequence
            if ("NEXTVAL".equalsIgnoreCase(tokens.get(tokens.size() - 1))) {
                result.put("resolved", true);
                result.put("reference_type", "sequence");
                result.put("reason", "Выдача нового сиквенса");
                return result;
            }

            // Подсчёт точек
            long dotCount = tokenDetails.stream()
                    .filter(td -> "PERIOD".equals(td.get("type"))).count();

            // schema.table.column (>= 2 точки, >= 5 токенов)
            if (dotCount >= 2 && tokens.size() >= 5) {
                String tableRef = tokens.get(0) + "." + tokens.get(2);
                String columnName = tokens.get(4);
                ResolvedRef resolved = nameResolver.resolve(tableRef, "table", statementGeoid);
                if (resolved.isResolved()) {
                    result.put("resolved", true);
                    result.put("table_geoid", resolved.getGeoid());
                    result.put("table_name", tableRef);
                    result.put("column_name", columnName);
                    result.put("reference_type", "tables");
                    result.put("is_column_reference", true);
                    result.put("reason", "schema.table.column resolved: " + tableRef);
                    return result;
                }
            }

            // table.column (1 точка, >= 3 токена)
            if (dotCount == 1 && tokens.size() >= 3) {
                String tableAlias = tokens.get(0);
                String columnName = tokens.get(2);
                ResolvedRef resolved = nameResolver.resolve(tableAlias, "any", statementGeoid);
                if (resolved.isResolved()) {
                    result.put("resolved", true);
                    result.put("table_geoid", resolved.getGeoid());
                    result.put("table_name", tableAlias);
                    result.put("column_name", columnName);
                    result.put("reference_type", resolved.getType().toLowerCase() + "s");
                    result.put("is_column_reference", true);
                    result.put("reason", "table.column resolved via " + resolved.getType());
                    return result;
                }
                result.put("reason", "Таблица/подзапрос не найден для: " + tableAlias);
                return result;
            }

            // DATE 'string' (ANSI date constant)
            if ("DATE".equalsIgnoreCase(tokenDetails.get(0).get("type"))
                    && tokenDetails.size() >= 2
                    && "CHAR_STRING".equals(tokenDetails.get(1).get("type"))) {
                result.put("resolved", true);
                result.put("reason", "Заглушка для константы ANSI Дата");
                return result;
            }
        }

        // ═══ 2. SIMPLE ATOMS (1 token, identifier) ═══
        String firstTokenType = tokenDetails.get(0).get("type");

        if (!isComplex && tokens.size() == 1
                && (isIdentifierType(firstTokenType)
                    || (firstTokenType != null && firstTokenType.endsWith("_LETTER")))) {

            // 2a. Проверка routine variables/parameters
            if (scopeManager != null) {
                String currentRoutine = scopeManager.currentRoutine();
                if (currentRoutine != null && builder != null) {
                    RoutineInfo routineInfo = builder.getRoutines().get(currentRoutine);
                    if (routineInfo != null) {
                        // TODO: RoutineInfo needs hasVariable/hasParameter methods
                        // when implemented, resolve as routine_var / routine_param
                    }
                }
            }

            // 2b. Поиск по таблицам текущего statement
            if (builder != null) {
                StatementInfo stmtInfo = builder.getStatements().get(statementGeoid);
                if (stmtInfo != null) {
                    String colName = tokens.get(0).toUpperCase();

                    // Ищем по source tables
                    for (String tblGeoid : stmtInfo.getSourceTableGeoids()) {
                        TableInfo tableInfo = builder.getTables().get(tblGeoid);
                        if (tableInfo != null) {
                            result.put("resolved", true);
                            result.put("table_geoid", tblGeoid);
                            result.put("table_name", tableInfo.tableName());
                            result.put("column_name", colName);
                            result.put("reference_type", "tables");
                            result.put("is_column_reference", true);
                            result.put("reason", "Простая ссылка resolved: таблица " + tableInfo.tableName());
                            return result;
                        }
                    }

                    // Также проверяем target tables (для UPDATE SET, INSERT)
                    for (String tblGeoid : stmtInfo.getTargetTableGeoids()) {
                        TableInfo tableInfo = builder.getTables().get(tblGeoid);
                        if (tableInfo != null) {
                            result.put("resolved", true);
                            result.put("table_geoid", tblGeoid);
                            result.put("table_name", tableInfo.tableName());
                            result.put("column_name", colName);
                            result.put("reference_type", "tables");
                            result.put("is_column_reference", true);
                            result.put("reason", "Простая ссылка resolved: target таблица " + tableInfo.tableName());
                            return result;
                        }
                    }
                }
            }

            // Fallback: implicit table resolution
            ResolvedRef implicitRef = nameResolver.resolveImplicitTable(statementGeoid);
            if (implicitRef.isResolved()) {
                result.put("resolved", true);
                result.put("table_geoid", implicitRef.getGeoid());
                result.put("column_name", tokens.get(0).toUpperCase());
                result.put("reference_type", "implicit");
                result.put("is_column_reference", true);
                result.put("reason", "Простая ссылка resolved через implicit table");
                return result;
            }

            result.put("reason", "Простая ссылка: таблица не определена");
            return result;
        }

        // ═══ 3. CONSTANTS ═══
        if (!isComplex && tokens.size() == 1 && CONSTANT_TYPES.contains(firstTokenType)) {
            result.put("resolved", true);
            result.put("reason", "Константа: " + firstTokenType);
            return result;
        }

        // ═══ 4. SYSTEM PSEUDO-COLUMNS ═══
        if (tokens.size() == 1 && SYSTEM_PSEUDO_COLUMNS.contains(tokens.get(0).toUpperCase())) {
            result.put("resolved", true);
            result.put("reason", "Системная псевдоколонка: " + tokens.get(0));
            return result;
        }

        result.put("reason", "Необрабатываемый тип атома");
        return result;
    }

    /**
     * Fallback resolution при отсутствии token_details (backward compat).
     */
    private Map<String, Object> resolveByText(String atomText, String statementGeoid,
                                               Map<String, Object> result) {
        String text = atomText.trim();
        String[] parts = text.split("\\.", 2);
        String tablePart = parts.length > 1 ? parts[0] : null;
        String columnPart = parts.length > 1 ? parts[1] : parts[0];

        if (tablePart != null) {
            ResolvedRef tableRef = nameResolver.resolve(tablePart, "any", statementGeoid);
            if (tableRef.isResolved()) {
                result.put("resolved", true);
                result.put("table_geoid", tableRef.getGeoid());
                result.put("table_name", tablePart);
                result.put("column_name", columnPart);
                result.put("reference_type", tableRef.getType());
                result.put("is_column_reference", true);
                return result;
            }
        }

        ResolvedRef implicitRef = nameResolver.resolveImplicitTable(statementGeoid);
        if (implicitRef.isResolved()) {
            result.put("resolved", true);
            result.put("table_geoid", implicitRef.getGeoid());
            result.put("column_name", columnPart);
            result.put("reference_type", "implicit");
            result.put("is_column_reference", true);
            return result;
        }

        result.put("reason", "Could not resolve table for atom");
        return result;
    }

    // =========================================================================
    // POSITION-BASED ATOM → COLUMN BINDING
    // Port from Python: exitSelect_list_elements → position-based atom binding
    // =========================================================================

    /**
     * Связывает atoms с output column по позиции (line:col range).
     */
    public void bindAtomsToOutputColumn(String statementGeoid,
                                         int startLine, int startCol,
                                         int endLine, int endCol, int columnOrder) {
        Map<String, Map<String, Object>> stmtAtoms = atomsByStatement.get(statementGeoid);
        if (stmtAtoms == null) return;

        for (var entry : stmtAtoms.entrySet()) {
            Map<String, Object> atomData = entry.getValue();

            // Уже привязан к другой колонке?
            if (atomData.get("output_column_sequence") != null) continue;

            String pos = (String) atomData.get("position");
            if (pos == null) continue;

            String[] parts = pos.split(":");
            if (parts.length < 2) continue;

            int atomLine;
            int atomCol;
            try {
                atomLine = Integer.parseInt(parts[0]);
                atomCol = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }

            if (isPositionInRange(atomLine, atomCol, startLine, startCol, endLine, endCol)) {
                atomData.put("output_column_sequence", columnOrder);
            }
        }
    }

    private boolean isPositionInRange(int line, int col,
                                       int startLine, int startCol,
                                       int endLine, int endCol) {
        if (line < startLine || line > endLine) return false;
        if (line == startLine && col < startCol) return false;
        if (line == endLine && col > endCol) return false;
        return true;
    }

    // =========================================================================
    // DATA RETRIEVAL — Port from Python get_atoms_data
    // =========================================================================

    /**
     * Порт Python: BaseSQLListener.get_atoms_data()
     * Полная сборка atoms для передачи через Arrow Flight.
     */
    public Map<String, Object> getAtomsData() {
        Map<String, Object> atomsData = new LinkedHashMap<>();
        int totalAtomsCount = 0;

        // 1. Atoms по statements
        for (var stmtEntry : atomsByStatement.entrySet()) {
            String stmtGeoid = stmtEntry.getKey();
            Map<String, Map<String, Object>> stmtAtoms = stmtEntry.getValue();
            if (stmtAtoms.isEmpty()) continue;

            int stmtTotal = stmtAtoms.size();
            long stmtResolved = stmtAtoms.values().stream()
                    .filter(a -> "Обработано".equals(a.get("status")))
                    .count();
            totalAtomsCount += stmtTotal;

            Map<String, Object> stmtData = new LinkedHashMap<>();
            stmtData.put("source_type", "statement");
            stmtData.put("source_geoid", stmtGeoid);
            stmtData.put("atoms", stmtAtoms);
            stmtData.put("total_atoms", stmtTotal);
            stmtData.put("resolved_atoms", stmtResolved);

            atomsData.put("statement:" + stmtGeoid, stmtData);
        }

        // 2. Atoms по routines (группировка statements, принадлежащих routine)
        if (builder != null) {
            Map<String, Map<String, Map<String, Object>>> routineAtoms = new LinkedHashMap<>();
            for (var stmtEntry : atomsByStatement.entrySet()) {
                StatementInfo stmtInfo = builder.getStatements().get(stmtEntry.getKey());
                if (stmtInfo != null && stmtInfo.getRoutineGeoid() != null) {
                    routineAtoms
                            .computeIfAbsent(stmtInfo.getRoutineGeoid(), k -> new LinkedHashMap<>())
                            .putAll(stmtEntry.getValue());
                }
            }
            for (var routineEntry : routineAtoms.entrySet()) {
                Map<String, Object> routineData = new LinkedHashMap<>();
                routineData.put("source_type", "routine");
                routineData.put("source_geoid", routineEntry.getKey());
                routineData.put("atoms", routineEntry.getValue());
                routineData.put("total_atoms", routineEntry.getValue().size());
                routineData.put("resolved_atoms",
                        routineEntry.getValue().values().stream()
                                .filter(a -> "Обработано".equals(a.get("status")))
                                .count());
                atomsData.put("routine:" + routineEntry.getKey(), routineData);
            }
        }

        // 3. Unattached atoms
        if (!unattachedAtoms.isEmpty()) {
            totalAtomsCount += unattachedAtoms.size();
            Map<String, Object> unattachedData = new LinkedHashMap<>();
            unattachedData.put("source_type", "unattached");
            unattachedData.put("source_geoid", "unattached");
            unattachedData.put("atoms", unattachedAtoms);
            unattachedData.put("total_atoms", unattachedAtoms.size());
            unattachedData.put("resolved_atoms",
                    unattachedAtoms.values().stream()
                            .filter(a -> "Обработано".equals(a.get("status")))
                            .count());
            atomsData.put("unattached", unattachedData);
        }

        // 4. Summary
        int statementsWithAtoms = (int) atomsData.keySet().stream()
                .filter(k -> k.startsWith("statement:")).count();
        int routinesWithAtoms = (int) atomsData.keySet().stream()
                .filter(k -> k.startsWith("routine:")).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_sources", atomsData.size());
        summary.put("total_atoms", totalAtomsCount);
        summary.put("statements_with_atoms", statementsWithAtoms);
        summary.put("routines_with_atoms", routinesWithAtoms);
        summary.put("has_unattached", atomsData.containsKey("unattached"));
        atomsData.put("summary", summary);

        return atomsData;
    }

    /**
     * Backward-compatible getAtoms — delegates to getAtomsData.
     */
    public Map<String, Object> getAtoms() {
        return getAtomsData();
    }

    /**
     * Возвращает атомы для конкретного statement.
     */
    public Map<String, Map<String, Object>> getAtomsForStatement(String statementGeoid) {
        return atomsByStatement.getOrDefault(statementGeoid, Map.of());
    }

    public Map<String, Map<String, Object>> getUnattachedAtoms() {
        return unattachedAtoms;
    }

    public void clear() {
        atomsByStatement.clear();
        unattachedAtoms.clear();
    }
}
