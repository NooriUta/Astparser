package com.hound.semantic.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AtomProcessor — сбор и разрешение атомарных выражений.
 * Портирование Python: _process_atoms_on_exit, get_atoms_data, enterAtom.
 */
public class AtomProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AtomProcessor.class);

    // statement_geoid → { atom_key → atom_data }
    private final Map<String, Map<String, Map<String, Object>>> atomsByStatement = new LinkedHashMap<>();

    // Внешние зависимости
    private NameResolver nameResolver;
    private StructureAndLineageBuilder builder;
    private ScopeManager scopeManager;

    public void wire(NameResolver nameResolver, StructureAndLineageBuilder builder, ScopeManager scopeManager) {
        this.nameResolver = nameResolver;
        this.builder = builder;
        this.scopeManager = scopeManager;
    }

    /**
     * Регистрирует атом.
     * Аналог Python: enterAtom → сохранение в self.statements[stmt]['atoms']
     */
    public void registerAtom(String text, int line, int col, int endLine, int endCol,
                             String context, String statementGeoid, String parentContext) {
        if (text == null || text.isBlank() || statementGeoid == null) return;

        Map<String, Map<String, Object>> stmtAtoms = atomsByStatement
                .computeIfAbsent(statementGeoid, k -> new LinkedHashMap<>());

        String atomKey = text.toUpperCase();
        if (stmtAtoms.containsKey(atomKey)) return; // уже зарегистрирован

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
        atomData.put("is_complex", text.contains(".") || text.contains("("));
        atomData.put("nested_atoms_count", 0);

        // Классификация
        if (text.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            atomData.put("is_constant", true);
        }
        if (text.contains("(") && text.contains(")")) {
            atomData.put("is_function_call", true);
        }

        stmtAtoms.put(atomKey, atomData);
    }

    /**
     * Разрешает атомы при выходе из statement.
     * Аналог Python: _process_atoms_on_exit(statement_name)
     *
     * Логика:
     * 1. Для каждого atom: попытаться привязать к таблице/колонке через NameResolver
     * 2. Если atom = "table.column" → resolve table, затем column
     * 3. Если atom = "column" → implicit table resolution
     */
    public void resolveAtomsOnStatementExit(String statementGeoid) {
        Map<String, Map<String, Object>> stmtAtoms = atomsByStatement.get(statementGeoid);
        if (stmtAtoms == null || stmtAtoms.isEmpty()) return;
        if (nameResolver == null) return;

        logger.debug("Resolving {} atoms for statement {}", stmtAtoms.size(), statementGeoid);

        for (var entry : stmtAtoms.entrySet()) {
            String atomText = entry.getKey();
            Map<String, Object> atomData = entry.getValue();

            // Пропускаем уже обработанные
            if (atomData.get("status") != null) continue;

            // Пропускаем константы и вызовы функций
            if (Boolean.TRUE.equals(atomData.get("is_constant"))) {
                atomData.put("status", "constant");
                continue;
            }
            if (Boolean.TRUE.equals(atomData.get("is_function_call"))) {
                atomData.put("status", "function_call");
                continue;
            }

            // Попытка resolve
            Map<String, Object> resolution = resolveAtomReference(atomText, atomData, statementGeoid);

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
                    builder.addColumn(tableGeoid, columnName, atomText, null);
                }

                logger.debug("Atom resolved: {} → table={}, column={}", atomText, tableGeoid, columnName);
            } else {
                String parentCtx = (String) atomData.get("parent_context");
                if ("SELECT".equals(parentCtx) || "INSERT".equals(parentCtx)
                        || "UPDATE".equals(parentCtx) || "MERGE".equals(parentCtx)) {
                    logger.warn("Could not resolve atom: {} in context {}", atomText, parentCtx);
                }
                atomData.put("status", resolution != null ? (String) resolution.get("reason") : "unresolved");
            }
        }
    }

    /**
     * Разрешает отдельный atom.
     * Аналог Python: _resolve_atom_reference(atom_text, atom_data, statement_name)
     */
    private Map<String, Object> resolveAtomReference(String atomText, Map<String, Object> atomData,
                                                     String statementGeoid) {
        String text = atomText.trim();

        // Разбиваем по точке: "table.column" или просто "column"
        String[] parts = text.split("\\.", 2);
        String tablePart = parts.length > 1 ? parts[0] : null;
        String columnPart = parts.length > 1 ? parts[1] : parts[0];

        // Если есть table-часть — resolve таблицу
        if (tablePart != null) {
            ResolvedRef tableRef = nameResolver.resolve(tablePart, "any", statementGeoid);
            if (tableRef.isResolved()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("resolved", true);
                result.put("table_geoid", tableRef.getGeoid());
                result.put("table_name", tablePart);
                result.put("column_name", columnPart);
                result.put("reference_type", tableRef.getType());
                result.put("is_column_reference", true);
                result.put("statement_geoid", statementGeoid);
                return result;
            }
        }

        // Без table-части — пробуем implicit table
        ResolvedRef implicitRef = nameResolver.resolveImplicitTable(statementGeoid);
        if (implicitRef.isResolved()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resolved", true);
            result.put("table_geoid", implicitRef.getGeoid());
            result.put("table_name", null);
            result.put("column_name", columnPart);
            result.put("reference_type", "implicit");
            result.put("is_column_reference", true);
            result.put("statement_geoid", statementGeoid);
            return result;
        }

        // Не удалось resolve
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resolved", false);
        result.put("reason", "Could not resolve table for atom: " + atomText);
        return result;
    }

    /**
     * Возвращает все атомы (аналог Python: get_atoms_data).
     */
    public Map<String, Object> getAtoms() {
        Map<String, Object> allAtoms = new LinkedHashMap<>();

        for (var stmtEntry : atomsByStatement.entrySet()) {
            String stmtGeoid = stmtEntry.getKey();
            Map<String, Map<String, Object>> stmtAtoms = stmtEntry.getValue();

            if (!stmtAtoms.isEmpty()) {
                Map<String, Object> stmtData = new LinkedHashMap<>();
                stmtData.put("source_type", "statement");
                stmtData.put("source_geoid", stmtGeoid);
                stmtData.put("atoms", stmtAtoms);
                stmtData.put("total_atoms", stmtAtoms.size());
                stmtData.put("resolved_atoms",
                        stmtAtoms.values().stream()
                                .filter(a -> "Обработано".equals(a.get("status")))
                                .count());
                allAtoms.put("statement:" + stmtGeoid, stmtData);
            }
        }

        return allAtoms;
    }

    /**
     * Возвращает атомы для конкретного statement.
     */
    public Map<String, Map<String, Object>> getAtomsForStatement(String statementGeoid) {
        return atomsByStatement.getOrDefault(statementGeoid, Map.of());
    }

    public void clear() {
        atomsByStatement.clear();
    }
}