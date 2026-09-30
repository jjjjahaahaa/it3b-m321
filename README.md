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
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — der Umsetzungsplan dazu, Aufgabe für
  Aufgabe, mit den gemessenen Werten am Ende.
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

Der **Bootstrap** und der **batch-writer** (Bewertung 1) sind abgeschlossen. Es laufen vier Dienste im
`docker compose`: `postgres`, `rabbitmq`, `chat-service` und `batch-writer`. Kein Dienst veröffentlicht
einen Port.

| Aufgabe | Stand |
|---|---|
| Bootstrap 1 — Projekt anlegen, Swagger erreichbar | fertig |
| Bootstrap 2 — PostgreSQL und RabbitMQ in docker-compose | fertig |
| Bootstrap 3 — `GET /api/messages` liest den Verlauf | fertig |
| Bootstrap 4 — `POST /api/messages` publiziert auf den Fanout-Exchange | fertig |
| batch-writer — Eltern-POM, Broker-Topologie, Parser, Schreiben in einer Transaktion | fertig |
| batch-writer — Listener, Bündeln mit Zeitlimit, kaputte Nachrichten, Datenbankausfall | fertig |
| batch-writer — Compose-Stack, `.env.example`, Abnahmeskript, Regeltests | fertig |
| Raumverwaltung, Keycloak, Live-Kanal (SSE), Gateway, Weboberfläche | offen |

Wie man den Stand startet und prüft, steht in [`docs/betrieb.md`](docs/betrieb.md). Die Testläufe
(`mvn clean test`, 37 Tests) brauchen Docker, aber keinen laufenden Stack.

**Wichtig zum Verständnis:** eine gesendete Nachricht landet im Broker und wird kurz danach vom
`batch-writer` in die Datenbank geschrieben, bis zu 200 ms später. Der `chat-service` publiziert nur,
geschrieben wird ausschliesslich vom `batch-writer`. Sie erscheint darum erst mit kleiner Verzögerung im
Verlauf.

Danach folgen, in dieser Reihenfolge: Raumverwaltung, Keycloak, der Live-Kanal für neue
Nachrichten, zuletzt Gateway und Weboberfläche.
