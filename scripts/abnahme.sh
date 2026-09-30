#!/bin/bash
# Abnahmeskript fuer den batch-writer: stellt die Szenarien S2 bis S8 aus
# docs/spec-batch-writer.md (Abschnitt 5) nach und schreibt zu jedem den gemessenen Wert neben den
# erwarteten. Ein Szenario ist bestanden oder nicht bestanden, dazwischen gibt es nichts.
#
# Aufruf im Wurzelverzeichnis des Repositories:
#   cp .env.example .env
#   docker compose up -d --build
#   scripts/abnahme.sh              # S2 bis S8
#   scripts/abnahme.sh --mit-tests  # zusaetzlich S1 (mvn clean test, dauert einige Minuten)
#
# Die Szenarien laufen in dieser Reihenfolge auf demselben Stack, ohne Aufraeumen dazwischen.
# Zaehlt wird immer die Differenz zu vorher, deshalb darf der Stack schon Nachrichten enthalten.

cd "$(dirname "$0")/.." || exit 1

if [ ! -f .env ]; then
    echo "Datei .env fehlt. Zuerst:  cp .env.example .env"
    exit 1
fi

# Die Zugangsdaten aus .env in die Umgebung dieses Skripts laden (set -a exportiert alles).
set -a
. ./.env
set +a

ROOM_ID="11111111-1111-1111-1111-111111111111"
SENDER="pruefung"

RESULTS=()
FAILED=0

# ---------------------------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------------------------

# Fuehrt eine SQL-Abfrage in der Datenbank aus und gibt nur den Wert zurueck (ohne Kopfzeile).
sql() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"
}

# Zaehlt die Nachrichten dieser Pruefung in der Tabelle.
count_rows() {
    sql "SELECT count(*) FROM message WHERE sender='$SENDER'"
}

# Gibt zurueck, wie viele Nachrichten (wartende und unbestaetigte) in einer Queue liegen.
queue_messages() {
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk -v name="$1" '$1 == name { print $2 }'
}

# Gibt zurueck, wie viele Konsumenten an einer Queue haengen.
queue_consumers() {
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues name consumers | awk -v name="$1" '$1 == name { print $2 }'
}

# Zaehler der Transaktionen, die die Datenbank bisher bestaetigt hat.
commits() {
    sql "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()"
}

# Sendet N Nachrichten ueber den chat-service, von INNEN (kein Port ist veroeffentlicht).
# Alles laeuft in EINEM docker exec. Gibt zurueck, wie viele mit 202 angenommen wurden.
send_messages() {
    docker compose exec -T chat-service sh -s -- "$1" "$ROOM_ID" "$SENDER" <<'INNER'
count="$1"
room="$2"
sender="$3"
accepted=0
number=1
while [ "$number" -le "$count" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/messages \
        -H 'Content-Type: application/json' \
        -d "{\"roomId\":\"$room\",\"sender\":\"$sender\",\"text\":\"Nr. $number\"}")
    if [ "$code" = "202" ]; then
        accepted=$((accepted + 1))
    fi
    number=$((number + 1))
done
echo "$accepted"
INNER
}

# Wartet, bis die Zahl der Nachrichten dieser Pruefung den Zielwert erreicht hat, hoechstens N Sekunden.
# Fragt alle 2 Sekunden. Der zuletzt gelesene Wert bleibt in LAST_COUNT.
wait_for_rows() {
    local target="$1"
    local limit="$2"
    local start
    start=$(date +%s)
    while true; do
        LAST_COUNT=$(count_rows)
        if [ "$LAST_COUNT" = "$target" ]; then
            return 0
        fi
        if [ $(( $(date +%s) - start )) -ge "$limit" ]; then
            return 1
        fi
        sleep 2
    done
}

# Wartet, bis eine Queue leer ist (RabbitMQ rechnet die Zahl nur alle paar Sekunden neu), hoechstens N Sekunden.
wait_for_empty_queue() {
    local queue="$1"
    local limit="$2"
    local start
    start=$(date +%s)
    while true; do
        LAST_QUEUE=$(queue_messages "$queue")
        if [ "$LAST_QUEUE" = "0" ]; then
            return 0
        fi
        if [ $(( $(date +%s) - start )) -ge "$limit" ]; then
            return 1
        fi
        sleep 2
    done
}

# Wartet, bis die Queue chat.persist die erwartete Zahl Konsumenten hat, hoechstens N Sekunden.
wait_for_consumers() {
    local target="$1"
    local limit="$2"
    local start
    start=$(date +%s)
    while true; do
        LAST_CONSUMERS=$(queue_consumers chat.persist)
        if [ "$LAST_CONSUMERS" = "$target" ]; then
            return 0
        fi
        if [ $(( $(date +%s) - start )) -ge "$limit" ]; then
            return 1
        fi
        sleep 2
    done
}

# Legt eine Nachricht von Hand in eine Queue, so wie ein Testskript es taete: ueber die Management-API,
# nur mit dem Header content_type = application/json und OHNE __TypeId__. Der Aufruf kommt aus dem
# chat-service-Container, denn nur dort ist curl und das interne Netz erreichbar.
publish_by_hand() {
    local body="$1"
    docker compose exec -T chat-service curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
        -H 'content-type: application/json' -X POST \
        http://rabbitmq:15672/api/exchanges/%2F/amq.default/publish \
        -d "{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"chat.persist\",\"payload\":$body,\"payload_encoding\":\"string\"}" \
        > /dev/null
}

# Haelt das Ergebnis eines Szenarios fest: Nummer, bestanden oder nicht, gemessen und erwartet.
record() {
    local id="$1"
    local passed="$2"
    local measured="$3"
    local expected="$4"
    local verdict="BESTANDEN"
    if [ "$passed" != "yes" ]; then
        verdict="NICHT BESTANDEN"
        FAILED=$((FAILED + 1))
    fi
    RESULTS+=("$id|$verdict|$measured|$expected")
    echo "  -> $id: $verdict   gemessen: $measured   erwartet: $expected"
}

# ---------------------------------------------------------------------------------------------
# S1  mvn clean test (nur mit --mit-tests)
# ---------------------------------------------------------------------------------------------
if [ "$1" = "--mit-tests" ]; then
    echo "S1  mvn clean test"
    if mvn -B clean test > /tmp/abnahme-s1.log 2>&1; then
        record S1 yes "BUILD SUCCESS" "BUILD SUCCESS, 0 Fehler"
    else
        record S1 no "Fehlschlag, siehe /tmp/abnahme-s1.log" "BUILD SUCCESS, 0 Fehler"
    fi
fi

# ---------------------------------------------------------------------------------------------
# S2  Alle Dienste laufen, kein Port veroeffentlicht
# ---------------------------------------------------------------------------------------------
echo "S2  Dienste und Ports"
RUNNING=$(docker compose ps --status running --format '{{.Service}}' | sort -u | tr '\n' ' ')
PUBLISHED=$(docker compose ps --format '{{.Ports}}' | grep -c -- '->')
S2_OK=no
if [ "$RUNNING" = "batch-writer chat-service postgres rabbitmq " ] && [ "$PUBLISHED" = "0" ]; then
    S2_OK=yes
fi
record S2 "$S2_OK" "laufen: ${RUNNING}/ veroeffentlichte Ports: $PUBLISHED" "4 Dienste, 0 veroeffentlichte Ports"

# ---------------------------------------------------------------------------------------------
# S3  1000 Nachrichten, alle nach hoechstens 60 s in der Tabelle, Queue leer
# ---------------------------------------------------------------------------------------------
echo "S3  1000 Nachrichten senden"
BEFORE=$(count_rows)
START=$(date +%s)
ACCEPTED=$(send_messages 1000)
SENT_AFTER=$(( $(date +%s) - START ))
S3_OK=no
if wait_for_rows $((BEFORE + 1000)) 60; then
    S3_OK=yes
fi
TOTAL_SECONDS=$(( $(date +%s) - START ))
wait_for_empty_queue chat.persist 20
if [ "$LAST_QUEUE" != "0" ]; then
    S3_OK=no
fi
record S3 "$S3_OK" "angenommen $ACCEPTED, neu in Tabelle $((LAST_COUNT - BEFORE)), Senden ${SENT_AFTER}s, alles da nach ${TOTAL_SECONDS}s, Queue $LAST_QUEUE" \
    "1000 in der Tabelle nach hoechstens 60s, Queue 0"

# ---------------------------------------------------------------------------------------------
# S4  Schreiber gestoppt, 1000 gesendet, gestartet: nichts verloren, hoechstens 100 Transaktionen
# ---------------------------------------------------------------------------------------------
echo "S4  batch-writer gestoppt, 1000 senden, wieder starten"
docker compose stop batch-writer > /dev/null 2>&1
BEFORE=$(count_rows)
ACCEPTED=$(send_messages 1000)
sleep 3
COMMITS_BEFORE=$(commits)
docker compose start batch-writer > /dev/null 2>&1
S4_OK=no
if wait_for_rows $((BEFORE + 1000)) 90; then
    S4_OK=yes
fi
sleep 3
COMMITS_AFTER=$(commits)
COMMIT_DIFFERENCE=$((COMMITS_AFTER - COMMITS_BEFORE))
if [ "$COMMIT_DIFFERENCE" -gt 100 ]; then
    S4_OK=no
fi
record S4 "$S4_OK" "angenommen $ACCEPTED, neu in Tabelle $((LAST_COUNT - BEFORE)), Transaktionen $COMMIT_DIFFERENCE" \
    "genau 1000 neu, hoechstens 100 Transaktionen"

# ---------------------------------------------------------------------------------------------
# S5  Dieselbe Nachricht zweimal direkt in chat.persist, nur mit content_type
# ---------------------------------------------------------------------------------------------
echo "S5  Duplikat"
MESSAGE_ID=$(docker compose exec -T chat-service cat /proc/sys/kernel/random/uuid | tr -d '\r\n')
SENT_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY="\"{\\\"id\\\":\\\"$MESSAGE_ID\\\",\\\"roomId\\\":\\\"$ROOM_ID\\\",\\\"sender\\\":\\\"duplikat\\\",\\\"text\\\":\\\"zweimal gesendet\\\",\\\"sentAt\\\":\\\"$SENT_AT\\\"}\""
DLQ_BEFORE=$(queue_messages chat.dlq)
publish_by_hand "$BODY"
publish_by_hand "$BODY"
sleep 5
wait_for_empty_queue chat.persist 20
ROWS_FOR_ID=$(sql "SELECT count(*) FROM message WHERE id = '$MESSAGE_ID'")
DLQ_AFTER=$(queue_messages chat.dlq)
S5_OK=no
if [ "$ROWS_FOR_ID" = "1" ] && [ "$DLQ_AFTER" = "$DLQ_BEFORE" ]; then
    S5_OK=yes
fi
record S5 "$S5_OK" "Zeilen mit dieser ID: $ROWS_FOR_ID, chat.dlq vorher $DLQ_BEFORE nachher $DLQ_AFTER" \
    "genau 1 Zeile, chat.dlq unveraendert"

# ---------------------------------------------------------------------------------------------
# S6  Zwei Instanzen, 1000 Nachrichten, alle da, keine doppelt
# ---------------------------------------------------------------------------------------------
echo "S6  zwei Instanzen"
docker compose up -d --scale batch-writer=2 > /dev/null 2>&1
S6_OK=no
wait_for_consumers 2 90
CONSUMERS="$LAST_CONSUMERS"
BEFORE=$(count_rows)
ACCEPTED=$(send_messages 1000)
if wait_for_rows $((BEFORE + 1000)) 90 && [ "$CONSUMERS" = "2" ]; then
    S6_OK=yes
fi
TOTAL_ROWS=$(sql "SELECT count(*) FROM message WHERE sender='$SENDER'")
DISTINCT_ROWS=$(sql "SELECT count(DISTINCT id) FROM message WHERE sender='$SENDER'")
if [ "$TOTAL_ROWS" != "$DISTINCT_ROWS" ]; then
    S6_OK=no
fi
record S6 "$S6_OK" "Konsumenten $CONSUMERS, angenommen $ACCEPTED, neu $((LAST_COUNT - BEFORE)), Zeilen $TOTAL_ROWS davon verschieden $DISTINCT_ROWS" \
    "2 Konsumenten, 1000 neu, Zeilen = verschiedene IDs"

# ---------------------------------------------------------------------------------------------
# S7  Datenbank faellt 15 s aus, 300 Nachrichten, alle nach hoechstens 90 s da, ohne Neustart
# ---------------------------------------------------------------------------------------------
echo "S7  Datenbank faellt aus"
WRITER_IDS=$(docker compose ps -q batch-writer)
STARTS_BEFORE=""
for id in $WRITER_IDS; do
    STARTS_BEFORE="$STARTS_BEFORE$(docker inspect -f '{{.State.StartedAt}}' "$id") "
done
BEFORE=$(count_rows)
docker compose stop postgres > /dev/null 2>&1
ACCEPTED=$(send_messages 300)
sleep 15
docker compose start postgres > /dev/null 2>&1
RESTART_MOMENT=$(date +%s)
S7_OK=no
# Solange die Datenbank startet, schlaegt die Abfrage fehl. Das ist beim Warten gewollt.
while [ $(( $(date +%s) - RESTART_MOMENT )) -lt 90 ]; do
    LAST_COUNT=$(count_rows 2> /dev/null)
    if [ "$LAST_COUNT" = "$((BEFORE + 300))" ]; then
        S7_OK=yes
        break
    fi
    sleep 2
done
SECONDS_AFTER_RESTART=$(( $(date +%s) - RESTART_MOMENT ))
STARTS_AFTER=""
for id in $WRITER_IDS; do
    STARTS_AFTER="$STARTS_AFTER$(docker inspect -f '{{.State.StartedAt}}' "$id") "
done
RESTARTED="nein"
if [ "$STARTS_BEFORE" != "$STARTS_AFTER" ]; then
    RESTARTED="JA"
    S7_OK=no
fi
NEW_ROWS=$(( ${LAST_COUNT:-0} - BEFORE ))
record S7 "$S7_OK" "angenommen $ACCEPTED, neu $NEW_ROWS, alles da ${SECONDS_AFTER_RESTART}s nach dem Neustart, batch-writer neu gestartet: $RESTARTED" \
    "300 neu nach hoechstens 90s, kein Neustart des batch-writer"

# ---------------------------------------------------------------------------------------------
# S8  Quelltext: keine Streams, Kommentare, .env nicht im Repo
# ---------------------------------------------------------------------------------------------
echo "S8  Quelltext"
STREAM_HITS=$(grep -rnE '\.stream\(|Stream<|[^a-zA-Z]Stream\.' batch-writer/src | wc -l | tr -d ' ')
ENV_TRACKED=$(git ls-files .env | wc -l | tr -d ' ')
COMMENT_STATE="uebersprungen (mvn fehlt)"
COMMENT_OK=yes
if command -v mvn > /dev/null 2>&1; then
    if mvn -B -q -pl batch-writer test -Dtest=CommentRulesTest > /tmp/abnahme-s8.log 2>&1; then
        COMMENT_STATE="CommentRulesTest gruen"
    else
        COMMENT_STATE="CommentRulesTest ROT (siehe /tmp/abnahme-s8.log)"
        COMMENT_OK=no
    fi
fi
S8_OK=no
if [ "$STREAM_HITS" = "0" ] && [ "$ENV_TRACKED" = "0" ] && [ "$COMMENT_OK" = "yes" ]; then
    S8_OK=yes
fi
record S8 "$S8_OK" "Stream-Treffer $STREAM_HITS, .env im Repo $ENV_TRACKED, $COMMENT_STATE" \
    "0 Treffer, 0, Test gruen"

# ---------------------------------------------------------------------------------------------
# Zusammenfassung
# ---------------------------------------------------------------------------------------------
echo
echo "================ Ergebnis ================"
for line in "${RESULTS[@]}"; do
    IFS='|' read -r id verdict measured expected <<< "$line"
    echo "$id  $verdict"
    echo "    gemessen: $measured"
    echo "    erwartet: $expected"
done
echo "=========================================="
if [ "$FAILED" = "0" ]; then
    echo "Alle Szenarien bestanden."
    exit 0
fi
echo "$FAILED Szenario(en) nicht bestanden."
exit 1
