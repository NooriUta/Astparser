package com.hound.parser.core;

import com.hound.graph.UniversalAstNode;
import com.hound.parser.AstListener;
import com.hound.parser.LanguageParser;
import com.hound.parser.UniversalTreeListener;
import com.hound.parser.registry.ParserRegistry;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Универсальный парсер для всех SQL-диалектов.
 * Теперь использует ParserRegistry вместо reflection.
 */
public class UniversalParser implements LanguageParser {

    private static final Logger logger = LoggerFactory.getLogger(UniversalParser.class);
    private AstListener listener;

    @Override
    public UniversalAstNode parse(String code, String fileName) {
        UniversalAstNode root = new UniversalAstNode("Program", "ROOT");
        root.addProperty("fileName", fileName);
        root.addProperty("size", code.length());

        try {
            String language = detectLanguage(fileName);
            logger.info("Начинаем парсинг файла: {} | Определён язык: {}", fileName, language.toUpperCase());

            // Берём информацию о парсере из реестра
            ParserRegistry.ParserInfo info = ParserRegistry.getParserInfo(language);

            if (info == null) {
                logger.warn("Парсер для языка '{}' не найден. Используем сырой текст.", language);
                return createRawNode(code, fileName, root);
            }

            // Создаём lexer и parser
            CharStream input = CharStreams.fromString(code);
            Lexer lexer = (Lexer) info.lexerClass().getConstructor(CharStream.class).newInstance(input);
            Parser parser = (Parser) info.parserClass().getConstructor(TokenStream.class)
                    .newInstance(new CommonTokenStream(lexer));

            logger.debug("Парсер {} успешно создан. Ищем стартовое правило...", info.parserClass().getSimpleName());

            // Находим подходящее стартовое правило
            Method startMethod = findStartRuleMethod(parser, info.startRuleName());
            if (startMethod == null) {
                logger.warn("Стартовое правило не найдено для {}. Используем сырой текст.", language);
                return createRawNode(code, fileName, root);
            }

            // Выполняем парсинг
            ParseTree tree = (ParseTree) startMethod.invoke(parser);

            // Обходим дерево
            ParseTreeWalker walker = new ParseTreeWalker();
            walker.walk(new UniversalTreeListener(root, listener), tree);

            root.addProperty("parsed", true);
            root.addProperty("language", language.toUpperCase());
            root.addProperty("parser", info.parserClass().getSimpleName());

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
        if (lower.contains("clickhouse")) return "clickhouse";
        if (lower.contains("databricks")) return "databricks";
        if (lower.contains("snowflake")) return "snowflake";
        if (lower.contains("mariadb")) return "mariadb";
        if (lower.contains("db2")) return "db2";
        if (lower.contains("derby")) return "derby";
        if (lower.contains("drill")) return "drill";
        if (lower.contains("phoenix")) return "phoenix";
        if (lower.contains("sqlite")) return "sqlite";

        return "sql"; // по умолчанию
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