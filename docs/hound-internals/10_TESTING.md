# Тестирование

## Структура тестов

Тесты разделены на два уровня: **семантические** (проверяют корректность анализа SQL)
и **storage** (проверяют запись и чтение из БД).

```
src/test/java/com/hound/
├── semantic/           — тесты семантического анализа (основные)
└── storage/            — тесты хранилища (интеграционные)
```

---

## Семантические тесты

### Регрессионные и coverage тесты

| Тест | Назначение |
|------|-----------|
| `HoundRegressionTest` | Основной регрессионный набор. Проверяет полный pipeline на реальных SQL-файлах. Запускается при каждом изменении логики разбора. |
| `LowGapsTest` | Тестирует coverage: убеждается, что для конкретных SQL-паттернов все атомы резолюцируются (quality ≥ порог). |
| `GeoidTest` | Проверяет корректность формул geoid для разных типов statement'ов и routine'ов. |
| `GeoidParentChainTest` | Проверяет цепочки parent geoid в иерархии вложенных statement'ов. |

### DML и Data Flow тесты

| Тест | Что проверяется |
|------|----------------|
| `DataFlowInMemoryTest` | Проверяет, что affected_columns и output_columns корректно определяются для INSERT, UPDATE, SELECT. |
| `DataFlowDbTest` | То же самое, но с записью в ArcadeDB и проверкой DATA_FLOW edges. |
| `AffectedColumnDbTest` | Проверяет target-колонки DML-операторов: правильно ли помечены колонки как affected. |

### MERGE-специфичные тесты

| Тест | Что проверяется |
|------|----------------|
| `MergeSetSourceAliasResolvedTest` | Regression для баг S2.BUG-1: alias USING-подзапроса должен быть виден в ON и UPDATE SET. Тестирует `registerAliasOnParent`. |
| `MergeUsingCollectionTest` | MERGE USING collection / inline SELECT. INSERT VALUES binding. |

### JOIN и структурные тесты

| Тест | Что проверяется |
|------|----------------|
| `JoinRegressionTest` | INNER / LEFT / RIGHT / FULL JOIN. ON-условия. Множественные JOIN'ы. |
| `CreateViewTest` | CREATE OR REPLACE VIEW — DDL lineage, VIEW тип в реестре таблиц. |
| `SchemaObjectAndInlineSubqueryTest` | Схемные объекты (SCHEMA.TABLE), inline subqueries. |
| `ContainsRoutineHierarchyTest` | Вложенные procedures/functions. Package → Procedure → Function. |

### Атомы и токены

| Тест | Что проверяется |
|------|----------------|
| `AtomClassificationTest` | Классификация атомов: column_ref / function_call / literal / operator. |
| `AtomQualityProbeTest` | Quality score statement'ов при разных сценариях. |
| `AtomQualityDbTest` | Quality score с записью в БД. |
| `PlSqlTokenMapperTest` | Маппинг PL/SQL-специфичных токенов (ROWTYPE, %TYPE, курсоры). |
| `CanonicalTokenTypeTest` | Canonical token type normalization. |

### Диагностика

| Тест | Что проверяется |
|------|----------------|
| `ResolutionLogDiagnosticTest` | ResolutionLogger в --diag режиме: записи попадают в DaliResolutionLog. |
| `SchemaRegistrationLogTest` | Suspicious schema names → SchemaRegistrationLog. |

---

## Storage тесты

| Тест | Что проверяется |
|------|----------------|
| `SchemaDbIsolationTest` | Изоляция схем между сессиями. |
| `SchemaInitializerTest` | Инициализация схемы БД (вершины/рёбра типов в ArcadeDB). |
| `EmbeddedVsBatchIT` | Сравнение embedded ArcadeDB vs batch JSON: одинаковые результаты. |
| `JsonlBatchBuilderTest` | Формат JSONL для batch-загрузки. |

---

## Паттерн теста

Типичный семантический тест:

```
1. Подготовить SQL-строку (inline или загрузить из resources)
2. Запустить через SemanticEngine:
     SemanticResult result = engine.analyze(sql, Dialect.PLSQL)
3. Проверить структуру:
     assertThat(result.getStatements()).hasSize(1)
     assertThat(result.getTables()).containsKey("SCHEMA.ACCOUNTS")
4. Проверить lineage:
     assertThat(result.getLineageEdges())
       .contains(edge("STAGING.BALANCE", "ACCOUNTS.BALANCE"))
5. Проверить атомы (опционально):
     assertThat(result.getAtoms("STMT_001"))
       .anyMatch(a -> "balance".equals(a.get("dml_target_ref")))
```

---

## Как запускать тесты

```
Все тесты:
  mvn test

Только семантические:
  mvn test -pl . -Dtest="com.hound.semantic.*"

Конкретный класс:
  mvn test -Dtest=MergeSetSourceAliasResolvedTest

С диагностикой (verbose):
  mvn test -Dtest=ResolutionLogDiagnosticTest -Dhound.diag=true
```

---

## Coverage целевые паттерны

Тесты `LowGapsTest` проверяют конкретные SQL-конструкции, которые были проблемными.
Каждый gap из GAP-анализа (G1–G15) должен быть покрыт хотя бы одним тестом.

```
G1      — MERGE USING alias visibility      ← MergeSetSourceAliasResolvedTest
G3      — MERGE UPDATE SET binding         ← MergeUsingCollectionTest
G5      — CTE в иерархии statements        ← LowGapsTest (CTE сценарии)
G7      — Курсорные record variables       ← PlSqlTokenMapperTest
G10     — Вложенные routines               ← ContainsRoutineHierarchyTest
G12     — Скалярные подзапросы в SELECT    ← SchemaObjectAndInlineSubqueryTest
G15     — CREATE/ALTER TABLE DDL           ← DataFlowDbTest (DDL variant)

⚠ Открытые gaps (не покрыты тестами):

G-UNION — UNION ALL output column mapping через USUBQUERY   ← НЕТ ТЕСТА
           Нужен тест: две ветки, проверить что output_column_sequence
           одинаков в каждой ветке, DATA_FLOW рёбра ведут к разным USQ statement'ам.
           Описание алгоритма: 08_CASE_STUDIES.md Case 6.

G-2B    — Strategy 2b silent fail в multi-schema окружении  ← НЕТ ТЕСТА
           Нужен тест: DWH.ACCOUNTS + STAGE.ACCOUNTS оба в реестре,
           FROM accounts без схемы → проверить что atom unresolved.
```
