package com.hound.semantic.engine;

public class ResolvedRef {

    private final String name;
    private final String type;   // TABLE, SUBQUERY, CTE, ALIAS, UNKNOWN
    private final String geoid;

    public ResolvedRef(String name, String type, String geoid) {
        this.name = name != null ? name : "";
        this.type = type != null ? type : "UNKNOWN";
        this.geoid = geoid;
    }

    public static ResolvedRef unresolved(String name) {
        return new ResolvedRef(name, "UNKNOWN", null);
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getGeoid() { return geoid; }

    public boolean isTable() { return "TABLE".equals(type); }
    public boolean isSubquery() { return "SUBQUERY".equals(type); }
    public boolean isCTE() { return "CTE".equals(type); }
    public boolean isResolved() { return geoid != null; }

    @Override
    public String toString() {
        return "ResolvedRef{name='" + name + "', type='" + type + "', geoid='" + geoid + "'}";
    }
}
