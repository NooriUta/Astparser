# HOUND — Архитектурный обзор

## Что такое HOUND

HOUND — инструмент статического анализа SQL, который читает исходные файлы процедур и скриптов
и извлекает из них **структуру базы данных** (таблицы, колонки, процедуры) и **data lineage** —
граф зависимостей между объектами на уровне столбцов.

Поддерживает 15+ SQL-диалектов: PL/SQL, PostgreSQL, T-SQL, Snowflake, Hive, Databricks, DB2 и другие.

---

## Три этапа обработки

```
╔══════════════════════════════════════════════════════════════════╗
║  ЭТАП 1: ПАРСИНГ                                                 ║
║                                                                  ║
║  SQL-текст файла                                                 ║
║       │                                                          ║
║       ▼  [определение диалекта]                                  ║
║  Выбор грамматики (ANTLR)                                        ║
║       │                                                          ║
║       ▼  [лексический анализ]                                    ║
║  Поток токенов (keywords, identifiers, literals, operators)      ║
║       │                                                          ║
║       ▼  [синтаксический анализ]                                 ║
║  ParseTree — «сырое» дерево грамматики                           ║
╚══════════════════════════════════════════════════════════════════╝
                           │
                           ▼
╔══════════════════════════════════════════════════════════════════╗
║  ЭТАП 2: ПОСТРОЕНИЕ AST                                          ║
║                                                                  ║
║  Обход ParseTree (depth-first, Listener-паттерн)                 ║
║       │                                                          ║
║       ▼  [EnterRule / ExitRule / VisitTerminal]                  ║
║  UniversalAstNode — наше дерево с позициями и типами             ║
║       │                                                          ║
║       ├── RULE-узлы  (нетерминалы: select_statement, from_clause)║
║       ├── TERMINAL-узлы (листья: identifier, numeric_literal)    ║
║       └── ERROR-узлы  (синтаксические ошибки)                    ║
╚══════════════════════════════════════════════════════════════════╝
                           │
                           ▼
╔══════════════════════════════════════════════════════════════════╗
║  ЭТАП 3: СЕМАНТИЧЕСКИЙ АНАЛИЗ                                    ║
║                                                                  ║
║  SemanticEngine оркестрирует 5 компонентов:                      ║
║                                                                  ║
║  ┌─────────────┐   ┌──────────────┐   ┌──────────────────────┐  ║
║  │ScopeManager │   │ NameResolver │   │  AtomProcessor       │  ║
║  │             │   │              │   │                      │  ║
║  │ Stack вход/ │   │ 8 стратегий  │   │ Регистрация →        │  ║
║  │ выхода из   │◄──│ разрешения   │   │ Классификация →      │  ║
║  │ контекстов  │   │ имён         │   │ Привязка к target    │  ║
║  └─────────────┘   └──────────────┘   └──────────────────────┘  ║
║                                                                  ║
║  ┌──────────────────────────────┐   ┌───────────────────────┐   ║
║  │StructureAndLineageBuilder    │   │   JoinProcessor       │   ║
║  │                              │   │                       │   ║
║  │ Tables / Columns / Routines  │   │ Регистрация JOIN'ов   │   ║
║  │ Statements / LineageEdges    │   │ Определение типа      │   ║
║  └──────────────────────────────┘   └───────────────────────┘   ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
                           │
                           ▼
╔══════════════════════════════════════════════════════════════════╗
║  РЕЗУЛЬТАТ                                                       ║
║                                                                  ║
║  Structure                    Lineage                            ║
║  ├── databases[]              ├── atoms[] (атомарные выражения)  ║
║  ├── schemas[]                ├── joins[] (JOIN-операции)        ║
║  ├── packages[]               └── tables_usage[] (src/target)   ║
║  ├── tables[]                                                    ║
║  ├── columns[]                                                   ║
║  ├── routines[]                                                  ║
║  └── statements[]                                               ║
╚══════════════════════════════════════════════════════════════════╝
```

---

## Ключевые концепции

### Geoid — иерархический идентификатор

Каждый объект в HOUND идентифицируется через **geoid** — строку, кодирующую
место объекта в иерархии.

Примеры:
```
Таблица:      SCHEMA_NAME.TABLE_NAME
Колонка:      SCHEMA_NAME.TABLE_NAME.COLUMN_NAME
Процедура:    PKG_NAME:PROCEDURE:PROC_NAME
  вложенная:  PKG_NAME:PROCEDURE:OUTER:FUNCTION:INNER
Statement:    PKG:PROCEDURE:PROC:SELECT:48
  subquery:   PKG:PROCEDURE:PROC:SELECT:48:SQ:ALIAS:72
  CTE:        PKG:PROCEDURE:PROC:MONTHLY_REVENUE:58
```

Разделитель `:` используется для execution hierarchy (routine → statement → subquery).
Разделитель `.` — для схемных объектов (schema.table.column).

### Атом — минимальная единица анализа

**Атом** — это токен или группа токенов, которые составляют осмысленное выражение:
ссылку на колонку, вызов функции, литерал, выражение.

Атом регистрируется с:
- Текстом (`user_id`, `SUM(amount)`, `t.balance`)
- Позицией (строка:колонка) — для point-in-range binding
- Контекстом (в каком clause находится: SELECT, FROM, WHERE, SET...)
- Статусом (обработан / не обработан)

### Statement lifecycle

Каждый SQL-оператор обрабатывается по единому циклу:

```
[enter statement]
      │
      ├── push ScopeContext на стек
      ├── зарегистрировать statement в Builder
      │
      ▼  [обход дочерних clause]
      │
      ├── FROM clause → регистрация таблиц и алиасов
      ├── SELECT list → регистрация атомов
      ├── WHERE / JOIN → регистрация предикатов
      ├── DML target → пометка target-таблиц
      │
      ▼
[exit statement]
      │
      ├── resolvePendingColumns()
      ├── buildLineageEdges()
      └── pop ScopeContext со стека
```

---

## Диалекты: как поддерживается 15+ грамматик

Каждый диалект — отдельная ANTLR-грамматика. Все они живут в `parser/base/grammars/sql/`.
`ParserFactory` определяет диалект по имени файла и выбирает нужные классы лексера/парсера.

```
.plsql / oracle / .pks / .pkb  →  PL/SQL
postgresql / postgres           →  PostgreSQL
tsql / mssql / sqlserver        →  T-SQL
snowflake                       →  Snowflake
hive                            →  Hive
databricks                      →  Databricks
athena                          →  Athena
clickhouse                      →  ClickHouse
trino                           →  Trino
starrocks                       →  StarRocks
mariadb                         →  MariaDB
db2                             →  DB2
derby                           →  Derby
drill                           →  Drill
cockroachdb                     →  CockroachDB
(default)                       →  Generic SQL
```

---

## Документация (содержание)

| Файл | Описание |
|------|----------|
| [01_PARSING_LAYER.md](01_PARSING_LAYER.md) | Парсинг SQL, ANTLR, UniversalTreeListener |
| [02_AST_NODE.md](02_AST_NODE.md) | Структура UniversalAstNode |
| [03_SEMANTIC_ENGINE.md](03_SEMANTIC_ENGINE.md) | Оркестратор и lifecycle statement |
| [04_SCOPE_MANAGEMENT.md](04_SCOPE_MANAGEMENT.md) | ScopeManager, ScopeContext, Geoid formula |
| [05_NAME_RESOLUTION.md](05_NAME_RESOLUTION.md) | 8 стратегий разрешения имён |
| [06_ATOM_PROCESSING.md](06_ATOM_PROCESSING.md) | Lifecycle атома: регистрация → классификация → binding |
| [07_LINEAGE_BUILDER.md](07_LINEAGE_BUILDER.md) | Построение структуры и lineage |
| [08_CASE_STUDIES.md](08_CASE_STUDIES.md) | Разбор MERGE, CTE, вложенных запросов |
| [09_DIAGNOSTICS.md](09_DIAGNOSTICS.md) | Диагностика: resolution log, schema log |
| [10_TESTING.md](10_TESTING.md) | Тестовое покрытие |
| [APPENDIX_GLOSSARY.md](APPENDIX_GLOSSARY.md) | Глоссарий и индекс файлов |
