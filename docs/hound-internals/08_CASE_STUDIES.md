# Case Studies: разбор сложных алгоритмов

---

## Case 1: MERGE с UPDATE SET и INSERT VALUES

### SQL

```sql
MERGE INTO accounts t
USING (SELECT id, new_balance, fee FROM staging WHERE status = 'READY') s
ON (t.id = s.id)
WHEN MATCHED THEN
    UPDATE SET t.balance = s.new_balance + s.fee
WHEN NOT MATCHED THEN
    INSERT (id, balance) VALUES (s.id, s.new_balance)
```

### Пошаговый разбор

```
═══════════════════════════════════════════════════════════════
Шаг 1: ENTER MERGE statement
═══════════════════════════════════════════════════════════════

  ScopeManager.push(ScopeContext{
    type = "MERGE",
    geoid = "PROCEDURE:LOAD:MERGE:5"   (если внутри routine)
  })

  Builder.addStatement("PROCEDURE:LOAD:MERGE:5", "MERGE", ...)

  Таблица-цель: ensureTable("ACCOUNTS", schema) → "SCHEMA.ACCOUNTS"
    → markAsTarget("SCHEMA.ACCOUNTS")


═══════════════════════════════════════════════════════════════
Шаг 2: ENTER USING subquery
═══════════════════════════════════════════════════════════════

  ScopeManager.push(ScopeContext{
    type = "SUBQUERY",
    alias = "S",
    geoid = "PROCEDURE:LOAD:MERGE:5:SQ:S:2"
  })

  Регистрируем source таблицу:
    ensureTable("STAGING") → "SCHEMA.STAGING"
    registerAlias("STAGING", "SCHEMA.STAGING") → в subquery scope

  Атомы USING SELECT:
    "id"          line=2, col=14 context=SELECT
    "new_balance" line=2, col=18 context=SELECT
    "fee"         line=2, col=31 context=SELECT
    "status"      line=2, col=46 context=WHERE
    "'READY'"     line=2, col=55 context=WHERE → is_constant=true


═══════════════════════════════════════════════════════════════
Шаг 3: EXIT USING subquery — скоп ещё на стеке
═══════════════════════════════════════════════════════════════

  Момент: exitEveryRule(USING_SUBQUERY) срабатывает ДО pop'а.
  Стек в этот момент:
    TOP → [SQ:S:2 (subquery scope)]
          [MERGE:5 (merge scope)]      ← это "parent"

  КРИТИЧНО: registerAliasOnParent("S", "SQ:S:2")
    → записывает alias "S" в MERGE scope (второй от вершины)
    → вызов происходит ПОКА subquery scope наверху стека

  ПОСЛЕ вызова → ScopeManager.pop() убирает subquery scope.
  Итог:
    stack: [MERGE:5]
    merge_scope.aliases = { "S" → "SQ:S:2" }  ✓

    → теперь в ON и WHEN MATCHED "s.id" будет разрешаться корректно

  ВАЖНО: если бы pop() произошёл раньше registerAliasOnParent(),
  то в moment вызова "parent" был бы неправильным фреймом стека.
  Всегда: register → pop, никогда: pop → register.


═══════════════════════════════════════════════════════════════
Шаг 4: ON condition
═══════════════════════════════════════════════════════════════

  scope.setInJoinContext(true)

  Атомы: "t"    → resolveAlias("T") → "SCHEMA.ACCOUNTS"
         "id"   → column в ACCOUNTS
         "s"    → resolveAlias("S") → "SQ:S:2"  ✓ (алиас был на parent!)
         "id"   → column в STAGING (через subquery)

  scope.setInJoinContext(false)


═══════════════════════════════════════════════════════════════
Шаг 5: WHEN MATCHED UPDATE SET
═══════════════════════════════════════════════════════════════

  scope.setMergeUpdatePart(true)
  scope.setInDmlTarget(true)

  Разбор "t.balance = s.new_balance + s.fee":

  LHS: "t.balance"
    → target column = "balance"
    → зарегистрировать "ACCOUNTS.BALANCE" как affected column
    → запомнить currentMergeTargetColumn = "balance"
    → диапазон RHS: startLine=5, startCol=36, endLine=5, endCol=58

  RHS — регистрация атомов:
    "s"           line=5, col=36 → context=MERGE_UPDATE
    "new_balance" line=5, col=38
    "s"           line=5, col=51
    "fee"         line=5, col=53

  После регистрации → bindAtomsToMergeUpdateTarget:
    statementGeoid = "PROCEDURE:LOAD:MERGE:5"
    targetColumnRef = "balance"
    range: [5:36..5:58]

    Position check:
      "s"           (5,36) → в диапазоне ✓
      "new_balance" (5,38) → в диапазоне ✓
      "s"           (5,51) → в диапазоне ✓
      "fee"         (5,53) → в диапазоне ✓

    Результат: все 4 атома получают:
      atom["dml_target_ref"] = "balance"
      atom["merge_clause"]   = "UPDATE"


═══════════════════════════════════════════════════════════════
Шаг 6: WHEN NOT MATCHED INSERT
═══════════════════════════════════════════════════════════════

  scope.setMergeInsertPart(true)

  Column list: (id, balance)
    → currentInsertColumns = ["id", "balance"]

  VALUES (s.id, s.new_balance):
    scope.setInValuesClause(true)

    Атомы:
      "s"           line=7, col=24 → positionally bound to "id"
      "id"          line=7, col=26 → dml_target_ref = "id", merge_clause = "INSERT"
      "s"           line=7, col=30 → bound to "balance"
      "new_balance" line=7, col=32 → dml_target_ref = "balance", merge_clause = "INSERT"


═══════════════════════════════════════════════════════════════
Шаг 7: EXIT MERGE
═══════════════════════════════════════════════════════════════

  resolvePendingColumns():
    "s" → resolveAlias("S") → "SQ:S:2" → STAGING (subquery)
    "new_balance" → STAGING.NEW_BALANCE → ensureColumn(...)
    "fee"         → STAGING.FEE        → ensureColumn(...)

  buildLineageEdges():
    STAGING.NEW_BALANCE → ACCOUNTS.BALANCE  (DIRECT, UPDATE)
    STAGING.FEE         → ACCOUNTS.BALANCE  (DIRECT, UPDATE)
    STAGING.ID          → ACCOUNTS.ID       (DIRECT, INSERT)
    STAGING.NEW_BALANCE → ACCOUNTS.BALANCE  (DIRECT, INSERT)

  ScopeManager.pop()  ← MERGE scope уходит
```

---

## Case 2: CTE + вложенный SELECT

### SQL

```sql
WITH monthly AS (
    SELECT cust_id, SUM(amount) AS total
    FROM orders
    GROUP BY cust_id
),
top AS (
    SELECT cust_id, total
    FROM monthly
    WHERE total > 1000
)
SELECT t.cust_id, c.name, t.total
FROM top t
JOIN customers c ON t.cust_id = c.id
```

### Алгоритм разрешения имён

```
CTE "monthly" разбирается первым:
───────────────────────────────────────────────────────
  geoid: "MONTHLY:1"
  source: ORDERS
  output: cust_id, total (aggregated SUM)
  registerAliasOnParent("MONTHLY", "MONTHLY:1")
    → alias виден в outer SELECT

CTE "top" разбирается вторым:
───────────────────────────────────────────────────────
  geoid: "TOP:6"
  FROM monthly:
    resolve("MONTHLY"):
      Стратегия 3: CTE lookup
        → найдено: type="CTE", geoid="MONTHLY:1"  ✓
  registerAliasOnParent("TOP", "TOP:6")

Outer SELECT:
───────────────────────────────────────────────────────
  FROM top t:
    resolve("TOP"):
      Стратегия 3: CTE lookup → "TOP:6"  ✓
    registerAlias("T", "TOP:6")

  JOIN customers c ON t.cust_id = c.id:
    resolve("CUSTOMERS") → Стратегия 1/2b → TABLE_customers  ✓
    registerAlias("C", "TABLE_customers")

  SELECT list:
    "t.cust_id":
      qualifier "T" → resolveAlias("T") → CTE "TOP:6"
      "cust_id" в TOP → трассировать в MONTHLY → traceировать в ORDERS
      lineage: ORDERS.cust_id → CTE:TOP → SELECT output

    "c.name":
      qualifier "C" → resolveAlias("C") → TABLE_customers
      lineage: CUSTOMERS.name → SELECT output

    "t.total":
      qualifier "T" → CTE "TOP:6"
      "total" в TOP → приходит из MONTHLY как SUM(amount)
      lineage: ORDERS.amount →[AGGREGATE]→ CTE:MONTHLY.total
               CTE:MONTHLY.total → CTE:TOP.total
               CTE:TOP.total → SELECT output
```

---

## Case 3: Вложенный подзапрос в SELECT list

### SQL

```sql
SELECT
    e.emp_id,
    e.name,
    (SELECT d.dept_name FROM departments d WHERE d.id = e.dept_id) AS dept
FROM employees e
```

### Особенность: скалярный подзапрос

```
Стек при обходе:

ENTER outer SELECT:
  Stack: [SELECT:1]
  FROM employees e → registerAlias("E", "SCHEMA.EMPLOYEES")

ENTER scalar subquery (SELECT d.dept_name...):
  Stack: [SUBQUERY:3, SELECT:1]
  parentStatement = "SELECT:1"
  alias = "dept"  (из AS)

  FROM departments d → registerAlias("D", "SCHEMA.DEPARTMENTS")

  "d.dept_name" → resolve("D") → Стратегия 2 → DEPARTMENTS  ✓
  "e.dept_id" в WHERE:
    resolve("E") в subquery scope → НЕ НАЙДЕНО
    → Стратегия 7: parent recursive
      → в parent SELECT:1 есть alias "E" → EMPLOYEES  ✓
    Это корреляция подзапроса!

EXIT scalar subquery:
  registerAliasOnParent("dept", "SUBQUERY:3")

EXIT outer SELECT:
  lineage:
    DEPARTMENTS.dept_name → SUBQUERY:3.output → SELECT.dept

  EMPLOYEES.dept_id используется как коррелирующее условие
  → lineage edge: EMPLOYEES.dept_id →[FILTER]→ DEPARTMENTS join
```

---

## Case 4: JOIN — регистрация и типизация

### SQL

```sql
SELECT t.id, c.name, o.total
FROM transactions t
LEFT OUTER JOIN customers c ON t.customer_id = c.id
INNER JOIN (SELECT order_id, SUM(amount) total FROM order_items GROUP BY order_id) o
    ON t.order_id = o.order_id
```

### Алгоритм JoinProcessor

```
JoinProcessor.inferJoinType(joinText):
  "LEFT OUTER JOIN" → "LEFT OUTER JOIN"
  "INNER JOIN"      → "INNER JOIN"
  "JOIN"            → "INNER JOIN"  (default)
  "CROSS JOIN"      → "CROSS JOIN"
  "FULL"            → "FULL OUTER JOIN"

При обработке каждого JOIN:
  1. Определить тип из текста JOIN-ключевого слова
  2. Получить joined table/subquery и её alias
  3. Получить ON-условие как текст
  4. Создать JoinInfo:
     { joinType, sourceTableGeoid/Alias, targetTableGeoid/Alias, conditions }
  5. joinProcessor.registerJoin(statementGeoid, joinInfo)

Для subquery-JOIN (второй JOIN в примере):
  1. ENTER subquery → geoid = "SELECT:1:SQ:O:4"
  2. registerAliasOnParent("O", "SQ:O:4")
  3. EXIT subquery
  4. JOIN: targetTable = "SQ:O:4", alias = "O"

Итоговые JOIN-записи для statement:
  [
    { type: "LEFT OUTER JOIN",
      src: "SCHEMA.TRANSACTIONS", srcAlias: "T",
      tgt: "SCHEMA.CUSTOMERS",    tgtAlias: "C",
      conditions: ["t.customer_id = c.id"] },
    { type: "INNER JOIN",
      src: "SCHEMA.TRANSACTIONS", srcAlias: "T",
      tgt: "SQ:O:4",              tgtAlias: "O",
      conditions: ["t.order_id = o.order_id"] }
  ]
```

---

## Case 5: CREATE VIEW — DDL lineage

### SQL

```sql
CREATE OR REPLACE VIEW v_active_customers AS
SELECT c.id, c.name, COUNT(o.id) AS order_count
FROM customers c
LEFT JOIN orders o ON c.id = o.customer_id
WHERE c.active = 1
GROUP BY c.id, c.name
```

### Особенности обработки

```
1. Тип statement = "CREATE_VIEW"
   ensureTableWithType("V_ACTIVE_CUSTOMERS", schema, "VIEW")
   → VIEW регистрируется как объект типа VIEW, а не TABLE

2. Внутренний SELECT анализируется как обычный:
   source: customers, orders
   target: v_active_customers (VIEW)

3. hasAggregation = true (COUNT + GROUP BY)

4. Lineage:
   CUSTOMERS.id    → V_ACTIVE_CUSTOMERS.id       DIRECT
   CUSTOMERS.name  → V_ACTIVE_CUSTOMERS.name     DIRECT
   ORDERS.id       →[AGGREGATE]→ V_ACTIVE_CUSTOMERS.order_count

5. quality score учитывает COUNT как function_call → не снижает метрику
```

---

## Case 6: UNION ALL — объединение веток

### SQL

```sql
SELECT emp_id, name, salary FROM employees WHERE dept = 'SALES'
UNION ALL
SELECT emp_id, name, salary FROM contractors WHERE active = 1
```

### Особенность: USUBQUERY геоиды и output column mapping

```
═══════════════════════════════════════════════════════════════
Шаг 1: ENTER outer SELECT (UNION root)
═══════════════════════════════════════════════════════════════

  Тип: SELECT (с флагом isUnion = true)
  geoid: "SELECT:1"

  Первая ветка (основной SELECT) разбирается как обычный statement:
    source: EMPLOYEES
    atoms: emp_id (col 1), name (col 2), salary (col 3)
    → output_column_sequence: 1, 2, 3


═══════════════════════════════════════════════════════════════
Шаг 2: ENTER UNION ветка (второй SELECT)
═══════════════════════════════════════════════════════════════

  Тип: USUBQUERY (UNION sub-statement)
  geoid: "SELECT:1:USQ:3"   (parentStatement = "SELECT:1", строка 3)

  ScopeManager.push(ScopeContext{
    type        = "USUBQUERY",
    geoid       = "SELECT:1:USQ:3",
    parentStatement = "SELECT:1"
  })

  source: CONTRACTORS
  atoms: emp_id (col 1), name (col 2), salary (col 3)
    → те же output_column_sequence: 1, 2, 3
      (порядковые позиции выровнены с первой веткой)


═══════════════════════════════════════════════════════════════
Шаг 3: Lineage и output column mapping
═══════════════════════════════════════════════════════════════

  Каждая ветка генерирует независимые OutputColumn записи
  с одинаковыми sequence-номерами:

  Ветка 1 (SELECT:1):
    EMPLOYEES.emp_id   → OutputColumn(SELECT:1, seq=1)  DIRECT
    EMPLOYEES.name     → OutputColumn(SELECT:1, seq=2)  DIRECT
    EMPLOYEES.salary   → OutputColumn(SELECT:1, seq=3)  DIRECT

  Ветка 2 (SELECT:1:USQ:3):
    CONTRACTORS.emp_id → OutputColumn(USQ:3, seq=1)  DIRECT
    CONTRACTORS.name   → OutputColumn(USQ:3, seq=2)  DIRECT
    CONTRACTORS.salary → OutputColumn(USQ:3, seq=3)  DIRECT

  UNION lineage семантически означает: потребитель UNION получает
  строки из обеих веток. DATA_FLOW рёбра идут от каждой ветки
  к соответствующей output-позиции своего USUBQUERY statement'а.

  Связь между ветками по позиции (seq=1 → одна колонка результата)
  остаётся на уровне интерпретации потребителем результата —
  HOUND не создаёт cross-branch UNION рёбер автоматически.


═══════════════════════════════════════════════════════════════
Шаг 4: registerAliasOnParent для USUBQUERY
═══════════════════════════════════════════════════════════════

  При exitEveryRule(USUBQUERY) — скоп USUBQUERY ещё на стеке:
    registerAliasOnParent(branchAlias, usubqueryGeoid)
    → alias доступен в outer SELECT scope

  Если UNION используется как подзапрос:
    SELECT * FROM (
      SELECT id FROM a
      UNION ALL
      SELECT id FROM b
    ) u
  то весь UNION subquery получает alias "u", регистрируется
  через registerAliasOnParent("U", unionSubqueryGeoid).
```

> **Известный пробел (gap G-UNION):**
> Тест на UNION не включён в `LowGapsTest`. Корректность output column
> mapping через UNION ALL не верифицирована регрессионным тестом.
> Добавление теста — открытый gap в `10_TESTING.md`.
