# Диагностика и логирование

## Принцип: prod vs --diag

HOUND имеет два режима работы:

```
┌───────────────────────────────────────────────────────────────┐
│  prod-режим (по умолчанию)                                    │
│                                                               │
│  • ResolutionLogger.disabled() — все log() вызовы — no-op    │
│  • Никаких записей в DaliResolutionLog / DaliSchemaLog        │
│  • Нет overhead на диагностику                                │
└───────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────┐
│  --diag режим                                                 │
│                                                               │
│  • ResolutionLogger(enabled=true, sessionId, db)             │
│  • Каждый resolution attempt записывается в БД               │
│  • Suspicious schema names → SchemaRegistrationLog           │
│  • Позволяет пост-анализ качества разбора                     │
└───────────────────────────────────────────────────────────────┘
```

---

## ResolutionLogger: что записывается

```
Каждый вызов NameResolver.resolve() → одна запись в DaliResolutionLog:

┌─────────────────────────────────────────────────────────────┐
│  DaliResolutionLog                                          │
├─────────────────────────────────────────────────────────────┤
│  session_id        — идентификатор сессии анализа           │
│  ts                — timestamp (epoch ms)                   │
│                                                             │
│  input_kind        — что разрешается:                       │
│                      ATOM / COLUMN_REF / TABLE_REF /        │
│                      PENDING_COLUMN                         │
│  raw_input         — исходное имя: "t.balance", "USERS"     │
│  statement_geoid   — из какого statement                     │
│                                                             │
│  strategy          — какая стратегия сработала:             │
│                      "1_exact_geoid", "2_alias_scope",      │
│                      "3_cte", "4_subquery_alias", ...       │
│                      "all_failed" если не разрешено         │
│                                                             │
│  result_kind       — RESOLVED / UNRESOLVED / SKIPPED / ERROR│
│  resolved_geoid    — geoid результата (если RESOLVED)       │
│  resolved_type     — TABLE / SUBQUERY / CTE (если RESOLVED) │
│                                                             │
│  has_special_chars — содержит () [] @ # и т.д.              │
│  is_qualified      — содержит точку (schema.table)          │
│  is_function_call  — выглядит как "FUNC("                   │
│                                                             │
│  note              — дополнительный контекст                │
└─────────────────────────────────────────────────────────────┘
```

---

## Диаграмма потока диагностики

```
  NameResolver.resolve("s.new_balance", "any", "MERGE:5")
                │
                ├── check cache → MISS
                │
                ├── resolveInternal(...)
                │     Стратегия 2: alias "S" → "SQ:S:2"  RESOLVED
                │
                ├── cache.put(key, result)
                │
                └── if (resolutionLogger.isEnabled()):
                      resolutionLogger.log(
                        inputKind = ATOM,
                        rawInput  = "s.new_balance",
                        stmtGeoid = "MERGE:5",
                        strategy  = "2_alias_scope",
                        result    = RESOLVED,
                        resolved  = "SQ:S:2",
                        type      = "SUBQUERY",
                        note      = null
                      )
                      → INSERT INTO DaliResolutionLog ...
```

---

## Анализ качества по данным DiagnosticLog

После прогона с `--diag` можно запросить:

```sql
-- Какие имена не разрешаются чаще всего?
SELECT raw_input, COUNT(*) unresolved_count
FROM DaliResolutionLog
WHERE result_kind = 'UNRESOLVED'
GROUP BY raw_input
ORDER BY unresolved_count DESC

-- Какие стратегии наиболее эффективны?
SELECT strategy, COUNT(*) hits, 
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 1) AS pct
FROM DaliResolutionLog
WHERE result_kind = 'RESOLVED'
GROUP BY strategy

-- Сколько function_call ошибочно попали в резолюцию таблиц?
SELECT raw_input, note
FROM DaliResolutionLog
WHERE is_function_call = true AND input_kind = 'TABLE_REF'
```

---

## Schema Registration Log

```
Builder.ensureSchema(name) проверяет suspicious names:

Триггеры:
  Имя содержит кавычки   → reason: quoted_identifier_not_stripped
  Имя содержит скобки    → reason: parenthesis_in_schema_name
  Имя содержит точку     → reason: dot_in_schema_name
  Имя содержит $         → reason: dollar_sign_in_schema_name
  Имя содержит :         → reason: colon_in_schema_name
  Имя содержит пробел    → reason: space_in_schema_name

Запись:
  { schema_name, reason, backtrace }

  backtrace содержит только com.hound.* фреймы вызова:
    engine.SomeListener.onTableRef:432
    engine.StructureAndLineageBuilder.ensureSchema:276
    ...

Назначение:
  Помогает понять, из какого места кода пришло подозрительное имя.
  Часто указывает на незачищенный quoted identifier или
  ошибочное распознавание SQL-выражения как имени объекта.
```

---

## Suspicious table name detection (STAB-2)

```
Builder.ensureTable(name) также проверяет:

  Имя содержит () [] {} ! % ^ & *  → is_function_call или special_chars
  Имя начинается с "FUNC("          → скорее всего функция, не таблица

При обнаружении (только в --diag):
  resolutionLogger.log(
    inputKind = TABLE_REF,
    ...
    resultKind = SKIPPED (для функций) или UNRESOLVED (для special_chars),
    note = "function_call_as_table" или "special_chars_in_table_name"
  )

Таблица всё равно регистрируется — анализ не останавливается.
Запись в лог позволяет найти места, где grammar неправильно распознала
функцию как ссылку на таблицу.
```

---

## DiagnosticRunner: запуск изолированной диагностики

```
DiagnosticRunner позволяет прогнать анализ одного файла
с подробным логированием всех resolution попыток.

Workflow:
  1. Создать embedded ArcadeDB базу (временную или постоянную)
  2. Инициализировать ResolutionLogger(enabled=true, sessionId, db)
  3. Прогнать анализ файла
  4. Запросить DaliResolutionLog для анализа

Используется при:
  • Отладке неразрешённых ссылок в конкретном файле
  • Профилировании эффективности стратегий
  • Проверке coverage после изменений в grammar
```
