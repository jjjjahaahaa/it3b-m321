# M321 — Chat-App (Klasse IT3b)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3b-m321.git
   cd it3b-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Was gebaut wird

| Baustein | Technologie | Aufgabe |
|---|---|---|
| chat-service | Spring Boot 3, Java 21 | REST-API, liefert Nachrichten live per SSE, prüft das Login-Token |
| batch-service | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank, speichert Nachrichten gebündelt |
| gateway | nginx | Einziger nach aussen offener Port, Reverse Proxy |
| keycloak | Keycloak | Login (OIDC) |
| rabbitmq | RabbitMQ | Message Queue zwischen den Services |
| postgres | PostgreSQL | Speichert den Chat-Verlauf |
| Web-UI | React | Browser-Client |
| Desktop-UI | JavaFX | Zweiter Client gegen dieselbe API |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Stack, Architektur, Nachrichtenfluss, Datenmodell, offene Punkte.
  Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-architektur.html`](docs/design/2026-08-28-chat-app-architektur.html)
  — grafische Fassung der Architekturdiagramme, lokal im Browser öffnen (funktioniert ohne Internet).
- [`docs/plan/2026-09-04-chat-service-bootstrap.md`](docs/plan/2026-09-04-chat-service-bootstrap.md)
  — Schritt-für-Schritt-Plan für den ersten Service: Projekt anlegen, Datenbank und Broker
  anbinden, Nachrichten lesen und senden. Jeder Schritt mit Test.
- [`docs/betrieb.md`](docs/betrieb.md) — starten, prüfen, aufräumen. Dazu die Stolpersteine,
  über die man beim ersten Mal fällt.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`
  (Bewertung 1): Vertrag der Queue, Verhalten in jedem Fehlerfall, Datenmodell, Abnahmekriterien.
- [`docs/erklaerung-batch-writer.md`](docs/erklaerung-batch-writer.md) — der Code erklärt: Weg einer
  Nachricht, welche Stelle welches Szenario entscheidet, Fragen und Antworten fürs Code-Review.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — der Umsetzungsplan dazu, Aufgabe für
  Aufgabe, mit den gemessenen Werten am Ende.
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md) — Spezifikation der Live-Zustellung
  (Bewertung 2): `GET /stream`, eine Live-Queue pro Instanz, Nachholen mit `Last-Event-ID`,
  Abnahmekriterien.
- [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md) — der Umsetzungsplan dazu in zehn
  Schritten, mit den Änderungen am Plan und ihren Gründen.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- `docs/skizze-architektur.heic` — die Handskizze aus dem Unterricht, von der die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).

## Stand

Der **Bootstrap**, der **batch-writer** (Bewertung 1) und die **Live-Zustellung** (Bewertung 2) sind
abgeschlossen. Es laufen vier Dienste im `docker compose`: `postgres`, `rabbitmq`, `chat-service` und
`batch-writer`. Kein Dienst veröffentlicht einen Port. Vom `chat-service` dürfen mehrere Instanzen laufen
(`docker compose up -d --scale chat-service=2`), jede bekommt jede Nachricht über ihre eigene Live-Queue.

| Aufgabe | Stand |
|---|---|
| Bootstrap 1 — Projekt anlegen, Swagger erreichbar | fertig |
| Bootstrap 2 — PostgreSQL und RabbitMQ in docker-compose | fertig |
| Bootstrap 3 — `GET /api/messages` liest den Verlauf | fertig |
| Bootstrap 4 — `POST /api/messages` publiziert auf den Fanout-Exchange | fertig |
| batch-writer — Eltern-POM, Broker-Topologie, Parser, Schreiben in einer Transaktion | fertig |
| batch-writer — Listener, Bündeln mit Zeitlimit, kaputte Nachrichten, Datenbankausfall | fertig |
| batch-writer — Compose-Stack, `.env.example`, Abnahmeskript, Regeltests | fertig |
| Live-Zustellung — `GET /stream` (SSE), Live-Queue pro Instanz, Raumfilter | fertig |
| Live-Zustellung — Grenze 1000 und Heartbeat, Streams beenden nach Neuverbindung | fertig |
| Live-Zustellung — Nachholen mit `Last-Event-ID`, nichts doppelt, unbekannte ID mit `reload` | fertig |
| Live-Zustellung — Abnahmeskript `scripts/abnahme-live.sh` | fertig |
| Raumverwaltung, Keycloak, Gateway, Weboberfläche | offen |

Wie man den Stand startet und prüft, steht in [`docs/betrieb.md`](docs/betrieb.md). Die Testläufe
(`mvn clean test`, 77 Tests) brauchen Docker, aber keinen laufenden Stack.

**Live mitlesen:** `GET /stream?roomId=…` hält die Antwort offen und schickt jede neue Nachricht des Raums
als Server-Sent Event (`id:` ist die Nachrichten-ID, `data:` das JSON). Verbindet sich ein Client mit
`Last-Event-ID` neu, holt der Stream das Verpasste aus der Datenbank nach. Ist die ID unbekannt, kommt
zuerst `event: reload`, und der Client lädt den Verlauf neu.

**Wichtig zum Verständnis:** eine gesendete Nachricht landet im Broker und wird kurz danach vom
`batch-writer` in die Datenbank geschrieben, bis zu 200 ms später. Der `chat-service` publiziert nur,
geschrieben wird ausschliesslich vom `batch-writer`. Sie erscheint darum erst mit kleiner Verzögerung im
Verlauf.

Danach folgen, in dieser Reihenfolge: Raumverwaltung, Keycloak, zuletzt Gateway und Weboberfläche.
