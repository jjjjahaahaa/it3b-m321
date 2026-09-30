# Betrieb — starten, prüfen, aufräumen

Diese Seite beschreibt, wie der heutige Stand auf einem Rechner zum Laufen kommt und woran man
erkennt, dass er wirklich läuft. Sie ist die Ergänzung zu [`PLANUNG.md`](../PLANUNG.md), die das
*Warum* beschreibt, zur [Spezifikation des batch-writer](spec-batch-writer.md) und zum
[Umsetzungsplan](plan-batch-writer.md).

## Was läuft

| Dienst | Aufgabe |
|---|---|
| `postgres` | speichert die Nachrichten. Schema und drei Demo-Nachrichten beim ersten Start |
| `rabbitmq` | Message Queue. Exchange `chat.messages`, Queue `chat.persist`, Dead-Letter-Queue `chat.dlq` |
| `chat-service` | nimmt Nachrichten an (`POST /api/messages`) und liest den Verlauf (`GET /api/messages`) |
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

`scripts/abnahme.sh` stellt die acht Szenarien aus der Spezifikation nach (S2 bis S8, mit
`--mit-tests` auch S1) und schreibt zu jedem den gemessenen neben den erwarteten Wert:

```bash
scripts/abnahme.sh
```

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

**Die Tests brauchen Docker, aber keinen laufenden Stack.** `mvn clean test` startet für die Tests des
`batch-writer` eigene Container mit PostgreSQL und RabbitMQ (Testcontainers) auf zufälligen Ports.
Sie stören den Stack nicht. Ohne laufendes Docker schlagen sie mit einer klaren Meldung fehl.

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
