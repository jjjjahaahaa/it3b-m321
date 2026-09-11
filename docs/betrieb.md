# Betrieb — starten, prüfen, aufräumen

Diese Seite beschreibt, wie der heutige Stand auf einem Rechner zum Laufen kommt und woran man
erkennt, dass er wirklich läuft. Sie ist die Ergänzung zu [`PLANUNG.md`](../PLANUNG.md), die das
*Warum* beschreibt, und zum [Bootstrap-Plan](plan/2026-09-04-chat-service-bootstrap.md), der den
Bau Schritt für Schritt erklärt.

## Was aktuell läuft

| Baustein | Zustand |
|---|---|
| `chat-service` | läuft auf dem Host, zwei Endpunkte, Swagger-Doku |
| PostgreSQL | im Container, Schema und drei Demo-Nachrichten |
| RabbitMQ | im Container, Fanout-Exchange `chat.messages` |
| `batch-service` | **fehlt noch** — niemand hört auf den Exchange |
| Gateway, Keycloak, Web-UI, Desktop-UI | **fehlen noch** |

## Voraussetzungen

- **Java 21.** Nicht ein neueres System-JDK. `java -version` muss 21 zeigen.
- **Maven.** `mvn -v` muss als Laufzeit dasselbe JDK 21 melden.
- **Docker Desktop.** Muss gestartet sein, bevor irgendetwas anderes passiert.

## Starten

Zuerst die Infrastruktur, vom Projektwurzelverzeichnis aus:

```bash
docker compose up -d
```

Dann den Dienst, aus dem Verzeichnis `chat-service`:

```bash
mvn spring-boot:run
```

## Prüfen

Der Gesundheitsbericht ist die ehrlichste Prüfung. Er sagt nicht nur, dass die Anwendung gestartet
ist, sondern auch, ob Datenbank und Broker wirklich antworten:

```bash
curl -s http://localhost:8080/actuator/health
```

Erwartet wird `"status":"UP"` und darin je ein Eintrag `db` und `rabbit`, beide ebenfalls `UP`.

Die Swagger-Oberfläche liegt unter <http://localhost:8080/swagger-ui.html>. Dort stehen beide
Endpunkte mit Beispielwerten. Der Demo-Raum hat die ID
`11111111-1111-1111-1111-111111111111`.

Verlauf lesen:

```bash
curl -s "http://localhost:8080/api/messages?roomId=11111111-1111-1111-1111-111111111111"
```

Nachricht senden:

```bash
curl -s -X POST http://localhost:8080/api/messages -H "Content-Type: application/json" -d "{\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"sender\":\"lernende1\",\"text\":\"Hallo\"}"
```

Die Antwort ist **202**, nicht 201. Angenommen ist nicht gespeichert.

Die RabbitMQ-Oberfläche liegt unter <http://localhost:15672>, Anmeldung `chat` / `chat`.

## Aufräumen

Container anhalten, Daten behalten:

```bash
docker compose down
```

Container anhalten und die Datenbank vollständig löschen:

```bash
docker compose down -v
```

## Stolpersteine

**Die gesendete Nachricht steht nicht im Verlauf.** Das ist kein Fehler, sondern der Kern des
Entwurfs. Der `chat-service` publiziert nur auf den Exchange, geschrieben wird später vom
`batch-service`. Solange der fehlt, hört niemand zu, und ein Exchange speichert nichts. Die
Nachricht verschwindet spurlos, ohne Fehler und ohne Logeintrag. Wer sie sehen will, muss im
Broker von Hand eine Queue anlegen und an `chat.messages` binden.

**Der Exchange fehlt direkt nach dem Start.** Spring meldet ihn erst an, wenn die erste Verbindung
zum Broker aufgebaut wird. Ein Aufruf des Gesundheitsberichts genügt, danach ist er da.

**Die Tests brauchen laufende Container.** Seit Aufgabe 2 fährt der Kontext-Test die ganze
Anwendung hoch. Ohne Datenbank kommt Spring nicht durch, und `mvn test` schlägt fehl.

**Schemaänderungen wirken nicht.** Die Skripte in `db/` führt das Postgres-Image nur beim
allerersten Start auf ein leeres Volume aus. Danach hilft nur `docker compose down -v`, was alle
Daten löscht. Im Unterricht ist das richtig, in Produktion nimmt man dafür ein Werkzeug wie Flyway.

**Docker Desktop immer sauber beenden**, über das Symbol in der Taskleiste mit «Quit». Wird es
abgeschossen, bleiben unter Windows kaputte Socket-Dateien liegen, die sich nicht mehr löschen
lassen. Docker startet dann nicht mehr und meldet sinngemäss «The file cannot be accessed by the
system». Hilft nur noch: Docker beenden und die betroffenen Verzeichnisse umbenennen, worauf Docker
sie neu anlegt. Betroffen sind `%LOCALAPPDATA%\Docker\run` und `%LOCALAPPDATA%\docker-secrets-engine`.

## Bewusste Abweichung von der Planung

[`PLANUNG.md`](../PLANUNG.md) verlangt, dass nach aussen nur ein einziger Port offen ist. Im
aktuellen Stand sind die Ports von PostgreSQL und RabbitMQ veröffentlicht, weil der `chat-service`
noch auf dem Host läuft und sie über `localhost` erreichen muss. Sobald er selbst im Compose läuft,
werden aus diesen Einträgen interne Ports und die Regel gilt wieder.
