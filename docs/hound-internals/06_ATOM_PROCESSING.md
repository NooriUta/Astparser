# Обработка атомов (Atom Processing)

## Что такое атом

**Атом** — минимальная семантически значимая единица SQL-выражения: ссылка на колонку,
вызов функции, литерал. Именно через атомы строится lineage: атом отслеживает,
откуда взяты данные и куда они помещаются.

Атомы живут внутри `StatementInfo` — каждый statement хранит свой словарь атомов.

---

## Жизненный цикл атома

```
  ┌──────────────────────────────────────────────────────────────┐
  │  1. РЕГИСТРАЦИЯ                                              │
  │                                                              │
  │  При обходе AST встречается выражение в значимом clause:     │
  │  SELECT list, WHERE, GROUP BY, HAVING, SET, VALUES, ON...    │
  │                                                              │
  │  registerAtom(text, line, col, context, tokenDetails)        │
  │  → ключ: TEXT.toUpperCase() + "~" + line + ":" + col         │
  │  → сохраняется в atoms[statementGeoid][atomKey]              │
  └──────────────────────────────────┬───────────────────────────┘
                                     │
                                     ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  2. КЛАССИФИКАЦИЯ                                            │
  │                                                              │
  │  На основе структуры токенов определяется тип атома:         │
  │  column_ref, table_ref, function_call, literal, operator...  │
  │                                                              │
  │  Влияет на дальнейшую обработку и quality metrics            │
  └──────────────────────────────────┬───────────────────────────┘
                                     │
                                     ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  3. DML BINDING (только для INSERT/UPDATE/MERGE)             │
  │                                                              │
  │  Атомы на "правой стороне" привязываются к target-колонке    │
  │  через position-based range matching                         │
  └──────────────────────────────────┬───────────────────────────┘
                                     │
                                     ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  4. РЕЗОЛЮЦИЯ (при exit statement)                           │
  │                                                              │
  │  resolvePendingColumns():                                    │
  │  для каждого нерезолюцированного атома:                      │
  │  → NameResolver.resolve(name) → tableGeoid                   │
  │  → ensureColumn(tableGeoid, columnName)                      │
  │  → atom.status = "Обработано"                                │
  └──────────────────────────────────────────────────────────────┘
```

---

## Структура одного атома

```
Атом хранится как Map<String, Object>:

  "text"              — исходный текст: "u.balance", "SUM(amount)"
  "line"              — строка в исходном SQL
  "col" (column)      — позиция символа
  "context"           — clause: SELECT, FROM, WHERE, JOIN, SET_EXPR...
  "parent_context"    — уточнение: GROUP BY, HAVING, ON...
  "statement_geoid"   — к какому statement принадлежит

  После классификации:
  "is_constant"       — true для литералов (42, 'hello')
  "is_function_call"  — true для SUM(...), NVL(...) и т.д.
  "atom_type"         — column_ref / table_ref / function_call / literal

  После резолюции:
  "table_geoid"       — geoid таблицы, к которой принадлежит колонка
  "column_name"       — имя колонки (если resolved)
  "status"            — значение зависит от результата резолюции:
                          null           — не обработан (начальное)
                          "constant"     — литерал (is_constant=true)
                          "function_call"— вызов функции (is_function_call=true)
                          "Обработано"   — разрешён как column reference
                          "unresolved"   — попытка резолюции не удалась
  "output_column_sequence" — порядковый номер в SELECT list (устанавливается
                          через bindAtomsToOutputColumn при обходе select list;
                          именно это поле активирует создание DATA_FLOW к
                          DaliOutputColumn)

  После DML binding:
  "dml_target_ref"    — имя target-колонки (для UPDATE SET / MERGE)
  "merge_clause"      — "UPDATE" или "INSERT" (для MERGE)
```

---

## Position-based DML Binding

### Проблема

В MERGE UPDATE SET:
```sql
WHEN MATCHED THEN UPDATE SET t.balance = s.new_balance + s.fee
```

Правая часть `s.new_balance + s.fee` содержит несколько атомов.
Нужно понять, что **оба** атома питают target-колонку `balance`.

Простой обход дерева не даёт этой связи — нужно знать, что атом
находится **в диапазоне позиций** правой части SET-выражения.

### Алгоритм position-based binding

```
Вход:
  statementGeoid  — какой statement смотрим
  targetColumnRef — имя target-колонки (из LHS выражения)
  startLine:startCol — начало RHS выражения
  endLine:endCol     — конец RHS выражения

Алгоритм:
  1. Взять все атомы данного statement'а
  2. Для каждого атома получить его (line, col)
  3. Проверить: находится ли (line, col) в диапазоне [start..end]?
  
     isWithinRange(atomLine, atomCol, start, end):
       если atomLine < startLine → нет
       если atomLine > endLine   → нет
       если atomLine == startLine и atomCol < startCol → нет
       если atomLine == endLine  и atomCol > endCol   → нет
       иначе → да
  
  4. Если атом в диапазоне → присвоить:
       atom["dml_target_ref"] = targetColumnRef
       atom["merge_clause"]   = "UPDATE"  (или "INSERT")

Результат: все атомы RHS "знают", в какую target-колонку идут их данные
```

### Визуализация для UPDATE SET

```sql
UPDATE accounts SET balance = old_balance * 1.05
                             ^                  ^
                          startPos           endPos
                              └──── диапазон ────┘

Атомы в этом диапазоне:
  "old_balance" (line 1, col 30)  → dml_target_ref = "balance"
  "1.05"        (line 1, col 44)  → dml_target_ref = "balance"
```

---

## Классификация токенов

```
Классификация происходит при регистрации атома на основе tokenDetails
(последовательность токенов, составляющих атом):

┌──────────────────────────────────────────────────────────────────┐
│  Паттерн токенов                │ Тип атома                      │
├──────────────────────────────────┼───────────────────────────────┤
│  IDENTIFIER                     │ column_ref  (в SELECT/WHERE)  │
│                                  │ table_ref   (в FROM)          │
│  IDENTIFIER . IDENTIFIER        │ column_ref  (qualified)       │
│  IDENTIFIER ( ... )             │ function_call                 │
│  NUMBER / STRING_LITERAL        │ literal / is_constant=true    │
│  IDENTIFIER [ INDEX ]           │ array_access                  │
│  в контексте GROUP BY           │ + parent_context="GROUP BY"   │
│  в контексте HAVING             │ + parent_context="HAVING"     │
└──────────────────────────────────────────────────────────────────┘
```

---

## output_column_sequence: механизм привязки к SELECT list

Поле `output_column_sequence` — связующее звено между атомом-источником
и конкретной позицией вывода SELECT. Устанавливается при обходе SELECT list:

```
onSelectItemExit(selectItem, sequenceNumber):
  Для каждого атома внутри selectItem:
    atom["output_column_sequence"] = sequenceNumber

  Порядковый номер начинается с 1, увеличивается для каждой колонки SELECT list.
  Для выражений типа SUM(a) + SUM(b) оба атома получают одинаковый sequence
  (оба питают одну output-позицию).
```

Без `output_column_sequence` атом не участвует в создании DATA_FLOW ребра —
DataFlowProcessor пропускает атомы без этого поля.

---

## Атом-агрегация: статус качества

По завершении анализа каждый statement получает **quality score** — доля
успешно обработанных атомов:

```
quality = (resolved + constants + function_calls) / total_atoms

Где:
  resolved       — атомы со статусом "Обработано"
  constants      — атомы со статусом "constant"
  function_calls — атомы со статусом "function_call"

Интерпретация:
  1.0  — все атомы распознаны (идеально)
  0.8+ — хорошее покрытие
  < 0.5 — много неразрешённых ссылок (проблема с alias/scope)
```

---

## DATA_FLOW: пост-обработка атомов

После завершения всего анализа запускается `DataFlowProcessor`:

```
Для каждого statement:
  Для каждого атома с status="Обработано":
    Если есть output_column_sequence (атом привязан к output-колонке):
      
      Создать DATA_FLOW запись:
        src_col_geoid    = tableGeoid + "." + columnName
        output_col_key   = statementGeoid + ":" + outputSequence
        flow_type        = определить тип потока (две ветки):

          SELECT-путь (resolveFlowType):
          ├── "AGGREGATE" если statement.hasAggregation И parent_context="GROUP BY"
          └── "DIRECT"    иначе

          DML-путь (resolveDmlFlowType):
          ├── "INSERT"  если statement.type = INSERT
          ├── "UPDATE"  если statement.type = UPDATE
          ├── "MERGE"   если statement.type = MERGE
          └── "DIRECT"  иначе

DATA_FLOW запись описывает: эта колонка источника → эта позиция в выводе statement'а
```

---

## Пример: полный путь атома `s.new_balance`

```sql
MERGE INTO accounts t
USING staging s ON t.id = s.id
WHEN MATCHED THEN UPDATE SET t.balance = s.new_balance
```

```
1. РЕГИСТРАЦИЯ
   Text: "s"              line=3, col=40  context=MERGE_UPDATE
   Text: "new_balance"    line=3, col=42  context=MERGE_UPDATE

2. КЛАССИФИКАЦИЯ
   "s"           → table_ref (identifier в DML context)
   "new_balance" → column_ref (identifier после qualifier)

3. DML BINDING
   Слушатель определяет: LHS = "t.balance", RHS диапазон = [col 40..55]
   bindAtomsToMergeUpdateTarget("MERGE_001", "balance", 3, 40, 3, 55)
   → "new_balance" попадает в диапазон
   → atom["dml_target_ref"] = "balance"
   → atom["merge_clause"] = "UPDATE"

4. РЕЗОЛЮЦИЯ (при exit MERGE)
   "s" → resolve("S") → Стратегия 2 → STAGING (alias "s")
   "new_balance" → ensureColumn("STAGING", "NEW_BALANCE")
   → atom["table_geoid"] = "STAGING", atom["column_name"] = "NEW_BALANCE"
   → atom.status = "Обработано"

5. LINEAGE
   addLineageEdge("STAGING.NEW_BALANCE", "ACCOUNTS.BALANCE", "DIRECT")
```
