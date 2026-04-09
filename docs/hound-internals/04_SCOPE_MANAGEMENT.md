# Управление контекстом (Scope Management)

## Зачем нужен scope

SQL-код имеет вложенную структуру: процедуры содержат операторы, операторы содержат
подзапросы, подзапросы могут содержать другие подзапросы. При разборе нужно в каждый
момент времени знать:

- В каком statement мы сейчас находимся
- Какому routine принадлежит этот statement
- Какие таблицы и алиасы видны в текущем контексте
- В какой clause мы находимся (SELECT / FROM / WHERE / SET...)

Для этого существует **ScopeManager** — стек контекстов.

---

## ScopeManager: стек контекстов

```
                     ТЕКУЩИЙ SCOPE
                          │
                          ▼
┌─────────────────────────────────────┐
│  ScopeContext  ← TOP (peek/pop)     │  ← outer query
├─────────────────────────────────────┤
│  ScopeContext                       │  ← subquery "s"
├─────────────────────────────────────┤
│  ScopeContext                       │  ← routine PROCESS_ORDERS
├─────────────────────────────────────┤
│  ScopeContext  ← BOTTOM             │  ← package context
└─────────────────────────────────────┘
         Deque<ScopeContext>
```

**Операции:**
- `push(ctx)` — вход в новый scope (новый statement, subquery, routine)
- `pop()` — выход из текущего scope
- `peek()` — посмотреть текущий scope без удаления
- `registerAlias(alias, geoid)` — зарегистрировать алиас в текущем scope
- `registerAliasOnParent(alias, geoid)` — зарегистрировать в родительском scope

---

## ScopeContext: что хранится в одном контексте

```
┌──────────────────────────────────────────────────────────────┐
│                      ScopeContext                            │
├──────────────────────────────────────────────────────────────┤
│  Идентификаторы:                                             │
│    statementGeoid   — geoid этого statement                  │
│    statementType    — SELECT / INSERT / UPDATE / MERGE / ... │
│    routineGeoid     — geoid родительской routine (если есть) │
│    schemaGeoid      — текущая схема                          │
│    packageGeoid     — текущий пакет (PL/SQL)                 │
│    parentStatement  — geoid родительского statement          │
│    alias            — имя CTE / alias подзапроса / курсора   │
│                                                              │
│  Флаги активного clause:                                     │
│    isInDmlTarget      — внутри target INSERT/UPDATE/MERGE    │
│    isInJoinContext     — внутри ON-условия JOIN              │
│    isInValuesClause   — внутри VALUES (...)                  │
│    isInUpdateSetExpr  — правая часть SET col = <expr>        │
│    isMergeInsertPart  — WHEN NOT MATCHED ... INSERT          │
│    isMergeUpdatePart  — WHEN MATCHED ... UPDATE SET          │
│    isUnion            — UNION / UNION ALL                    │
│                                                              │
│  Алиасы (локальный реестр):                                  │
│    aliases: Map<ALIAS_UPPER, geoid>                          │
│    registerAlias(alias, geoid)                               │
│    resolveAlias(alias) → geoid или null                      │
└──────────────────────────────────────────────────────────────┘
```

### `getActiveClause()` — справочник возвращаемых значений

Метод вычисляет строку-контекст для регистрации атома. Значение сохраняется
в поле `context` каждого `DaliAtom`.

```
Приоритет проверок (первое совпадение побеждает):

  isMergeInsertPart && isInValuesClause  → "MERGE_INSERT_VALUES"
  isInValuesClause                       → "VALUES"
  isInJoinContext                        → "JOIN"
  isInUpdateSetExpr                      → "SET_EXPR"
  isMergeInsertPart                      → "MERGE_INSERT"
  isMergeUpdatePart                      → "MERGE_UPDATE"
  statementType != null                  → statementType   (SELECT / INSERT / UPDATE / etc.)
  иначе                                  → "UNKNOWN"
```

| Значение | Когда появляется |
|---------|-----------------|
| `MERGE_INSERT_VALUES` | `WHEN NOT MATCHED THEN INSERT VALUES (...)` — VALUES-выражения |
| `VALUES` | `INSERT INTO t VALUES (...)` — обычный VALUES |
| `JOIN` | Условие ON-clause JOIN |
| `SET_EXPR` | Правая часть `SET col = <expr>` (UPDATE и MERGE UPDATE) |
| `MERGE_INSERT` | Список колонок `INSERT (col1, col2)` в MERGE |
| `MERGE_UPDATE` | Имена колонок в `UPDATE SET col = ...` (левая сторона) |
| `SELECT` / `INSERT` / `UPDATE` / `MERGE` / `DELETE` | Тип statement'а — дефолт |
| `UNKNOWN` | statementType не задан (редко) |

---

## Формула Geoid: как вычисляется идентификатор statement'а

Geoid строится иерархически — кодирует цепочку контекстов от пакета до конкретного
оператора. Разделитель `:`.

```
Тип statement        Формула geoid
─────────────────────────────────────────────────────────────────
SELECT, INSERT,      {general}:TYPE:LINE
UPDATE, DELETE,      где {general} = parentStatement или routineGeoid
MERGE

CTE                  {general}:CTE_NAME:LINE
                     (имя CTE пишется заглавными буквами)
                     ГРАНИЧНЫЙ СЛУЧАЙ: если {general} пуст (top-level CTE
                     без routine и без parent statement), ведущее `:` не
                     добавляется — geoid = "CTE_NAME:LINE", например "MONTHLY:1".
                     При пустом {general} стратегия 3 NameResolver'а
                     использует rootStmt="" — совпадает с любым geoid по startsWith.
                     Это корректное поведение для изолированного WITH-блока.

SUBQUERY             {general}:SQ:ALIAS:LINE   (если есть alias)
                     {general}:SQ:LINE          (без alias)

USUBQUERY            {general}:USQ:ALIAS:LINE
(UNION-подзапрос)    {general}:USQ:LINE

CURSOR (PL/SQL)      {general}:CURSOR_NAME:LINE

VIEW                 VIEW_NAME (верхнеуровневый, без иерархии)
```

**Примеры:**

```
Пакет TEST_PKG, процедура LOAD_DATA, SELECT на строке 42:
  geoid → "TEST_PKG:PROCEDURE:LOAD_DATA:SELECT:42"

Subquery с alias "s" внутри этого SELECT, строка 44:
  geoid → "TEST_PKG:PROCEDURE:LOAD_DATA:SELECT:42:SQ:S:44"

CTE "monthly" в этой процедуре, строка 38:
  geoid → "TEST_PKG:PROCEDURE:LOAD_DATA:MONTHLY:38"

Standalone SELECT (без routine), строка 5:
  geoid → "SELECT:5"
```

---

## Alias registration: где регистрировать

Регистрация алиаса в **текущем** или **родительском** scope имеет принципиальное значение.

### Обычный алиас таблицы

```sql
SELECT t.col FROM transactions t
```

```
Правило: registerAlias("T", tableGeoid) → в ТЕКУЩЕМ scope

После регистрации:
  currentScope.aliases = { "T" → "SCHEMA.TRANSACTIONS" }

Когда встречаем "t.col" в SELECT:
  resolveAlias("T") → "SCHEMA.TRANSACTIONS"  ✓
```

### Subquery alias (ВАЖНО: родительский scope)

```sql
SELECT s.total
FROM (SELECT cust_id, SUM(amount) total FROM orders) s
```

```
Правило: registerAliasOnParent("S", subqueryGeoid)
         → во ВТОРОМ от верхушки scope (parent)

Зачем это нужно:
  Когда мы выходим из subquery (exitEveryRule для subquery),
  его scope POP'ается со стека.
  
  Если алиас "S" зарегистрирован в scope subquery — он исчезает
  вместе с ним, и outer query не может его найти.
  
  Если зарегистрирован в PARENT scope — он остаётся доступен
  для outer query после того, как subquery был обработан.

Схема:
  [До выхода из subquery]       [После выхода из subquery]
  Stack: [outer, subquery]      Stack: [outer]
  outer.aliases = {}            outer.aliases = { "S" → subqGeoid }
  subquery.aliases = { "S"... } (subquery ушёл со стека)
  
  registerAliasOnParent("S", ...) → записывает в outer
```

### MERGE USING alias (тот же принцип)

```sql
MERGE INTO accounts a
USING (SELECT ... FROM staging) src
ON a.id = src.id
```

```
При выходе из USING-subquery:
  registerAliasOnParent("SRC", usingSubqueryGeoid)
  → alias "SRC" доступен в MERGE scope
  → ON-условие может разрешить "src.id" → staging.id  ✓
```

---

## Cursor Registry (PL/SQL)

Для PL/SQL добавлен отдельный регистр курсоров — потому что курсоры
имеют специфику: их тело — это SELECT, а переменная FOR loop — это "alias" на курсор.

```
Именованный курсор:
  CURSOR c1 IS SELECT id, name FROM users;
  ...
  FOR rec IN c1 LOOP ...

  registerCursor("C1", cursorStatementGeoid)
  registerCursorRecord("REC", "C1", isInline=false)
  → cursorRecordAliases["REC"] = cursorStatementGeoid

  Когда встречаем "rec.id":
    getCursorRecordStmt("REC") → cursorStatementGeoid
    → resolve "id" из cursor's SELECT output

Inline-курсор:
  FOR rec IN (SELECT id, name FROM users) LOOP ...

  registerCursorRecord("REC", stmtGeoid, isInline=true)
  → сразу связываем record с geoid inline-подзапроса

Очистка:
  clearCursorState()  ← вызывается при выходе из routine
```

---

## Динамика стека: вложенный запрос

```sql
CREATE OR REPLACE PROCEDURE get_top_customers AS
BEGIN
  WITH monthly AS (
    SELECT cust_id, SUM(amt) total FROM orders GROUP BY cust_id
  )
  SELECT m.cust_id, c.name, m.total
  FROM monthly m
  JOIN customers c ON m.cust_id = c.id;
END;
```

```
Момент времени →  Стек (TOP слева):

enter PROCEDURE:
  [PROCEDURE:GET_TOP_CUSTOMERS]

enter CTE "monthly":
  [CTE:MONTHLY:3, PROCEDURE:GET_TOP_CUSTOMERS]
    CTE scope: alias="MONTHLY"

exitEveryRule(CTE) — скоп CTE ЕЩЁ НА СТЕКЕ:
  [CTE:MONTHLY:3, PROCEDURE:GET_TOP_CUSTOMERS]
  registerAliasOnParent("MONTHLY", cteGeoid)
    → записывает в PROCEDURE scope (2-й от верхушки)
    → pop НЕ произошёл, вызов идёт пока CTE наверху!

  stack после pop:
  [PROCEDURE:GET_TOP_CUSTOMERS]
    procedure.aliases = { "MONTHLY" → cteGeoid }  ✓

  ВАЖНО для реализаторов нового диалекта:
    В ANTLR listener-паттерне exitEveryRule срабатывает ДО pop'а.
    Поэтому registerAliasOnParent всегда вызывается при exitXxx,
    когда соответствующий scope ещё является TOP элементом стека.
    "Parent" в данном случае = второй элемент стека (next after top).

enter SELECT:
  [PROCEDURE:GET_TOP_CUSTOMERS:SELECT:6, PROCEDURE:GET_TOP_CUSTOMERS]
    SELECT scope видит: "MONTHLY" → cteGeoid

registerAlias("M", cteGeoid)   → в SELECT scope
registerAlias("C", TABLE_customers) → в SELECT scope

exit SELECT:
  [PROCEDURE:GET_TOP_CUSTOMERS]
    резолюция: m.cust_id → MONTHLY.cust_id → ORDERS.cust_id
               c.name    → CUSTOMERS.name

exit PROCEDURE:
  []  (стек пуст)
```
