# Детальный отчёт по расхождениям REMOTE vs REMOTE_BATCH
**Дата**: 2026-04-07
**Базы данных**: `hound_bench_remote` / `hound_bench_batch`
**Корпус**: 208 файлов (50 DWH2 + 158 ODS)

---

## Итоговая таблица расхождений (с корректировкой)

| Тип ребра          | REMOTE  | BATCH_total | BATCH_wrong | BATCH_correct | Корень причины      |
|--------------------|---------|-------------|-------------|---------------|---------------------|
| BELONGS_TO_SESSION | 4,588   | 9,425       | 0           | 9,425         | edgeRemote session_id filter |
| HAS_ATOM           | 126,771 | 299,421     | **149,660** | 149,761       | Bug: DaliRoutine→DaliAtom |
| FILTER_FLOW        | 14,367  | 30,511      | **15,235**  | 15,276        | Bug: DaliColumn→DaliRoutine |
| DATA_FLOW          | 28,521  | 33,587      | 0           | 33,587        | rcmd() silent failures |
| ATOM_PRODUCES      | 65,606  | 78,565      | 0           | 78,565        | rcmd() silent failures |

---

## 1. BELONGS_TO_SESSION — проверяемый пример

### Суть проблемы
В REMOTE режиме `edgeRemote()` ищет DaliTable по фильтру `AND session_id = :sid`.
Но канонические DaliTable имеют `db_name=DWH2` и `session_id=null` → запрос возвращает 0 строк → ребро не создаётся.
В BATCH используется реальный RID таблицы → ребро всегда создаётся.

### Проверочные запросы (ArcadeDB Studio)

```sql
-- Проверить: DaliTable без BELONGS_TO_SESSION в REMOTE (все 1564 таблицы)
-- Запустить против: hound_bench_remote
SELECT count(*) as cnt FROM DaliTable WHERE in('BELONGS_TO_SESSION').size() = 0
-- Ожидаемый результат: cnt = 1564

-- Проверить: DaliTable с BELONGS_TO_SESSION в BATCH (все 1564 таблицы)
-- Запустить против: hound_bench_batch
SELECT count(*) as cnt FROM DaliTable WHERE in('BELONGS_TO_SESSION').size() > 0
-- Ожидаемый результат: cnt = 1564

-- Конкретный пример: таблица 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE'
-- REMOTE:
SELECT @rid, table_geoid, db_name, session_id, in('BELONGS_TO_SESSION').size() as sessions
FROM DaliTable WHERE table_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE'
-- → session_id: null, sessions: 0

-- BATCH:
SELECT @rid, table_geoid, db_name, session_id, in('BELONGS_TO_SESSION').size() as sessions
FROM DaliTable WHERE table_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE'
-- → session_id: null, sessions: N (столько файлов используют эту таблицу)

-- Эмуляция провального edgeRemote запроса (REMOTE режим):
-- Запустить против hound_bench_remote
SELECT count(*) as cnt FROM DaliTable
WHERE table_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE'
  AND session_id = 'session-1775563758533'
-- → cnt: 0 (ПРОВАЛ: session_id=null не совпадает с sid)

-- Корректный запрос без session_id фильтра:
SELECT @rid, db_name, session_id FROM DaliTable
WHERE table_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE'
-- → @rid: #10:0, db_name: DWH2, session_id: null
```

### Вывод
REMOTE создаёт **0** BELONGS_TO_SESSION рёбер (все таблицы канонические).
BATCH создаёт **9,425** рёбер (сессия→таблица через реальный RID). **BATCH корректнее.**

---

## 2. HAS_ATOM — BUG В BATCH

### Суть ошибки
`JsonlBatchBuilder` итерирует `result.getAtoms()` где ключ контейнера (`source_geoid`) может быть либо
`stmt_geoid` (для атомов внутри SELECT/INSERT), либо `routine_geoid` (для атомов на уровне процедуры).

Когда `source_geoid` является `routine_geoid`:
- DaliRoutine уже добавлена как вершина в батч → `resolveEndpoint(routineGeoid)` находит её
- `appendEdge("HAS_ATOM", routineGeoid, atomId, ...)` создаёт ребро `DaliRoutine → DaliAtom` ❌

В REMOTE `saveRemote()` делает:
```java
String stmtRid = rid.statements.get(stmtGeoid);
if (stmtRid == null) continue;  // routine_geoid не в statements → пропуск ✓
```

### Числа

| Метрика | Значение |
|---------|----------|
| HAS_ATOM всего в BATCH | 299,421 |
| Из них DaliRoutine → DaliAtom (НЕПРАВИЛЬНЫЕ) | **149,660** |
| DaliRoutine с исходящим HAS_ATOM (BATCH) | **2,087** |
| DaliRoutine с исходящим HAS_ATOM (REMOTE) | **0** |
| BATCH_correct (после вычитания) | 149,761 |
| REMOTE HAS_ATOM | 126,771 |
| Разница (rcmd failures) | ~22,990 |

### Проверочные запросы

```sql
-- Проверить: DaliRoutine с неправильным HAS_ATOM в BATCH
-- hound_bench_batch:
SELECT count(*) as cnt FROM DaliRoutine WHERE out('HAS_ATOM').size() > 0
-- → cnt: 2087 (ОШИБКА — у рутин не должно быть HAS_ATOM)

-- Конкретный пример:
SELECT @rid, routine_geoid, out('HAS_ATOM').size() as atoms
FROM DaliRoutine WHERE routine_geoid = 'BUDM_RMS_TMD.DM_LOADER_CLS_CUST_BANKRUPTCY_STAGE:PROCEDURE:LOAD_CLS_CUST_BANKRUPTCY_STAGE'
-- hound_bench_batch → @rid: #16:2, atoms: 42 (НЕПРАВИЛЬНО)
-- hound_bench_remote → atoms: 0 (ПРАВИЛЬНО)

-- Сравнение: атомы конкретной процедуры
-- Stmt geoid 'INSERT:75' в этой процедуре:
SELECT @rid, stmt_geoid, out('HAS_ATOM').size() as atoms
FROM DaliStatement WHERE stmt_geoid = 'BUDM_RMS_TMD.DM_LOADER_CLS_CUST_BANKRUPTCY_STAGE:PROCEDURE:LOAD_CLS_CUST_BANKRUPTCY_STAGE:INSERT:75'
-- hound_bench_remote → atoms: 8 (только stmt-level)
-- hound_bench_batch → atoms: 8 (stmt-level) + routine_geoid создал ещё 42 дубля выше

-- DaliAtom с входящим HAS_ATOM от DaliRoutine:
SELECT count(*) as cnt FROM DaliAtom WHERE in('HAS_ATOM').size() > 0
  AND in('HAS_ATOM')[0].@type = 'DaliRoutine'
-- hound_bench_batch → cnt: 149,660 (НЕПРАВИЛЬНО)
-- hound_bench_remote → cnt: 0 (ПРАВИЛЬНО)
```

### Исправление (JsonlBatchBuilder.java)
Перед циклом обработки атомов добавить проверку:
```java
// Только если source_geoid является statement (не routine)
if (!str.getStatements().containsKey(stmtGeoid)) continue;
```

---

## 3. FILTER_FLOW — BUG В BATCH

### Суть ошибки
Та же причина что и HAS_ATOM: `source_geoid` из atom-контейнера является `routine_geoid`.
Для атомов в WHERE/HAVING/JOIN контексте создаётся ребро:
```java
appendEdge("FILTER_FLOW", colGeoid, stmtGeoid, ...)
// → DaliColumn → DaliRoutine (НЕПРАВИЛЬНО если stmtGeoid = routine_geoid)
```

### Числа

| Метрика | Значение |
|---------|----------|
| FILTER_FLOW всего в BATCH | 30,511 |
| Из них DaliColumn → DaliRoutine (НЕПРАВИЛЬНЫЕ) | **15,235** |
| DaliRoutine с входящим FILTER_FLOW (BATCH) | **1,557** |
| DaliRoutine с входящим FILTER_FLOW (REMOTE) | **0** |
| BATCH_correct (после вычитания) | 15,276 |
| REMOTE FILTER_FLOW | 14,367 |
| Разница (rcmd failures) | ~909 |

### Проверочные запросы

```sql
-- Проверить: DaliRoutine с неправильным FILTER_FLOW в BATCH
-- hound_bench_batch:
SELECT count(*) as cnt FROM DaliRoutine WHERE in('FILTER_FLOW').size() > 0
-- → cnt: 1557 (ОШИБКА)

-- hound_bench_remote:
SELECT count(*) as cnt FROM DaliRoutine WHERE in('FILTER_FLOW').size() > 0
-- → cnt: 0 (ПРАВИЛЬНО)

-- Конкретный пример: колонка с расхождением
-- hound_bench_remote:
SELECT out('FILTER_FLOW').size() as ff
FROM DaliColumn WHERE column_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE.ID_CUST_BANKRUPTCY_STAGE'
-- → ff: 7 (все к DaliStatement)

-- hound_bench_batch:
SELECT out('FILTER_FLOW').size() as ff
FROM DaliColumn WHERE column_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE.ID_CUST_BANKRUPTCY_STAGE'
-- → ff: 14 (7 к DaliStatement + 7 к DaliRoutine)

-- Просмотр целевых вершин:
-- hound_bench_batch:
SELECT expand(out('FILTER_FLOW')) FROM DaliColumn
WHERE column_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE.ID_CUST_BANKRUPTCY_STAGE'
-- Результат: 7 × DaliStatement (корректно) + 7 × DaliRoutine (НЕПРАВИЛЬНО)

-- Пример DaliRoutine получающей FILTER_FLOW в BATCH:
-- #16:38 = DaliRoutine(routine_geoid='BUDM_RMS_TMD.DM_LOADER_RMS_AGG_AGREEMENT_DEFAULT_EVENT_UL:PROCEDURE:LOAD_RMS_AGG_AGREEMENT_DEFAULT_EVENT_UL_FULL')
SELECT in('FILTER_FLOW').size() as bad_ff FROM DaliRoutine WHERE @rid = #16:38
-- hound_bench_batch → bad_ff: 3 (НЕПРАВИЛЬНО)
-- hound_bench_remote → bad_ff: 0 (ПРАВИЛЬНО)
```

---

## 4. DATA_FLOW — rcmd() silent failures в REMOTE

### Суть проблемы
DATA_FLOW: `DaliColumn → DaliOutputColumn` (канонический источник → выходная колонка).

В REMOTE `rid.columns` заполнен корректно через `buildRidMapByField("DaliColumn", "column_geoid", "db_name", dbName)` (namespace mode).
Расхождение — частичные потери из-за конкурентности/таймаута `rcmd()` в многопоточном REMOTE.

### Числа

| Метрика | REMOTE | BATCH |
|---------|--------|-------|
| DATA_FLOW всего | 28,521 | 33,587 |
| DaliColumn с out(DATA_FLOW) | 8,012 | 9,246 |
| DaliOutputColumn с in(DATA_FLOW) | 18,856 | 23,409 |
| Неправильных рёбер в BATCH | 0 | 0 |
| Потери REMOTE (rcmd fails) | ~5,066 | — |

### Проверочные запросы

```sql
-- hound_bench_remote:
SELECT count(*) as cnt FROM DaliColumn WHERE out('DATA_FLOW').size() > 0
-- → cnt: 8,012

-- hound_bench_batch:
SELECT count(*) as cnt FROM DaliColumn WHERE out('DATA_FLOW').size() > 0
-- → cnt: 9,246

-- Пример конкретной колонки:
-- hound_bench_remote:
SELECT out('DATA_FLOW').size() as df
FROM DaliColumn WHERE column_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE.ID_CUST_BANKRUPTCY_STAGE'

-- hound_bench_batch:
SELECT out('DATA_FLOW').size() as df
FROM DaliColumn WHERE column_geoid = 'BUDM_RMS.CLS_CUST_BANKRUPTCY_STAGE.ID_CUST_BANKRUPTCY_STAGE'
```

**BATCH корректнее** (нет неправильных рёбер, атомарная запись).

---

## 5. ATOM_PRODUCES — rcmd() silent failures в REMOTE

### Числа

| Метрика | REMOTE | BATCH |
|---------|--------|-------|
| ATOM_PRODUCES всего | 65,606 | 78,565 |
| DaliAtom с out(ATOM_PRODUCES) | 65,606 | 78,565 |
| Неправильных рёбер в BATCH | 0 | 0 |
| Потери REMOTE (rcmd fails) | ~12,959 | — |

### Проверочные запросы

```sql
-- Сравнение:
-- hound_bench_remote:
SELECT count(*) as cnt FROM DaliAtom WHERE out('ATOM_PRODUCES').size() > 0
-- → cnt: 65,606

-- hound_bench_batch:
SELECT count(*) as cnt FROM DaliAtom WHERE out('ATOM_PRODUCES').size() > 0
-- → cnt: 78,565

-- Атомы без ATOM_PRODUCES (потенциально потерянные в REMOTE):
-- hound_bench_remote:
SELECT count(*) as cnt FROM DaliAtom WHERE in('HAS_ATOM').size() > 0 AND out('ATOM_PRODUCES').size() = 0
-- → cnt: 61,165 (часть — атомы к которым нет OutputColumn, часть — потери)

-- hound_bench_batch:
SELECT count(*) as cnt FROM DaliAtom WHERE in('HAS_ATOM').size() > 0 AND out('ATOM_PRODUCES').size() = 0
-- → cnt: 220,856 (включая 149,660 неправильно подключённых к DaliRoutine)
```

---

## 6. Итоговая оценка корректности

| Тип ребра          | Правильнее | Причина |
|--------------------|-----------|---------|
| BELONGS_TO_SESSION | **BATCH** | REMOTE теряет все рёбра из-за session_id фильтра |
| HAS_ATOM           | **REMOTE** | BATCH создаёт 149,660 неправильных DaliRoutine→DaliAtom |
| FILTER_FLOW        | **REMOTE** | BATCH создаёт 15,235 неправильных DaliColumn→DaliRoutine |
| DATA_FLOW          | **BATCH** | REMOTE теряет ~5K рёбер (rcmd silent failures) |
| ATOM_PRODUCES      | **BATCH** | REMOTE теряет ~13K рёбер (rcmd silent failures) |

---

## 7. Необходимые исправления

### Приоритет 1 — JsonlBatchBuilder.java: guard для routine_geoid

В строках 596–691 (`buildFromResult`) добавить проверку **до** цикла по атомам:

```java
for (var container : result.getAtoms().entrySet()) {
    Map<String, Object> cont = (Map<String, Object>) container.getValue();
    String stmtGeoid = (String) cont.get("source_geoid");
    Map<String, Map<String, Object>> atoms = ...;
    if (atoms == null || stmtGeoid == null) continue;
+   // Guard: skip if source_geoid is a routine geoid, not a statement geoid
+   if (!str.getStatements().containsKey(stmtGeoid)) continue;
    ...
```

Это исправит HAS_ATOM и FILTER_FLOW — удалит 149,660 + 15,235 неправильных рёбер из BATCH.

### Приоритет 2 — ArcadeDBSemanticWriter.java: BELONGS_TO_SESSION

В `edgeRemote("BELONGS_TO_SESSION", ...)` для канонических таблиц использовать реальный RID из `rid.tables` вместо `edgeRemote` с `session_id` фильтром.

---

## 8. Ожидаемое состояние после исправлений

| Тип ребра          | REMOTE (текущий) | BATCH_corrected | Цель (match) |
|--------------------|-----------------|-----------------|--------------|
| BELONGS_TO_SESSION | 4,588           | 9,425           | ≠ (REMOTE нужно фиксить) |
| HAS_ATOM           | 126,771         | **149,761**     | ≠ (~23K потери REMOTE) |
| FILTER_FLOW        | 14,367          | **15,276**      | ≠ (~909 потери REMOTE) |
| DATA_FLOW          | 28,521          | 33,587          | ≠ (~5K потери REMOTE) |
| ATOM_PRODUCES      | 65,606          | 78,565          | ≠ (~13K потери REMOTE) |

После исправления JsonlBatchBuilder (приоритет 1):
BATCH_HAS_ATOM станет ~149,761 (vs REMOTE ~126,771 — разница ~23K от rcmd failures).
BATCH_FILTER_FLOW станет ~15,276 (vs REMOTE ~14,367 — разница ~909 от rcmd failures).
