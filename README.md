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

Der **Bootstrap ist abgeschlossen**. Es gibt einen lauffähigen `chat-service` mit zwei Endpunkten,
dazu PostgreSQL und RabbitMQ in `docker-compose`.

| Aufgabe aus dem Bootstrap-Plan | Stand |
|---|---|
| 1 — Projekt anlegen, Swagger erreichbar | fertig |
| 2 — PostgreSQL und RabbitMQ in docker-compose | fertig |
| 3 — `GET /api/messages` liest den Verlauf | fertig |
| 4 — `POST /api/messages` publiziert auf den Fanout-Exchange | fertig |

Wie man den Stand startet und prüft, steht in [`docs/betrieb.md`](docs/betrieb.md).

**Wichtig zum Verständnis:** eine gesendete Nachricht landet im Broker, aber **nicht** in der
Datenbank. Das ist Absicht. Der `chat-service` publiziert nur, geschrieben wird vom
`batch-service` — und der ist der nächste Schritt.

Danach folgen, in dieser Reihenfolge: Raumverwaltung, Keycloak, der Live-Kanal für neue
Nachrichten, zuletzt Gateway und Weboberfläche.
