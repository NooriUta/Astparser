// src/main/java/com/hound/semantic/model/SemanticResult.java
package com.hound.semantic.model;

import java.util.List;
import java.util.Map;

/**
 * Финальный результат семантического анализа файла.
 */
public class SemanticResult {

    private final String sessionId;
    private final String filePath;
    private final String dialect;
    private final long processingTimeMs;

    private final Structure structure;
    private final List<LineageEdge> lineage;
    private final Map<String, Object> atoms;

    public SemanticResult(String sessionId, String filePath, String dialect, long processingTimeMs,
                          Structure structure, List<LineageEdge> lineage, Map<String, Object> atoms) {
        this.sessionId = sessionId;
        this.filePath = filePath;
        this.dialect = dialect;
        this.processingTimeMs = processingTimeMs;
        this.structure = structure;
        this.lineage = lineage != null ? lineage : List.of();
        this.atoms = atoms != null ? atoms : Map.of();
    }

    public String getSessionId() { return sessionId; }
    public String getFilePath() { return filePath; }
    public String getDialect() { return dialect; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public Structure getStructure() { return structure; }
    public List<LineageEdge> getLineage() { return lineage; }
    public Map<String, Object> getAtoms() { return atoms; }

    @Override
    public String toString() {
        return "SemanticResult{" +
                "sessionId='" + sessionId + '\'' +
                ", filePath='" + filePath + '\'' +
                ", dialect='" + dialect + '\'' +
                ", processingTimeMs=" + processingTimeMs +
                ", tables=" + (structure != null ? structure.getTables().size() : 0) +
                ", lineage=" + lineage.size() +
                ", atoms=" + atoms.size() +
                '}';
    }
}