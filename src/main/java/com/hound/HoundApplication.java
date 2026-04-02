// src/main/java/com/hound/HoundApplication.java
package com.hound;

import com.hound.semantic.engine.UniversalSemanticEngine;
import com.hound.semantic.dialect.plsql.PlSqlSemanticListener;
import com.hound.parser.base.grammars.sql.plsql.PlSqlParser;
import com.hound.parser.base.grammars.sql.plsql.PlSqlLexer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

/**
 * Главный класс приложения Hound (временная версия для тестирования семантики)
 */
public class HoundApplication {

    public static void main(String[] args) {
        System.out.println("=== Hound Semantic Engine Starting ===");

        if (args.length == 0) {
            System.out.println("Usage: provide SQL file or inline SQL as argument");
            testSemanticEngine();
            return;
        }

        String sql = args[0];
        testSemanticEngineWithSql(sql);
    }

    /**
     * Тестовый запуск семантического движка
     */
    private static void testSemanticEngine() {
        String testSql = """
            SELECT u.name, d.department_name 
            FROM users u 
            JOIN departments d ON u.department_id = d.id 
            WHERE u.age > 30
            """;

        testSemanticEngineWithSql(testSql);
    }

    private static void testSemanticEngineWithSql(String sql) {
        try {
            long startTime = System.currentTimeMillis();

            // 1. Создаём engine
            UniversalSemanticEngine engine = new UniversalSemanticEngine();

            // 2. Создаём listener
            PlSqlSemanticListener listener = new PlSqlSemanticListener(engine);

            // 3. Парсим SQL
            PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(sql));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PlSqlParser parser = new PlSqlParser(tokens);

            // 4. Запускаем парсинг
            PlSqlParser.Sql_scriptContext tree = parser.sql_script();

            // 5. Проходим listener'ом
            ParseTreeWalker.DEFAULT.walk(listener, tree);

            // 6. Получаем результат
            Object result = engine.getResult();

            long duration = System.currentTimeMillis() - startTime;

            System.out.println("Semantic analysis completed in " + duration + " ms");
            System.out.println("Result: " + result);

        } catch (Exception e) {
            System.err.println("Error during semantic analysis:");
            e.printStackTrace();
        }
    }
}