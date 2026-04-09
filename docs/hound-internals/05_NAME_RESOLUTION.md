# Разрешение имён (Name Resolution)

## Задача

Когда в SQL-тексте встречается имя (`users`, `t`, `monthly_sales`, `src`) — нужно
выяснить, что именно оно обозначает: реальную таблицу, подзапрос, CTE или алиас.

Это нетривиально: одно и то же имя в разных частях запроса может означать разное,
а одна таблица может быть известна под несколькими алиасами.

---

## Общая схема резолюции

```
    resolve(name, contextType, currentStatementGeoid)
                         │
                         ▼
               ┌──────────────────┐
               │  Проверить кеш   │─── HIT ──► вернуть cached
               └────────┬─────────┘
                        │ MISS
                        ▼
    ╔══════════════════════════════════════════════╗
    ║           8 стратегий по порядку             ║
    ║                                              ║
    ║  contextType="table" или "any":              ║
    ║    1. Точный geoid (SCHEMA.TABLE)            ║
    ║    2. Алиас в текущем scope                  ║
    ║    2b. Имя таблицы без схемы (одно совпадение)║
    ║                                              ║
    ║  contextType="subquery" или "any":           ║
    ║    3. CTE по имени                           ║
    ║    4. Подзапрос по алиасу (child_statements) ║
    ║    5. Child subqueries по geoid              ║
    ║    6. Source subqueries (source_subqueries)  ║
    ║                                              ║
    ║  Всегда:                                     ║
    ║    7. Рекурсивный поиск по parent statements ║
    ║                                              ║
    ╚══════════════════════════════════════════════╝
                        │
                        ├── найдено → записать в кеш, вернуть
                        └── не найдено → ResolvedRef.unresolved()
```

---

## Стратегии в деталях

### Стратегия 1: Точный geoid (schema.table)

**Когда срабатывает:** имя явно квалифицировано схемой — `DWH.ACCOUNTS`

```
Алгоритм:
  1. Проверить, есть ли "DWH.ACCOUNTS" в реестре таблиц как есть
  2. Если нет — разбить по точке: schema="DWH", table="ACCOUNTS"
  3. Найти таблицу, у которой schemaGeoid="DWH" и tableName="ACCOUNTS"
  4. Если найдена → вернуть её geoid

Результат: ResolvedRef(type="TABLE", strategy="1_exact_geoid")
```

---

### Стратегия 2: Алиас в текущем scope

**Когда срабатывает:** имя — это алиас таблицы (`t`, `u`, `src`)

```
Алгоритм:
  а) Спросить ScopeManager.resolveAlias(name)
     → ищет в currentScope.aliases (то, что зарегистрировано через registerAlias)
  
  б) Если не нашёл — перебрать все зарегистрированные таблицы
     и проверить поле aliases у каждой
  
Результат: ResolvedRef(type="TABLE", strategy="2_alias_scope")

Пример:
  FROM transactions t  → registerAlias("T", "SCHEMA.TRANSACTIONS")
  SELECT t.amount       → resolve("T") → "SCHEMA.TRANSACTIONS"
```

---

### Стратегия 2b: Имя таблицы без схемы

**Когда срабатывает:** имя не содержит точку, совпадение единственное

```
Алгоритм:
  1. Перебрать все таблицы в реестре
  2. Найти те, у которых tableName == name (case-insensitive)
  3. Если ровно одно совпадение → вернуть его geoid
  4. Если несколько → стратегия не срабатывает (неоднозначность)

Результат: ResolvedRef(type="TABLE", strategy="2b_table_name_only")

Типичный случай: SELECT * FROM users  (без алиаса, без схемы)
```

> **Риск в multi-schema окружении**
>
> Если в реестре есть `DWH.ACCOUNTS` и `STAGE.ACCOUNTS`, стратегия 2b
> молча провалится при запросе `FROM accounts` — найдено 2 совпадения,
> оба игнорируются. Управление передаётся стратегии 7 (parent recursive).
>
> Если parent тоже не содержит нужной схемы, имя остаётся **unresolved**.
> Fallback по «ближайшей» схеме в текущей версии отсутствует.
>
> **Обходное решение:** явно квалифицировать имя в SQL (`DWH.ACCOUNTS`)
> или убедиться, что DDL для нужной схемы обрабатывается до DML-файлов.

---

### Стратегия 3: CTE по имени

**Когда срабатывает:** имя совпадает с именем CTE в текущей иерархии

```
Алгоритм:
  1. Найти root statement (идти вверх по parentStatementGeoid)
  2. Среди всех statement'ов найти те, у которых type="CTE"
     и они принадлежат той же иерархии (geoid начинается с rootStmt)
  3. Проверить: совпадает ли alias или shortName CTE с искомым именем

Результат: ResolvedRef(type="CTE", strategy="3_cte")

Пример:
  WITH monthly_sales AS (...)
  SELECT * FROM monthly_sales   → resolve("MONTHLY_SALES") → CTE geoid
```

---

### Стратегия 4: Подзапрос по алиасу (child statements)

**Когда срабатывает:** имя — alias подзапроса в дочерних statement'ах

```
Алгоритм:
  1. Получить текущий statement из Builder
  2. Перебрать child_statements этого statement'а
  3. Для каждого child проверить: входит ли name в его aliases,
     или совпадает ли с его shortName

Результат: ResolvedRef(type="SUBQUERY", strategy="4_subquery_alias")

Пример:
  SELECT s.total
  FROM (SELECT cust_id, SUM(amt) total FROM orders) s
  → resolve("S") → SUBQUERY_geoid для inner SELECT
```

---

### Стратегия 5: Child subqueries по geoid

**Когда срабатывает:** имя частично совпадает с geoid дочернего подзапроса

```
Алгоритм:
  Аналогично стратегии 4, но проверяет содержит ли childGeoid.toUpperCase()
  искомое имя — как fallback для случаев без явного alias.

Результат: ResolvedRef(type="SUBQUERY", strategy="5_child_subquery")
```

---

### Стратегия 6: Source subqueries

**Когда срабатывает:** подзапрос зарегистрирован в source_subqueries statement'а

```
Алгоритм:
  1. Получить current statement
  2. Перебрать source_subqueries (Map: geoid → SubqueryUsage)
  3. Для каждого source subquery проверить его aliases
  4. Если alias совпадает → вернуть реальный stmt geoid

Результат: ResolvedRef(type="SUBQUERY", strategy="6_source_subquery")

Используется для MERGE USING subquery и схожих конструкций
```

---

### Стратегия 7: Рекурсивный поиск вверх по иерархии

**Когда срабатывает:** имя не найдено в текущем контексте, пробуем parent'ов

```
Алгоритм (рекурсия, глубина ≤ 20):
  1. Получить parentStatementGeoid текущего statement
  2. Попробовать разрешить name в контексте parent'а:
     а) Проверить source_tables parent'а (имя и aliases)
     б) Проверить target_tables parent'а
     в) Попробовать CTE в иерархии parent'а
     г) Попробовать source_subqueries parent'а
  3. Если не нашли → идти к parent'у parent'а (depth++)
  4. Остановиться если нет более parent'ов или depth > 20

Результат: ResolvedRef(strategy="7_parent_recursive")

Типичный случай:
  Вложенный подзапрос обращается к таблице, определённой во внешнем FROM
```

> **Поведение на границе depth = 20**
>
> При достижении `depth == 20` рекурсия прерывается и возвращается
> `ResolvedRef.unresolved(name)` — **без исключения, без warning'а**.
>
> Это проявляется как атом со статусом "не обработан" и снижает quality score
> statement'а. В --diag режиме запись в DaliResolutionLog будет иметь
> `strategy="all_failed"`, `result_kind="UNRESOLVED"`.
>
> Лимит 20 рассчитан на реальные SQL файлы (обычно глубина ≤ 5–7).
> При глубоко вложенных PL/SQL пакетах с CTE внутри вложенных подзапросов
> лимит потенциально достижим — признак чрезмерно сложной структуры кода.

---

### Стратегия 8 (implicit): Единственная таблица

**Точка вызова:** стратегия 8 вызывается явно — вне цепочки `resolve()`.
В SemanticListener есть **три** точки вызова:

```
1. onColumnRef() — при обходе каждого column reference без prefix-qualifier
   Условие: у атома нет table-qualifier (просто "balance", не "t.balance")
   Действие: попробовать resolveImplicitTable(statementGeoid) немедленно

2. resolvePendingColumns() — pending atoms без qualifier
   Условие: атом status=null + no table qualifier
   Действие: resolveImplicitTable(statementGeoid) как последний шанс

3. resolvePendingColumns() — pending atoms с флагом implicit_table_needed
   Условие: атом был зарегистрирован с маркером "нужна implicit резолюция"
   Действие: resolveImplicitTable(statementGeoid)
```

Стратегия 8 не входит в основную цепочку `resolve()` (стратегии 1–7).
Вызывается только для неквалифицированных колонок в однотабличных запросах,
после того как стандартная резолюция не помогла.

```
Алгоритм:
  Анализируем состав FROM в текущем statement:
  
  sourceTables   sourceSubqueries   targetTables
  ─────────────────────────────────────────────────
  1 таблица,      пусто              →  вернуть эту таблицу
  пусто           1 подзапрос        →  вернуть этот подзапрос
  пусто           пусто,   1 target  →  вернуть target (UPDATE/DELETE)
  пусто           пусто, пусто       →  спросить parent statement
  UPDATE/DELETE   любой target       →  вернуть первый target

Результат: ResolvedRef(strategy="8_implicit_table")

Пример:
  UPDATE accounts SET balance = 100  → единственный target = accounts
  SELECT id, name FROM users         → единственный source = users
```

---

## Кеш резолюций

```
Ключ кеша:  NAME.toUpperCase() + "|" + contextType + "|" + statementGeoid

Записывается: только успешные резолюции (isResolved() = true)
Не записываются: неудачные попытки (могут стать успешными в другом контексте)

Инвалидация:
  clearCache() вызывается при начале нового файла/сессии
  (кеш не инвалидируется в середине анализа — область видимости
   фиксирована для одного файла)

Метрики (в debug-логе):
  "NameResolver: 'users' → TABLE:SCHEMA.USERS [2_alias_scope] (cache: 42/107)"
  → 42 hits из 107 попыток (39% cache hit rate)
```

---

## ResolvedRef: результат резолюции

```
Поля:
  name     — исходное имя (как было запрошено)
  type     — "TABLE" | "SUBQUERY" | "CTE" | "UNKNOWN"
  geoid    — geoid найденного объекта (null если не найден)
  strategy — какая стратегия сработала (для диагностики)

Проверки:
  isResolved()  → geoid != null
  isTable()     → type == "TABLE"
  isSubquery()  → type == "SUBQUERY"
  isCTE()       → type == "CTE"

Неудача:
  ResolvedRef.unresolved(name)
  → type="UNKNOWN", geoid=null, strategy=null
```

---

## Приоритеты и типичные ошибки резолюции

```
Частые причины неудачи:
  • Алиас зарегистрирован в дочернем scope, а ищем в родительском
    → Используй registerAliasOnParent() для subquery aliases

  • CTE определён в одной ветке WITH, а ищем из другой ветки
    → buildStatementGeoid генерирует разные root'ы — CTE не виден

  • Таблица встречается впервые в WHERE, а FROM не обработан ещё
    → Стратегия 7 (parent) должна её найти после EXIT

  • Функция принята за имя таблицы: "TRUNC(DATE)" → ensureTable
    → STAB-2 диагностика помечает это как suspicious
```
