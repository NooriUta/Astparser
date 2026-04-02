package com.hound.semantic.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ScopeManager — управляет стеком контекстов и текущим состоянием разбора.
 * Заменяет Python _current dict + subquery_alias_stack.
 */
public class ScopeManager {

    private final Deque<ScopeContext> scopeStack = new ArrayDeque<>();

    /**
     * Вход в новый scope (statement, subquery, routine и т.д.)
     */
    public void push(ScopeContext context) {
        scopeStack.push(context);
    }

    /**
     * Выход из текущего scope
     */
    public ScopeContext pop() {
        return scopeStack.isEmpty() ? null : scopeStack.pop();
    }

    /**
     * Текущий контекст (без удаления)
     */
    public ScopeContext peek() {
        return scopeStack.isEmpty() ? null : scopeStack.peek();
    }

    public boolean isEmpty() {
        return scopeStack.isEmpty();
    }

    // ====================== Удобные геттеры ======================

    public String currentStatement() {
        ScopeContext ctx = peek();
        return ctx != null ? ctx.getStatementGeoid() : null;
    }

    public String currentRoutine() {
        ScopeContext ctx = peek();
        return ctx != null ? ctx.getRoutineGeoid() : null;
    }

    public String currentSchema() {
        ScopeContext ctx = peek();
        return ctx != null ? ctx.getSchemaGeoid() : null;
    }

    public boolean isInDmlTarget() {
        ScopeContext ctx = peek();
        return ctx != null && ctx.isInDmlTarget();
    }

    public boolean isInJoinContext() {
        ScopeContext ctx = peek();
        return ctx != null && ctx.isInJoinContext();
    }

    public boolean isInValuesClause() {
        ScopeContext ctx = peek();
        return ctx != null && ctx.isInValuesClause();
    }

    public String getActiveClause() {
        ScopeContext ctx = peek();
        return ctx != null ? ctx.getActiveClause() : "UNKNOWN";
    }

    /**
     * Регистрация алиаса в текущем scope
     */
    public void registerAlias(String alias, String geoid) {
        ScopeContext ctx = peek();
        if (ctx != null) {
            ctx.registerAlias(alias, geoid);
        }
    }

    public String resolveAlias(String alias) {
        // Можно реализовать поиск по всему стеку при необходимости
        ScopeContext ctx = peek();
        return ctx != null ? ctx.resolveAlias(alias) : null;
    }

    public int depth() {
        return scopeStack.size();
    }

    @Override
    public String toString() {
        return "ScopeManager{depth=" + depth() + ", currentStatement=" + currentStatement() + "}";
    }
}