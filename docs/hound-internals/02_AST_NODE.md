# UniversalAstNode — структура AST-узла

## Назначение

`UniversalAstNode` — единица хранения информации в нашем дереве. Все диалекты
после парсинга представляются деревьями из таких узлов — одна модель для всего.

---

## Анатомия узла

```
┌──────────────────────────────────────────────────────┐
│                  UniversalAstNode                    │
├──────────────────────────────────────────────────────┤
│  id         UUID  — уникальный идентификатор         │
│             (используется при записи в граф БД)      │
│                                                      │
│  type       String — семантический тип узла          │
│             RULE:     "select_statement",             │
│                       "from_clause", "where_clause"  │
│             TERMINAL: "identifier", "numeric_literal"│
│                       "string_literal", "operator"   │
│             ERROR:    "error_node"                   │
│             ROOT:     "Program"                      │
│                                                      │
│  nodeType   String — категория узла                  │
│             "RULE" | "TERMINAL" | "ERROR" | "ROOT"   │
│             "UNPARSED"                               │
│                                                      │
│  properties Map<String, Object>                      │
│             (см. раздел Properties ниже)             │
│                                                      │
│  children   List<UniversalAstNode>                   │
│             Упорядоченный список дочерних узлов      │
│                                                      │
│  parent     UniversalAstNode (back-reference)        │
│                                                      │
│  position   Position(line, column, startIdx, stopIdx)│
└──────────────────────────────────────────────────────┘
```

---

## Properties: что хранится в узле

### Для RULE-узлов

| Ключ | Тип | Описание |
|------|-----|----------|
| `line` | int | Строка начала правила в исходном тексте |
| `column` | int | Позиция (символ) начала правила |
| `startIndex` | int | Абсолютный индекс начального токена |
| `stopIndex` | int | Абсолютный индекс конечного токена |
| `endLine` | int | Строка конца правила |
| `endColumn` | int | Позиция конца правила |
| `snippet` | String | Исходный SQL-текст этого правила (до 500 символов) — **см. примечание** |
| `childCount` | int | Количество непосредственных детей |
| `depth` | int | Глубина в дереве |
| `ruleIndex` | int | Числовой индекс правила в ANTLR-грамматике |
| `qualifiedName` | String | `startIndex:stopIndex:ruleName` — уникальный ключ |

### Для TERMINAL-узлов

| Ключ | Тип | Описание |
|------|-----|----------|
| `text` | String | Исходный текст токена |
| `line` | int | Строка токена |
| `column` | int | Позиция токена |
| `startIndex` | int | Абсолютный индекс в тексте |
| `stopIndex` | int | Конечный индекс (включительно) |
| `tokenType` | int | Числовой тип из ANTLR-грамматики |
| `qualifiedName` | String | `startIndex:stopIndex:tokenType` |

> **Примечание: два разных источника `snippet`**
>
> Существуют два места, где хранится SQL-текст, и они имеют разные лимиты:
>
> | Место | Источник | Лимит |
> |-------|---------|-------|
> | `UniversalAstNode.properties["snippet"]` | Текст между start-токеном и stop-токеном правила | **500 символов** (ANTLR, для AST-узлов промежуточного уровня) |
> | `StatementInfo.snippet` → `DaliSnippet` / `DaliSnippetScript` | Текст всего statement'а из SemanticListener | **без ограничений** (`SNIPPET_MAX = Integer.MAX_VALUE`) |
>
> При записи в БД вершина `DaliSnippet` использует `StatementInfo.snippet` —
> полный текст SQL-оператора. Усечения в `UniversalAstNode` на слой хранения
> не влияют: DaliSnippet создаётся до записи AST-узлов и берёт текст напрямую
> из результата семантического анализа, не из дерева узлов.

### Для ROOT-узла (Program)

| Ключ | Тип | Описание |
|------|-----|----------|
| `fileName` | String | Имя обрабатываемого файла |
| `parsed` | boolean | true если парсинг успешен |
| `language` | String | Диалект ("PLSQL", "POSTGRESQL"...) |
| `parser` | String | Имя класса парсера |
| `size` | int | Длина исходного текста в символах |

---

## Типы узлов и их роли

```
RULE — нетерминальный узел (внутренний)
─────────────────────────────────────────
  Представляет синтаксическую конструкцию.
  Всегда имеет children.
  Примеры типов:
    select_statement  — оператор SELECT целиком
    from_clause       — секция FROM
    where_clause      — секция WHERE
    join_clause       — операция JOIN
    subquery          — подзапрос
    merge_element     — WHEN MATCHED / WHEN NOT MATCHED

TERMINAL — терминальный узел (лист)
──────────────────────────────────────
  Представляет один токен.
  Никогда не имеет children.
  Примеры типов:
    identifier       — имя таблицы, колонки, алиаса
    numeric_literal  — 42, 3.14, 1e5
    string_literal   — 'hello', "world"
    operator         — =, ||, <>, +, -
  
  ОТФИЛЬТРОВАНЫ (не добавляются в дерево):
    keyword          — SELECT, FROM, WHERE, JOIN...
    punctuation      — (, ), ,, ;, ., [, ]

ERROR — узел синтаксической ошибки
────────────────────────────────────
  Создаётся когда парсер встречает неожиданный токен.
  Не останавливает парсинг — дерево строится дальше.
  Используется для диагностики.

ROOT (Program) — корень дерева
───────────────────────────────
  Один на весь файл.
  Содержит метаданные файла.
```

---

## Дерево vs граф

Дерево `UniversalAstNode` затем конвертируется в граф для записи в ArcadeDB.

```
UniversalAstNode.toGraphNode()
──────────────────────────────
  1. Создать GraphNode с тем же id (ВАЖНО: id должен совпадать,
     иначе связи в графе не найдут узлы)
  2. Скопировать все примитивные properties (String, Number, Boolean)
     Пропустить вложенные объекты — граф хранит только примитивы
  3. Для корневого узла добавить fileName / filePath
  4. Если есть Position — добавить line, column, startIndex, stopIndex

  Затем:
  parent.id → GraphRelationship(PARENT_EDGE) → child.id
```

---

## Пример: дерево для `SUM(amount) AS total`

```
RULE: select_item  [line 3:4, snippet="SUM(amount) AS total"]
├── RULE: function_call  [line 3:4]
│   ├── TERMINAL: identifier "SUM"     [line 3:4, col 4]
│   └── RULE: function_args  [line 3:7]
│       └── TERMINAL: identifier "amount"  [line 3:8, col 8]
└── TERMINAL: identifier "total"  [line 3:23, col 23]
```

Заметьте: `(`, `)`, `AS` — отфильтрованы и не попадают в дерево.

---

## Обход всего поддерева

Метод `getAllNodes()` возвращает все узлы дерева в порядке depth-first:

```
root → child1 → child1.child1 → child1.child2 → child2 → ...
```

Используется семантическим движком для поиска атомов по всему statement.
