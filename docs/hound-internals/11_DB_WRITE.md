# Запись результатов в базу данных

## Общая схема

HOUND сохраняет результат анализа в ArcadeDB — графовой БД, где объекты (вершины)
связаны рёбрами. Один прогон одного файла называется **сессией** и привязывается
к вершине `DaliSession`.

```
╔═══════════════════════════════════════════════════════════════════╗
║  SemanticResult (в памяти)                                        ║
║    Structure: databases, schemas, packages,                       ║
║               tables, columns, routines, statements               ║
║    Lineage:   atoms, joins, tables_usage                          ║
║    Extra:     resolutionLog, schemaRegistrationLog, calledRoutines║
╚═══════════════════════════════╤═══════════════════════════════════╝
                                │ saveResult(result, timer)
                                ▼
╔═══════════════════════════════════════════════════════════════════╗
║  ArcadeDBSemanticWriter                                           ║
║                                                                   ║
║  Три режима:                                                      ║
║    EMBEDDED      — локальная ArcadeDB, одна транзакция            ║
║    REMOTE        — удалённая ArcadeDB, команды по одной           ║
║    REMOTE_BATCH  — удалённая ArcadeDB, пакетная HTTP-загрузка     ║
╚═══════════════════════════════════════════════════════════════════╝
                                │
                                ▼
╔═══════════════════════════════════════════════════════════════════╗
║  ArcadeDB Graph                                                   ║
║                                                                   ║
║  Vertices (вершины):    DaliSession, DaliDatabase, DaliSchema,    ║
║                         DaliPackage, DaliTable, DaliColumn,       ║
║                         DaliRoutine, DaliStatement,               ║
║                         DaliOutputColumn, DaliAffectedColumn,     ║
║                         DaliJoin, DaliAtom,                       ║
║                         DaliParameter, DaliVariable,              ║
║                         DaliApplication                           ║
║                                                                   ║
║  Documents (без рёбер): DaliSnippet, DaliSnippetScript,           ║
║                         DaliResolutionLog, DaliSchemaLog          ║
╚═══════════════════════════════════════════════════════════════════╝
```

---

## Порядок записи (transaction)

Запись происходит в одной транзакции в строго определённом порядке — потому что
вершины должны существовать прежде, чем к ним будут созданы рёбра:

```
1.  DaliSession            — «корень» всей сессии
2.  DaliDatabase           — база данных (или берётся из CanonicalPool)
3.  DaliSchema             — схемы; ребро Database →CONTAINS_SCHEMA→ Schema
4.  DaliPackage            — PL/SQL пакеты; ребро Schema →CONTAINS_ROUTINE→ Package
5.  DaliTable              — таблицы и VIEW; ребро Schema →CONTAINS_TABLE→ Table
6.  DaliColumn             — колонки; ребро Table →HAS_COLUMN→ Column
7.  DaliRoutine            — процедуры/функции; рёбра к Schema/Package
    DaliParameter          — параметры; ребро Routine →HAS_PARAMETER→ Parameter
    DaliVariable           — переменные; ребро Routine →HAS_VARIABLE→ Variable
8.  DaliStatement          — SQL-операторы; DaliSnippet (document)
    DaliOutputColumn       — выходные колонки; ребро Stmt →HAS_OUTPUT_COL→ OC
    DaliJoin               — JOIN операции; ребро Stmt →HAS_JOIN→ Join
    DaliAffectedColumn     — target-колонки DML; ребро Stmt →HAS_AFFECTED_COL→ AC
    + рёбра между Statement'ами: CHILD_OF, USES_SUBQUERY
    + рёбра Statement↔Table: READS_FROM, WRITES_TO
9.  DaliAtom               — атомарные выражения; ребро Stmt →HAS_ATOM→ Atom
    + resolution рёбра: ATOM_REF_TABLE, ATOM_REF_COLUMN, ATOM_REF_STMT, ATOM_REF_OUTPUT_COL
    + ATOM_PRODUCES → OutputColumn / AffectedColumn
    + DATA_FLOW → OutputColumn / AffectedColumn
    + FILTER_FLOW → Statement
10. DaliSnippetScript      — полный текст файла (document)
11. DaliResolutionLog      — диагностика resolution (document, только --diag)
12. DaliSchemaLog          — suspicious schemas (document, только --diag)
13. CALLS рёбра            — вызовы между Routine'ами
14. Lineage рёбра          — READS_FROM_TABLE, WRITES_TO_TABLE и т.д. из LineageEdge
```

---

## Вершины: поля и назначение

### DaliSession
> Одна сессия = один прогон одного файла.

| Поле | Тип | Источник |
|------|-----|---------|
| `session_id` | String | UUID, генерируется перед анализом |
| `db_name` | String | Имя проекта/БД (передаётся явно) |
| `file_path` | String | Путь к исходному файлу |
| `dialect` | String | Определённый диалект SQL |
| `processing_time_ms` | Long | Время парсинга + анализа |
| `created_at` | Long | epoch ms записи |

---

### DaliDatabase / DaliApplication
> Логический контейнер для схем. В режиме CanonicalPool — общий для всех файлов проекта.

| Поле | DaliDatabase | DaliApplication |
|------|-------------|----------------|
| `db_geoid` | geoid БД | — |
| `db_name` | Имя БД | — |
| `app_geoid` | — | geoid приложения |
| `app_name` | — | Имя приложения |
| `created_at` | epoch ms | epoch ms |

---

### DaliSchema
> Схема (namespace) в базе данных.

| Поле | Тип | Описание |
|------|-----|---------|
| `schema_geoid` | String | Имя схемы в верхнем регистре: `DWH` |
| `schema_name` | String | Оригинальное имя |
| `db_geoid` | String | Geoid родительской БД |
| `db_name` | String | Имя БД |
| `session_id` | String | Только в non-pool режиме |

---

### DaliPackage
> PL/SQL пакет (Package Spec + Body).

| Поле | Тип | Описание |
|------|-----|---------|
| `package_geoid` | String | Geoid пакета: `DWH.PKG_LOAD` |
| `package_name` | String | Имя пакета |
| `schema_geoid` | String | Родительская схема |
| `routine_geoid` | String | = package_geoid (для совместимости с Routine) |
| `routine_type` | String | Всегда `"PACKAGE"` |
| `session_id` | String | ID сессии |

---

### DaliTable
> Таблица, VIEW, CTE или временная таблица.

| Поле | Тип | Описание |
|------|-----|---------|
| `table_geoid` | String | `SCHEMA.TABLE_NAME` или просто `TABLE_NAME` |
| `table_name` | String | Имя таблицы (без схемы) |
| `schema_geoid` | String | Родительская схема |
| `table_type` | String | `TABLE` / `VIEW` / `CTE` / `TEMP` / `TABLE_FUNCTION` |
| `aliases` | List\<String\> | Все алиасы, встреченные в SQL |
| `column_count` | Integer | Количество зарегистрированных колонок |
| `data_source` | String | `MASTER` (из DDL) или `RECONSTRUCTED` (из DML) |
| `db_name` | String | В pool-режиме |
| `session_id` | String | В non-pool режиме |

**`data_source`: MASTER vs RECONSTRUCTED**

```
MASTER       — таблица встречена в CREATE TABLE / CREATE VIEW DDL
               → известна точная структура
RECONSTRUCTED — таблица встречена только в DML (INSERT/UPDATE/SELECT)
               → колонки восстановлены из контекста использования
               → при появлении DDL в той же сессии upgrade до MASTER
```

---

### DaliColumn
> Колонка таблицы.

| Поле | Тип | Описание |
|------|-----|---------|
| `column_geoid` | String | `TABLE_GEOID.COLUMN_NAME` |
| `table_geoid` | String | Родительская таблица |
| `column_name` | String | Имя колонки (UPPER) |
| `expression` | String | SQL-выражение (если computed) |
| `alias` | String | Псевдоним в SELECT list |
| `is_output` | Boolean | Колонка в output SELECT'а |
| `col_order` | Integer | Порядок в SELECT list (0-based) |
| `ordinal_position` | Integer | Позиция колонки: из DDL (явная) или порядок первого появления |
| `used_in_statements` | List\<String\> | Geoid'ы statement'ов, где используется |
| `data_source` | String | MASTER / RECONSTRUCTED (наследуется от таблицы) |
| `db_name` | String | В pool-режиме |

**Вычисляемые поля:**
- `col_order` — порядок встречи данной колонки в SELECT list конкретного statement'а (0-based)
- `ordinal_position` — позиция колонки в таблице:
  - DDL-путь (`addColumnWithOrdinal()`): явный порядок из CREATE TABLE (1-based)
  - RECONSTRUCTED-путь: `tableColumnCount()` в момент первой встречи колонки — 1-based
    счётчик по таблице, **не равен** `col_order` (который по statement'у)
- `data_source` — определяется флагом `isMasterTable()` для родительской таблицы

---

### DaliRoutine
> Хранимая процедура, функция, триггер.

| Поле | Тип | Описание |
|------|-----|---------|
| `routine_geoid` | String | `PKG:PROCEDURE:NAME` или `PROCEDURE:NAME` |
| `routine_name` | String | Имя (UPPER) |
| `routine_type` | String | `PROCEDURE` / `FUNCTION` / `TRIGGER` / `UNKNOWN` |
| `return_type` | String | Тип возвращаемого значения (для FUNCTION) |
| `package_geoid` | String | Родительский пакет (если есть) |
| `schema_geoid` | String | Родительская схема |
| `line_start` | Integer | Строка объявления в файле |
| `data_source` | String | Всегда `MASTER` |
| `session_id` | String | ID сессии |

**Внешний вызов (CALLS на неизвестную routine):**
- Создаётся stub-вершина с `routine_geoid = "EXT:PROC_NAME"`, `data_source = RECONSTRUCTED`
- Позволяет создать CALLS ребро даже для процедур из других файлов

---

### DaliParameter / DaliVariable
> Параметр или локальная переменная routine.

| Поле | DaliParameter | DaliVariable |
|------|--------------|-------------|
| `routine_geoid` | geoid routine | geoid routine |
| `param_name` / `var_name` | имя | имя |
| `param_type` / `var_type` | тип данных | тип данных |
| `param_mode` | IN / OUT / IN OUT | — |
| `session_id` | ID сессии | ID сессии |

---

### DaliStatement
> SQL-оператор. Содержит много вычисляемых полей.

| Поле | Тип | Источник |
|------|-----|---------|
| `stmt_geoid` | String | Иерархический geoid |
| `type` | String | SELECT / INSERT / UPDATE / MERGE / CTE / ... |
| `subtype` | String | BULK COLLECT / RETURNING / FORALL / ... |
| `line_start`, `line_end` | Integer | Строки в исходном файле |
| `parent_statement` | String | Geoid родителя (для subquery) |
| `routine_geoid` | String | Geoid routine-контейнера |
| `short_name` | String | Alias CTE / subquery |
| `aliases` | List\<String\> | Все алиасы statement'а |
| `child_statements` | List\<String\> | Geoid'ы дочерних statement'ов |
| `source_table_geoids` | List\<String\> | FROM-таблицы |
| `target_table_geoids` | List\<String\> | DML target-таблицы |
| `source_subquery_geoids` | List\<String\> | Subquery в FROM |
| `source_tables_json` | String | JSON с alias-информацией |
| `target_tables_json` | String | JSON с alias-информацией |
| `session_id` | String | ID сессии |

**Вычисляемые boolean поля:**

| Поле | Логика вычисления |
|------|-----------------|
| `is_dml` | type ∈ {INSERT, UPDATE, DELETE, MERGE} |
| `is_ddl` | type начинается с CREATE / ALTER / DROP |
| `is_union` | statement является UNION / UNION ALL |
| `has_aggregation` | встречены GROUP BY или агрегатные функции |
| `has_window` | встречены OVER (...) window-функции |
| `has_cte` | среди child_statements есть CTE |

**Вычисляемые числовые поля:**

| Поле | Логика вычисления |
|------|-----------------|
| `join_count` | количество JOIN'ов в statement'е |
| `col_count_output` | размер columnsOutput map |
| `col_count_input` | количество атомов с is_column_reference=true |
| `depth` | глубина вложенности: количество parent'ов до root |
| `quality` | (resolved + constants + functions) / total_atoms |
| `parent_statement_type` | тип родительского statement (lookup по geoid) |

---

### DaliOutputColumn
> Одна колонка в выводе SELECT (позиция в SELECT list).

| Поле | Тип | Описание |
|------|-----|---------|
| `statement_geoid` | String | Родительский statement |
| `col_key` | String | Ключ в columnsOutput map |
| `name` | String | Имя колонки (или alias) |
| `expression` | String | Полное SQL-выражение |
| `alias` | String | AS alias |
| `col_order` | Integer | Порядок в SELECT list |
| `source_type` | String | TABLE / SUBQUERY / LITERAL / FUNCTION |
| `table_ref` | String | Geoid источника (если известен) |
| `session_id` | String | ID сессии |

---

### DaliAffectedColumn
> Target-колонка DML-оператора (то, куда пишутся данные).

| Поле | Тип | Описание |
|------|-----|---------|
| `statement_geoid` | String | DML statement |
| `column_ref` | String | Имя колонки (`balance`, `user_id`) |
| `column_name` | String | Имя (может отличаться от column_ref если alias) |
| `table_geoid` | String | Geoid target-таблицы |
| `dataset_alias` | String | Alias таблицы в DML (`t` из `t.balance`) |
| `source_type` | String | `INSERT` / `UPDATE` / `MERGE` — тип DML-операции |
| `resolution_status` | String | Resolved / Unresolved |
| `type_affect` | String | `UPDATE` / `INSERT` / `MERGE` |
| `order_affect` | Integer | Позиция в INSERT column list |
| `session_id` | String | ID сессии |

**Вычисляемые поля:**
- `source_type` — тип DML-операции: `INSERT` / `UPDATE` / `MERGE`
  (передаётся через `StatementInfo.addAffectedColumn`, не TABLE/SUBQUERY)
- `type_affect` — нормализованный тип из `StatementInfo.toTypeAffect()`:
  `"INSERT"`, `"MERGE_INSERT_TARGET"` → `"INSERT"`;
  `"UPDATE"`, `"MERGE_UPDATE_TARGET"` → `"UPDATE"`;
  `"MERGE"` → `"MERGE"`.
  Значения `MERGE_UPDATE` и `MERGE_INSERT` — устаревшие (до T10), не используются.
- `order_affect` — позиционный порядок из INSERT column list

---

### DaliJoin
> Одна JOIN-операция в statement'е.

| Поле | Тип | Описание |
|------|-----|---------|
| `statement_geoid` | String | Родительский statement |
| `join_type` | String | INNER JOIN / LEFT OUTER JOIN / CROSS JOIN / ... |
| `source_table_geoid` | String | Левая таблица JOIN |
| `source_alias` | String | Alias левой таблицы |
| `source_type` | String | TABLE / SUBQUERY |
| `target_table_geoid` | String | Правая таблица JOIN |
| `target_alias` | String | Alias правой таблицы |
| `target_type` | String | TABLE / SUBQUERY |
| `conditions` | String | ON-условие как текст |
| `line_start` | Integer | Строка в исходном файле |
| `session_id` | String | ID сессии |

**Вычисляемое поле `join_type`:**
```
Текст JOIN-ключевого слова → нормализованный тип:
  содержит "NATURAL"  → "NATURAL JOIN"
  содержит "CROSS"    → "CROSS JOIN"
  содержит "FULL"     → "FULL OUTER JOIN"
  содержит "RIGHT"    → "RIGHT OUTER JOIN"
  содержит "LEFT"     → "LEFT OUTER JOIN"
  содержит "INNER"    → "INNER JOIN"
  иначе               → "INNER JOIN"  (default для bare JOIN)
```

---

### DaliAtom
> Атомарное выражение: ссылка на колонку, функция, литерал.

| Поле | Тип | Описание |
|------|-----|---------|
| `atom_id` | String | MD5(statementGeoid + ":" + atomText) |
| `statement_geoid` | String | Родительский statement |
| `atom_text` | String | Текст атома: `t.balance`, `SUM(amount)` |
| `atom_context` | String | Clause: SELECT / FROM / WHERE / SET_EXPR ... |
| `parent_context` | String | Уточнение: GROUP BY / HAVING / ON / ... |
| `position` | String | `line:col` позиция в исходном тексте |
| `sposition` | String | Строковое представление позиции |
| `is_column_reference` | Boolean | Ссылка на колонку реальной таблицы |
| `is_function_call` | Boolean | Вызов функции: SUM(...), NVL(...) |
| `is_constant` | Boolean | Литерал: 42, 'text', NULL |
| `is_complex` | Boolean | Сложное выражение (несколько операндов) |
| `is_routine_param` | Boolean | Параметр routine |
| `is_routine_var` | Boolean | Локальная переменная routine |
| `table_name` | String | Имя таблицы (до резолюции) |
| `column_name` | String | Имя колонки (после резолюции) |
| `table_geoid` | String | Geoid таблицы (после резолюции) |
| `status` | String | `null` / `"constant"` / `"function_call"` / `"Обработано"` / `"unresolved"` |
| `warning` | String | `"Не связано"` или `"Не разобрано"` — см. ниже |
| `merge_clause` | String | `UPDATE` / `INSERT` для MERGE атомов |
| `output_column_sequence` | Integer | Порядок output-колонки (для SELECT atoms) |
| `nested_atoms_count` | Integer | Кол-во вложенных атомов (для сложных выражений) |
| `session_id` | String | ID сессии |

**Значения поля `status`:**

| Значение | Устанавливается | Смысл |
|---------|----------------|-------|
| `null` | изначально | атом зарегистрирован, ещё не обработан |
| `"constant"` | AtomProcessor | литерал: `42`, `'text'`, `NULL` |
| `"function_call"` | AtomProcessor | вызов функции: `SUM(...)`, `NVL(...)` |
| `"Обработано"` | resolvePendingColumns | column reference успешно разрешён |
| `"unresolved"` | resolvePendingColumns | разрешить не удалось — все 8 стратегий провалились |

Только атомы со статусом `"Обработано"` участвуют в DATA_FLOW и lineage.
При подсчёте quality score: `resolved("Обработано") + constants + functions`.

**Поле `warning` — два источника с разной семантикой:**

```
Источник 1: AtomProcessor (при классификации)
  Условие: (is_routine_var OR is_routine_param) AND dml_target_ref != null
  Значение: "Не связано"
  Смысл: routine-переменная или параметр используется как источник DML,
         но не является column reference реальной таблицы →
         DATA_FLOW ребро создать невозможно

Источник 2: AtomProcessor (при резолюции)
  Условие: не удалось разрешить вообще
  Значение: "Не разобрано"
  Смысл: имя не найдено ни в одной таблице/алиасе

Источник 3: EmbeddedWriter (при записи в БД)
  Условие: status="Обработано" AND is_column_reference=true
           AND DaliColumn с table_geoid+column_name НЕ создан в этой сессии
  Значение: "Не связано" (может перезаписать или дополнить)
  Смысл: атом указывает на колонку, которая не попала в реестр колонок —
         например, таблица RECONSTRUCTED и колонка встречена только косвенно
```

**Вычисляемые поля:**
- `atom_id` — MD5-хеш от составного ключа (уникальность без UUID)

---

## Рёбра: полный справочник

Для каждой группы рёбер указано: **когда создаётся** (фаза транзакции),
**какой алгоритм** инициирует создание, и **что обозначает** семантически.

---

### Группа 1: Структурные рёбра (иерархия объектов)

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliDatabase  → DaliSchema        CONTAINS_SCHEMA       —
DaliSchema    → DaliTable         CONTAINS_TABLE        —
DaliSchema    → DaliRoutine       CONTAINS_ROUTINE      —
DaliSchema    → DaliPackage       CONTAINS_ROUTINE      —
DaliPackage   → DaliRoutine       CONTAINS_ROUTINE      —
DaliTable     → DaliColumn        HAS_COLUMN            —
DaliRoutine   → DaliParameter     HAS_PARAMETER         —
DaliRoutine   → DaliVariable      HAS_VARIABLE          —
DaliRoutine   → DaliRoutine       NESTED_IN             session_id
DaliApplication → DaliDatabase    BELONGS_TO_APP        —
DaliSession   → DaliSchema        BELONGS_TO_SESSION    —
DaliSession   → DaliRoutine       BELONGS_TO_SESSION    —
DaliSession   → DaliStatement     BELONGS_TO_SESSION    —
```

**Когда создаётся:** фазы 2–7 транзакции, при создании каждой вершины.

**Алгоритм:** `StructureAndLineageBuilder.ensureSchema()`, `ensureTable()`,
`addColumn()`, `ensureRoutine()` — сразу после создания вершины создаётся ребро
к родительскому объекту. Родитель всегда создаётся первым (гарантировано порядком
транзакции).

**Семантика:** описывают **ownership** — кому принадлежит объект в иерархии
схемы базы данных. BELONGS_TO_SESSION — дополнительная связь для быстрой выборки
всех объектов одной сессии. NESTED_IN — routine объявлена внутри другой routine.

---

### Группа 2: Statement → контейнер

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliStatement → DaliStatement     CHILD_OF              —
                                  (subquery → parent)
DaliRoutine   → DaliStatement     CONTAINS_STMT         —
```

**Когда создаётся:** фаза 8 транзакции.

**Алгоритм:** при создании вершины DaliStatement — если у statement есть
`parent_statement_geoid`, создаётся CHILD_OF к родительскому statement.
Если statement принадлежит routine (есть `routine_geoid`) — создаётся
CONTAINS_STMT.

**Семантика:**
- `CHILD_OF` — statement является вложенным подзапросом (subquery, CTE, UNION ветка).
  Определяет иерархию вложенности. Используется стратегией 7 NameResolver'а
  (рекурсивный поиск вверх).
- `CONTAINS_STMT` — statement выполняется внутри данной routine.

---

### Группа 3: Statement → таблицы (data access)

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliStatement → DaliTable         READS_FROM            aliases[], session_id
DaliStatement → DaliTable         WRITES_TO             aliases[], session_id
DaliStatement → DaliStatement     USES_SUBQUERY         aliases[], subquery_type
```

**Когда создаётся:** фаза 8 транзакции.

**Алгоритм:** из `StatementInfo.source_table_geoids` (FROM-таблицы) →
READS_FROM; из `target_table_geoids` (DML-таблицы) → WRITES_TO;
из `source_subquery_geoids` → USES_SUBQUERY.

Список таблиц заполняется SemanticListener при обходе FROM-clause и DML-clause
в Statement Lifecycle:
- `markAsSource(tableGeoid)` при exitFromClause → source_table_geoids
- `markAsTarget(tableGeoid)` при enterDmlTarget → target_table_geoids

**Семантика:**
- `READS_FROM` — statement читает данные из этой таблицы (SELECT FROM, USING)
- `WRITES_TO` — statement записывает в эту таблицу (INSERT/UPDATE/DELETE/MERGE INTO)
- `USES_SUBQUERY` — statement использует другой statement как источник данных
  (inline subquery в FROM или MERGE USING). Тип подзапроса в `subquery_type`.

---

### Группа 4: Statement → вложенные объекты

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliStatement → DaliOutputColumn  HAS_OUTPUT_COL        —
DaliStatement → DaliJoin          HAS_JOIN              —
DaliStatement → DaliAffectedColumn HAS_AFFECTED_COL     session_id
DaliStatement → DaliAtom          HAS_ATOM              —
```

**Когда создаётся:** фаза 8 (OutputColumn, Join, AffectedColumn) и фаза 9 (Atom).

**Алгоритм:** создаётся сразу после создания каждой вложенной вершины.

**Семантика:** обозначает **composit ownership** — statement содержит эти объекты
как составные части своего вывода и анализа. Без этих рёбер невозможно найти
атомы/колонки конкретного statement'а при query-time.
- `HAS_OUTPUT_COL` — колонка в SELECT list этого statement
- `HAS_AFFECTED_COL` — target-колонка DML (куда записываются данные)
- `HAS_JOIN` — JOIN-операция, входящая в этот statement
- `HAS_ATOM` — атомарное выражение, зарегистрированное в этом statement

---

### Группа 5: Join → таблицы

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliJoin → DaliTable              JOIN_SOURCE_TABLE     —
DaliJoin → DaliTable              JOIN_TARGET_TABLE     —
```

**Когда создаётся:** фаза 8 транзакции, после создания вершины DaliJoin.

**Алгоритм:** `JoinProcessor.registerJoin()` сохраняет `JoinInfo` с geoid'ами
левой и правой таблиц. При записи в БД — JOIN → левая таблица (JOIN_SOURCE_TABLE),
JOIN → правая таблица (JOIN_TARGET_TABLE).

**Семантика:** связывает конкретную JOIN-операцию с её участниками. Позволяет
понять, **между какими объектами** установлена JOIN-связь и с каким ON-условием.

---

### Группа 6: Atom → resolution targets

```
FROM → TO                         Тип ребра            Условие создания
──────────────────────────────────────────────────────────────────
DaliAtom → DaliTable              ATOM_REF_TABLE        table_geoid разрешён в tblV
DaliAtom → DaliColumn             ATOM_REF_COLUMN       + column_name разрешён в colV
DaliAtom → DaliStatement          ATOM_REF_STMT         table_geoid = subquery geoid
DaliAtom → DaliOutputColumn       ATOM_REF_OUTPUT_COL   + column_name ∈ output cols
```

**Когда создаётся:** фаза 9 транзакции.

**Алгоритм:** при записи каждого DaliAtom проверяется результат резолюции:
- если `atom.table_geoid` найден в реестре таблиц → ATOM_REF_TABLE
- если дополнительно `atom.column_name` найден в колонках этой таблицы → ATOM_REF_COLUMN
- если `atom.table_geoid` является geoid statement'а (подзапрос) → ATOM_REF_STMT
- если `atom.column_name` совпадает с именем output-колонки подзапроса → ATOM_REF_OUTPUT_COL

Условия проверяются последовательно (таблица ИЛИ statement, не оба сразу).

**Семантика:** показывает **к чему привязан** этот атом после резолюции имён.
Это "указатели" атома на реальные объекты схемы — результат работы NameResolver.
Связка ATOM → COLUMN позволяет найти все места использования конкретной колонки
в любом statement'е.

---

### Группа 7: ATOM_PRODUCES — атом производит данные

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliAtom → DaliOutputColumn       ATOM_PRODUCES         session_id
                                  (SELECT вывод)        [при output_column_sequence]
DaliAtom → DaliAffectedColumn     ATOM_PRODUCES         session_id, merge_clause
                                  (DML target)          [при dml_target_ref]
```

**Когда создаётся:** фаза 9 транзакции.

**Алгоритм:**
- Если `atom.output_column_sequence != null` → атом присутствует в SELECT list
  и является источником данных для output-колонки с тем же порядковым номером.
  `output_column_sequence` заполняется AtomProcessor при обходе SELECT list.
- Если `atom.dml_target_ref != null` → атом привязан к target-колонке DML.
  `dml_target_ref` устанавливается через `bindAtomsToMergeUpdateTarget()` или
  аналогичный mechanism для INSERT/UPDATE (position-based binding, см. `06_ATOM_PROCESSING.md`).

**Семантика:** "этот атом **вносит вклад** в данный output/affected slot."
Является атомарной единицей lineage: от конкретного выражения к конкретной
позиции вывода или записи. Предшествует DATA_FLOW — сначала ATOM_PRODUCES,
потом DataFlowProcessor строит DATA_FLOW.

---

### Группа 8: DATA_FLOW — поток данных между колонками

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliColumn → DaliOutputColumn     DATA_FLOW             session_id, statement_geoid,
                                  (SELECT lineage)      atom_id, flow_type
DaliColumn → DaliAffectedColumn   DATA_FLOW             session_id, statement_geoid,
                                  (DML lineage)         atom_id, flow_type
```

**Когда создаётся:** пост-обходная фаза — `DataFlowProcessor.process()` после
завершения анализа всех statement'ов файла, до сериализации (см. `03_SEMANTIC_ENGINE.md`).

**Алгоритм:**
```
Для каждого атома со status="Обработано" и output_column_sequence != null:
  src_col = DaliColumn(table_geoid + "." + column_name)
  tgt     = DaliOutputColumn или DaliAffectedColumn (по dml_target_ref / sequence)
  flow_type = вычислить (см. ниже)
  Создать DATA_FLOW(src_col → tgt, flow_type, statement_geoid, atom_id)
```

**Вычисляемое поле `flow_type`:** логика различается по writer'у:

```
EmbeddedWriter / JsonlBatchBuilder (inline при записи атомов):
  SELECT-путь — WriteHelpers.resolveFlowType():
    hasAggregation AND parent_context="GROUP BY"  → "AGGREGATE"
    иначе                                          → "DIRECT"
    ⚠ is_function_call НЕ обрабатывается здесь

  DML-путь — WriteHelpers.resolveDmlFlowType():
    type = "INSERT"  → "INSERT"
    type = "UPDATE"  → "UPDATE"
    type = "MERGE"   → "MERGE"
    иначе            → "DIRECT"

DataFlowProcessor (Arrow/Remote serialization path):
  SELECT-путь — DataFlowProcessor.resolveFlowType():
    hasAggregation AND parent_context="GROUP BY"  → "AGGREGATE"
    is_function_call = true                        → "TRANSFORM"  ← только здесь
    иначе                                          → "DIRECT"
```

**Итоговый набор значений по writer:**
| flow_type | EmbeddedWriter | DataFlowProcessor |
|-----------|:--------------:|:-----------------:|
| DIRECT    | ✓              | ✓                 |
| AGGREGATE | ✓              | ✓                 |
| INSERT    | ✓              | —                 |
| UPDATE    | ✓              | —                 |
| MERGE     | ✓              | —                 |
| TRANSFORM | —              | ✓ (only)          |

> `TRANSFORM` генерируется только через `DataFlowProcessor` (Arrow/Flight путь).
> В embedded и batch режимах `TRANSFORM` не появляется.
> Значение `"DML"` не генерируется ни одним writer'ом.

**Семантика:** главное ребро **column-level lineage**. Отвечает на вопрос:
"из какой физической колонки таблицы пришли данные в эту позицию вывода или запись?"
Тип потока объясняет **характер операции**:
- `DIRECT` — колонка передана без изменений (SELECT) или DELETE
- `AGGREGATE` — была агрегирована (SUM, COUNT, MAX...)
- `INSERT` — данные записываются через INSERT INTO
- `UPDATE` — данные записываются через UPDATE SET
- `MERGE` — данные записываются через MERGE INTO

---

### Группа 9: FILTER_FLOW — колонка используется в фильтрации

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliColumn → DaliStatement        FILTER_FLOW           filter_type (WHERE/HAVING/JOIN),
                                                        session_id, statement_geoid,
                                                        via_atom (текст атома)
```

**Когда создаётся:** фаза 9 транзакции, при записи атомов.

**Алгоритм:** при записи каждого DaliAtom проверяется: если атом resolved
(`status = "Обработано"`) И его `parent_context` ∈ {WHERE, HAVING, JOIN} →
создаётся FILTER_FLOW от DaliColumn к DaliStatement.

**Семантика:** показывает, что **данная колонка влияет на выборку строк**
в этом statement'е, но не является частью вывода. Используется для impact analysis:
"если изменится значение этой колонки, какие SELECT-операторы изменят свой результат?"
`filter_type` уточняет контекст использования.

> **Замечание по JOIN context:** атомы в ON-условии JOIN имеют `parent_context="JOIN"`,
> что приводит к созданию FILTER_FLOW. Тот же JOIN обрабатывается `JoinProcessor` и
> записывается как вершина `DaliJoin` с рёбрами `JOIN_SOURCE_TABLE` / `JOIN_TARGET_TABLE`.
>
> Оба ребра создаются намеренно с разными целями:
> - `DaliJoin` → структурная информация о JOIN (тип, условие, участники)
> - `FILTER_FLOW` → impact analysis: "эта колонка управляет тем, какие строки попадут в JOIN"
>
> Для атомов в ON-условии FILTER_FLOW фиксирует конкретную колонку-предикат,
> а не просто факт существования JOIN.

---

### Группа 10: CALLS — вызов routine

```
FROM → TO                         Тип ребра            Свойства
──────────────────────────────────────────────────────────────────
DaliRoutine → DaliRoutine         CALLS                 session_id, caller_geoid,
                                                        callee_name, line_start
```

**Когда создаётся:** фаза 13 транзакции (последняя, после lineage).

**Алгоритм:** SemanticListener регистрирует вызовы через `addCalledRoutine()`.
При записи: найти вершину callee по geoid в реестре; если не найдена —
создать stub с `routine_geoid = "EXT:PROC_NAME"`, `data_source = RECONSTRUCTED`.
Затем создать CALLS ребро.

**Семантика:** граф вызовов между хранимыми процедурами и функциями.
Позволяет строить call graph: кто вызывает кого, на какой строке.
Stub-вершины обозначают вызовы во внешние пакеты/процедуры, ещё не обработанные
(или из других схем).

---

### Группа 11: Lineage рёбра из LineageEdge

```
FROM → TO                         Тип ребра           Свойства
──────────────────────────────────────────────────────────────────
DaliColumn → DaliColumn           DIRECT / JOIN        session_id,
                                                       statement_geoid
```

**Когда создаётся:** фаза 14 транзакции.

**Алгоритм:** два источника LineageEdge объектов:

```
buildLineageEdges() при exitStatement:
  Для атомов с dml_target_ref != null:
    LineageEdge(src=column_geoid, tgt=target_col_geoid, relationType="DIRECT")

JoinProcessor при регистрации JOIN ON:
  LineageEdge(src=left_col_geoid, tgt=right_col_geoid, relationType="JOIN")
```

При записи в БД: тип ребра = `LineageEdge.relationType`, участники разрешаются
по geoid в реестре вершин.

**Семантика:** высокоуровневый **column-level lineage** между двумя физическими
колонками. В отличие от DATA_FLOW (который идёт к OutputColumn/AffectedColumn),
LineageEdge связывает Column → Column напрямую, минуя промежуточные statement-объекты.
Это "итоговый" граф lineage — пригоден для построения upstream/downstream impact
без обхода промежуточных statement'ов.

Типы рёбер (реально генерируемые):
- `DIRECT` — данные переданы напрямую через DML (INSERT/UPDATE/MERGE)
- `JOIN` — связь через JOIN ON-условие (колонки используются как ключи соединения)

> **TRANSFORM, AGGREGATE в LineageEdge:** зарезервированы в модели `LineageEdge`
> и в `VALID_FLOW_TYPES` тестов, но **не генерируются** текущей реализацией
> `buildLineageEdges()`. Агрегационная и трансформационная семантика хранится
> в `flow_type` рёбер **DATA_FLOW** (Column→OutputColumn), а не в LineageEdge.
>
> `DataFlowProcessor` создаёт DATA_FLOW рёбра напрямую в БД — без LineageEdge объектов.

---

## Полная карта графа

```
DaliApplication
  └─[BELONGS_TO_APP]─► DaliDatabase
       └─[CONTAINS_SCHEMA]─► DaliSchema
            ├─[CONTAINS_TABLE]─► DaliTable
            │    └─[HAS_COLUMN]─► DaliColumn
            │         └─[DATA_FLOW]──────────────────────────────┐
            │         └─[FILTER_FLOW]─► DaliStatement           │
            │                                                    │
            └─[CONTAINS_ROUTINE]─► DaliPackage                  │
                 └─[CONTAINS_ROUTINE]─► DaliRoutine             │
                      ├─[HAS_PARAMETER]─► DaliParameter         │
                      ├─[HAS_VARIABLE]─► DaliVariable           │
                      ├─[NESTED_IN]─► DaliRoutine               │
                      ├─[CALLS]─► DaliRoutine                   │
                      └─[CONTAINS_STMT]─► DaliStatement ◄───────┘
                           ├─[CHILD_OF]─► DaliStatement
                           ├─[READS_FROM]─► DaliTable
                           ├─[WRITES_TO]─► DaliTable
                           ├─[USES_SUBQUERY]─► DaliStatement
                           ├─[HAS_OUTPUT_COL]─► DaliOutputColumn ◄──┐
                           ├─[HAS_AFFECTED_COL]─► DaliAffectedColumn ◄─┐
                           ├─[HAS_JOIN]─► DaliJoin               │   │
                           │    ├─[JOIN_SOURCE_TABLE]─► DaliTable │   │
                           │    └─[JOIN_TARGET_TABLE]─► DaliTable │   │
                           └─[HAS_ATOM]─► DaliAtom                │   │
                                ├─[ATOM_REF_TABLE]─► DaliTable    │   │
                                ├─[ATOM_REF_COLUMN]─► DaliColumn  │   │
                                ├─[ATOM_REF_STMT]─► DaliStatement │   │
                                ├─[ATOM_REF_OUTPUT_COL]──────────►│   │
                                ├─[ATOM_PRODUCES]────────────────►│   │
                                └─[ATOM_PRODUCES]─────────────────────►│

Documents (без рёбер):
  DaliSnippet         — SQL-текст каждого statement
  DaliSnippetScript   — полный текст исходного файла
  DaliResolutionLog   — попытки resolution (только --diag)
  DaliSchemaLog       — suspicious schema registrations
```

---

## CanonicalPool: переиспользование вершин между сессиями

Без пула каждая сессия создаёт **новые** вершины DaliTable и DaliColumn.
Это приводит к дублированию одних и тех же объектов схемы.

`CanonicalPool` решает это: он хранит уже созданные вершины в памяти
и переиспользует их при записи новых сессий.

```
Логика при записи DaliTable (pool режим):
  1. canonicalize geoid: "DWH.ACCOUNTS" → canonical_key
  2. pool.getTableVtx(canonical_key) → вершина в памяти?
       ДА → использовать существующую
       НЕТ → SELECT FROM DaliTable WHERE db_name=? AND table_geoid=?
              найдено в БД → загрузить в pool
              не найдено → CREATE новую вершину → поместить в pool
  3. Если таблица из DDL (isMasterTable=true) и текущая data_source=RECONSTRUCTED
     → upgrade: установить data_source=MASTER

Для DaliColumn — аналогичная логика с canonicalCol(tableGeoid, columnName)
```

---

## DaliPerfStats: метрики записи

После каждой записи сохраняется вершина `DaliPerfStats` с метриками:

| Поле | Значение |
|------|---------|
| `session_id` | ID сессии |
| `cnt_tables` | Кол-во таблиц |
| `cnt_columns` | Кол-во колонок |
| `cnt_statements` | Кол-во statement'ов |
| `cnt_routines` | Кол-во routine'ов |
| `cnt_atoms` | Всего атомов |
| `cnt_joins` | Всего JOIN'ов |
| `cnt_output_cols` | Всего output-колонок |
| `cnt_lineage` | Кол-во lineage edges |
| `atom_resolved` | Атомов со статусом "Обработано" |
| `atom_const` | Атомов-констант |
| `atom_func` | Атомов-функций |
| `atom_failed` | Нерезолюцированных атомов |
| `parse_ms`, `analyze_ms`, `write_ms` | Время по фазам (из PipelineTimer) |
