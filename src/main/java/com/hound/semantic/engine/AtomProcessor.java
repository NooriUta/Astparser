// src/main/java/com/hound/semantic/engine/AtomProcessor.java
package com.hound.semantic.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AtomProcessor — сбор и разрешение атомов
 */
public class AtomProcessor {

    private final Map<String, Object> atoms = new LinkedHashMap<>();

    public void registerAtom(String text, int line, int col, int endLine, int endCol,
                             String context, String statementGeoid, String parentContext) {

        String key = statementGeoid + ":" + line + ":" + col;
        atoms.put(key, Map.of(
                "text", text,
                "position", line + ":" + col,
                "context", context
        ));
    }

    public void resolveAtomsOnStatementExit(String statementGeoid) {
        // TODO: полная реализация разрешения атомов
    }

    public Map<String, Object> getAtoms() {
        return new LinkedHashMap<>(atoms);
    }

    public void clear() {
        atoms.clear();
    }
}