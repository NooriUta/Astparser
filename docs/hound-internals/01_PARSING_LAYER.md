# Слой парсинга (Parsing Layer)

## Задача этого слоя

Превратить сырой SQL-текст в структурированное дерево, пригодное для семантического анализа.
Слой работает независимо от диалекта — снаружи всегда один и тот же интерфейс.

---

## Алгоритм парсинга

```
Шаг 1: Определение диалекта
──────────────────────────────────────────────────────────────────
Вход: имя файла (например, "my_oracle_proc.plsql")

Правила определения (по убыванию приоритета):
  • Явно указан диалект → использовать его
  • Расширение .plsql / .pks / .pkb → PL/SQL
  • Имя содержит "oracle"/"package" → PL/SQL
  • Имя содержит "postgresql"/"postgres" → PostgreSQL
  • Имя содержит "tsql"/"mssql"/"sqlserver" → T-SQL
  • ... (аналогично для других диалектов)
  • Иначе → generic SQL

Шаг 2: Загрузка грамматики
──────────────────────────────────────────────────────────────────
ParserRegistry содержит таблицу:
  диалект → { lexerClass, parserClass, startRuleName }

Шаг 3: Лексический анализ (Lexer)
──────────────────────────────────────────────────────────────────
SQL-текст → CharStream → Lexer → поток токенов

Каждый токен содержит:
  • тип (keyword, identifier, number, string, operator, punctuation)
  • текст ("SELECT", "user_id", "42", "'hello'")
  • позицию (строка, колонка, индекс в тексте)

Шаг 4: Синтаксический анализ (Parser)
──────────────────────────────────────────────────────────────────
Поток токенов → Parser → ParseTree

Поиск стартового правила (по убыванию приоритета):
  1. Из конфига ParserRegistry
  2. Перебор кандидатов: stmt → statement → sql_script →
     program → root → query → start → sql

Шаг 5: Обход дерева (TreeListener)
──────────────────────────────────────────────────────────────────
ParseTree + TokenStream → ParseTreeWalker → UniversalTreeListener

Шаг 6: Построение UniversalAstNode
──────────────────────────────────────────────────────────────────
Результат: корневой UniversalAstNode с полным деревом
```

---

## ParseTree vs UniversalAstNode

```
┌─────────────────────────────────────────────────────────┐
│                    ParseTree (ANTLR)                     │
│                                                         │
│  • Грамматически-зависимый (разный для каждого диалекта)│
│  • Содержит всё: ключевые слова, скобки, точки с запятой│
│  • Узлы: SelectStatementContext, FromClauseContext ...   │
│  • Прямой Java-класс из ANTLR-генерации                 │
└────────────────────────┬────────────────────────────────┘
                         │
                         │ преобразование через
                         │ UniversalTreeListener
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                 UniversalAstNode (HOUND)                 │
│                                                         │
│  • Единый формат для всех диалектов                     │
│  • Отфильтрован от "шума" (keywords, punctuation)       │
│  • Типизирован: RULE / TERMINAL / ERROR                 │
│  • Обогащён позицией (line:col:startIndex:stopIndex)    │
│  • Имеет snippet — исходный SQL-текст узла              │
└─────────────────────────────────────────────────────────┘
```

---

## UniversalTreeListener — алгоритм обхода

Listener получает события от `ParseTreeWalker` при обходе ParseTree в глубину (depth-first).

### Обработка RULE-узла (нетерминала)

```
enterEveryRule(ctx):
  1. Получить имя правила из имени класса Context
     (SelectStatementContext → select_statement)
  2. Создать UniversalAstNode(type="select_statement", nodeType="RULE")
  3. Добавить позицию: start.line, start.column, startIndex, stopIndex
  4. Добавить snippet: текст от start-токена до stop-токена
  5. Добавить дочерний узел к parent'у (верхушка стека)
  6. Push узла на nodeStack
  7. Оповестить внешний AstListener (если подключён)

exitEveryRule(ctx):
  1. Pop узла со стека
  2. Оповестить внешний AstListener
```

### Обработка TERMINAL-узла (токена/листа)

```
visitTerminal(node):
  1. Пропустить EOF и пустые токены
  2. Пропустить скрытые каналы (комментарии, whitespace)
  3. Классифицировать токен:
     ┌──────────────────────────────────────────────────┐
     │  Паттерн текста        │  Тип                    │
     ├────────────────────────┼─────────────────────────┤
     │  -123, 3.14, 1e10      │  numeric_literal         │
     │  'text', "text"        │  string_literal          │
     │  +,-,*,/,=,||,<>,...   │  operator               │
     │  (, ), ,, ;, ., [, ]   │  punctuation  [SKIP]    │
     │  SELECT, FROM, WHERE   │  keyword      [SKIP]    │
     │  всё остальное         │  identifier              │
     └──────────────────────────────────────────────────┘
  4. Если тип в SKIP_SET (punctuation, keyword) → не добавлять
  5. Создать UniversalAstNode(type=тип, nodeType="TERMINAL")
  6. Добавить: text, line, column, startIndex, stopIndex
  7. Добавить к parent'у (верхушка стека)
```

> **Важно: точка отфильтрована из дерева, но используется в `tokenDetails`**
>
> Знак `.` классифицируется как `punctuation → SKIP` и **не попадает** в
> `UniversalAstNode`. Однако SemanticListener при регистрации атома передаёт
> в `registerAtom()` параметр `tokenDetails` — **сырой список ANTLR-токенов**
> из `TokenStream` до фильтрации, включая `.`.
>
> Именно по этому сырому потоку классификатор атомов (см. `06_ATOM_PROCESSING.md`)
> распознаёт паттерн `IDENTIFIER . IDENTIFIER` как `column_ref (qualified)`.
>
> ```
> AST дерево:                     tokenDetails (сырой поток):
>   identifier "u"      [TERM]      [ IDENTIFIER("u"), DOT("."), IDENTIFIER("id") ]
>   identifier "id"     [TERM]          ↑
>   (точка отсутствует)             используется классификатором атомов
> ```
>
> Это неявная зависимость: `06_ATOM_PROCESSING.md` работает не с AST-деревом,
> а с сырым токенным потоком. Добавление нового диалекта требует убедиться,
> что SemanticListener передаёт полный `tokenDetails` (с точками) при вызове
> `registerAtom`.

### Обработка ERROR-узла

```
visitErrorNode(node):
  1. Создать UniversalAstNode(nodeType="ERROR")
  2. Добавить text, line, column
  3. Добавить к parent'у
  4. Оповестить AstListener с описанием ошибки
```

### Стек при обходе

```
Пример SQL: SELECT col FROM t

Состояние стека в момент обхода:

[ENTER program]          stack: [program]
 [ENTER select_stmt]     stack: [select_stmt, program]
  [ENTER select_list]    stack: [select_list, select_stmt, program]
   [VISIT "col" IDENT]   → добавить leaf к select_list
  [EXIT select_list]     stack: [select_stmt, program]
  [ENTER from_clause]    stack: [from_clause, select_stmt, program]
   [VISIT "t" IDENT]     → добавить leaf к from_clause
  [EXIT from_clause]     stack: [select_stmt, program]
 [EXIT select_stmt]      stack: [program]
[EXIT program]           stack: []
```

---

## Fallback: нераспознанный диалект или ошибка

Если диалект не найден или парсинг завершился исключением:

```
createRawNode(code, fileName):
  1. Создать UniversalAstNode(type="RawText", nodeType="UNPARSED")
  2. Вставить content: первые 3000 символов исходного текста
  3. Пометить root: parsed=false, error=<сообщение>
  4. Вернуть root с единственным дочерним UNPARSED-узлом
```

Это позволяет конвейеру продолжить работу без fatal error — файл будет помечен
как "не распознан", но обработка других файлов продолжится.

---

## Итоговая структура узлов для простого SELECT

```sql
SELECT u.id, u.name FROM users u WHERE u.active = 1
```

```
program [ROOT]
└── select_statement [RULE, line 1:1–1:51]
    ├── select_list [RULE, line 1:8–1:15]
    │   ├── identifier "u"      [TERMINAL, line 1:8]
    │   ├── identifier "id"     [TERMINAL, line 1:10]
    │   ├── identifier "u"      [TERMINAL, line 1:13]
    │   └── identifier "name"   [TERMINAL, line 1:15]
    ├── from_clause [RULE, line 1:22–1:30]
    │   ├── identifier "users"  [TERMINAL, line 1:27]
    │   └── identifier "u"      [TERMINAL, line 1:33]
    └── where_clause [RULE, line 1:35–1:51]
        ├── identifier "u"      [TERMINAL, line 1:41]
        ├── identifier "active" [TERMINAL, line 1:43]
        ├── operator "="        [TERMINAL, line 1:50]
        └── numeric_literal "1" [TERMINAL, line 1:52]
```

Ключевые слова `SELECT`, `FROM`, `WHERE` и знаки препинания `,`, `.` отфильтрованы
и не попадают в дерево.
