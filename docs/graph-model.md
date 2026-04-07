---
config:
  layout: elk
---
erDiagram
    DaliApplication {
        string app_geoid   PK
        string app_name
        long   created_at
    }

    DaliDatabase {
        string db_geoid    PK
        string db_name
        long   created_at
    }

    DaliSchema {
        string schema_geoid
        string schema_name
        string db_name
        string db_geoid
        string session_id
    }

    DaliTable {
        string table_geoid
        string table_name
        string table_type
        string schema_geoid
        string db_name
        string session_id
    }

    DaliColumn {
        string column_geoid
        string column_name
        string table_geoid
        string expression
        string alias
        bool   is_output
        int    col_order
        string db_name
        string session_id
    }

    DaliRoutine {
        string routine_geoid
        string routine_name
        string routine_type
        string package_geoid
        string schema_geoid
        string session_id
    }

    %% DaliPackage EXTENDS DaliRoutine (IS-A, ArcadeDB vertex inheritance ADR-012)
    %% Наследует все поля DaliRoutine; объявляет собственные ниже
    DaliPackage {
        string package_geoid
        string package_name
        string schema_geoid
        string session_id
    }

    DaliSession {
        string session_id   PK
        string db_name
        string file_path
        string dialect
        long   processing_time_ms
        long   created_at
    }

    DaliStatement {
        string stmt_geoid
        string type
        string subtype
        string short_name
        string snippet
        int    line_start
        int    line_end
        string parent_statement
        string routine_geoid
        bool   is_dml
        bool   is_ddl
        bool   has_aggregation
        bool   has_cte
        int    depth
        string session_id
    }

    DaliAtom {
        string atom_id
        string atom_text
        string atom_context
        string parent_context
        string status
        string table_geoid
        string table_name
        string column_name
        int    output_column_sequence
        bool   is_function_call
        bool   is_constant
        bool   is_column_reference
        string statement_geoid
        string session_id
    }

    DaliOutputColumn {
        string col_key
        string name
        string expression
        string alias
        int    col_order
        string source_type
        string table_ref
        string statement_geoid
        string session_id
    }

    DaliJoin {
        string join_type
        string source_table_geoid
        string source_alias
        string source_type
        string target_table_geoid
        string target_alias
        string target_type
        string conditions
        int    line_start
        string statement_geoid
        string session_id
    }

    DaliParameter {
        string param_name
        string param_type
        string param_mode
        string routine_geoid
        string session_id
    }

    DaliVariable {
        string var_name
        string var_type
        string routine_geoid
        string session_id
    }

    DaliAffectedColumn {
        string column_ref
        string column_name
        string expression
        string alias
        string table_geoid
        string dataset_alias
        string source_type
        string resolution_status
        string type_affect
        int    order_affect
        string statement_geoid
        string session_id
    }

    %% ── Document types (не vertex, граф-навигация не используется) ──────
    DaliSnippet {
        string session_id
        string stmt_geoid
        string snippet
        string snippet_hash
    }

    DaliPerfStats {
        string session_id
        string db_name
        long   ms_parse
        long   ms_walk
        long   ms_resolve
        long   ms_write_vtx
        long   ms_write_edge
        long   ms_total
        int    cnt_tables
        int    cnt_routines
        int    cnt_statements
        int    cnt_atoms
        int    cnt_atoms_resolved
    }

    DaliSchemaLog {
        string session_id
        string schema_name
        string reason
        string backtrace
    }

    DaliResolutionLog {
        string session_id
        string file_path
        string statement_geoid
        string raw_input
        string result_kind
        string atom_context
        string parent_context
        string note
        string strategy
        string table_name
        string column_name
    }

    %% ── Namespace hierarchy ──────────────────────────────────────────────
    DaliApplication ||--o{ DaliDatabase    : "BELONGS_TO_APP"
    DaliDatabase    ||--o{ DaliSchema      : "CONTAINS_SCHEMA"
    DaliSchema      ||--o{ DaliTable       : "CONTAINS_TABLE"
    DaliTable       ||--o{ DaliColumn      : "HAS_COLUMN"

    %% ── Routine hierarchy ────────────────────────────────────────────────
    DaliSchema      ||--o{ DaliPackage     : "CONTAINS_ROUTINE"
    DaliSchema      ||--o{ DaliRoutine     : "CONTAINS_ROUTINE (standalone)"
    DaliPackage     ||--o{ DaliRoutine     : "CONTAINS_ROUTINE"
    DaliRoutine     ||--o{ DaliRoutine     : "NESTED_IN (parent->child)"
    DaliRoutine     ||--o{ DaliRoutine     : "CALLS"

    %% ── Session ──────────────────────────────────────────────────────────
    DaliSession     ||--o{ DaliRoutine     : "BELONGS_TO_SESSION"
    DaliSession     ||--o{ DaliTable       : "BELONGS_TO_SESSION (ad-hoc)"

    %% ── Routine -> Statement / Parameter / Variable ──────────────────────
    DaliRoutine     ||--o{ DaliStatement   : "CONTAINS_STMT"
    DaliRoutine     ||--o{ DaliParameter   : "HAS_PARAMETER"
    DaliRoutine     ||--o{ DaliVariable    : "HAS_VARIABLE"

    %% ── Statement graph ──────────────────────────────────────────────────
    DaliStatement   ||--o{ DaliStatement      : "CHILD_OF (child->parent)"
    DaliStatement   ||--o{ DaliStatement      : "USES_SUBQUERY"
    DaliStatement   ||--o{ DaliTable          : "READS_FROM"
    DaliStatement   ||--o{ DaliTable          : "WRITES_TO"
    DaliStatement   ||--o{ DaliOutputColumn   : "HAS_OUTPUT_COL"
    DaliStatement   ||--o{ DaliAtom           : "HAS_ATOM"
    DaliStatement   ||--o{ DaliJoin           : "HAS_JOIN"
    DaliStatement   ||--o{ DaliAffectedColumn : "HAS_AFFECTED_COL"

    %% ── Join ─────────────────────────────────────────────────────────────
    DaliJoin        ||--o{ DaliTable       : "JOIN_SOURCE_TABLE"
    DaliJoin        ||--o{ DaliTable       : "JOIN_TARGET_TABLE"

    %% ── Atom refs ────────────────────────────────────────────────────────
    DaliAtom        ||--o{ DaliTable          : "ATOM_REF_TABLE"
    DaliAtom        ||--o{ DaliColumn         : "ATOM_REF_COLUMN"
    DaliAtom        ||--o{ DaliStatement      : "ATOM_REF_STMT (subquery src)"
    DaliAtom        ||--o{ DaliOutputColumn   : "ATOM_REF_OUTPUT_COL (subquery col)"
    DaliAtom        ||--o{ DaliOutputColumn   : "ATOM_PRODUCES"

    %% ── Column-level lineage ─────────────────────────────────────────────
    DaliColumn      ||--o{ DaliOutputColumn   : "DATA_FLOW"
    DaliColumn      ||--o{ DaliStatement      : "FILTER_FLOW"

    %% ── AffectedColumn ───────────────────────────────────────────────────
    DaliAffectedColumn ||--o{ DaliTable       : "AFFECTED_COL_REF_TABLE"
    DaliAffectedColumn ||--o{ DaliColumn      : "AFFECTED_COL_REF_COLUMN"
