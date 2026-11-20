# Betrieb — starten, prüfen, aufräumen

Diese Seite beschreibt, wie der heutige Stand auf einem Rechner zum Laufen kommt und woran man
erkennt, dass er wirklich läuft. Sie ist die Ergänzung zu [`PLANUNG.md`](../PLANUNG.md), die das
*Warum* beschreibt, zu den Spezifikationen des [batch-writer](spec-batch-writer.md) und der
[Live-Zustellung](spec-live-zustellung.md) und zu den Umsetzungsplänen
([batch-writer](plan-batch-writer.md), [Live-Zustellung](plan-live-zustellung.md)).

## Was läuft

| Dienst | Aufgabe |
|---|---|
| `postgres` | speichert die Nachrichten. Schema und drei Demo-Nachrichten beim ersten Start |
| `rabbitmq` | Message Queue. Exchange `chat.messages`, Queue `chat.persist`, Dead-Letter-Queue `chat.dlq`, dazu eine Live-Queue `chat.live.<uuid>` pro Instanz des `chat-service` |
| `chat-service` | nimmt Nachrichten an (`POST /api/messages`), liest den Verlauf (`GET /api/messages`) und schickt neue Nachrichten live (`GET /stream`). Darf mehrfach laufen |
| `batch-writer` | holt die Nachrichten aus `chat.persist` und schreibt sie gebündelt in PostgreSQL |
| Gateway, Keycloak, Web-UI, Desktop-UI | **fehlen noch** |

**Kein Dienst veröffentlicht einen Port** auf dem Rechner. Alles läuft im internen Netz `chat-net`.
Von aussen kommt man nur mit `docker compose exec <dienst> …` hinein (Beispiele unten).

## Voraussetzungen

- **Docker Desktop.** Muss gestartet sein, bevor irgendetwas anderes passiert.
- **Java 21 und Maven**, nur für `mvn clean test`. `java -version` muss 21 zeigen.
  Die Dienste selbst werden im Docker-Build gebaut, dafür braucht der Rechner kein Maven.

## Starten

Vom Projektwurzelverzeichnis aus:

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Die Datei `.env` enthält die Zugangsdaten und ist nicht im Repository. Fehlt sie, bricht Compose mit
der Meldung «Datei .env fehlt» ab. Der erste Start dauert einige Minuten (Maven lädt Bibliotheken).
Danach sind alle vier Dienste `running`, `postgres`, `rabbitmq` und `chat-service` zusätzlich `healthy`.

## Prüfen

Der `chat-service` meldet sich, ohne dass ein Port offen ist, von innen:

```bash
docker compose exec chat-service curl -s http://localhost:8080/actuator/health
```

Nachricht senden (Antwort ist **202**, nicht 201: angenommen ist noch nicht gespeichert):

```bash
docker compose exec -T chat-service curl -s -X POST http://localhost:8080/api/messages -H "Content-Type: application/json" -d "{\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"sender\":\"lernende1\",\"text\":\"Hallo\"}"
```

Verlauf lesen. Die Nachricht steht nach spätestens 200 ms darin, bei viel Betrieb nach kurzer Zeit:

```bash
docker compose exec chat-service curl -s "http://localhost:8080/api/messages?roomId=11111111-1111-1111-1111-111111111111"
```

Direkt in der Datenbank nachsehen (Benutzer und Datenbank stehen in `.env`, vorgegeben `chat`):

```bash
docker compose exec postgres psql -U chat -d chat -c "SELECT sender, text, sent_at FROM message ORDER BY sent_at DESC LIMIT 5"
```

Die Queues ansehen: `messages` ist die Zahl wartender Nachrichten, `consumers` die Zahl der Schreiber.
Im Normalfall steht bei `chat.persist` eine `0` und bei `chat.dlq` ebenso:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

Die Swagger-Oberfläche und die RabbitMQ-Oberfläche sind nicht mehr vom Rechner aus erreichbar. Wer sie
kurz braucht, legt eine Datei `docker-compose.override.yml` mit den gewünschten `ports:` an. Compose
liest sie automatisch, sie ist in `.gitignore` und kommt nicht ins Repository.

### Alle Szenarien auf einmal

`scripts/abnahme.sh` stellt die acht Szenarien aus der Spezifikation des batch-writer nach (S2 bis S8,
mit `--mit-tests` auch S1) und schreibt zu jedem den gemessenen neben den erwarteten Wert:

```bash
scripts/abnahme.sh
```

`scripts/abnahme-live.sh` macht dasselbe für die Live-Zustellung: S1 bis S8 und Z1, Z2 aus
[Abschnitt 4 der Spezifikation](spec-live-zustellung.md). Es startet den Stack selbst mit zwei
Instanzen, klont für S2 den Fork frisch in einen temporären Ordner und hält für S6 eine Instanz an.
Bei jedem Szenario steht `OK` oder `FEHLER`, der Exit-Code ist nur bei allen `OK` gleich 0. Es dauert
etwa 20 Minuten, die meiste Zeit brauchen `mvn clean test` (S1) und der frische Klon (S2):

```bash
cp .env.example .env
scripts/abnahme-live.sh
```

## Zwei Instanzen des chat-service

```bash
docker compose up -d --scale chat-service=2
docker compose ps chat-service
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready consumers
```

Jede Instanz legt beim Start ihre eigene Queue `chat.live.<uuid>` an und hängt sie an den Exchange
`chat.messages`. So bekommt jede Instanz jede Nachricht, egal über welche sie gesendet wurde. Die Queue
ist exklusiv: hört die Verbindung der Instanz auf (Heartbeat 15 Sekunden), löscht RabbitMQ sie. Darin
warten höchstens 1000 Nachrichten, bei mehr fliegt die älteste raus. Weil `docker-compose.yml` keinen
`container_name` setzt, kann Compose beliebig viele Instanzen starten. Eine einzelne Instanz spricht man
mit `docker exec <container-id> …` an, die IDs zeigt `docker compose ps -q chat-service`.

## Live mitlesen: `GET /stream`

Ein Stream bleibt offen und bekommt jede neue Nachricht seines Raums als Server-Sent Event. Von innen,
zum Beispiel für 30 Sekunden:

```bash
docker compose exec chat-service curl -sN --max-time 30 "http://localhost:8080/stream?roomId=11111111-1111-1111-1111-111111111111"
```

In einem zweiten Terminal eine Nachricht senden (siehe oben), und sie erscheint so:

```text
id:3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47
data:{"id":"3c9a2f1e-…","roomId":"11111111-…","sender":"lernende1","text":"Hallo","sentAt":"…"}

```

`id:` ist die ID der Nachricht, `data:` dasselbe JSON, das auch in `chat.persist` liegt. Spring lässt nach
dem Doppelpunkt das Leerzeichen weg, für SSE ist das dasselbe.

**Nachholen.** Verbindet sich ein Browser neu, schickt er die letzte gesehene ID als Kopfzeile
`Last-Event-ID` mit. Der Stream meldet sich dann zuerst für Live-Nachrichten an (sie warten in einer
Warteliste), wartet eine Sekunde, holt das Verpasste aus der Datenbank und schickt danach die Warteliste
ohne die Nachrichten, die schon nachgeholt sind. So fehlt nichts und nichts kommt doppelt. Von Hand:

```bash
docker compose exec chat-service curl -sN --max-time 10 -H "Last-Event-ID: <letzte id>" "http://localhost:8080/stream?roomId=11111111-1111-1111-1111-111111111111"
```

**Unbekannte ID.** Findet der Stream die ID auch beim zweiten Suchen nicht, gehört sie zu einem anderen
Raum oder ist die Datenbank nicht erreichbar, antwortet er trotzdem mit 200. Das erste Ereignis ist dann
`event: reload` mit `data: {"reason":"unknown-last-event-id"}`, danach läuft der Stream live weiter. Der
Client lädt den Verlauf mit `GET /api/messages` neu. Ein Fehlerstatus wäre falsch: `EventSource` im Browser
gibt dann auf und verbindet sich nicht mehr neu.

## Mehr als ein Schreiber

```bash
docker compose up -d --scale batch-writer=2
docker compose exec rabbitmq rabbitmqctl list_queues name consumers
```

Beide hängen an derselben Queue, RabbitMQ verteilt die Nachrichten. Doppelte Zeilen gibt es nicht, weil
die ID vom `chat-service` kommt und der Primärschlüssel eine Wiederholung verwirft.

## Störungen ausprobieren

**Datenbank fällt aus.** Der Schreiber wartet und versucht es alle zwei Sekunden neu. Die Nachrichten
bleiben in der Queue, sichtbar an der wachsenden Zahl bei `chat.persist`:

```bash
docker compose stop postgres
# ... Nachrichten senden, dann bei chat.persist die Zahl ansehen ...
docker compose start postgres
```

**Kaputte Nachricht.** Eine Nachricht ohne gültiges JSON landet in `chat.dlq`, die anderen werden
trotzdem gespeichert. `chat.dlq` in der Liste oben zeigt, ob etwas ausgesondert wurde.

**Schreiber stoppen.** `docker compose stop batch-writer`: die Nachrichten sammeln sich in der Queue und
werden nach `docker compose start batch-writer` in wenigen Paketen geschrieben.

**Live ohne Datenbank.** Auch wenn `postgres` und `batch-writer` gestoppt sind, kommen neue Nachrichten
an jedem offenen Stream an. Der Live-Weg braucht keine Datenbank. Nur das Nachholen geht in dieser Zeit
nicht, ein Client mit `Last-Event-ID` bekommt nach etwa drei Sekunden `event: reload`.

**Instanz anhalten.** `docker pause <container-id>` friert eine Instanz ein. Nach etwa 15 Sekunden ohne
Heartbeat schliesst RabbitMQ ihre Verbindung und löscht ihre Live-Queue. Nach `docker unpause` verbindet
sie sich neu, legt die Queue wieder an und beendet ihre offenen Streams, damit die Clients neu verbinden
und das Verpasste nachholen.

## Aufräumen

Container anhalten, Daten behalten (Datenbank **und** Queue-Inhalt bleiben):

```bash
docker compose down
```

Container anhalten und alles löschen, auch Datenbank und Queues:

```bash
docker compose down -v
```

## Stolpersteine

**Die gesendete Nachricht steht nicht sofort im Verlauf.** Das ist der Kern des Entwurfs. Der
`chat-service` publiziert nur, der `batch-writer` schreibt gebündelt, das dauert bis zu 200 ms. Steht sie
nach ein paar Sekunden noch nicht da: `docker compose logs batch-writer` zeigt, was er gerade tut.

**`docker compose ps` zeigt `5432/tcp` bei `postgres`.** Das ist kein veröffentlichter Port. Er wäre mit
`0.0.0.0:5432->5432/tcp` angegeben. Ein Pfeil (`->`) heisst «vom Rechner aus erreichbar».

**Die Tests brauchen Docker, aber keinen laufenden Stack.** `mvn clean test` startet für die Tests von
`batch-writer` und `chat-service` eigene Container mit PostgreSQL und RabbitMQ (Testcontainers) auf
zufälligen Ports. Sie stören den Stack nicht. Ohne laufendes Docker schlagen sie mit einer klaren Meldung
fehl. Die Tests des `chat-service` dauern einige Minuten, weil mehrere davon eigene Instanzen starten und
der Test für «nichts doppelt» 20 Mal läuft.

**`curl` auf `/stream` zeigt lange gar nichts, nicht einmal Kopfzeilen.** Spring schickt die Kopfzeilen
erst zusammen mit dem ersten Ereignis. Solange im Raum niemand etwas sendet, sieht man nichts. Das ist
kein Fehler.

**Schemaänderungen wirken nicht.** Die Skripte in `db/` führt das Postgres-Image nur beim
allerersten Start auf ein leeres Volume aus. Danach hilft nur `docker compose down -v`, was alle
Daten löscht. Im Unterricht ist das richtig, in Produktion nimmt man dafür ein Werkzeug wie Flyway.

**Neue Passwörter in `.env` wirken nicht.** PostgreSQL und RabbitMQ legen den Benutzer beim ersten
Start an. Wer die Zugangsdaten ändert, braucht danach `docker compose down -v`.

**Der Broker-Container braucht beim ersten Start etwas länger.** Er gilt erst als gesund, wenn
`chat.persist` existiert. Dafür spielt sein Healthcheck die Datei `rabbitmq/definitions.json` ein.
`chat-service` und `batch-writer` warten darauf.

**Docker Desktop immer sauber beenden**, über das Symbol in der Taskleiste mit «Quit». Wird es
abgeschossen, bleiben unter Windows kaputte Socket-Dateien liegen, die sich nicht mehr löschen
lassen. Docker startet dann nicht mehr und meldet sinngemäss «The file cannot be accessed by the
system». Hilft nur noch: Docker beenden und die betroffenen Verzeichnisse umbenennen, worauf Docker
sie neu anlegt. Betroffen sind `%LOCALAPPDATA%\Docker\run` und `%LOCALAPPDATA%\docker-secrets-engine`.
