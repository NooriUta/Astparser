# Name Resolution & `table_ref` Algorithm

## 1. Алгоритм заполнения `table_ref` на DaliOutputColumn

`table_ref` — поле в `Map<String, Object>` записи `StatementInfo.getColumnsOutput()`.
Хранит ссылку на DaliColumn-цель в формате `tableGeoid + "." + columnName`.
Заполняется в `UniversalSemanticEngine.onStatementExit()`.

### 1.1 INSERT … SELECT (H1.2-INS)

**Условие срабатывания:**
- Текущий statement типа `SELECT`
- Его parent — `INSERT`
- У INSERT есть явный список колонок (`insertTargetColumns` не пуст)

**Алгоритм (позиционное связывание):**

```
INSERT INTO t (col_a, col_b)   ← insertTargetColumns = [col_a, col_b]
SELECT x, y FROM src           ← columnsOutput = {x: {order:1}, y: {order:2}}
```

```
ocEntries = list(si.getColumnsOutput().entrySet())   // SELECT output cols по порядку
for i in range(min(ocEntries.size(), insertCols.size())):
    ocEntries[i].getValue().put("table_ref", tgtGeoid + "." + insertCols[i].toUpperCase())
```

Результат: `x.table_ref = "SCHEMA.T.COL_A"`, `y.table_ref = "SCHEMA.T.COL_B"`

Parent INSERT при этом получает subtype `"INSERT_SELECT"`.

---

### 1.2 UPDATE SET col = (subquery) (H1.2-UPD)

**Условие срабатывания:**
- Текущий statement типа `USUBQUERY`
- Его parent — `UPDATE`
- У USUBQUERY есть output columns (SELECT внутри scalar subquery)

**Алгоритм:**

```
UPDATE t SET col_a = (SELECT max(x) FROM src), col_b = (SELECT min(y) FROM src)
```

Шаги:
1. Из parent UPDATE собрать `updateTargets` — список affected columns типа `"UPDATE"` (в порядке регистрации)
2. Взять последние `ocCount` записей из `updateTargets` (они соответствуют данному USUBQUERY)
3. Позиционно связать: `ocEntries[i].table_ref = tblGeoid + "." + colName`

```
relevant = updateTargets[from : from + ocCount]
for i in range(min(ocEntries.size(), relevant.size())):
    ocEntries[i].getValue().putIfAbsent("table_ref", relevant[i].table_geoid + "." + relevant[i].column_name)
```

---

### 1.3 Использование `table_ref` при записи в граф

`table_ref` пишется на вершину `DaliOutputColumn` (поле `table_ref`).
Используется при построении `DATA_FLOW` рёбер: `DaliColumn → DaliOutputColumn`.

---

## 2. Алгоритм разрешения имён (NameResolver)

Класс: `com.hound.semantic.engine.NameResolver`  
Метод входа: `resolve(name, contextType, statementGeoid)`

`contextType` ∈ `{"any", "table", "subquery"}`

### 2.1 Порядок стратегий (7 шагов)

```
resolveInternal(upperName, contextType, currentStmt):

    if contextType in ["any", "table"]:
        1. resolveTableByExactGeoid(name)
        2. resolveTableByAliasInScope(name, stmt)
        2b. resolveTableByNameOnly(name)          // только при единственном совпадении

    if contextType in ["any", "subquery"]:
        3. resolveCTE(name, stmt)
        4. resolveSubqueryByAlias(name, stmt)
        5. resolveChildSubqueries(name, stmt)
        6. resolveSourceSubqueries(name, stmt)

    7. resolveParentRecursive(name, contextType, stmt, depth=0)
```

Каждая стратегия возвращает `ResolvedRef`. При успехе немедленный возврат с тегом стратегии.  
Результат кэшируется: ключ = `"NAME|contextType|statementGeoid"`.

---

### 2.2 Стратегия 1: Exact geoid

Проверяет `StructureAndLineageBuilder.getTables()` напрямую по ключу:
- Точное совпадение с ключом (`SCHEMA.TABLE` или `TABLE`)
- Если имя содержит `.` — дополнительный поиск по `(schema, tableName)` паре

---

### 2.3 Стратегия 2: Alias в текущем scope

**Два источника алиасов:**

#### a) ScopeContext.aliasMap (живой scope)

Заполняется в `UniversalSemanticEngine.onTableReference()`:
```java
scopeManager.registerAlias(alias.toUpperCase(), tableGeoid);  // explicit alias
scopeManager.registerAlias(tableName.toUpperCase(), tableGeoid);  // table name = implicit alias
```

Живёт в стеке `ScopeContext` — только пока scope открыт.  
Поиск: `ScopeManager.resolveAlias(alias)` → заглядывает в текущий `ScopeContext.aliasMap`.

#### b) TableInfo.aliases (персистентный)

Заполняется в `builder.addTableAlias(tableGeoid, alias)` при регистрации таблицы.  
Хранится в `TableInfo.aliases` (Set<String>).  
Поиск перебором всех таблиц: `t.aliases().contains(alias)`.

**Разница:**
- `ScopeContext.aliasMap` актуален только пока грамматический scope открыт (атомы внутри SELECT)
- `TableInfo.aliases` персистентен на всю сессию — используется при разрешении ПОСЛЕ закрытия scope (например, при постобработке)

---

### 2.4 Стратегия 2b: По имени таблицы (без схемы)

Поиск по `tableName` без учёта schema. Срабатывает **только** при единственном совпадении.  
При нескольких таблицах с одинаковым именем — не применяется (неоднозначность).

---

### 2.5 Стратегия 3: CTE по имени

Ищет statement типа `CTE` в иерархии текущего root statement.  
Проверяет: `stmt.alias`, `stmt.shortName`, `stmt.geoid` против искомого имени.

---

### 2.6 Стратегия 4: Subquery по алиасу

Проверяет `child_statements` текущего statement.  
Каждый child: `childStmt.aliases`, `childStmt.shortName`.

---

### 2.7 Стратегии 5, 6: Child / Source subqueries

- **5**: Child subqueries — поиск по имени/геоид среди дочерних statement'ов
- **6**: Source subqueries (`StatementInfo.sourceSubqueries`) — подзапросы из FROM

---

### 2.8 Стратегия 7: Рекурсивный поиск по parent statements

Поднимается по цепочке `parentStatementGeoid`, применяя стратегии 1–6 для каждого уровня.  
Ограничение глубины рекурсии.

---

## 3. Источники алиасов — итоговая таблица

| Источник | Тип | Lifetime | Используется |
|----------|-----|----------|-------------|
| `ScopeContext.aliasMap` | `Map<String, String>` alias→geoid | Пока scope открыт | Стратегия 2a (текущий scope) |
| `TableInfo.aliases` | `Set<String>` | Вся сессия | Стратегия 2b (перебор таблиц) |
| `StatementInfo.aliases` | `Set<String>` | Вся сессия | Стратегии 4, 5 (subquery alias) |
| `StatementInfo.shortName` | `String` | Вся сессия | Стратегии 3, 4, 5 (CTE name) |

### Регистрация алиасов при парсинге

```
enterTable_ref_aux(ctx)
    tableAlias = extractAlias(ctx.table_alias())
    tableName  = extractTableNameViaReflection(ctx, tableAlias)
    ↓
onTableReference(tableName, tableAlias, ...)
    ↓ (в UniversalSemanticEngine)
    scopeManager.registerAlias(alias.toUpperCase(), tableGeoid)    // ScopeContext
    builder.addTableAlias(tableGeoid, alias)                       // TableInfo
    scopeManager.registerAlias(tableName.toUpperCase(), tableGeoid) // имя = implicit alias
```

### Проблемные случаи

1. **Alias из другого SELECT в UNION**: каждая ветка `USUBQUERY` имеет свой scope — алиасы не перетекают
2. **Alias после закрытия scope**: `ScopeContext` уже поп'нут → только `TableInfo.aliases` доступен (стратегия 2b). Если алиас нигде не был добавлен через `addTableAlias` — резолюция упадёт на стратегию 7 (parent) или не найдёт
3. **Динамические курсоры** (`DINAMIC_CURSOR`): scope слит с SELECT → алиасы регистрируются в DINAMIC_CURSOR scope, доступны атомам внутри тела курсора
