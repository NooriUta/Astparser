#!/bin/bash
# =============================================================================
# bench_remote_batch.sh — Сравнение REMOTE vs REMOTE_BATCH на корпусе SQL-файлов
# =============================================================================
# Использование:
#   ./scripts/bench_remote_batch.sh [/path/to/sql/dir]
#
# Требования:
#   - ArcadeDB запущен: docker run -d --name arcade \
#       -p 2480:2480 \
#       -e ARCADEDB_SERVER_ROOT_PASSWORD=playwithdata \
#       arcadedata/arcadedb:latest
#   - hound.jar собран: ./gradlew shadowJar

set -euo pipefail

INPUT_DIR="${1:-C:/AIDA/Dali4/HOUND/test_plsql}"
HOST="localhost"
PORT=2480
DB="hound_bench"
USER="root"
PASS="playwithdata"
JAR="$(ls build/libs/*-all.jar 2>/dev/null | head -1)"

# ─── Проверки ────────────────────────────────────────────────────────────────

if [[ -z "$JAR" ]]; then
    echo "ERROR: hound.jar не найден. Запустите: ./gradlew shadowJar"
    exit 1
fi

if [[ ! -d "$INPUT_DIR" ]]; then
    echo "ERROR: директория не существует: $INPUT_DIR"
    echo "Использование: $0 /path/to/sql/dir"
    exit 1
fi

SQL_COUNT=$(find "$INPUT_DIR" -name "*.sql" -o -name "*.pck" -o -name "*.pkb" -o -name "*.pks" 2>/dev/null | wc -l)
if [[ "$SQL_COUNT" -eq 0 ]]; then
    echo "ERROR: SQL-файлы не найдены в: $INPUT_DIR"
    exit 1
fi

# ─── Проверка ArcadeDB ───────────────────────────────────────────────────────

echo "Проверка ArcadeDB на $HOST:$PORT ..."
if ! curl -s -u "$USER:$PASS" "http://$HOST:$PORT/api/v1/ready" > /dev/null 2>&1; then
    echo "ERROR: ArcadeDB недоступен на $HOST:$PORT"
    echo "Запустите: docker run -d --name arcade -p 2480:2480 \\"
    echo "           -e ARCADEDB_SERVER_ROOT_PASSWORD=$PASS \\"
    echo "           arcadedata/arcadedb:latest"
    exit 1
fi
echo "ArcadeDB OK"

# ─── Создание баз данных ─────────────────────────────────────────────────────

DB_REMOTE="${DB}_remote"
DB_BATCH="${DB}_batch"

for db in "$DB_REMOTE" "$DB_BATCH"; do
    curl -s -u "$USER:$PASS" -X POST "http://$HOST:$PORT/api/v1/create/$db" > /dev/null 2>&1 || true
done

echo "Базы данных готовы: $DB_REMOTE | $DB_BATCH"
echo "Входные файлы:      $SQL_COUNT шт. в $INPUT_DIR"
echo "JAR:                $JAR"
echo ""

# ─── Функция запуска + замера ─────────────────────────────────────────────────

run_mode() {
    local mode_label="$1"
    local db_name="$2"
    shift 2
    local extra_args=("$@")

    echo "═══════════════════════════════════════════════════"
    echo " РЕЖИМ: $mode_label  →  db: $db_name"
    echo "═══════════════════════════════════════════════════"

    local t0
    t0=$(date +%s%3N)

    java -jar "$JAR" \
        --input "$INPUT_DIR" \
        --db-type arcadedb \
        --db-host "$HOST" \
        --db-port "$PORT" \
        --db-name "$db_name" \
        --db-user "$USER" \
        --db-password "$PASS" \
        --clean \
        "${extra_args[@]}" \
        2>&1 | grep -E "write\.|ArcadeDB|Batch|RID|ERROR|WARN"

    local t1
    t1=$(date +%s%3N)
    local elapsed=$(( t1 - t0 ))

    echo ""
    echo "Итого: ${elapsed}ms ($(( elapsed / 1000 ))s)"

    # Подсчёт записей в БД
    echo ""
    echo "Записи в ArcadeDB ($db_name):"
    for type in DaliSession DaliTable DaliColumn DaliStatement DaliRoutine DaliAtom DaliOutputColumn DaliJoin DaliSnippet; do
        local cnt
        cnt=$(curl -s -u "$USER:$PASS" \
            -X POST "http://$HOST:$PORT/api/v1/command/$db_name" \
            -H "Content-Type: application/json" \
            -d "{\"language\":\"sql\",\"command\":\"SELECT count(*) AS cnt FROM $type\"}" \
            2>/dev/null | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['result'][0]['cnt'])" 2>/dev/null || echo "?")
        printf "  %-22s %s\n" "$type" "$cnt"
    done
    echo ""

    echo "$elapsed"
}

# ─── Запуск обоих режимов ────────────────────────────────────────────────────

echo "Старт бенчмарка: $(date)"
echo ""

t_remote=$(run_mode "REMOTE (N запросов/файл)" "$DB_REMOTE")
t_batch=$(run_mode  "REMOTE_BATCH (1 POST/файл)" "$DB_BATCH" "--db-batch")

# ─── Итоговое сравнение ──────────────────────────────────────────────────────

echo "═══════════════════════════════════════════════════"
echo " ИТОГ"
echo "═══════════════════════════════════════════════════"
printf " REMOTE:       %6s ms\n" "$t_remote"
printf " REMOTE_BATCH: %6s ms\n" "$t_batch"

if [[ "$t_remote" -gt 0 && "$t_batch" -gt 0 ]]; then
    speedup=$(python3 -c "print(f'{$t_remote / $t_batch:.2f}x')" 2>/dev/null || echo "?")
    echo " Ускорение:    $speedup"
fi

echo "═══════════════════════════════════════════════════"
echo "Конец: $(date)"
