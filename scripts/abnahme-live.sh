#!/bin/bash
# Abnahmeskript fuer die Live-Zustellung: stellt die Szenarien S1 bis S8 und Z1, Z2 aus
# docs/spec-live-zustellung.md (Abschnitt 4) nach und schreibt zu jedem den gemessenen Wert neben den
# erwarteten, dazu OK oder FEHLER. Am Schluss kommt eine Zusammenfassung. Der Exit-Code ist nur 0,
# wenn alles bestanden ist.
#
# Aufruf im Wurzelverzeichnis des Repositories (Docker, Maven und git muessen installiert sein):
#   cp .env.example .env
#   scripts/abnahme-live.sh
#
# Das Skript startet den Stack selbst mit zwei Instanzen des chat-service. Es dauert etwa 20 Minuten,
# die meiste Zeit brauchen S1 (alle Tests) und S2 (frischer Klon, alles neu bauen).
# Die Messdateien (a1.txt, s5.txt, ...) landen in einem temporaeren Ordner, nicht im Repository.

cd "$(dirname "$0")/.." || exit 1

if [ ! -f .env ]; then
    echo "Datei .env fehlt. Zuerst:  cp .env.example .env"
    exit 1
fi

# Die Zugangsdaten aus .env in die Umgebung dieses Skripts laden (set -a exportiert alles).
set -a
. ./.env
set +a

RAUM_A=11111111-1111-1111-1111-111111111111
RAUM_B=22222222-2222-2222-2222-222222222222
STREAM="http://localhost:8080/stream?roomId=$RAUM_A"

# Ordner fuer die Messdateien. mktemp waehlt einen freien Namen.
WORK=$(mktemp -d)
echo "Messdateien liegen in $WORK"

RESULTS=()
FAILED=0

# ---------------------------------------------------------------------------------------------
# Hilfsfunktionen aus der Spec (Abschnitt 4)
# ---------------------------------------------------------------------------------------------

# SQL in der Datenbank ausfuehren, nur der Wert kommt zurueck.
sql() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"
}

# INSTANZ RAUM ABSENDER TEXT: eine Nachricht von innen senden, gibt den HTTP-Status aus.
send_message() {
    docker exec "$1" curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/messages \
        -H 'Content-Type: application/json' -d "{\"roomId\":\"$2\",\"sender\":\"$3\",\"text\":\"$4\"}"
}

# INSTANZ RAUM ABSENDER ANZAHL: Texte 1 bis ANZAHL, alles in einem docker exec.
# Jedes curl wartet auf seine Antwort, bevor das naechste startet. So ist die Reihenfolge pro Absender fest.
send_many() {
    docker exec "$1" sh -c 'i=1; while [ "$i" -le "$4" ]; do
        curl -s -o /dev/null -X POST localhost:8080/api/messages -H "Content-Type: application/json" \
            -d "{\"roomId\":\"$2\",\"sender\":\"$3\",\"text\":\"$i\"}"
        i=$((i + 1))
    done' _ "$@"
}

# INSTANZ RAUM SEKUNDEN DATEI: einen Stream oeffnen und alles in die Datei schreiben, im Hintergrund.
listen() {
    docker exec "$1" curl -sN --max-time "$3" "http://localhost:8080/stream?roomId=$2" > "$4" &
}

# DATEI ABSENDER: die Texte dieses Absenders in der Reihenfolge ihrer Ankunft, mit Leerzeichen getrennt.
texts_of() {
    grep "\"sender\":\"$2\"" "$1" | grep -o '"text":"[^"]*"' | cut -d'"' -f4 | paste -sd' '
}

# Die Live-Queues: Name, wartende Nachrichten, Leser.
queues() {
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready consumers | grep '^chat\.live\.'
}

# ---------------------------------------------------------------------------------------------
# Weitere Hilfsfunktionen des Skripts
# ---------------------------------------------------------------------------------------------

# Wartet, bis genau so viele chat-service-Instanzen laufen und gesund sind, hoechstens 180 Sekunden.
# Ohne dieses Warten wuerde listen eine Instanz fragen, die noch startet, und curl bekaeme keine Antwort.
wait_for_instances() {
    local target="$1"
    local start
    start=$(date +%s)
    while true; do
        local total
        local healthy
        total=$(docker compose ps -q chat-service | wc -l | tr -d ' ')
        healthy=$(docker compose ps chat-service --format '{{.Health}}' | grep -c '^healthy$')
        if [ "$total" = "$target" ] && [ "$healthy" = "$target" ]; then
            return 0
        fi
        if [ $(( $(date +%s) - start )) -ge 180 ]; then
            echo "  Warnung: nach 180 s laufen $total Instanzen, davon $healthy gesund"
            return 1
        fi
        sleep 2
    done
}

# Bestimmt A und B neu: die erste und die zweite chat-service-Instanz.
pick_instances() {
    A=$(docker compose ps -q chat-service | sed -n 1p)
    B=$(docker compose ps -q chat-service | sed -n 2p)
}

# Haelt das Ergebnis eines Szenarios fest: Nummer, bestanden (yes) oder nicht, gemessen und erwartet.
record() {
    local id="$1"
    local passed="$2"
    local measured="$3"
    local expected="$4"
    local verdict="OK"
    if [ "$passed" != "yes" ]; then
        verdict="FEHLER"
        FAILED=$((FAILED + 1))
    fi
    RESULTS+=("$id|$verdict|$measured|$expected")
    echo "  -> $id: $verdict   gemessen: $measured   erwartet: $expected"
}

# ---------------------------------------------------------------------------------------------
# S1  Alle Tests gruen
# ---------------------------------------------------------------------------------------------
echo "S1  mvn clean test (dauert einige Minuten)"
S1_OK=no
if mvn -B clean test > "$WORK/s1.log" 2>&1; then
    S1_OK=yes
fi
# Die Zusammenfassungen pro Modul ("Tests run: ..." ohne "-- in") zusammenzaehlen.
S1_TOTALS=$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run:' "$WORK/s1.log" | grep -v -- ' -- in ' \
    | awk -F'[:,]' '{ run += $2; failed += $4 + $6 } END { print run " Tests, " failed " Fehler" }')
S1_BUILD=$(grep -oE 'BUILD (SUCCESS|FAILURE)' "$WORK/s1.log" | tail -1)
record S1 "$S1_OK" "$S1_BUILD, $S1_TOTALS" "BUILD SUCCESS, 0 Fehler"

# ---------------------------------------------------------------------------------------------
# S2  Frischer Klon startet, kein Port veroeffentlicht
# ---------------------------------------------------------------------------------------------
echo "S2  frischer Klon in $WORK/s2-klon (baut alles neu)"
# Der Ordner heisst bewusst nicht wie das Repository. Compose benennt den Stack nach dem Ordner,
# so kommt sich der Stack des Klons nicht mit dem Stack der anderen Szenarien in die Quere.
ORIGIN=$(git remote get-url origin)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
git clone -q --branch "$BRANCH" "$ORIGIN" "$WORK/s2-klon"
(
    cd "$WORK/s2-klon" || exit 1
    cp .env.example .env
    docker compose up -d --build > "$WORK/s2-build.log" 2>&1
    docker compose ps --status running --format '{{.Service}}' | sort | tr '\n' ' ' > "$WORK/s2-running.txt"
    docker compose ps --format '{{.Ports}}' | grep -c -- '->' > "$WORK/s2-ports.txt"
    # Der Stack des Klons wird nicht mehr gebraucht. -v entfernt auch seine Volumes.
    docker compose down -v > /dev/null 2>&1
)
S2_RUNNING=$(cat "$WORK/s2-running.txt")
S2_PORTS=$(cat "$WORK/s2-ports.txt")
S2_OK=no
if [ "$S2_RUNNING" = "batch-writer chat-service postgres rabbitmq " ] && [ "$S2_PORTS" = "0" ]; then
    S2_OK=yes
fi
record S2 "$S2_OK" "laufen: ${S2_RUNNING}/ Zeilen mit ->: $S2_PORTS" "postgres, rabbitmq, chat-service, batch-writer; keine Zeile mit ->"

# ---------------------------------------------------------------------------------------------
# Vorbereitung ab S3: Stack mit zwei Instanzen, Raum B anlegen
# ---------------------------------------------------------------------------------------------
echo "Vorbereitung: Stack mit zwei Instanzen starten"
docker compose up -d --build --scale chat-service=2 > "$WORK/stack-build.log" 2>&1
wait_for_instances 2
pick_instances
# ON CONFLICT: laeuft das Skript ein zweites Mal auf demselben Stack, gibt es Raum B schon.
sql "INSERT INTO room VALUES ('$RAUM_B', 'Raum B', 'pruefung', now()) ON CONFLICT (id) DO NOTHING" > /dev/null

# ---------------------------------------------------------------------------------------------
# S3  Raum und Reihenfolge
# ---------------------------------------------------------------------------------------------
echo "S3  Raum und Reihenfolge (2 x 200 Nachrichten)"
listen "$A" "$RAUM_A" 20 "$WORK/a1.txt"
listen "$B" "$RAUM_A" 20 "$WORK/a2.txt"
listen "$A" "$RAUM_B" 20 "$WORK/b.txt"
sleep 1
# Beide Absender gleichzeitig, jeder ueber eine andere Instanz.
send_many "$A" "$RAUM_A" s3-a 200 &
send_many "$B" "$RAUM_A" s3-b 200 &
wait
S3_A1=$(grep -c '^data:' "$WORK/a1.txt")
S3_A2=$(grep -c '^data:' "$WORK/a2.txt")
S3_B=$(grep -c '^data:' "$WORK/b.txt")
S3_EXPECTED_ORDER=$(seq -s ' ' 1 200)
S3_ORDER_OK=0
for file in a1 a2; do
    for sender in s3-a s3-b; do
        if [ "$(texts_of "$WORK/$file.txt" "$sender")" = "$S3_EXPECTED_ORDER" ]; then
            S3_ORDER_OK=$((S3_ORDER_OK + 1))
        fi
    done
done
S3_OK=no
if [ "$S3_A1" = "400" ] && [ "$S3_A2" = "400" ] && [ "$S3_B" = "0" ] && [ "$S3_ORDER_OK" = "4" ]; then
    S3_OK=yes
fi
record S3 "$S3_OK" "a1.txt:$S3_A1 a2.txt:$S3_A2 b.txt:$S3_B, Reihenfolge 1..200 stimmt $S3_ORDER_OK von 4 Mal" \
    "a1.txt:400 a2.txt:400 b.txt:0, alle 4 Mal 1 2 ... 200"

# ---------------------------------------------------------------------------------------------
# S4  Queue verschwindet mit der Instanz
# ---------------------------------------------------------------------------------------------
echo "S4  eine Instanz weniger"
S4_BEFORE=$(queues)
docker compose up -d --scale chat-service=1 > /dev/null 2>&1
sleep 30
S4_AFTER=$(queues)
S4_BEFORE_LINES=$(echo "$S4_BEFORE" | grep -c '^chat\.live\.')
S4_BEFORE_ONE_READER=$(echo "$S4_BEFORE" | awk '$3 == 1' | grep -c '^chat\.live\.')
S4_AFTER_LINES=$(echo "$S4_AFTER" | grep -c '^chat\.live\.')
S4_OK=no
if [ "$S4_BEFORE_LINES" = "2" ] && [ "$S4_BEFORE_ONE_READER" = "2" ] && [ "$S4_AFTER_LINES" = "1" ]; then
    S4_OK=yes
fi
record S4 "$S4_OK" "vorher $S4_BEFORE_LINES Live-Queues ($S4_BEFORE_ONE_READER mit 1 Leser), danach $S4_AFTER_LINES" \
    "vorher 2 mit je 1 Leser, danach 1"

# Nach S4 wieder zwei Instanzen, A und B neu bestimmen (Spec Abschnitt 4).
docker compose up -d --scale chat-service=2 > /dev/null 2>&1
wait_for_instances 2
pick_instances

# ---------------------------------------------------------------------------------------------
# S5  Live ohne batch-writer und Datenbank
# ---------------------------------------------------------------------------------------------
echo "S5  batch-writer und Datenbank gestoppt"
# Vorher zaehlen, solange die Datenbank noch laeuft. Gezaehlt wird dann die Differenz, damit ein
# zweiter Lauf auf demselben Stack nicht an den Nachrichten des ersten scheitert.
S5_ROWS_BEFORE=$(sql "SELECT count(*) FROM message WHERE sender='s5'")
docker compose stop batch-writer postgres > /dev/null 2>&1
listen "$B" "$RAUM_A" 30 "$WORK/s5.txt"
sleep 1
send_many "$A" "$RAUM_A" s5 100
sleep 5
S5_STREAM=$(grep -c '^data:' "$WORK/s5.txt")
docker compose start postgres batch-writer > /dev/null 2>&1
S5_RESTART=$(date +%s)
S5_NEW_ROWS=0
# Solange die Datenbank startet, schlaegt die Abfrage fehl. Das ist beim Warten gewollt.
while [ $(( $(date +%s) - S5_RESTART )) -lt 90 ]; do
    S5_ROWS_NOW=$(sql "SELECT count(*) FROM message WHERE sender='s5'" 2> /dev/null)
    if [ -n "$S5_ROWS_NOW" ]; then
        S5_NEW_ROWS=$((S5_ROWS_NOW - S5_ROWS_BEFORE))
    fi
    if [ "$S5_NEW_ROWS" = "100" ]; then
        break
    fi
    sleep 2
done
S5_SECONDS=$(( $(date +%s) - S5_RESTART ))
S5_OK=no
if [ "$S5_STREAM" = "100" ] && [ "$S5_NEW_ROWS" = "100" ]; then
    S5_OK=yes
fi
record S5 "$S5_OK" "$S5_STREAM am Stream nach 5 s, $S5_NEW_ROWS in der Tabelle ${S5_SECONDS} s nach dem Start" \
    "100 am Stream, 100 in der Tabelle nach hoechstens 90 s"

# ---------------------------------------------------------------------------------------------
# S6  Angehaltene Instanz
# ---------------------------------------------------------------------------------------------
echo "S6  Instanz A angehalten, 5000 Nachrichten ueber B"
listen "$B" "$RAUM_A" 300 "$WORK/s6.txt"
sleep 1
docker pause "$A" > /dev/null
send_many "$B" "$RAUM_A" s6 5000 &
S6_SENDER=$!
S6_MAX=0
# Waehrend gesendet wird, etwa jede Sekunde die Queues ansehen und den groessten Wert festhalten.
while kill -0 "$S6_SENDER" 2> /dev/null; do
    S6_NOW=$(queues | awk 'BEGIN { max = 0 } $2 > max { max = $2 } END { print max }')
    # Klappt die Abfrage einmal nicht, zaehlt dieser Durchgang als 0.
    S6_NOW=${S6_NOW:-0}
    if [ "$S6_NOW" -gt "$S6_MAX" ]; then
        S6_MAX="$S6_NOW"
    fi
    sleep 1
done
S6_AT_B=$(grep -c '^data:' "$WORK/s6.txt")
docker unpause "$A" > /dev/null
sleep 15
S6_QUEUES=$(queues)
S6_QUEUE_LINES=$(echo "$S6_QUEUES" | grep -c '^chat\.live\.')
S6_QUEUE_ONE_READER=$(echo "$S6_QUEUES" | awk '$3 == 1' | grep -c '^chat\.live\.')
listen "$A" "$RAUM_A" 5 "$WORK/s6-a.txt"
sleep 1
send_message "$B" "$RAUM_A" s6 nachher > /dev/null
sleep 5
S6_AT_A=$(grep -c '^data:' "$WORK/s6-a.txt")
S6_OK=no
if [ "$S6_MAX" -le 1000 ] && [ "$S6_AT_B" = "5000" ] && [ "$S6_QUEUE_LINES" = "2" ] \
        && [ "$S6_QUEUE_ONE_READER" = "2" ] && [ "$S6_AT_A" = "1" ]; then
    S6_OK=yes
fi
record S6 "$S6_OK" "groesster Wert $S6_MAX, $S6_AT_B beim Client an B, danach $S6_QUEUE_LINES Live-Queues ($S6_QUEUE_ONE_READER mit 1 Leser), $S6_AT_A beim Client an A" \
    "hoechstens 1000, 5000, 2 mit je 1 Leser, 1"

# ---------------------------------------------------------------------------------------------
# S7  Nachholen
# ---------------------------------------------------------------------------------------------
echo "S7  Nachholen mit Last-Event-ID"
listen "$B" "$RAUM_A" 3 "$WORK/s7-1.txt"
sleep 1
send_message "$A" "$RAUM_A" s7 0 > /dev/null
sleep 3
LAST=$(sed -n 's/^id: *//p' "$WORK/s7-1.txt")
send_many "$A" "$RAUM_A" s7 50
sleep 10
docker exec "$B" curl -sN --max-time 10 -H "Last-Event-ID: $LAST" "$STREAM" > "$WORK/s7-2.txt" &
sleep 3
send_message "$A" "$RAUM_A" s7-live danach > /dev/null
wait $!
S7_TEXTS=$(texts_of "$WORK/s7-2.txt" s7)
S7_DOUBLES=$(sed -n 's/^id: *//p' "$WORK/s7-2.txt" | sort | uniq -d | wc -l | tr -d ' ')
S7_LIVE=$(texts_of "$WORK/s7-2.txt" s7-live)
S7_EXPECTED=$(seq -s ' ' 1 50)
S7_TEXTS_SHORT="abweichend: $S7_TEXTS"
if [ "$S7_TEXTS" = "$S7_EXPECTED" ]; then
    S7_TEXTS_SHORT="1 2 ... 50"
fi
S7_OK=no
if [ "$S7_TEXTS" = "$S7_EXPECTED" ] && [ "$S7_DOUBLES" = "0" ] && [ "$S7_LIVE" = "danach" ]; then
    S7_OK=yes
fi
record S7 "$S7_OK" "$S7_TEXTS_SHORT; doppelt $S7_DOUBLES; live: $S7_LIVE" "1 2 ... 50; 0; danach"

# ---------------------------------------------------------------------------------------------
# S8  Regeln im chat-service
# ---------------------------------------------------------------------------------------------
echo "S8  Quelltext"
S8_STREAM_HITS=$(grep -rn -e 'stream()' -e '\.stream\b' -e 'Stream<' chat-service/src | wc -l | tr -d ' ')
# Der CommentRulesTest des chat-service lief in S1 mit. Hier wird nur sein Ergebnis aus dem Log gelesen.
S8_COMMENT_LINE=$(grep 'in ch.benedict.m321.chat.CommentRulesTest' "$WORK/s1.log" | tail -1)
S8_COMMENT="nicht gefunden"
if echo "$S8_COMMENT_LINE" | grep -q 'Failures: 0, Errors: 0'; then
    S8_COMMENT="gruen"
elif [ -n "$S8_COMMENT_LINE" ]; then
    S8_COMMENT="ROT"
fi
S8_ENV=$(git ls-files .env)
S8_ENV_SHOWN="leer"
if [ -n "$S8_ENV" ]; then
    S8_ENV_SHOWN="$S8_ENV"
fi
S8_OK=no
if [ "$S8_STREAM_HITS" = "0" ] && [ "$S8_COMMENT" = "gruen" ] && [ -z "$S8_ENV" ]; then
    S8_OK=yes
fi
record S8 "$S8_OK" "$S8_STREAM_HITS Treffer; CommentRulesTest $S8_COMMENT; git ls-files .env: $S8_ENV_SHOWN" \
    "keine Treffer; Test gruen; leere Ausgabe"

# ---------------------------------------------------------------------------------------------
# Z1  Format des Ereignisses
# ---------------------------------------------------------------------------------------------
echo "Z1  Format des Ereignisses"
docker exec "$A" curl -sN -D - --max-time 5 "$STREAM" > "$WORK/z1.txt" &
sleep 1
send_message "$B" "$RAUM_A" z1 Hallo > /dev/null
wait $!
Z1_EXIT=$?
# curl schreibt die Kopfzeilen mit \r am Ende. Fuer die Pruefung wird es entfernt.
tr -d '\r' < "$WORK/z1.txt" > "$WORK/z1-ohne-cr.txt"
Z1_HEADER=$(grep -ci '^content-type: text/event-stream' "$WORK/z1-ohne-cr.txt")
Z1_ID=$(grep -c '^id:' "$WORK/z1-ohne-cr.txt")
Z1_DATA=$(grep -c '^data:.*"text":"Hallo"' "$WORK/z1-ohne-cr.txt")
Z1_AFTER_DATA=$(grep -A1 '^data:.*"text":"Hallo"' "$WORK/z1-ohne-cr.txt" | sed -n 2p)
Z1_BLANK="nein"
if [ "$Z1_DATA" = "1" ] && [ -z "$Z1_AFTER_DATA" ]; then
    Z1_BLANK="ja"
fi
Z1_OK=no
if [ "$Z1_EXIT" = "28" ] && [ "$Z1_HEADER" = "1" ] && [ "$Z1_ID" = "1" ] && [ "$Z1_DATA" = "1" ] && [ "$Z1_BLANK" = "ja" ]; then
    Z1_OK=yes
fi
record Z1 "$Z1_OK" "Exit $Z1_EXIT, Header text/event-stream: $Z1_HEADER, id: $Z1_ID, data mit Hallo: $Z1_DATA, Leerzeile danach: $Z1_BLANK" \
    "28, Header text/event-stream, ein Ereignis mit id: und data:, danach eine Leerzeile"

# ---------------------------------------------------------------------------------------------
# Z2  Unbekannte ID
# ---------------------------------------------------------------------------------------------
echo "Z2  unbekannte Last-Event-ID"
docker exec "$A" curl -sN -D - --max-time 4 -H "Last-Event-ID: 00000000-0000-0000-0000-000000000000" "$STREAM" > "$WORK/z2.txt"
tr -d '\r' < "$WORK/z2.txt" > "$WORK/z2-ohne-cr.txt"
Z2_STATUS=$(head -1 "$WORK/z2-ohne-cr.txt" | awk '{ print $2 }')
Z2_FIRST_EVENT=$(grep -m1 -E '^(event|id|data):' "$WORK/z2-ohne-cr.txt")
Z2_OK=no
# Spring schreibt "event:reload" ohne Leerzeichen. Fuer SSE ist das dasselbe wie "event: reload".
if [ "$Z2_STATUS" = "200" ] && echo "$Z2_FIRST_EVENT" | grep -qE '^event: ?reload$'; then
    Z2_OK=yes
fi
record Z2 "$Z2_OK" "Status $Z2_STATUS, erstes Ereignis: $Z2_FIRST_EVENT" "Status 200, als erstes Ereignis event: reload"

# Streams, die noch im Hintergrund laufen (zum Beispiel der von S6 mit 300 s), werden hier beendet.
for job in $(jobs -p); do
    kill "$job" 2> /dev/null
done

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
echo "Messdateien: $WORK"
if [ "$FAILED" = "0" ]; then
    echo "Alle Szenarien bestanden."
    exit 0
fi
echo "$FAILED Szenario(en) nicht bestanden."
exit 1
