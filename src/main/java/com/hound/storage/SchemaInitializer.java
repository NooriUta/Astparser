// File: src/main/java/com/hound/storage/SchemaInitializer.java
package com.hound.storage;

import com.arcadedb.database.Database;
import com.arcadedb.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Создаёт схему ArcadeDB (embedded mode).
 * Все типы с префиксом "Dali".
 */
public final class SchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(SchemaInitializer.class);

    private SchemaInitializer() {}

    public static void ensureSchema(Database db) {
        Schema schema = db.getSchema();
        int created = 0;

        // Vertex types
        created += vertexIfAbsent(schema, "DaliSession");
        created += vertexIfAbsent(schema, "DaliDatabase");
        created += vertexIfAbsent(schema, "DaliSchema");
        created += vertexIfAbsent(schema, "DaliPackage");
        created += vertexIfAbsent(schema, "DaliTable");
        created += vertexIfAbsent(schema, "DaliColumn");
        created += vertexIfAbsent(schema, "DaliRoutine");
        created += vertexIfAbsent(schema, "DaliStatement");

        // Document types
        created += documentIfAbsent(schema, "DaliAtom");
        created += documentIfAbsent(schema, "DaliJoin");
        created += documentIfAbsent(schema, "DaliParameter");
        created += documentIfAbsent(schema, "DaliVariable");

        // Edge types
        created += edgeIfAbsent(schema, "READS_FROM");
        created += edgeIfAbsent(schema, "WRITES_TO");
        created += edgeIfAbsent(schema, "CONTAINS_STMT");
        created += edgeIfAbsent(schema, "CONTAINS_ROUTINE");
        created += edgeIfAbsent(schema, "CONTAINS_TABLE");
        created += edgeIfAbsent(schema, "HAS_COLUMN");
        created += edgeIfAbsent(schema, "CHILD_OF");
        created += edgeIfAbsent(schema, "CALLS");
        created += edgeIfAbsent(schema, "DEPENDS_ON");
        created += edgeIfAbsent(schema, "BELONGS_TO_SESSION");

        if (created > 0) logger.info("ArcadeDB schema: created {} types", created);
    }

    private static int vertexIfAbsent(Schema s, String n) {
        if (!s.existsType(n)) { s.createVertexType(n); return 1; } return 0;
    }
    private static int documentIfAbsent(Schema s, String n) {
        if (!s.existsType(n)) { s.createDocumentType(n); return 1; } return 0;
    }
    private static int edgeIfAbsent(Schema s, String n) {
        if (!s.existsType(n)) { s.createEdgeType(n); return 1; } return 0;
    }
}