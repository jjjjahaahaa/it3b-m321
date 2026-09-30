# Spezifikation — batch-writer

Bewertung 1, Modul M321, Klasse IT3b. Diese Spezifikation entsteht **vor** dem Code. Der
Umsetzungsplan ([`plan-batch-writer.md`](plan-batch-writer.md)) baut darauf auf, der Code hält sich
an sie. Wo der Code später von ihr abweicht, wird zuerst dieses Dokument geändert.

Massstab: Wer diese Seite gelesen hat, kann den Dienst bauen, ohne mich zu fragen.

---

## 1. Zweck und Abgrenzung

### Was der Dienst tut

Der `batch-writer` holt Nachrichten aus der RabbitMQ-Queue `chat.persist` und legt sie
**dauerhaft und gebündelt** in der PostgreSQL-Tabelle `message` ab. Er ist der **einzige
Schreiber** in diese Tabelle.

### Warum es ihn gibt

`PLANUNG.md` (Abschnitt 2.3) rechnet mit sehr vielen Nachrichten pro Sekunde. Ein einzelner
`INSERT` pro Nachricht kostet je einen Netzwerk-Roundtrip, einen Parse-Vorgang und einen eigenen
Commit. Der Dienst sammelt deshalb bis zu 500 Nachrichten und schreibt sie mit **einer**
Transaktion. Ohne ihn bleiben die Nachrichten in der Queue liegen: der `chat-service` publiziert
nur, er schreibt nie in die Datenbank.

### Was er bewusst nicht tut

| Nicht Teil des Dienstes | Wer es stattdessen tut / warum nicht |
|---|---|
| HTTP-Schnittstelle | Er hat keinen Webserver und öffnet keinen Port. Er ist nur Konsument. |
| Verlauf lesen | Das macht der `chat-service` (`GET /api/messages`). |
| Räume, Mitgliedschaften, Keycloak | Ausdrücklich nicht Teil von Bewertung 1. |
| Schema anlegen oder ändern | Das Schema entsteht beim ersten Start von PostgreSQL (Abschnitt 4.2). |
| Queues und Exchange anlegen | Das richtet der Broker-Container selbst ein (Abschnitt 4.3). |
| Nachrichten prüfen, ob der Absender im Raum sein darf | Das ist Sache des `chat-service` vor dem Publizieren. |
| Inhalt verändern | Er schreibt exakt, was ankommt. ID und Zeitstempel bleiben unangetastet. |

---

## 2. Vertrag: was auf der Queue ankommt

### 2.1 Woher ich das weiss

Aus zwei Quellen, beide nachprüfbar:

1. **Der Quelltext des `chat-service`.** `MessageService.sendMessage` baut ein `Message`-Objekt
   (`id`, `roomId`, `sender`, `text`, `sentAt`) und ruft
   `rabbitTemplate.convertAndSend("chat.messages", "", message)` auf.
   `RabbitConfiguration` stellt dafür einen `Jackson2JsonMessageConverter` mit dem Jackson-Mapper
   von Spring Boot ein.
2. **Eine Beobachtung am laufenden System** (30.09.2026). Ich habe den `chat-service` gestartet,
   eine Test-Queue an den Exchange `chat.messages` gebunden, eine Nachricht gesendet und sie mit der
   Management-API der Queue entnommen (`POST /api/queues/%2F/<queue>/get`). Das Ergebnis liegt als
   Testdatei im Repo: `batch-writer/src/test/resources/chat-service-message.json`.

Beobachtet wurde:

```text
exchange:        chat.messages   (Routing-Key leer)
properties:      content_type     = application/json
                 content_encoding = UTF-8
                 delivery_mode    = 2 (persistent)
                 headers          = { "__TypeId__": "ch.benedict.m321.chat.message.Message" }
payload:         {"id":"f696fd36-053b-422d-ae03-7b3f461d17a7",
                  "roomId":"11111111-1111-1111-1111-111111111111",
                  "sender":"lernende1",
                  "text":"Hallo Vertrag",
                  "sentAt":"2026-09-30T06:54:53.879436190Z"}
```

### 2.2 Der Nachrichtenkörper

Ein JSON-Objekt, UTF-8. Genau diese fünf Felder werden gelesen:

| Feld | JSON-Typ | Bedeutung | Pflicht |
|---|---|---|---|
| `id` | String (UUID) | vom `chat-service` vergeben, wird der Primärschlüssel | ja |
| `roomId` | String (UUID) | Raum, in den die Nachricht gehört | ja |
| `sender` | String | Benutzername des Absenders, höchstens 100 Zeichen | ja |
| `text` | String | Nachrichtentext | ja |
| `sentAt` | String (ISO-8601, UTC) | Sendezeitpunkt, **mit bis zu 9 Nachkommastellen** | ja |

Unbekannte weitere Felder werden **ignoriert**, damit der `chat-service` später ein Feld ergänzen
kann, ohne den Schreiber zu brechen.

### 2.3 Was der Dienst sich **nicht** auf den Kopfzeilen verlässt

Der Header `__TypeId__` nennt eine Java-Klasse des `chat-service`. Diese Klasse gibt es im
`batch-writer` nicht. Ein Konverter, der dem Header vertraut, würde daran scheitern. Und wer eine
Nachricht von Hand in die Queue legt (Management-UI, `rabbitmqadmin`, ein Testskript), setzt diesen
Header nicht.

**Regel:** Der Dienst liest den Körper der Nachricht als UTF-8-Text und wandelt ihn selbst in sein
eigenes Objekt um. `__TypeId__` wird nicht beachtet. Auch `content_type` wird nicht ausgewertet.
Zwingend ist nur, dass der Körper gültiges JSON mit den fünf Feldern ist.

### 2.4 Zustellgarantie

RabbitMQ garantiert **mindestens einmal**, nicht genau einmal. Dieselbe Nachricht kann mehrfach
ankommen: nach einem Absturz vor der Bestätigung, nach einem Neustart des Brokers, oder wenn ein
Sender sie doppelt publiziert. Der Dienst muss damit umgehen (Abschnitt 3.3).

---

## 3. Verhalten

### 3.1 Normalfall

1. Der Dienst hängt mit **genau einem Konsumenten** an der Queue `chat.persist`. Die Bestätigung
   (ACK) macht er **von Hand**, nicht das Framework.
2. Er sammelt Nachrichten zu einem Paket. Ein Paket ist fertig, sobald **500 Nachrichten** da sind
   **oder 200 ms** seit der ersten Nachricht des Pakets vergangen sind, was zuerst eintritt.
3. Jede Nachricht des Pakets wird einzeln in ein Objekt umgewandelt (Abschnitt 3.4).
4. Alle gültigen Nachrichten werden mit **einer Transaktion** geschrieben:
   `INSERT … ON CONFLICT (id) DO NOTHING`, als JDBC-Batch.
5. **Erst nach dem Commit** bestätigt der Dienst jede Nachricht des Pakets bei RabbitMQ.

**Begründung Schritt 5.** Stürzt der Dienst zwischen Schreiben und Bestätigen ab, stellt RabbitMQ
das Paket erneut zu. Die Nachricht ist dann doppelt *angekommen*, aber nur einmal *gespeichert*
(Abschnitt 3.3). Bestätigte man vor dem Commit, wäre sie bei einem Absturz für immer weg.

**Begründung Schritt 2, Zeitlimit.** Das Limit von 200 ms gilt für das **ganze Paket**, gemessen ab
der ersten Nachricht. `PLANUNG.md` nennt `setReceiveTimeout(200)`. Das wäre nur die Wartezeit auf
die *nächste* Nachricht: kommt alle 150 ms eine, wird das Paket nie „zu langsam" und füllt sich erst
bei 500 Stück, also nach über einer Minute. Spring AMQP 3.2 hat dafür `setBatchReceiveTimeout`.
Beides wird auf 200 ms gesetzt, und ein Test beweist das Verhalten (Plan, Aufgabe 6).

**Begründung Prefetch.** RabbitMQ liefert höchstens so viele unbestätigte Nachrichten an einen
Konsumenten, wie der Prefetch erlaubt. Ist er kleiner als das Paket (z. B. 250 bei Paket 500), kann
das Paket nie voll werden und läuft immer ins Zeitlimit. Prefetch = Paketgrösse.

### 3.2 Fehlerfälle im Überblick

| # | Fehlerfall | Verhalten | Ergebnis |
|---|---|---|---|
| F1 | Dieselbe `id` kommt zweimal (S5) | `ON CONFLICT DO NOTHING` überspringt die Zeile, gilt als Erfolg, ACK | genau eine Zeile |
| F2 | Datenbank nicht erreichbar (S7) | Paket **nicht** bestätigen, alle 2 s neu versuchen, ohne Neustart | Nachrichten warten, dann geschrieben |
| F3 | Nachricht ist kein gültiges JSON oder es fehlt ein Feld | sofort ablehnen ohne Wiedereinreihen | Nachricht liegt in `chat.dlq` |
| F4 | Datenbank lehnt eine Zeile ab (zu lang, unbekannter Raum, unzulässiges Zeichen) | Paket zurückrollen, **dasselbe Paket Zeile für Zeile** schreiben, nur die kaputte Zeile ablehnen | Rest gespeichert, eine Zeile in `chat.dlq` |
| F5 | Unbekannter Datenbankfehler | wie F2: warten und neu versuchen | nichts geht verloren |
| F6 | Dienst wird zwischen Commit und ACK gestoppt (S4) | RabbitMQ stellt das Paket wieder zu | doppelt zugestellt, einmal gespeichert (F1) |
| F7 | RabbitMQ nicht erreichbar | Verbindung baut sich von selbst wieder auf; unbestätigte Pakete kommen erneut | wie F6 |
| F8 | Unerwarteter Programmfehler im Listener | der Listener fängt ihn selbst ab und gibt das **ganze Paket** mit `nack` an die Queue zurück; nach 3 Zustellungen wandert es in `chat.dlq` | Sicherheitsnetz (`x-delivery-limit: 3`) |
| F9 | Zweite Instanz (S6) | konkurrierender Konsument an derselben Queue | jede Nachricht geht an genau eine Instanz |
| F10 | Dienst wird beendet (SIGTERM) | neue Lieferung stoppt, Warteschleife aus F2 wird unterbrochen, unbestätigte Nachrichten kommen zurück in die Queue | nichts verloren |

### 3.3 Begründungen zu den Fehlerfällen

**F1 — Duplikat: warum `ON CONFLICT DO NOTHING` und nicht ein Duplikat-Test vorher.**
Der Test „gibt es die ID schon?" und das anschliessende Schreiben sind zwei Schritte. Bei zwei
Instanzen kann dazwischen die andere Instanz dieselbe Zeile schreiben. Die Datenbank entscheidet
mit dem Primärschlüssel atomar. `DO NOTHING` (statt `DO UPDATE`) heisst: die zuerst gespeicherte
Fassung bleibt, eine wiederholte Zustellung ändert nie etwas. Voraussetzung ist, dass die ID vom
`chat-service` kommt und nicht von der Datenbank. Eine `SERIAL`-ID würde bei jeder Wiederholung eine
neue Zeile erzeugen. Innerhalb eines einzigen Pakets darf dieselbe ID sogar zweimal vorkommen, das
ist bei `DO NOTHING` erlaubt.

**F2 — Datenbank weg: warum warten und nicht ablehnen oder beenden.**
- *Ablehnen mit Wiedereinreihen* (`nack` mit `requeue`): RabbitMQ stellt sofort wieder zu, es
  entsteht eine Endlosschleife mit voller Geschwindigkeit. Jede Runde zählt ausserdem gegen das
  `x-delivery-limit`, nach drei Runden landeten **gesunde** Nachrichten in `chat.dlq`, nur weil die
  Datenbank kurz weg war.
- *Dienst beenden:* ohne Neustartregel ist der Schreiber danach tot. S7 verlangt ausdrücklich, dass er
  ohne Neustart von Hand weiterläuft.
- *Warten:* der Konsument hält die Nachrichten unbestätigt. Die Queue wächst sichtbar in der
  Management-UI. Das ist gewollt (Backpressure, `PLANUNG.md` 2.4): lieber ein sichtbar wachsender
  Rückstau als eine still verlorene Nachricht.

Damit ein Ausfall schnell auffällt und nicht ewig hängt, setzt die JDBC-Verbindung
`connectTimeout=5` und `socketTimeout=30` (Sekunden), der Verbindungspool wartet höchstens 5 s auf
eine Verbindung. Die Wartezeit zwischen zwei Versuchen ist `DB_RETRY_PAUSE_MS` (2000).

**F3 und F4 — Giftnachricht: nur ablehnen, was sicher nie klappt.**
Ein Fehler zählt nur dann als „diese Zeile ist kaputt", wenn PostgreSQL das mit einem SQLSTATE der
Klasse **22** (Datenfehler, z. B. Text zu lang, Nullzeichen) oder **23** (Integritätsverletzung,
z. B. Raum unbekannt) meldet. **Alles andere** wird wie F2 behandelt und wiederholt. Das ist die
sichere Richtung: eine unbekannte Fehlerart blockiert den Schreiber sichtbar, statt Nachrichten still
in die Dead-Letter-Queue zu leeren.

Warum F4 Zeile für Zeile schreibt: eine einzige kaputte Zeile lässt die ganze Transaktion von 500
scheitern (`PLANUNG.md` 2.4). Ohne den Einzelweg stünden alle 500 in der Dead-Letter-Queue. Mit ihm
nur die eine. Der Einzelweg gilt nur für das betroffene Paket, der Normalfall bleibt gebündelt.

**F8 — Sicherheitsnetz.** Der Listener fängt die erwarteten Fehler selbst ab. Was trotzdem
herauskommt, ist ein Programmfehler. Ihn endlos zu wiederholen wäre falsch, deshalb greift das
`x-delivery-limit: 3` der Queue. Das Zurückgeben macht der Listener **von Hand**: bei manueller
Bestätigung gibt Spring AMQP ein Paket nach einer Ausnahme *nicht* von selbst zurück (im Quelltext von
`BlockingQueueConsumer.rollbackOnExceptionIfNecessary` geprüft). Die Nachrichten blieben unbestätigt
hängen, und der Konsument stünde still. Deshalb ein einziges `basicNack(höchsteNummer, multiple=true,
requeue=true)`: es gibt alles zurück, was in diesem Paket noch offen ist, und lässt bereits
Bestätigtes in Ruhe.

**F9 — Zwei Instanzen brauchen keine Absprache.** Beide hängen an derselben Queue, RabbitMQ verteilt
die Nachrichten (Competing Consumers). Es gibt keinen gemeinsamen Zustand im Dienst. Schreiben
beide zufällig dieselbe ID (F1), entscheidet der Primärschlüssel. Eine Reihenfolge über Instanzen
hinweg gibt es nicht. Sie wird auch nicht gebraucht, weil `sent_at` vom Sender kommt und der Verlauf
danach sortiert wird.

### 3.4 Umwandlung einer Nachricht

`bytes → Text (UTF-8) → JSON → Objekt mit fünf Feldern`. Ungültig ist eine Nachricht bei:
kein JSON · ein Pflichtfeld fehlt oder ist `null` · `id` oder `roomId` keine UUID · `sentAt` kein
ISO-8601-Zeitpunkt. `sender` und `text` werden hier **nicht** auf Länge geprüft. Das übernimmt die
Datenbank (F4). So gibt es genau eine Stelle, die Längen festlegt.

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle `message`

Die Tabelle bleibt so, wie sie in `db/01-schema.sql` und `PLANUNG.md` Abschnitt 3 steht. Der Dienst
braucht **keine** Änderung am Schema.

| Spalte | Typ | Entscheid und Begründung |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | kommt vom `chat-service`. Macht das Wiederholen gefahrlos (F1). |
| `room_id` | `UUID NOT NULL REFERENCES room(id)` | Fremdschlüssel bleibt: ein unbekannter Raum ist ein Datenfehler (Klasse 23), die Nachricht landet in `chat.dlq` und ist dort auffindbar. Preis: pro Zeile ein Nachschlagen in `room`. |
| `sender` | `VARCHAR(100) NOT NULL` | Benutzername, kurz. Die Grenze fängt Unsinn ab (F4). |
| `text` | `TEXT NOT NULL` | Länge bewusst nicht hier begrenzt, das ist Sache des `chat-service`. |
| `sent_at` | `TIMESTAMPTZ NOT NULL`, **kein** `DEFAULT now()` | Sendezeit vom `chat-service`. Ein Default hielte den Zeitpunkt des Schreibens fest, und alle 500 Nachrichten eines Pakets hätten dieselbe Zeit. PostgreSQL speichert auf Mikrosekunden, der Sender liefert Nanosekunden. Es wird gerundet, die Reihenfolge bleibt. |

**Index** `idx_message_room_time ON message (room_id, sent_at DESC)`. Er ist für das *Lesen* da
(Verlauf pro Raum, neueste zuerst). Er kostet beim *Schreiben* eine zusätzliche Indexpflege pro
Zeile. Das ist der Preis des Lesewegs und bei Paketen gut zu tragen.

**Schreibbefehl** (genau ein Statement, für jede Nachricht ein Parametersatz):

```sql
INSERT INTO message (id, room_id, sender, text, sent_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (id) DO NOTHING
```

Mit `reWriteBatchedInserts=true` in der JDBC-URL schreibt der PostgreSQL-Treiber die 500
Parametersätze zu **einem** mehrzeiligen `INSERT` um. Ohne diese Option ginge jede Zeile einzeln über
die Leitung.

### 4.2 Wo das Schema entsteht

`db/01-schema.sql` wird vom PostgreSQL-Image **einmalig** beim ersten Start mit leerem Datenträger
ausgeführt (`/docker-entrypoint-initdb.d`). Der `batch-writer` legt nichts an. Folge: eine
Schemaänderung wirkt erst nach `docker compose down -v`. In Produktion nähme man dafür ein Werkzeug
wie Flyway. Für den Unterricht ist das die einfachste Lösung.

### 4.3 Wo Exchange und Queues entstehen

Sie stehen in der Datei `rabbitmq/definitions.json`. Der Broker spielt sie **nach seinem Start** mit
`rabbitmqctl import_definitions` ein. Ausgelöst wird das vom Healthcheck des Broker-Containers
(`rabbitmq/healthcheck.sh`): solange `chat.persist` nicht existiert, importiert er die Datei. Der
Broker gilt erst als gesund, wenn die Queue da ist. Die Tests spielen dieselbe Datei mit demselben
Befehl ein.

| Objekt | Art | Einstellungen |
|---|---|---|
| Exchange `chat.messages` | fanout, dauerhaft | wird auch vom `chat-service` angemeldet, identische Werte |
| Queue `chat.persist` | **Quorum-Queue**, dauerhaft | `x-delivery-limit: 3`, Dead-Letter-Exchange `""` (Standard-Exchange) mit Routing-Key `chat.dlq` |
| Queue `chat.dlq` | klassisch, dauerhaft | nimmt abgelehnte Nachrichten auf |
| Bindung | `chat.messages` → `chat.persist` | |

**Warum der Broker und nicht der Dienst.** Legte der `batch-writer` die Queue an, gäbe es nach
`docker compose up` ein Zeitfenster, in dem der `chat-service` schon Nachrichten annimmt, die Queue
aber noch nicht existiert. Ein Exchange ohne gebundene Queue **verwirft still**. Weil beide Dienste
erst starten, wenn der Broker gesund ist, und der Broker erst gesund ist, wenn die Queue existiert,
gibt es dieses Fenster nicht. Ausserdem gibt es nur *eine* Quelle für die Queue-Einstellungen.

**Warum nicht `load_definitions` beim Start.** Das wurde zuerst geplant und ausprobiert. Es geht
nicht: sobald der Broker beim Start Definitionen lädt, meldet er
*«Will not seed default virtual host and user: have definitions to load»* und legt den Benutzer aus
`RABBITMQ_DEFAULT_USER` **nicht** an. Dann kämen die Zugangsdaten entweder ins Repository (in die
Definitionsdatei) oder der Login schlüge fehl. Der Import nach dem Start lässt den Benutzer
unangetastet, und ein zweiter Import ist harmlos (gleiche Werte, nichts ändert sich).

**Namensabweichung.** `PLANUNG.md` nennt die Dead-Letter-Queue `chat.persist.dlq`. Der Auftrag von
Bewertung 1 nennt `chat.dlq`. Massgebend ist der Auftrag: **`chat.dlq`**.

### 4.4 Umgebungsvariablen

Jede Variable des Dienstes. Die Werte für die Datenbank kommen aus `.env` (Vorlage: `.env.example`).
Die Datei `.env` selbst gehört **nicht** ins Repository.

| Variable | Pflicht | Standard | Bedeutung |
|---|---|---|---|
| `POSTGRES_USER` | ja | — | Datenbankbenutzer. Derselbe Wert konfiguriert den Datenbank-Container. |
| `POSTGRES_PASSWORD` | ja | — | Passwort dazu. |
| `POSTGRES_DB` | ja | — | Name der Datenbank. |
| `POSTGRES_HOST` | nein | `postgres` | Hostname der Datenbank im Netz `chat-net`. |
| `POSTGRES_PORT` | nein | `5432` | Port der Datenbank. |
| `RABBITMQ_DEFAULT_USER` | ja | — | Broker-Benutzer. Derselbe Wert konfiguriert den Broker-Container. |
| `RABBITMQ_DEFAULT_PASS` | ja | — | Passwort dazu. |
| `RABBITMQ_HOST` | nein | `rabbitmq` | Hostname des Brokers im Netz `chat-net`. |
| `RABBITMQ_PORT` | nein | `5672` | Port des Brokers. |
| `BATCH_SIZE` | nein | `500` | Höchstzahl Nachrichten pro Paket. Zugleich der Prefetch. |
| `BATCH_TIMEOUT_MS` | nein | `200` | Zeitlimit pro Paket in Millisekunden, ab der ersten Nachricht. |
| `DB_RETRY_PAUSE_MS` | nein | `2000` | Pause zwischen zwei Versuchen, wenn die Datenbank fehlt (F2). |

`BATCH_SIZE` und `BATCH_TIMEOUT_MS` sind Startwerte aus `PLANUNG.md` und **nicht gemessen**
(`PLANUNG.md`, offener Punkt 3). Deshalb sind sie einstellbar.

### 4.5 Betrieb im Compose-Stack

- Dienste: `postgres`, `rabbitmq`, `chat-service`, `batch-writer`, alle im Netz `chat-net`.
- **Kein Dienst veröffentlicht einen Port** auf dem Host (kein `ports:`-Eintrag). Zugriff von aussen
  geht nur über `docker compose exec`.
- Startreihenfolge: `postgres` meldet sich als gesund, wenn es Anfragen beantwortet. `rabbitmq`, wenn
  es läuft **und** `chat.persist` existiert (Abschnitt 4.3). `chat-service` und `batch-writer`
  starten erst danach.
- `batch-writer` hat **keine** Neustartregel. Ein gestoppter Dienst (S4) soll gestoppt bleiben.
  Ein Fehler beim Start bleibt so sichtbar und dreht sich nicht im Kreis.
- Skalieren geht ohne Anpassung: `docker compose up -d --scale batch-writer=2` (deshalb kein fester
  `container_name`).

---

## 5. Abnahmekriterien

Jedes Kriterium ist ein Befehl und ein erwarteter Wert. Grundlage ist ein frischer Klon mit
`cp .env.example .env`. Die Szenarien laufen in dieser Reihenfolge auf demselben Stack. Die Abkürzung
`psql` steht für `docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c`
(Variablen aus `.env`). `senden` steht für einen `POST` auf den `chat-service` von innen:
`docker compose exec -T chat-service curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/messages -H 'Content-Type: application/json' -d '{"roomId":"11111111-1111-1111-1111-111111111111","sender":"pruefung","text":"Nr. <n>"}'`
(Antwort `202`).

| Nr | Kriterium | Messbefehl | Erwartet |
|---|---|---|---|
| S1 | Alle Tests grün in einem Lauf | `mvn clean test` im Wurzelverzeichnis | `BUILD SUCCESS`, 0 Fehler |
| S2a | Alle Dienste laufen | `docker compose ps --status running --format '{{.Service}}'` | `postgres`, `rabbitmq`, `chat-service`, `batch-writer` |
| S2b | Kein Port veröffentlicht | `docker compose ps --format '{{.Ports}}'` | keine Zeile mit `->` |
| S3a | 1000 gesendet, alle gespeichert | `psql "SELECT count(*) FROM message WHERE sender='pruefung'"` | `1000` innerhalb 60 s |
| S3b | Queue leer | `docker compose exec -T rabbitmq rabbitmqctl list_queues name messages` | `chat.persist 0` |
| S4a | Schreiber gestoppt, 1000 gesendet, gestartet: nichts fehlt | `docker compose stop batch-writer`, senden, `docker compose start batch-writer`, dann `psql`-Zählung | Anzahl steigt um exakt 1000 |
| S4b | Höchstens 100 Transaktionen | `psql "SELECT xact_commit FROM pg_stat_database WHERE datname=current_database()"` vor Start und nach Ende, Differenz bilden | Differenz ≤ 100 |
| S5 | Duplikat | dieselbe Nachricht (Format aus 2.1, nur `content_type: application/json`, **ohne** `__TypeId__`) zweimal an `chat.persist` | genau 1 Zeile mit dieser `id`, `chat.dlq` hat `0` Nachrichten |
| S6a | Zwei Konsumenten | `docker compose up -d --scale batch-writer=2`, dann `rabbitmqctl list_queues name consumers` | `chat.persist 2` |
| S6b | Nichts doppelt, nichts fehlt | 1000 senden, `SELECT count(*), count(DISTINCT id) FROM message WHERE sender='pruefung'` | beide Zahlen gleich, plus 1000 |
| S7 | Datenbank fällt 15 s aus | `docker compose stop postgres`, 300 senden, 15 s warten, `docker compose start postgres` | nach ≤ 90 s alle 300 gespeichert, `docker compose ps batch-writer` zeigt keinen Neustart |
| S8a | Keine Streams | `grep -rn "stream()\|\.stream\b\|Stream<" batch-writer/src` | keine Treffer |
| S8b | Kommentar über jeder Klasse und Methode | Sichtprüfung, ergänzt durch den Test `CommentRulesTest` | Test grün |
| S8c | `.env` nicht im Repo | `git ls-files .env` | leere Ausgabe |

Die Tests unter `batch-writer/src/test/` decken S5 (Duplikat), S7 (Datenbankausfall), F3, F4 und das
Zeitlimit mit **echtem RabbitMQ und echter PostgreSQL** ab (Testcontainers). Sie laufen in
`mvn clean test` mit, ein Stack muss dafür nicht laufen. Docker muss laufen.
