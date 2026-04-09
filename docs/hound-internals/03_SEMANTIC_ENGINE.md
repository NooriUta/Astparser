# Семантический движок (SemanticEngine)

## Роль в системе

Семантический движок — оркестратор второго этапа анализа. Он принимает уже готовое
AST-дерево и координирует работу пяти специализированных компонентов, каждый из
которых отвечает за свой аспект анализа.

---

## Архитектура: пять компонентов

```
╔══════════════════════════════════════════════════════════════╗
║              UniversalSemanticEngine                         ║
║                                                              ║
║  ┌─────────────────┐         ┌─────────────────┐            ║
║  │  ScopeManager   │◄───────►│   NameResolver  │            ║
║  │                 │         │                 │            ║
║  │  Stack текущих  │  даёт   │  Разрешает имя  │            ║
║  │  контекстов     │ текущий │  таблицы/алиаса │            ║
║  │  (что сейчас    │  scope  │  в нужном       │            ║
║  │   разбирается)  │         │  контексте      │            ║
║  └─────────────────┘         └─────────────────┘            ║
║                                                              ║
║  ┌──────────────────────────────────────────────────────┐   ║
║  │           StructureAndLineageBuilder                  │   ║
║  │                                                      │   ║
║  │  Хранит реестр всех объектов:                        │   ║
║  │  databases, schemas, packages, tables, columns,      │   ║
║  │  routines, statements, lineage edges                 │   ║
║  └──────────────────────────────────────────────────────┘   ║
║                                                              ║
║  ┌─────────────────┐         ┌─────────────────┐            ║
║  │  AtomProcessor  │         │  JoinProcessor  │            ║
║  │                 │         │                 │            ║
║  │  Регистрирует   │         │  Собирает JOIN  │            ║
║  │  и классифицир. │         │  операции по    │            ║
║  │  выражения      │         │  statement'у    │            ║
║  └─────────────────┘         └─────────────────┘            ║
╚══════════════════════════════════════════════════════════════╝
```

Движок не хранит состояния сам — он делегирует его компонентам. Его роль — принимать
события от SemanticListener'а и передавать их нужным компонентам в нужном порядке.

---

## Инициализация: паттерн "wire"

Перед началом анализа компоненты «подключаются» (wired) — каждый получает ссылки
на те компоненты, которые ему нужны для работы:

```
Создание компонентов:
  scopeManager  = new ScopeManager()
  builder       = new StructureAndLineageBuilder()
  nameResolver  = new NameResolver()
  atomProcessor = (внутри StatementInfo / SemanticListener)
  joinProcessor = new JoinProcessor()

Подключение зависимостей:
  nameResolver.wire(builder, scopeManager)
  → NameResolver получает доступ к реестру объектов и текущему scope

Подключение диагностики (только в --diag режиме):
  resolutionLogger = new ResolutionLogger(enabled=true, sessionId, db)
  nameResolver.setResolutionLogger(resolutionLogger)
  builder.setResolutionLogger(resolutionLogger)
```

---

## Statement Lifecycle — жизненный цикл statement'а

Каждый SQL-оператор (SELECT, INSERT, UPDATE, MERGE и т.д.) проходит одинаковый цикл:

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  [ENTER STATEMENT]                                          │
│       │                                                     │
│       ├─ Определить тип: SELECT / INSERT / MERGE / ...     │
│       ├─ Создать ScopeContext (geoid по формуле)            │
│       ├─ Push ScopeContext на ScopeManager.stack            │
│       └─ Зарегистрировать statement в Builder              │
│                                                             │
│  [ОБХОД CLAUSE'ов]                                          │
│       │                                                     │
│       ├─ FROM clause ──────────────────────────────────┐   │
│       │    ├─ ensureTable(tableName, schema)           │   │
│       │    ├─ registerAlias(alias, tableGeoid)         │   │
│       │    └─ пометить таблицу как SOURCE              │   │
│       │                                                │   │
│       ├─ SELECT list ──────────────────────────────────┤   │
│       │    └─ registerAtom(expr, line, col, "SELECT")  │   │
│       │                                                │   │
│       ├─ WHERE / HAVING ──────────────────────────────┤   │
│       │    └─ registerAtom(expr, line, col, "WHERE")  │   │
│       │                                                │   │
│       ├─ JOIN ─────────────────────────────────────────┤   │
│       │    ├─ registerAtom(expr, ...)                  │   │
│       │    └─ joinProcessor.registerJoin(...)          │   │
│       │                                                │   │
│       └─ DML target ───────────────────────────────────┘   │
│            ├─ пометить таблицу как TARGET               │   │
│            └─ scope.setInDmlTarget(true)                │   │
│                                                             │
│  [EXIT STATEMENT]                                           │
│       │                                                     │
│       ├─ resolvePendingColumns()                            │
│       │    └─ для каждого атома:                           │
│       │         resolve(name) → tableGeoid                 │
│       │         → addColumn(tableGeoid, columnName)        │
│       │         → пометить atom.status = "Обработано"     │
│       │                                                     │
│       ├─ buildLineageEdges()                                │
│       │    └─ для атомов с dml_target_ref:                 │
│       │         addLineageEdge(src_col, tgt_col)           │
│       │                                                     │
│       └─ pop ScopeContext со стека                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Routine Lifecycle — жизненный цикл процедуры/функции

Routine обрабатывается как "обёртка" вокруг statement'ов:

```
[ENTER ROUTINE]
  │
  ├─ Зарегистрировать routine в Builder
  │    geoid = PKG:PROCEDURE:NAME или PROCEDURE:NAME
  ├─ Push ScopeContext.forRoutine(routineGeoid)
  └─ Инициализировать cursor registry

[Statements внутри routine]
  └─ Каждый statement получает routineGeoid из текущего scope
     и включает его в свой geoid: ROUTINE_GEOID:SELECT:48

[EXIT ROUTINE]
  ├─ clearCursorState()  (курсоры и record-переменные PL/SQL)
  └─ pop ScopeContext
```

---

## Иерархия geoid: как читать идентификаторы

```
Пример procedure в пакете:
  Пакет:     TEST_PKG
  Процедура: PROCESS_ORDERS
  SELECT:    на строке 48

  → Statement geoid: TEST_PKG:PROCEDURE:PROCESS_ORDERS:SELECT:48

Subquery внутри этого SELECT (алиас "s", строка 52):
  → TEST_PKG:PROCEDURE:PROCESS_ORDERS:SELECT:48:SQ:S:52

CTE "monthly" на строке 35:
  → TEST_PKG:PROCEDURE:PROCESS_ORDERS:MONTHLY:35
```

Geoid позволяет точно установить:
- К какой routine принадлежит statement
- Является ли он подзапросом и кому принадлежит
- На какой строке исходного файла определён

---

## Флаги активного clause в ScopeContext

В каждый момент времени `ScopeContext` знает, какой SQL-clause сейчас разбирается.
Это определяет, как интерпретируются встречаемые атомы:

```
Флаг / состояние           → getActiveClause()
─────────────────────────────────────────────────
isInValuesClause + isMergeInsertPart  → "MERGE_INSERT_VALUES"
isInValuesClause                      → "VALUES"
isInJoinContext                       → "JOIN"
isInUpdateSetExpr                     → "SET_EXPR"
isMergeInsertPart                     → "MERGE_INSERT"
isMergeUpdatePart                     → "MERGE_UPDATE"
(default)                             → тип statement'а (SELECT, INSERT...)
```

Например, когда атом встречается в состоянии `SET_EXPR` — это правая часть
`UPDATE SET col = <expr>` — он будет привязан к target-колонке.

`MERGE_INSERT_VALUES` — составной контекст (VALUES внутри MERGE INSERT):
позиционный binding привязывает VALUES-атомы к INSERT column list.

---

## Типы подзапросов: SUBQUERY / MSUBQUERY / USUBQUERY

ScopeContext различает три вида вложенных statement'ов:

```
Тип          Когда используется
─────────────────────────────────────────────────────────────────
SUBQUERY     Обычный inline подзапрос в FROM или скалярный в SELECT:
               SELECT * FROM (SELECT id FROM t) s
               → geoid: parentStmt:SQ:ALIAS:LINE

MSUBQUERY    MERGE USING подзапрос:
               MERGE INTO t USING (SELECT ...) s ON ...
               → geoid: parentStmt:MSQ:ALIAS:LINE
               → registerAliasOnParent() при exit — alias виден в MERGE scope

USUBQUERY    UNION ALL ветка:
               SELECT a FROM t1
               UNION ALL
               SELECT a FROM t2   ← это USUBQUERY
               → geoid: parentStmt:USQ:LINE или parentStmt:USQ:ALIAS:LINE
               → output_column_sequence нумеруется независимо для каждой ветки
```

Для разработчика нового диалекта: SemanticListener должен вызывать
`enterMSubquery()` для MERGE USING subquery и `enterUSubquery()` для UNION ветки
— иначе scope будет создан как `SUBQUERY` с неверным типом, что нарушит
стратегию 6 NameResolver'а (source subqueries для MERGE).

---

## Результат анализа: две структуры

По завершении анализа всего файла движок возвращает:

```
getFullStructure() → Map
─────────────────────────
  databases  : [...]        — базы данных
  schemas    : [...]        — схемы
  packages   : [...]        — PL/SQL пакеты
  tables     : [...]        — таблицы (с алиасами, типом, кол-вом колонок)
  columns    : [...]        — колонки (с ordinal_position)
  routines   : [...]        — процедуры и функции (с параметрами)
  statements : [...]        — SQL-операторы (с source/target таблицами)

getFullLineage() → Map
───────────────────────
  atoms        : [...]      — атомарные выражения с их резолюцией
  joins        : [...]      — JOIN-операции
  tables_usage : [...]      — использование таблиц (SOURCE/TARGET) по statement'ам
```

Эти структуры сериализуются в Apache Arrow (IPC-формат) или записываются
напрямую в граф ArcadeDB.

---

## DataFlowProcessor — пост-обходной этап

`DataFlowProcessor` запускается **отдельно** — после завершения обхода AST
всего файла (после последнего `exitStatement`), но до сериализации результата.
Это не часть Statement Lifecycle — он запускается один раз на сессию.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Statement lifecycle (×N, каждый statement)                          │
│  enter → clauses → resolvePendingColumns → buildLineageEdges → pop   │
└─────────────────────────────────┬────────────────────────────────────┘
                                  │ все statements файла завершены
                                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│  DataFlowProcessor.process(structure, lineage)  ← один раз          │
│                                                                      │
│  Для каждого statement:                                              │
│    Для каждого атома со status="Обработано"                          │
│    и output_column_sequence != null:                                 │
│      Определить flow_type (зависит от writer):                       │
│        EmbeddedWriter/Batch SELECT: "AGGREGATE" если agg+GROUP BY   │
│                                     "DIRECT" иначе                  │
│        EmbeddedWriter/Batch DML: "INSERT"/"UPDATE"/"MERGE"/"DIRECT" │
│        DataFlowProcessor (Arrow): + "TRANSFORM" если function_call  │
│      Создать DATA_FLOW запись:                                       │
│        src_col_geoid → output_col_key, flow_type                     │
└─────────────────────────────────┬────────────────────────────────────┘
                                  │
                                  ▼
                    getFullStructure() / getFullLineage()
                    → сериализация / запись в ArcadeDB
```

**Момент запуска:** после `engine.analyze()` завершает обход AST и перед
`writer.saveResult()`. В коде вызов выглядит как:
```
SemanticResult result = engine.analyze(sql, dialect)
dataFlowProcessor.process(result.getStructure(), result.getLineage())
writer.saveResult(result, timer)
```
