// src/main/java/com/hound/semantic/model/TableInfo.java
package com.hound.semantic.model;

import java.util.HashSet;
import java.util.Set;

public class TableInfo {

    private final String geoid;
    private final String tableName;
    private final String schemaGeoid;
    private final String tableType;
    private final Set<String> aliases = new HashSet<>();
    private int columnCount = 0;

    // Основной конструктор
    public TableInfo(String geoid, String tableName, String schemaGeoid, String tableType) {
        this.geoid = geoid;
        this.tableName = tableName;
        this.schemaGeoid = schemaGeoid;
        this.tableType = tableType != null ? tableType : "TABLE";
    }

    // Геттеры
    public String getGeoid() { return geoid; }
    public String getTableName() { return tableName; }
    public String getSchemaGeoid() { return schemaGeoid; }
    public String getTableType() { return tableType; }
    public Set<String> getAliases() { return new HashSet<>(aliases); }
    public int getColumnCount() { return columnCount; }

    public void addAlias(String alias) {
        if (alias != null) aliases.add(alias.toUpperCase());
    }

    public void incrementColumnCount() {
        columnCount++;
    }
}