# Spezifikation — Live-Zustellung

Modul M321 · Klasse IT3b · Bewertung 2 · Jaro · Stand 19.11.2026

## 1. Vertrag

### 1.1 Was am Exchange ankommt

**Frage:** Was macht der chat-service mit einer Nachricht, bevor sie beim Client ist? An welche Stellen geht sie, und welche Angaben stecken darin?

**Antwort:**

Der chat-service gibt der Nachricht zuerst eine ID und einen Zeitstempel. Dann schickt er sie einmal an den Exchange `chat.messages`. Weil das ein Fanout-Exchange ist, macht RabbitMQ für jede Queue, die dranhängt, eine Kopie. Eine Kopie geht in die Live-Queue `chat.live.{uuid}` von jeder Instanz und wird von dort an die Clients geschickt. Eine andere geht in `chat.persist`, dort wird sie nur gespeichert. In der Nachricht steht, wer sie geschickt hat (`sender`), in welchen Raum sie gehört (`roomId`) und was drin steht (`text`). Dazu kommen noch die `id` und wann sie geschickt wurde (`sentAt`).

### 1.2 Woher ich das weiss

**Frage:** In welcher Datei und in welchen Zeilen im Code sieht man das?

**Antwort:**

Das sieht man in `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageService.java`. In Zeile 55 wird die Nachricht gebaut und in Zeile 63 wird sie publiziert:

```java
rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);
```

Das ist der einzige Aufruf. Der Name `chat.messages` steht in `RabbitConfiguration.java` in Zeile 20, und in Zeile 31 wird der Exchange als Fanout erstellt. Das steht auch in `rabbitmq/definitions.json` in Zeile 6. Dass `chat.persist` am Exchange hängt, sieht man in `rabbitmq/definitions.json` ab Zeile 36. Wie das JSON aussieht, habe ich schon in Bewertung 1 gezeigt: `docs/spec-batch-writer.md`, Abschnitt 2.1, und die mitgeschnittene Nachricht in `batch-writer/src/test/resources/chat-service-message.json`.

### 1.3 Was am Stream hinausgeht

**Frage:** Was bekommt ein Client über den Stream, und welche Nachrichten bekommt er?

**Antwort:**

Der Client macht einmal `GET /stream?roomId=...` mit der UUID von seinem Raum auf, zum Beispiel `/stream?roomId=11111111-1111-1111-1111-111111111111`. Später geht das über das Gateway auf `localhost:8080`. Bis dahin geht es nur aus einem Container heraus mit `curl -N`.

Die Antwort hat `Content-Type: text/event-stream`. Die Verbindung bleibt offen, und der Server schreibt jede neue Nachricht in die gleiche Antwort. Sie geht erst zu, wenn der Client sie schliesst oder wenn sie abbricht.

Ein Ereignis hat bei `id:` die UUID von der Nachricht und bei `data:` das JSON aus der Queue. Nach jedem Ereignis kommt eine Leerzeile, und erst die schliesst es ab. So sehen zwei Ereignisse hintereinander aus:

```text
id: 3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47
data: {"id":"3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47","roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende1","text":"Hallo","sentAt":"2026-11-17T08:15:30.123Z"}

id: 8d41b0c7-2e5f-4a19-b6d3-7c0e9f2a5b18
data: {"id":"8d41b0c7-2e5f-4a19-b6d3-7c0e9f2a5b18","roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende2","text":"Hoi","sentAt":"2026-11-17T08:15:31.456Z"}

```

Der Client bekommt nur Nachrichten aus seinem Raum. Die Live-Queue bekommt zwar alle, aber der chat-service schaut bei jeder Nachricht, ob die `roomId` zum Raum vom Stream passt.

## 2. Verhalten

### 2.1 Normalfall: nur der eigene Raum

**Frage:** Mehrere Clients sind in verschiedenen Räumen. Wer bekommt welche Nachricht?

**Antwort:**

Jeder Client ist in einem Raum. Ein Beispiel: Es gibt drei Clients. Client 1 und Client 2 sind in Raum A und Client 3 ist in einem anderen Raum. Wenn jemand in Raum A schreibt, bekommen das Client 1 und Client 2, aber Client 3 nicht. So kommen die Nachrichten von verschiedenen Räumen nicht durcheinander.

### 2.2 Reihenfolge (S3)

**Frage:** Warum kommen die Nachrichten in der richtigen Reihenfolge an?

**Antwort:**

Das geht nur, wenn ein Absender seine nächste Nachricht erst schickt, wenn er die 202 für die vorherige bekommen hat.

Dann bleibt pro Absender die Reihenfolge gleich, auch beim Client an der anderen Instanz. Instanz A publiziert die Nachrichten von einem Absender der Reihe nach. Der Exchange legt sie in der gleichen Reihenfolge in die Queue von Instanz B, und B liest ihre Queue nur mit einem Thread. Zwischen zwei verschiedenen Absendern gibt es keine feste Reihenfolge, aber das ist auch nicht verlangt.

### 2.3 Hängende Instanz (S6)

**Frage:** Eine Instanz liest nicht mehr weiter. Was passiert mit ihrer Queue, und was macht RabbitMQ?

**Antwort:**

Zuerst sammeln sich die Nachrichten in ihrer Queue, aber höchstens 1000 (siehe 3.3).

Ob eine Instanz tot ist, merkt RabbitMQ am Heartbeat. Mit `spring.rabbitmq.requested-heartbeat: 15s` schicken sich der Broker und die Instanz ungefähr alle 7,5 Sekunden ein Lebenszeichen. Wenn zwei davon fehlen, also nach etwa 15 Sekunden, macht RabbitMQ die Verbindung zu.

Wenn die Instanz in S6 länger angehalten ist, löscht RabbitMQ mit der Verbindung auch ihre Queue und alles, was drin ist. Die anderen Queues bekommen trotzdem weiter alles, und in `chat.persist` fehlt nichts. Wenn die Instanz wieder läuft, verbindet sie sich neu, und Spring macht die Queue und die Bindung wieder. Danach beendet die Instanz ihre offenen Streams. Ihre Clients haben während der Pause etwas verpasst. Sie verbinden sich neu und holen es mit `Last-Event-ID` nach (2.4).

Auch die Queue von der gesunden Instanz kommt nie über 1000. Wenn dort die 1001. kommen würde, würde die älteste rausfliegen, und genau diese Nachricht würde dem Client an der gesunden Instanz fehlen, ohne dass er es merkt. In S6 passiert das aber nicht, weil die gesunde Instanz schneller liest, als gesendet wird (in Bewertung 1 waren das etwa 70 Nachrichten pro Sekunde). Ausserdem zählen die bis zu 250 Nachrichten, die sie schon abgeholt, aber noch nicht bestätigt hat, nicht zur Grenze.

Eine TTL pro Nachricht will ich nicht. In S6 bringt sie nichts, weil die Queue nach 15 Sekunden sowieso weg ist. Sie würde nur etwas machen, wenn die Instanz noch lebt, aber zu langsam liest. Dann ist der Stream aber noch offen und der Client merkt nicht, dass eine Nachricht abgelaufen ist. Da kommt sie lieber zu spät. Den Speicher begrenzt ja schon `x-max-length`.

### 2.4 Nachholen nach einem Abbruch (S7)

**Frage:** Ein Client verliert kurz die Verbindung. Wie bekommt er die verpassten Nachrichten?

**Antwort:**

Die letzte ID kommt im Header `Last-Event-ID`. Den schickt der Browser beim Neuverbinden automatisch mit.

Die verpassten Nachrichten hole ich aus der Datenbank. Wenn ich sie im chat-service speichern würde, wäre der Speicher nach einem Neustart leer. Ausserdem landet der Client beim Neuverbinden vielleicht auf einer anderen Instanz. In der Datenbank ist alles drin, und der chat-service liest dort sowieso schon den Verlauf.

UUIDs kann man nicht sortieren. Darum suche ich zuerst die Nachricht mit dieser ID und nehme ihr `sent_at`. Dann lese ich alle Nachrichten im gleichen Raum, die das gleiche oder ein späteres `sent_at` haben, die älteste zuerst. Nur die Nachricht mit der ID selbst lasse ich weg.

Dass zwei Nachrichten das gleiche `sent_at` haben, kann passieren, weil die Datenbank nur Mikrosekunden speichert. Darum nehme ich «gleich oder später» und nicht nur «später». Sonst würde eine zweite Nachricht mit genau dem gleichen Zeitpunkt fehlen. In seltenen Fällen bekommt der Client dadurch eine Nachricht doppelt, aber das ist besser, als wenn sie verloren geht.

Es kann sein, dass die letzte Nachricht noch gar nicht in der Datenbank ist, weil der batch-writer bis zu 200 ms braucht oder gerade nicht läuft. Dann sucht der Dienst nach einer Sekunde nochmals. Wenn er sie dann immer noch nicht findet, behandelt er die ID wie eine unbekannte (2.6). Weil der Browser erst nach ein paar Sekunden neu verbindet, ist die Nachricht meistens schon lange gespeichert.

Damit nichts doppelt kommt, melde ich den Stream zuerst für Live-Nachrichten an und sammle die in einer Warteliste. Dann schicke ich die Nachrichten aus der Datenbank und speichere ihre IDs in einem `HashSet`. Am Schluss schicke ich die Warteliste und lasse jede ID weg, die schon im Set ist. Wenn ich es andersrum machen würde, also zuerst lesen und dann anmelden, könnte genau dazwischen eine Nachricht reinkommen und dann würde sie ganz fehlen. Weil der Web-Thread und der RabbitMQ-Listener gleichzeitig auf den Stream zugreifen, mache ich das mit `synchronized`.

### 2.5 Datenbank weg (S5)

**Frage:** Die Datenbank ist ausgefallen. Warum kommt die Nachricht trotzdem an?

**Antwort:**

Weil die Nachrichten über zwei Wege gehen, über `chat.persist` und über `chat.live.{uuid}`. In `chat.persist` bleiben sie liegen, bis der batch-writer und die Datenbank wieder laufen, und dann trägt der batch-writer sie nach. Über `chat.live.{uuid}` werden sie direkt geschickt, und dafür braucht man keine Datenbank. Man sieht die Nachrichten also sofort und sie gehen trotzdem nicht verloren. Nur das Nachholen (2.4) funktioniert in dieser Zeit nicht.

Für `/stream` ohne `Last-Event-ID` braucht es die Datenbank nicht. Es gibt ja nichts nachzuholen, der Stream meldet sich nur für Live-Nachrichten an. Darum kann sich ein Client in S5 auch verbinden, wenn die Datenbank gestoppt ist, und die 100 Nachrichten sind nach ein paar Sekunden bei ihm.

### 2.6 Unbekannte ID

**Frage:** Ein Client will ab einer ID nachholen, die es nicht mehr gibt. Was soll dann passieren?

**Antwort:**

Die Antwort ist trotzdem 200. Als erstes kommt ein eigenes Ereignis, und danach läuft der Stream normal live weiter:

```text
event: reload
data: {"reason":"unknown-last-event-id"}

id: 3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47
data: {"id":"3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47","roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende1","text":"Hallo","sentAt":"2026-11-17T08:15:30.123Z"}

```

Einen Fehlerstatus schicke ich nicht, weil `EventSource` dann aufgibt und sich nicht mehr neu verbindet. Durch `reload` weiss der Client, dass ihm vielleicht etwas fehlt, und lädt den Verlauf mit `GET /api/messages`. Ins Log schreibe ich eine Warnung.

## 3. Queues

### 3.1 Warum jede Instanz eine eigene Queue hat

**Frage:** Warum hat jede Instanz eine eigene Queue?

**Antwort:**

Wenn alle an der gleichen Queue hängen würden, also wie an einem gemeinsamen Briefkasten, dann würden sie sich die Nachrichten aufteilen. Person A an Instanz A würde dann nur die eine Hälfte sehen und Person B an Instanz B nur die andere. Darum hat jede Instanz ihre eigene Queue, damit jeder jede Nachricht sieht.

Hinter `chat.live.` kommt eine UUID, zum Beispiel `chat.live.5b0c2f9e-1d7a-4c3e-9a8b-2f6e4d1c0a93`. Die Instanz macht sie beim Start mit `UUID.randomUUID()`. Dass zwei Instanzen die gleiche UUID bekommen, passiert praktisch nie. Den Hostnamen nehme ich nicht, weil der nach einem Neustart gleich bleibt und die alte Queue dann vielleicht noch da ist.

### 3.2 Lebensdauer (S4)

**Frage:** Was passiert mit der Queue einer Instanz, wenn die Instanz stoppt, und warum?

**Antwort:**

Das macht `exclusive: true`. Eine exklusive Queue gehört zu der Verbindung, die sie erstellt hat, und RabbitMQ löscht sie, sobald diese Verbindung weg ist. Sonst würden sich in einer Queue, die niemand mehr liest, immer mehr Nachrichten sammeln.

Bei einem normalen Stopp macht Spring die Verbindung zu, und die Queue ist sofort weg. Auch wenn Docker die Instanz hart beendet, macht das Betriebssystem die Verbindung sofort zu. Nur wenn sie nicht sauber zugeht, zum Beispiel wenn die Instanz eingefroren ist, merkt RabbitMQ es erst am Heartbeat. Den stelle ich auf 15 Sekunden statt auf die normalen 60. Die Queue ist also sicher nach weniger als 30 Sekunden weg.

### 3.3 Grenze (S6)

**Frage:** Wie viele Nachrichten darf eine Queue höchstens haben, und was passiert mit der nächsten?

**Antwort:**

Die Queue darf höchstens 1000 Nachrichten haben. Wenn die 1001. kommt, wird die älteste weggeworfen. Bei einem Live-Chat, z.B. wie WhatsApp, ist die neuste Nachricht halt wichtiger als die älteste. Die Grenze kommt von `x-max-length: 1000`, und dass die älteste rausfliegt, macht `x-overflow: drop-head`.

## 4. Abnahmekriterien

**Frage:** Wie prüft man, ob alles stimmt?

**Antwort:**

S1 und S2 laufen ohne Vorbereitung. Ab S3 läuft der Stack mit zwei Instanzen, und dafür setze ich zuerst diese Befehle:

```bash
set -a; . ./.env; set +a
docker compose up -d --scale chat-service=2
A=$(docker compose ps -q chat-service | sed -n 1p)
B=$(docker compose ps -q chat-service | sed -n 2p)
RAUM_A=11111111-1111-1111-1111-111111111111
RAUM_B=22222222-2222-2222-2222-222222222222
STREAM="http://localhost:8080/stream?roomId=$RAUM_A"

sql() {            # SQL in der Datenbank ausführen
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"
}
send_message() {   # INSTANZ RAUM ABSENDER TEXT
    docker exec "$1" curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/messages \
        -H 'Content-Type: application/json' -d "{\"roomId\":\"$2\",\"sender\":\"$3\",\"text\":\"$4\"}"
}
send_many() {      # INSTANZ RAUM ABSENDER ANZAHL: Texte 1 bis ANZAHL, alles in einem docker exec
    docker exec "$1" sh -c 'i=1; while [ "$i" -le "$4" ]; do
        curl -s -o /dev/null -X POST localhost:8080/api/messages -H "Content-Type: application/json" \
            -d "{\"roomId\":\"$2\",\"sender\":\"$3\",\"text\":\"$i\"}"
        i=$((i + 1))
    done' _ "$@"
}
listen() {         # INSTANZ RAUM SEKUNDEN DATEI, läuft im Hintergrund
    docker exec "$1" curl -sN --max-time "$3" "http://localhost:8080/stream?roomId=$2" > "$4" &
}
texts_of() {       # DATEI ABSENDER: Texte dieses Absenders in Ankunftsreihenfolge
    grep "\"sender\":\"$2\"" "$1" | grep -o '"text":"[^"]*"' | cut -d'"' -f4 | paste -sd' ' -
}
queues() {         # Live-Queues: Name, wartende Nachrichten, Leser
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready consumers | grep '^chat\.live\.'
}

sql "INSERT INTO room VALUES ('$RAUM_B', 'Raum B', 'pruefung', now())"
```

Nach S4 muss man den Stack wieder mit `--scale chat-service=2` starten und A und B neu bestimmen.

| Nr | Was gemessen wird | Befehl | Erwartet |
|---|---|---|---|
| S1 | Alle Tests grün | `mvn clean test` im Wurzelverzeichnis | `BUILD SUCCESS`, 0 Fehler |
| S2 | Frischer Klon startet | Fork klonen, `cp .env.example .env`, `docker compose up -d --build`; dann `docker compose ps --status running --format '{{.Service}}'` und `docker compose ps --format '{{.Ports}}'` | postgres, rabbitmq, chat-service, batch-writer laufen; keine Zeile mit `->` |
| S3 | Raum und Reihenfolge | `listen "$A" "$RAUM_A" 20 a1.txt`, `listen "$B" "$RAUM_A" 20 a2.txt`, `listen "$A" "$RAUM_B" 20 b.txt`, `sleep 1`; gleichzeitig `send_many "$A" "$RAUM_A" s3-a 200` und `send_many "$B" "$RAUM_A" s3-b 200`; `wait`; `grep -c '^data:' a1.txt a2.txt b.txt`; `texts_of` für `s3-a` und `s3-b` in `a1.txt` und `a2.txt` | `a1.txt:400`, `a2.txt:400`, `b.txt:0`; alle vier Male `1 2 … 200` |
| S4 | Queue verschwindet mit der Instanz | `queues`; `docker compose up -d --scale chat-service=1`; `sleep 30`; `queues` | vorher 2 Zeilen `chat.live.…` mit je 1 Leser, danach nur noch 1 |
| S5 | Live ohne batch-writer und Datenbank | `docker compose stop batch-writer postgres`; `listen "$B" "$RAUM_A" 30 s5.txt`, `sleep 1`; `send_many "$A" "$RAUM_A" s5 100`; `sleep 5`; `grep -c '^data:' s5.txt`; `docker compose start postgres batch-writer`; `sql "SELECT count(*) FROM message WHERE sender='s5'"` | 100 am Stream 5 s nach dem Senden; 100 in der Tabelle, sobald beide wieder laufen (höchstens 90 s) |
| S6 | Angehaltene Instanz | `listen "$B" "$RAUM_A" 300 s6.txt`, `sleep 1`; `docker pause "$A"`; `send_many "$B" "$RAUM_A" s6 5000` und währenddessen jede Sekunde `queues`, den grössten Wert von `messages_ready` festhalten; nach dem Senden `grep -c '^data:' s6.txt`; `docker unpause "$A"`, `sleep 15`, `queues`; `listen "$A" "$RAUM_A" 5 s6-a.txt`, `sleep 1`, `send_message "$B" "$RAUM_A" s6 nachher`, `sleep 5`, `grep -c '^data:' s6-a.txt` | grösster Wert höchstens 1000; 5000 beim Client an B; wieder 2 Live-Queues mit je 1 Leser; 1 beim Client an A |
| S7 | Nachholen | `listen "$B" "$RAUM_A" 3 s7-1.txt`, `sleep 1`, `send_message "$A" "$RAUM_A" s7 0`, `sleep 3`; `LAST=$(sed -n 's/^id: *//p' s7-1.txt)`; `send_many "$A" "$RAUM_A" s7 50`; `sleep 10`; `docker exec "$B" curl -sN --max-time 10 -H "Last-Event-ID: $LAST" "$STREAM" > s7-2.txt &`, `sleep 3`, `send_message "$A" "$RAUM_A" s7-live danach`, `wait $!`; `texts_of s7-2.txt s7`; `sed -n 's/^id: *//p' s7-2.txt \| sort \| uniq -d \| wc -l`; `texts_of s7-2.txt s7-live` | `1 2 … 50`; `0`; `danach` |
| S8 | Regeln im chat-service | `grep -rn -e 'stream()' -e '\.stream\b' -e 'Stream<' chat-service/src`; ein `CommentRulesTest` wie im batch-writer, aber für `chat-service/src` (läuft in S1 mit); `git ls-files .env` | keine Treffer; Test grün; leere Ausgabe |

Dazu teste ich noch das hier:

| Nr | Was gemessen wird | Befehl | Erwartet |
|---|---|---|---|
| Z1 | Format des Ereignisses | `docker exec "$A" curl -sN -D - --max-time 5 "$STREAM" > z1.txt &`, `sleep 1`, `send_message "$B" "$RAUM_A" z1 Hallo`, `wait $!; echo $?`, `cat z1.txt` | `28` (der Server hat nicht geschlossen), Header `text/event-stream`, ein Ereignis mit `id:` und `data:` mit `"text":"Hallo"`, danach eine Leerzeile |
| Z2 | Unbekannte ID | `docker exec "$A" curl -sN -D - --max-time 4 -H "Last-Event-ID: 00000000-0000-0000-0000-000000000000" "$STREAM"` | Status 200, als erstes Ereignis `event: reload` |
