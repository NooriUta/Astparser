# HOUND Internals — Документация алгоритмов

Документация описывает внутреннее устройство HOUND: как SQL-текст превращается
в граф структуры базы данных и data lineage на уровне столбцов.

---

## Навигация

```
┌───────────────────────────────────────────────────────────────────┐
│  С чего начать:                                                   │
│                                                                   │
│  Новичок в проекте   →  00_OVERVIEW.md  (5 мин)                  │
│  Дебажишь резолюцию  →  05_NAME_RESOLUTION.md                    │
│  Разбираешь MERGE    →  08_CASE_STUDIES.md  (Case 1)             │
│  Добавляешь диалект  →  01_PARSING_LAYER.md + APPENDIX           │
│  Пишешь тест         →  10_TESTING.md                              │
  Дебажишь запись в БД →  11_DB_WRITE.md                            │
└───────────────────────────────────────────────────────────────────┘
```

---

## Документы

| # | Файл | Описание | Ключевые концепции |
|---|------|----------|--------------------|
| 0 | [00_OVERVIEW.md](00_OVERVIEW.md) | Архитектурный обзор и data flow | 3 этапа, Geoid, Statement lifecycle |
| 1 | [01_PARSING_LAYER.md](01_PARSING_LAYER.md) | Парсинг SQL в AST | ANTLR, диалекты, UniversalTreeListener |
| 2 | [02_AST_NODE.md](02_AST_NODE.md) | Структура узла AST | RULE/TERMINAL/ERROR, properties, position |
| 3 | [03_SEMANTIC_ENGINE.md](03_SEMANTIC_ENGINE.md) | Оркестратор анализа | Wire pattern, Statement lifecycle, флаги clause |
| 4 | [04_SCOPE_MANAGEMENT.md](04_SCOPE_MANAGEMENT.md) | Стек контекстов | ScopeManager, ScopeContext, registerAliasOnParent |
| 5 | [05_NAME_RESOLUTION.md](05_NAME_RESOLUTION.md) | Разрешение имён | 8 стратегий, кеш, ResolvedRef |
| 6 | [06_ATOM_PROCESSING.md](06_ATOM_PROCESSING.md) | Жизненный цикл атома | Регистрация, классификация, position-binding |
| 7 | [07_LINEAGE_BUILDER.md](07_LINEAGE_BUILDER.md) | Реестр структуры и lineage | Builder, TableInfo, LineageEdge, quality score |
| 8 | [08_CASE_STUDIES.md](08_CASE_STUDIES.md) | Разбор сложных случаев | MERGE, CTE, скалярный subquery, JOIN, VIEW |
| 9 | [09_DIAGNOSTICS.md](09_DIAGNOSTICS.md) | Диагностика | ResolutionLogger, SchemaRegistrationLog, --diag |
| 10 | [10_TESTING.md](10_TESTING.md) | Тестовое покрытие | Тест-классы, паттерны, gap coverage |
| 11 | [11_DB_WRITE.md](11_DB_WRITE.md) | Запись в БД: вершины, рёбра, поля | ArcadeDB, все типы edges, вычисляемые поля, CanonicalPool |
| A | [APPENDIX_GLOSSARY.md](APPENDIX_GLOSSARY.md) | Глоссарий и индекс файлов | Термины, типы, ключевые пути |

---

## Схема зависимостей компонентов

```
                    ┌──────────────────────────────┐
                    │   SemanticListener           │
                    │ (PlSqlSemanticListener и др.)│
                    └──────────────┬───────────────┘
                                   │ события: enter/exit
                                   │ statement, clause, atom
                                   ▼
                    ┌──────────────────────────────┐
                    │   UniversalSemanticEngine    │
                    └──┬────────┬────────┬────┬───┘
                       │        │        │    │
             ┌─────────┘  ┌─────┘   ┌───┘  ┌─┘
             ▼            ▼         ▼      ▼
        ┌─────────┐  ┌──────────┐  ┌────────────────────────┐
        │ Scope   │  │ Name     │  │StructureAndLineage      │
        │ Manager │◄─┤ Resolver │─►│ Builder                 │
        └─────────┘  └──────────┘  │ (tables, columns,       │
                                   │  statements, lineage)   │
                     ┌─────────┐   └────────────────────────┘
                     │  Join   │           ▲
                     │Processor│           │ читает
                     └─────────┘   ┌───────────────┐
                                   │DataFlowProces-│
                                   │sor (post-walk)│
                                   └───────────────┘
                                           │
                                           ▼
                                   ┌───────────────┐
                                   │ Arrow IPC /   │
                                   │ ArcadeDB      │
                                   │(→ 11_DB_WRITE)│
                                   └───────────────┘
```

---

## Быстрая шпаргалка

```
Почему alias не резолюцируется?
  → Проверь, что registerAliasOnParent() вызывается для subquery
  → Проверь, что alias регистрируется ДО того, как outer query его ищет
  → Включи --diag и посмотри ResolutionLog

Почему атом не привязан к target-колонке в MERGE?
  → Проверь диапазоны позиций LHS/RHS в listener'е
  → bindAtomsToMergeUpdateTarget должен вызываться при exitMergeElement

Почему quality score низкий?
  → Посмотри какие атомы в status != "Обработано"
  → Вероятно проблема с alias или scope depth

Почему таблица регистрируется с подозрительным именем?
  → Включи --diag, посмотри SchemaRegistrationLog с backtrace
  → Вероятно grammar rule неправильно классифицировала expression как table ref

Почему DATA_FLOW edge не создан / flow_type неверный?
  → 11_DB_WRITE.md §DATA_FLOW: проверь status атома и output_column_sequence
  → SELECT-путь: "AGGREGATE" если hasAggregation+GROUP BY, иначе "DIRECT"
  → DML-путь:   "INSERT" / "UPDATE" / "MERGE" по типу stmt, "DIRECT" иначе
  → Значения "DML" и "TRANSFORM" в коде не генерируются

Почему вершина дублируется / не находится по geoid?
  → 11_DB_WRITE.md §CanonicalPool: MASTER vs RECONSTRUCTED upgrade logic
  → Проверь, что DDL был обработан до DML (порядок файлов)
```

---

*Документация соответствует состоянию кодовой базы на ветке `feat/stub-sprint-index-migration-dataflow`.*
*Последнее обновление: 2026-04-09*
