// src/main/java/com/hound/semantic/model/RoutineInfo.java
package com.hound.semantic.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Информация о процедуре или функции
 */
public class RoutineInfo {

    private final String geoid;
    private final String name;
    private final String routineType; // PROCEDURE, FUNCTION
    private final String packageGeoid;
    private final String schemaGeoid;
    private final List<Object> parameters = new ArrayList<>();
    private final List<Object> variables = new ArrayList<>();

    public RoutineInfo(String geoid, String name, String routineType, String packageGeoid, String schemaGeoid) {
        this.geoid = geoid;
        this.name = name;
        this.routineType = routineType != null ? routineType : "PROCEDURE";
        this.packageGeoid = packageGeoid;
        this.schemaGeoid = schemaGeoid;
    }

    public String getGeoid() { return geoid; }
    public String getName() { return name; }
    public String getRoutineType() { return routineType; }
    public String getPackageGeoid() { return packageGeoid; }
    public String getSchemaGeoid() { return schemaGeoid; }

    public void addParameter(Object param) {
        parameters.add(param);
    }

    public void addVariable(Object var) {
        variables.add(var);
    }
}