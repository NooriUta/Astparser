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
    // S1.PRE: per-atom resolution diagnostics (raw_input, result_kind, note, strategy, …)
    private final List<Map<String, Object>> resolutionLog;
    // CALLS-1: callerGeoid → [{name, line, type}] for CALLS edge materialization
    private final Map<String, List<Map<String, String>>> calledRoutines;
    // S1.SCH: suspicious schema registrations with backtrace (schema_name, reason, backtrace)
    private final List<Map<String, Object>> schemaRegistrationLog;

    public SemanticResult(String sessionId, String filePath, String dialect, long processingTimeMs,
                          Structure structure, List<LineageEdge> lineage, Map<String, Object> atoms,
                          List<Map<String, Object>> resolutionLog,
                          Map<String, List<Map<String, String>>> calledRoutines,
                          List<Map<String, Object>> schemaRegistrationLog) {
        this.sessionId = sessionId;
        this.filePath = filePath;
        this.dialect = dialect;
        this.processingTimeMs = processingTimeMs;
        this.structure = structure;
        this.lineage = lineage != null ? lineage : List.of();
        this.atoms = atoms != null ? atoms : Map.of();
        this.resolutionLog = resolutionLog != null ? resolutionLog : List.of();
        this.calledRoutines = calledRoutines != null ? calledRoutines : Map.of();
        this.schemaRegistrationLog = schemaRegistrationLog != null ? schemaRegistrationLog : List.of();
    }

    public String getSessionId() { return sessionId; }
    public String getFilePath() { return filePath; }
    public String getDialect() { return dialect; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public Structure getStructure() { return structure; }
    public List<LineageEdge> getLineage() { return lineage; }
    public Map<String, Object> getAtoms() { return atoms; }
    public List<Map<String, Object>> getResolutionLog() { return resolutionLog; }
    public Map<String, List<Map<String, String>>> getCalledRoutines() { return calledRoutines; }
    public List<Map<String, Object>> getSchemaRegistrationLog() { return schemaRegistrationLog; }

    /**
     * Arrow Flight compatible serialization.
     */
    public Map<String, Object> toArrowCompatibleMap() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("structure", structure);
        result.put("lineage", lineage);
        result.put("atoms", atoms);
        result.put("session_id", sessionId);
        result.put("file_path", filePath);
        result.put("dialect", dialect);
        result.put("processing_time_ms", processingTimeMs);
        return result;
    }

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