# Приложение: Глоссарий и индекс файлов

## Глоссарий

| Термин | Определение |
|--------|------------|
| **AST** | Abstract Syntax Tree — абстрактное синтаксическое дерево. Структурированное представление SQL-текста, очищенное от синтаксического "шума" (скобок, ключевых слов). |
| **Атом** | Минимальная семантически значимая единица выражения: ссылка на колонку (`t.balance`), вызов функции (`SUM(amount)`), литерал (`42`). |
| **Геоид (Geoid)** | Иерархический строковый идентификатор объекта. Кодирует положение объекта в иерархии: `PKG:PROCEDURE:NAME:SELECT:48`. |
| **Геоид схемный** | Геоид объекта схемы: `SCHEMA.TABLE`, `SCHEMA.TABLE.COLUMN`. Разделитель `.`. |
| **Геоид execution** | Геоид statement'а или routine'а: `PKG:PROC:SELECT:48`. Разделитель `:`. |
| **CTE** | Common Table Expression — именованный подзапрос в `WITH` clause. |
| **DML** | Data Manipulation Language — INSERT, UPDATE, DELETE, MERGE. |
| **DDL** | Data Definition Language — CREATE TABLE/VIEW, ALTER TABLE и т.д. |
| **Lineage** | Граф зависимостей между колонками: откуда данные пришли и куда ушли. |
| **LineageEdge** | Ребро lineage: `{sourceGeoid → targetGeoid, relationType, statementGeoid}`. |
| **Listener** | Паттерн ANTLR: объект, получающий события (enterRule, exitRule, visitTerminal) при обходе ParseTree. |
| **ParseTree** | Грамматически-зависимое дерево, генерируемое ANTLR. Каждый диалект имеет свой ParseTree. |
| **Pending column** | Атом, который ещё не разрешён (table не найдена). Разрешается при `exitStatement`. |
| **Quality score** | Доля разрешённых атомов в statement'е: `(resolved + constants + functions) / total`. |
| **Routine** | Хранимая процедура или функция (Procedure / Function / Trigger). |
| **Scope** | Контекст разбора: текущий statement, его тип, alias-таблица, активный clause. |
| **ScopeContext** | Один фрейм стека: хранит geoid, тип, флаги clause, локальные алиасы. |
| **ScopeManager** | Стек `ScopeContext`'ов. Хранит историю вложенности. |
| **Snippet** | Фрагмент исходного SQL-текста. В AST-узле — до 500 символов. В `StatementInfo` → `DaliSnippet`: EMBEDDED/REMOTE — полный текст (`SNIPPET_MAX = Integer.MAX_VALUE`); REMOTE_BATCH (JsonlBatchBuilder) — до 4000 символов (`SNIPPET_MAX = 4000`). |
| **Source table** | Таблица, из которой данные читаются (FROM, JOIN, USING). |
| **Target table** | Таблица, в которую данные пишутся (INSERT INTO, UPDATE, MERGE INTO). |
| **TERMINAL** | Листовой узел AST: один токен (identifier, literal, operator). |
| **RULE** | Нетерминальный узел AST: синтаксическая конструкция (select_statement, from_clause). |
| **UniversalAstNode** | Единый формат AST-узла для всех диалектов. |
| **Wire** | Паттерн инициализации: компоненты получают ссылки на зависимости через `wire()`. |

---

## Типы RelationType в LineageEdge

| Тип | Генерируется | Когда используется |
|-----|:-----------:|--------------------|
| `DIRECT` | ✓ | Прямая передача данных: `source.col → target.col` через DML |
| `JOIN` | ✓ | Связь через JOIN ON-условие (Column → Column) |
| `AGGREGATE` | ✓ DATA_FLOW only | Агрегация в DATA_FLOW рёбрах (Column→OutputColumn) |
| `TRANSFORM` | ✓ DataFlowProcessor only | Трансформация ф-цией в DATA_FLOW (только Arrow/Remote путь) |

> `AGGREGATE` и `TRANSFORM` появляются только как `flow_type` рёбер **DATA_FLOW**
> (Column→OutputColumn/AffectedColumn), а не в LineageEdge (Column→Column).
> В LineageEdge генерируются только `DIRECT` и `JOIN`.

---

## Типы Statement

| Тип | SQL-конструкция |
|-----|----------------|
| `SELECT` | SELECT statement |
| `INSERT` | INSERT INTO ... |
| `UPDATE` | UPDATE ... SET |
| `DELETE` | DELETE FROM ... |
| `MERGE` | MERGE INTO ... USING |
| `CTE` | WITH name AS (...) |
| `SUBQUERY` | Подзапрос в FROM / IN / EXISTS |
| `MSUBQUERY` | MERGE USING subquery |
| `USUBQUERY` | UNION подзапрос |
| `CURSOR` | PL/SQL курсор |
| `VIEW` | CREATE VIEW |
| `CREATE_TABLE` | CREATE TABLE |
| `ALTER_TABLE` | ALTER TABLE ADD/MODIFY/DROP |
| `CREATE_PROCEDURE` | CREATE PROCEDURE |
| `CREATE_FUNCTION` | CREATE FUNCTION |

---

## Типы TableType

| Тип | Значение |
|-----|---------|
| `TABLE` | Обычная таблица |
| `VIEW` | Представление (CREATE VIEW) |
| `CTE` | Common Table Expression |
| `TEMP` | Временная таблица |
| `TABLE_FUNCTION` | Табличная функция |

---

## Значения DaliAtom.status

| Значение | Устанавливается | Семантика |
|---------|----------------|-----------|
| `null` | начальное | атом зарегистрирован, ещё не обработан |
| `"constant"` | AtomProcessor | литерал: `42`, `'text'`, `NULL`, `TRUE` |
| `"function_call"` | AtomProcessor | вызов функции: `SUM(...)`, `NVL(...)`, `TRUNC(...)` |
| `"Обработано"` | resolvePendingColumns | column reference успешно разрешён к таблице |
| `"unresolved"` | resolvePendingColumns | все 8 стратегий резолюции провалились |

Подсчёт quality score: `("Обработано" + "constant" + "function_call") / total`.
Только `"Обработано"` создаёт DATA_FLOW и lineage рёбра.

## Значения DATA_FLOW.flow_type

| flow_type | Путь генерации | Условие |
|-----------|:--------------:|---------|
| `DIRECT` | Embedded + DataFlowProcessor | SELECT без агрегации, или DELETE |
| `AGGREGATE` | Embedded + DataFlowProcessor | `hasAggregation AND parent_context="GROUP BY"` |
| `INSERT` | Embedded only | DML statement type = INSERT |
| `UPDATE` | Embedded only | DML statement type = UPDATE |
| `MERGE` | Embedded only | DML statement type = MERGE |
| `TRANSFORM` | DataFlowProcessor only | `is_function_call = true` (только Arrow/Remote путь) |

---

## Индекс ключевых файлов

### Парсинг

| Путь | Назначение |
|------|-----------|
| `parser/core/UniversalParser.java` | Точка входа парсинга; выбор диалекта, создание ANTLR pipeline, fallback для ошибок |
| `parser/ParserFactory.java` | Определение диалекта по имени файла; делегация в ParserRegistry |
| `parser/registry/ParserRegistry.java` | Реестр диалект→{lexerClass, parserClass, startRule} |
| `parser/UniversalTreeListener.java` | ANTLR ParseTreeListener; строит UniversalAstNode дерево |
| `parser/AstListener.java` | Интерфейс для внешних обработчиков AST событий |
| `graph/UniversalAstNode.java` | Модель AST-узла; конвертация в GraphNode |

### Семантический анализ — ядро

| Путь | Назначение |
|------|-----------|
| `semantic/engine/ScopeManager.java` | Стек контекстов; cursor registry для PL/SQL |
| `semantic/engine/ScopeContext.java` | Один фрейм стека; флаги clause; geoid formula |
| `semantic/engine/NameResolver.java` | 8 стратегий разрешения имён; кеш; диагностика |
| `semantic/engine/ResolvedRef.java` | Результат резолюции: name, type, geoid, strategy |
| `semantic/engine/StructureAndLineageBuilder.java` | Центральный реестр; сериализация; suspicious schema detection |
| `semantic/engine/JoinProcessor.java` | Регистрация JOIN'ов; inferJoinType |

### Семантический анализ — диалекты

| Путь | Назначение |
|------|-----------|
| `semantic/dialect/DialectRegistry.java` | Реестр dialect-адаптеров |
| `semantic/dialect/DialectAdapter.java` | Интерфейс адаптера |
| `semantic/dialect/plsql/PlSqlDialectAdapter.java` | PL/SQL специфика |

### Модели данных

| Путь | Назначение |
|------|-----------|
| `semantic/model/LineageEdge.java` | Ребро lineage (record) |
| `semantic/model/TableInfo.java` | Таблица: geoid, name, schema, type, aliases |
| `semantic/model/ColumnInfo.java` | Колонка: geoid, table, name, ordinal |
| `semantic/model/StatementInfo.java` | Statement: source/target tables, atoms, joins |
| `semantic/model/RoutineInfo.java` | Routine: parameters, variables, return type |
| `semantic/model/JoinInfo.java` | JOIN: тип, source, target, conditions |
| `semantic/model/Structure.java` | Итоговая структура файла |
| `semantic/model/SubqueryUsage.java` | Использование подзапроса: aliases, stmt |

### Post-processing и вывод

| Путь | Назначение |
|------|-----------|
| `storage/DataFlowProcessor.java` | Post-walk: DATA_FLOW записи из resolved атомов |
| `semantic/arrow/SemanticResultSerializer.java` | Arrow IPC сериализация результата |

### Диагностика

| Путь | Назначение |
|------|-----------|
| `diagnostic/ResolutionLogger.java` | Запись resolution попыток в DaliResolutionLog |
| `diagnostic/DiagnosticRunner.java` | Isolated diagnostic run для одного файла |

### Тесты (ключевые)

| Путь | Назначение |
|------|-----------|
| `test/.../HoundRegressionTest.java` | Основной регрессионный набор |
| `test/.../MergeSetSourceAliasResolvedTest.java` | MERGE USING alias (BUG-S2) |
| `test/.../DataFlowDbTest.java` | DATA_FLOW с записью в БД |
| `test/.../ContainsRoutineHierarchyTest.java` | Вложенные routines |
| `test/.../ResolutionLogDiagnosticTest.java` | Диагностический логгер |
