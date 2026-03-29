package com.hound.parser;

import com.hound.graph.UniversalAstNode;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальный парсер для всех сгенерированных ANTLR грамматик.
 * Строит единое универсальное AST-дерево (UniversalAstNode) независимо от языка.
 */
public class UniversalParser implements LanguageParser {

    private static final Logger logger = LoggerFactory.getLogger(UniversalParser.class);
    private AstListener listener;

    // Кэш загруженных парсеров для производительности
    private final Map<String, ParserInfo> parserCache = new ConcurrentHashMap<>();

    private static class ParserInfo {
        final Class<?> lexerClass;
        final Class<?> parserClass;
        final String startRuleName;   // стартовое правило (stmt, sql_script и т.д.)

        ParserInfo(Class<?> lexerClass, Class<?> parserClass, String startRuleName) {
            this.lexerClass = lexerClass;
            this.parserClass = parserClass;
            this.startRuleName = startRuleName;
        }
    }

    @Override
    public UniversalAstNode parse(String code, String fileName) {
        UniversalAstNode root = new UniversalAstNode("Program", "ROOT");
        root.addProperty("fileName", fileName);
        root.addProperty("size", code.length());

        try {
            String language = detectLanguage(fileName);
            logger.info("Начинаем парсинг файла: {} | Определён язык: {}", fileName, language.toUpperCase());

            ParserInfo info = getParserForLanguage(language);

            if (info == null) {
                logger.warn("Парсер для языка '{}' не найден. Используем сырой текст.", language);
                return createRawNode(code, fileName, root);
            }

            // Создаём lexer и parser
            CharStream input = CharStreams.fromString(code);
            Lexer lexer = (Lexer) info.lexerClass.getConstructor(CharStream.class).newInstance(input);
            Parser parser = (Parser) info.parserClass.getConstructor(TokenStream.class)
                    .newInstance(new CommonTokenStream(lexer));

            logger.debug("Парсер {} успешно создан. Ищем стартовое правило...", info.parserClass.getSimpleName());

            // Находим подходящее стартовое правило
            Method startMethod = findStartRuleMethod(parser, info.startRuleName);
            if (startMethod == null) {
                logger.warn("Стартовое правило не найдено для {}. Используем сырой текст.", language);
                return createRawNode(code, fileName, root);
            }

            // Выполняем парсинг
            ParseTree tree = (ParseTree) startMethod.invoke(parser);

            // Обходим дерево с помощью универсального слушателя
            ParseTreeWalker walker = new ParseTreeWalker();
            walker.walk(new UniversalTreeListener(root, listener), tree);

            root.addProperty("parsed", true);
            root.addProperty("language", language.toUpperCase());
            root.addProperty("parser", info.parserClass.getSimpleName());

            logger.info("Файл {} успешно распарсен (язык: {}, узлов: {})",
                    fileName, language.toUpperCase(), root.getChildren().size());

        } catch (Exception e) {
            logger.error("Ошибка парсинга файла: {}", fileName, e);
            root.addProperty("parsed", false);
            root.addProperty("error", e.getMessage());
            return createRawNode(code, fileName, root);
        }

        return root;
    }

    /**
     * Определяет язык по имени файла
     */
    private String detectLanguage(String fileName) {
        if (fileName == null) return "sql";

        String lower = fileName.toLowerCase();

        if (lower.contains("athena")) return "athena";
        if (lower.contains("hive")) return "hive";
        if (lower.contains("mysql")) return "mysql";
        if (lower.contains("plsql") || lower.contains("oracle")) return "plsql";
        if (lower.contains("postgresql") || lower.contains("postgres")) return "postgresql";
        if (lower.contains("starrocks")) return "starrocks";
        if (lower.contains("trino")) return "trino";
        if (lower.contains("tsql") || lower.contains("sqlserver")) return "tsql";

        return "sql"; // по умолчанию
    }

    /**
     * Возвращает информацию о парсере для указанного языка
     */
    private ParserInfo getParserForLanguage(String language) {
        return parserCache.computeIfAbsent(language.toLowerCase(), lang -> {
            try {
                String base = "com.hound.parser.base.grammars.sql.";

                return switch (lang) {
                    case "athena" -> new ParserInfo(
                            Class.forName(base + "athena.AthenaLexer"),
                            Class.forName(base + "athena.AthenaParser"),
                            "stmt"                    // стартовое правило для Athena
                    );

                    case "plsql" -> new ParserInfo(
                            Class.forName("com.hound.parser.base.grammars.plsql.PlSqlLexer"),
                            Class.forName("com.hound.parser.base.grammars.plsql.PlSqlParser"),
                            "sql_script"              // наиболее частое стартовое правило для PL/SQL
                    );

                    // Добавляй сюда другие языки по мере генерации
                    case "hive" -> new ParserInfo(
                            Class.forName(base + "hive.v4.HiveLexer"),
                            Class.forName(base + "hive.v4.HiveParser"),
                            "statement"
                    );

                    default -> {
                        logger.warn("Неизвестный язык: {}", lang);
                        yield null;
                    }
                };
            } catch (ClassNotFoundException e) {
                logger.error("Не удалось загрузить классы парсера для языка: {}", lang, e);
                return null;
            } catch (Exception e) {
                logger.error("Ошибка при инициализации парсера для языка: {}", lang, e);
                return null;
            }
        });
    }

    /**
     * Пытается найти подходящее стартовое правило парсера
     */
    private Method findStartRuleMethod(Parser parser, String preferredRule) {
        String[] candidates = {
                preferredRule,
                "stmt", "statement", "sql_script", "program", "root", "query", "start", "sql"
        };

        for (String ruleName : candidates) {
            try {
                return parser.getClass().getMethod(ruleName);
            } catch (NoSuchMethodException ignored) {
                // продолжаем поиск
            }
        }
        return null;
    }

    /**
     * Создаёт fallback-узел с сырым текстом при ошибке парсинга
     */
    private UniversalAstNode createRawNode(String code, String fileName, UniversalAstNode root) {
        UniversalAstNode raw = new UniversalAstNode("RawText", "UNPARSED");
        String snippet = code.length() > 3000 ? code.substring(0, 3000) + "..." : code;
        raw.addProperty("content", snippet);
        raw.addProperty("fileName", fileName);
        root.addChild(raw);
        return root;
    }

    @Override
    public String getLanguageName() {
        return "Universal";
    }

    @Override
    public boolean supports(String code) {
        return true;
    }

    @Override
    public void setListener(AstListener listener) {
        this.listener = listener;
    }
}