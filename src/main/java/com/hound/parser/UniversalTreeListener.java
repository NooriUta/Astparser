package com.hound.parser;

import com.hound.graph.UniversalAstNode;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Универсальный слушатель для обхода AST дерева
 * Записывает все узлы в универсальную структуру
 */
public class UniversalTreeListener implements ParseTreeListener {
    private static final Logger logger = LoggerFactory.getLogger(UniversalTreeListener.class);

    private final Deque<UniversalAstNode> nodeStack = new ArrayDeque<>();
    private final AstListener listener;

    public UniversalTreeListener(UniversalAstNode root, AstListener listener) {
        this.nodeStack.push(root);
        this.listener = listener;
    }

    @Override
    public void enterEveryRule(ParserRuleContext ctx) {
        try {
            // Получаем имя правила
            String ruleName = getRuleName(ctx);

            // Создаем узел для этого правила
            UniversalAstNode node = new UniversalAstNode("AstNode", ruleName);

            // Добавляем позицию в исходном коде
            Token startToken = ctx.getStart();
            if (startToken != null) {
                node.addProperty("line", startToken.getLine());
                node.addProperty("column", startToken.getCharPositionInLine());
                node.addProperty("startIndex", startToken.getStartIndex());

                Token stopToken = ctx.getStop();
                if (stopToken != null) {
                    node.addProperty("stopIndex", stopToken.getStopIndex());
                }

                // Добавляем текст узла (первые 100 символов)
                String text = ctx.getText();
                if (text != null && !text.isEmpty()) {
                    if (text.length() > 100) {
                        node.addProperty("snippet", text.substring(0, 100) + "...");
                    } else {
                        node.addProperty("snippet", text);
                    }
                }
            }

            // Добавляем количество детей
            node.addProperty("childCount", ctx.getChildCount());

            // Добавляем в дерево
            UniversalAstNode parent = nodeStack.peek();
            if (parent != null) {
                parent.addChild(node);
            }

            nodeStack.push(node);

            // Уведомляем слушателя
            if (listener != null) {
                listener.onNodeEnter(node);
            }

        } catch (Exception e) {
            logger.debug("Error in enterEveryRule: {}", e.getMessage());
        }
    }

    @Override
    public void exitEveryRule(ParserRuleContext ctx) {
        try {
            if (!nodeStack.isEmpty()) {
                UniversalAstNode node = nodeStack.pop();
                if (listener != null) {
                    listener.onNodeExit(node);
                }
            }
        } catch (Exception e) {
            logger.debug("Error in exitEveryRule: {}", e.getMessage());
        }
    }

    @Override
    public void visitTerminal(TerminalNode node) {
        try {
            // Получаем текст терминала
            String text = node.getText();
            if (text == null || text.trim().isEmpty()) {
                return;
            }

            // Создаем узел для терминала
            UniversalAstNode terminalNode = new UniversalAstNode("Terminal", "TOKEN");
            terminalNode.addProperty("text", text);

            // Добавляем позицию
            Token symbol = node.getSymbol();
            if (symbol != null) {
                terminalNode.addProperty("line", symbol.getLine());
                terminalNode.addProperty("column", symbol.getCharPositionInLine());
                terminalNode.addProperty("startIndex", symbol.getStartIndex());
                terminalNode.addProperty("stopIndex", symbol.getStopIndex());
                terminalNode.addProperty("tokenType", symbol.getType());
            }

            // Добавляем в дерево
            UniversalAstNode parent = nodeStack.peek();
            if (parent != null) {
                parent.addChild(terminalNode);
            }

            // Уведомляем слушателя
            if (listener != null) {
                listener.onNodeEnter(terminalNode);
                listener.onNodeExit(terminalNode);
            }

        } catch (Exception e) {
            logger.debug("Error in visitTerminal: {}", e.getMessage());
        }
    }

    @Override
    public void visitErrorNode(ErrorNode node) {
        try {
            String errorText = node.getText();
            Token symbol = node.getSymbol();

            if (listener != null) {
                int line = symbol != null ? symbol.getLine() : 0;
                int column = symbol != null ? symbol.getCharPositionInLine() : 0;
                listener.onError("Parse error: " + errorText, line, column, "unknown");
            }

            logger.error("Parse error: {} at line {}", errorText,
                    symbol != null ? symbol.getLine() : 0);

        } catch (Exception e) {
            logger.debug("Error in visitErrorNode: {}", e.getMessage());
        }
    }

    /**
     * Получает имя правила из контекста
     */
    private String getRuleName(ParserRuleContext ctx) {
        try {
            // Пробуем получить через имя класса
            String className = ctx.getClass().getSimpleName();
            if (className.endsWith("Context")) {
                return className.substring(0, className.length() - 7);
            }
            return className;
        } catch (Exception e) {
            return "UnknownRule";
        }
    }
}