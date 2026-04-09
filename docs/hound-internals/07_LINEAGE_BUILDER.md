# Построение структуры и Lineage

## Роль StructureAndLineageBuilder

Builder — центральный реестр всего, что HOUND узнаёт о коде в процессе анализа.
Это единственный источник истины для всех объектов: каждый компонент
(NameResolver, SemanticEngine, DiagnosticWriter) читает данные отсюда.

---

## Что хранится в Builder

```
StructureAndLineageBuilder
│
├── databases   Map<geoid, Object>      — базы данных
├── schemas     Map<geoid, Object>      — схемы (SCHEMA_NAME → {name, db})
├── packages    Map<geoid, Object>      — PL/SQL пакеты
│
├── tables      Map<geoid, TableInfo>   — таблицы (включая VIEW, CTE, TEMP)
├── columns     Map<geoid, ColumnInfo>  — колонки
│
├── routines    Map<geoid, RoutineInfo> — процедуры и функции
├── statements  Map<geoid, StatementInfo> — SQL-операторы
│
└── lineageEdges List<LineageEdge>      — рёбра зависимостей
```

---

## Иерархия объектов

```
Database
  └─ Schema
       ├─ Package
       │    └─ Routine (Procedure / Function)
       │         └─ Statement (SELECT / INSERT / ...)
       │              ├─ ChildStatement (subquery, CTE)
       │              └─ Atoms
       │
       └─ Table (TABLE / VIEW / CTE / TEMP)
            └─ Column
```

---

## Алгоритм регистрации таблицы

```
ensureTable(tableName, schemaGeoid):

  1. Нормализовать имя в UPPER_CASE

  2. Автодетект схемы из квалифицированного имени:
     "DWH.ACCOUNTS" → schema="DWH", table="ACCOUNTS"
     → ensureSchema("DWH", null)

  3. Вычислить geoid:
     schema != null → geoid = "SCHEMA.TABLE_NAME"
     schema == null → geoid = "TABLE_NAME"

  4. Диагностика (только в --diag режиме):
     Имя содержит ( ) [ ] ! % ^ & * ?  → SUSPICIOUS (скорее всего функция)
     Имя начинается с "FUNC("          → вероятно ошибочное распознавание

  5. computeIfAbsent: если таблица с таким geoid ещё не существует —
     создать TableInfo и добавить в реестр

  6. Вернуть geoid

Метод ensureTableWithType(name, schema, tableType):
  То же самое, но явно указывает тип: TABLE / VIEW / CTE / TEMP
```

---

## TableInfo: что хранится в записи таблицы

```
TableInfo
  geoid        — "SCHEMA.TABLE_NAME"
  tableName    — "TABLE_NAME" (без схемы)
  schemaGeoid  — "SCHEMA"
  tableType    — TABLE / VIEW / CTE / TEMP / TABLE_FUNCTION
  aliases      — Set<String>  (все алиасы, встреченные в коде)
  columnCount  — счётчик зарегистрированных колонок
```

---

## Алгоритм регистрации колонки

```
addColumn(tableGeoid, columnName, expression, alias):

  1. Нормализовать columnName в UPPER_CASE
  2. Вычислить geoid = tableGeoid + "." + columnName
  3. Если колонка ещё не существует:
     а) Увеличить columnCount у таблицы
     б) ordinal = columnCount (1-based, в порядке встречи)
     в) Создать ColumnInfo и добавить в реестр

addColumnWithOrdinal(tableGeoid, columnName, expression, alias, ordinal):
  Аналогично, но ordinal берётся явно — из DDL (CREATE TABLE).
  Используется для точного сохранения порядка колонок из DDL.
  DDL-регистрация имеет приоритет: если колонка уже есть — не перезаписывается.
```

---

## Регистрация Routine: формула geoid

```
Контекст                      → geoid
──────────────────────────────────────────────────────
Standalone (без пакета):      PROCEDURE:PROC_NAME
  или со схемой:              SCHEMA:PROCEDURE:PROC_NAME
В пакете:                     PKG_NAME:PROCEDURE:PROC_NAME
Вложенная:                    PKG_NAME:PROCEDURE:OUTER:FUNCTION:INNER
Вложенная без пакета:         PROCEDURE:OUTER:FUNCTION:LOCAL_HELPER

Разделитель: ":" (не ".")
```

При регистрации routine убеждаемся, что её схема тоже зарегистрирована —
чтобы граф мог создать рёбра Schema→Routine.

---

## StatementInfo: полная запись об операторе

```
StatementInfo содержит:
  geoid              — иерархический идентификатор
  type               — SELECT / INSERT / UPDATE / DELETE / MERGE / CTE / VIEW...
  snippet            — первые N символов SQL-текста
  lineStart/End      — строки в исходном файле
  parentStatementGeoid — родитель (для subquery)
  routineGeoid       — routine-контейнер
  alias              — для CTE и subquery

  sourceTables       — Map<geoid, tableInfo>  (FROM/USING)
  targetTables       — Map<geoid, tableInfo>  (INSERT INTO/UPDATE/MERGE INTO)
  childStatements    — List<geoid>            (подзапросы, CTEs)
  sourceSubqueries   — Map<geoid, SubqueryUsage>
  columnsOutput      — Map<seq, columnRef>    (SELECT list output)
  joins              — List<JoinInfo>

  hasAggregation     — есть GROUP BY / агрегатные функции
  hasWindow          — есть OVER (...)
  isUnion            — UNION / UNION ALL
  subtype            — BULK COLLECT, RETURNING, FORALL...

  atoms              — Map<atomKey, Map<String,Object>>  (все атомы)
```

---

## Lineage Edge: ребро зависимости

```
LineageEdge
  sourceGeoid    — geoid источника (column или table)
  targetGeoid    — geoid цели (column или table)
  relationType   — см. таблицу ниже
  statementGeoid — в каком statement создано ребро
  usageContext   — "SELECT" / "INSERT" / "UPDATE" / "MERGE" / ...
```

Рёбра добавляются в двух ситуациях:

```
1. buildLineageEdges() при exitStatement — для атомов с dml_target_ref:
   STAGING.NEW_BALANCE → ACCOUNTS.BALANCE
   relationType = "DIRECT"

2. JoinProcessor → при регистрации JOIN ON-условия:
   TABLE_A.key → TABLE_B.key
   relationType = "JOIN"
```

> **TRANSFORM и DataFlowProcessor:**
>
> `LineageEdge.relationType` может принимать значение `"TRANSFORM"` по модели,
> но в текущей реализации `buildLineageEdges()` не генерирует его —
> вся DML-lineage получает `"DIRECT"`. `DataFlowProcessor` не создаёт
> `LineageEdge` объектов — он создаёт рёбра **DATA_FLOW** напрямую в БД
> (Column→OutputColumn/AffectedColumn) с flow_type из своей логики.
>
> `"TRANSFORM"` зарезервирован в модели и в `VALID_FLOW_TYPES` тестов,
> но на момент текущей реализации не генерируется ни одним из кодовых путей.

---

## getFullStructure() и getFullLineage(): финальная сериализация

```
getFullStructure():
  Собирает всё в один Map для Arrow/JSON:
    databases  → plain list
    schemas    → plain list
    packages   → plain list
    tables     → serializeTables()   [геоид, имя, схема, тип, алиасы, column_count]
    columns    → serializeColumns()  [геоид, таблица, имя, expression, alias, ordinal]
    routines   → serializeRoutines() [геоид, имя, тип, параметры, переменные]
    statements → serializeStatements() + computed fields:
                   is_dml, is_ddl, has_cte, depth, quality

getFullLineage():
  Собирает:
    atoms        → все атомы из всех statement'ов (плоский список)
    joins        → все JOIN'ы из всех statement'ов
    tables_usage → для каждого statement: какие таблицы SOURCE / TARGET

  Используется для построения DATA_FLOW графа в ArcadeDB.
```

---

## Quality score statement'а

```
quality = (resolved + constants + function_calls) / total_atoms

Каждый statement при сериализации получает quality ∈ [0.0 .. 1.0]:
  1.0 — все выражения разрешены
  0.8 — хороший результат
  < 0.5 — много неразрешённых ссылок

Используется в SEER для цветовой индикации качества lineage.
```

---

## Suspicious schema detection (S1.SCH)

При регистрации схемы Builder проверяет имя на подозрительность:

```
Признаки suspicious schema name:
  Содержит " или '    → quoted_identifier_not_stripped
  Содержит ( или )    → parenthesis_in_schema_name
  Содержит .          → dot_in_schema_name
  Содержит $          → dollar_sign_in_schema_name
  Содержит :          → colon_in_schema_name
  Содержит пробел     → space_in_schema_name

При обнаружении:
  1. Зарегистрировать схему (не отклонять — линейку не ломать)
  2. Добавить запись в schemaRegistrationLog:
     { schema_name, reason, backtrace (только com.hound.* фреймы) }
  3. Записать WARNING в лог

schemaRegistrationLog доступен через getSchemaRegistrationLog()
и записывается в БД для пост-анализа.
```
