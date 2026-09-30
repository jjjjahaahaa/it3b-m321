# batch-writer — Umsetzungsplan

Grundlage: [`spec-batch-writer.md`](spec-batch-writer.md). Jede Aufgabe ist **ein Commit**, jede hat
einen **Test**, der vor der Änderung fehlschlägt oder gar nicht existiert und danach grün ist.
Die Reihenfolge der Aufgaben ist die Reihenfolge im `git log`.

Als Vorbild diente der [Bootstrap-Plan des chat-service](plan/2026-09-04-chat-service-bootstrap.md).
(Der Auftrag nennt ihn `docs/plan-chat-service.md`. In diesem Fork liegt er unter dem
oben genannten Pfad.)

## Globale Vorgaben

| Vorgabe | Wert |
|---|---|
| Sprache | Code **englisch**, Kommentare, Logs, Commit-Messages und Dokumente **deutsch** |
| Stil | `CLAUDE.md`: eine Anweisung pro Zeile, **keine Streams**, keine Reflection, kurze Methoden |
| Kommentare | über **jeder** Klasse und **jeder** Methode ein bis zwei Sätze: was und warum |
| Java / Spring | Java 21, Spring Boot 3.5.16 (wie der `chat-service`) |
| Tests | echte PostgreSQL und echtes RabbitMQ in Containern (Testcontainers), keine Attrappen |
| Voraussetzung | Docker läuft. Ohne Docker schlagen die Tests des `batch-writer` mit klarer Meldung fehl |
| Zeiten warten | Tests warten mit einer eigenen Schleife bis zu einer Obergrenze, nie mit einem festen `sleep` |
| Testisolation | Alle Tests, die die Anwendung starten, erben von `IntegrationTest`. Diese schliesst den Spring-Kontext nach der Klasse. Sonst blieben alte Kontexte im Zwischenspeicher am Leben, und ihre Listener stünden als Konkurrenten an `chat.persist` |

## Zielbild der Dateien

```
it3b-m321/
├── pom.xml                              Eltern-POM, Module: chat-service, batch-writer
├── .env.example                         Vorlage für .env (die .env selbst ist ignoriert)
├── docker-compose.yml                   postgres, rabbitmq, chat-service, batch-writer
├── rabbitmq/
│   ├── definitions.json                 Exchange, Queues, Bindung
│   └── healthcheck.sh                   Broker gesund = läuft und chat.persist existiert
├── scripts/abnahme.sh                   S2 bis S8 als Skript
├── chat-service/Dockerfile              (neu, damit der Dienst im Stack läuft)
└── batch-writer/
    ├── Dockerfile
    ├── pom.xml
    └── src/
        ├── main/java/ch/benedict/m321/batchwriter/
        │   ├── BatchWriterApplication.java
        │   ├── message/   IncomingMessage · MessageParser · InvalidMessageException
        │   │              MessageRepository · BadDataException · DatabaseUnavailableException
        │   └── rabbit/    RabbitConfiguration · PersistListener
        ├── main/resources/application.yml
        └── test/…         siehe Aufgaben
```

---

## Aufgabe 1 — Eltern-POM, `chat-service` wird Modul

**Warum zuerst:** S1 verlangt `mvn clean test` im Wurzelverzeichnis. Alles Weitere baut auf einem
Maven-Lauf auf, der beide Dienste kennt. Das ändert noch kein Verhalten und ist deshalb der
sicherste Anfang.

- `pom.xml` im Wurzelverzeichnis (Spring-Boot-Eltern, `packaging` = `pom`, Modul `chat-service`).
- `chat-service/pom.xml` bekommt diesen Eltern-POM statt des Spring-Boot-Eltern.
- Der frühere Entscheid «kein Eltern-POM» (Bootstrap-Plan) entfällt, der Auftrag verlangt ihn.

**Test:** `mvn clean test` im Wurzelverzeichnis → die bisherigen 4 Tests des `chat-service` sind grün.
Sie laufen auch ohne laufende Container (geprüft).

**Commit:** `build: Eltern-POM einfuehren, chat-service als Modul`

## Aufgabe 2 — Broker-Topologie und echte Container für die Tests

**Warum jetzt:** Alle folgenden Tests brauchen eine echte Datenbank und einen echten Broker. Die
Queue-Einstellungen (Quorum, `x-delivery-limit`, Dead-Letter) sind Teil des Vertrags. Sie werden
zuerst festgeschrieben und geprüft, bevor ein Konsument darauf aufsetzt.

- `rabbitmq/definitions.json` (Spezifikation 4.3). Die Datei wird nach dem Start mit
  `rabbitmqctl import_definitions` eingespielt. `load_definitions` beim Start wurde ausprobiert und
  verworfen, weil der Broker dann den Benutzer aus `.env` nicht mehr anlegt.
- Modul `batch-writer`: `pom.xml` (Spring AMQP, JDBC, PostgreSQL-Treiber, Jackson, Testcontainers),
  leere `BatchWriterApplication`.
- Testhilfe `TestInfrastructure`: startet **einmal** ein PostgreSQL mit `db/01-schema.sql` und ein
  RabbitMQ, in das sie `rabbitmq/definitions.json` einspielt. Beide sind für alle Testklassen
  gemeinsam.

**Test:** `InfrastructureTest` — `chat.persist` ist eine Quorum-Queue mit Limit 3 und Dead-Letter auf
`chat.dlq`, `chat.dlq` existiert, die Bindung an `chat.messages` besteht, die Tabelle `message` hat
die fünf Spalten.

**Commit:** `feat(batch-writer): Modul anlegen, Broker-Topologie und Test-Container`

## Aufgabe 3 — Vertrag: Nachricht umwandeln

**Warum jetzt:** Reine Logik ohne Netzwerk, schnell testbar. Alles Spätere setzt ein gültiges
Objekt voraus.

- `batch-writer/src/test/resources/chat-service-message.json` — die am laufenden `chat-service`
  beobachtete Nachricht (Spezifikation 2.1).
- `IncomingMessage` (Datensatz), `MessageParser`, `InvalidMessageException`.

**Test:** `MessageParserTest` — die beobachtete Nachricht wird gelesen, inklusive 9 Nachkommastellen ·
unbekanntes Zusatzfeld wird ignoriert · **kein** `__TypeId__` nötig (es wird nur der Körper gelesen) ·
kein JSON, fehlendes Feld, `null`-Feld, keine UUID, kaputtes Datum → `InvalidMessageException`.

**Commit:** `feat(batch-writer): Nachrichtenkoerper aus der Queue in ein Objekt umwandeln`

## Aufgabe 4 — Schreiben: ein Paket, eine Transaktion, Duplikate egal

**Warum jetzt:** Das ist der Kern (`INSERT … ON CONFLICT DO NOTHING`). Er wird an der echten Datenbank
geprüft, bevor RabbitMQ ins Spiel kommt. So sind Fehler in Datenbank und Broker getrennt
auffindbar.

- `MessageRepository.insertBatch` (eine Transaktion, JDBC-Batch), `insertOne`.
- `BadDataException` (SQLSTATE-Klasse 22/23), `DatabaseUnavailableException` (alles andere).

**Test:** `MessageRepositoryTest` — 500 Nachrichten → 500 Zeilen in **einer** Transaktion (gemessen an
`xact_commit`) · dieselbe ID im selben Paket und in einem zweiten Paket → eine Zeile · zu langer
`sender` und unbekannter Raum → `BadDataException`, und die Zeilen davor im Paket sind **nicht**
gespeichert (Rollback) · Datenbank unter falschem Port → `DatabaseUnavailableException`.

**Commit:** `feat(batch-writer): Nachrichten gebuendelt in einer Transaktion schreiben`

## Aufgabe 5 — Listener: Queue → Datenbank → ACK (Normalfall und Duplikat, S5)

**Warum jetzt:** Erst wenn Parser und Repository einzeln stimmen, verbindet man sie mit dem Broker.
Der Ende-zu-Ende-Weg wird sichtbar, bevor Bündeln und Fehlerfälle dazukommen.

- `application.yml` mit allen Umgebungsvariablen (Spezifikation 4.4).
- `RabbitConfiguration` (Listener-Fabrik: manuelle Bestätigung, ein Konsument, Prefetch =
  Paketgrösse), `PersistListener` (parsen, schreiben, **danach** ACK).

**Test:** `PersistListenerTest` — drei Nachrichten in `chat.persist` → drei Zeilen, Queue leer ·
**Duplikat (S5):** dieselbe Nachricht zweimal, nur mit `content_type: application/json`, ohne
`__TypeId__` → genau eine Zeile, `chat.dlq` leer.

**Commit:** `feat(batch-writer): Listener liest chat.persist, schreibt und bestaetigt erst nach dem Commit`

## Aufgabe 6 — Paketgrösse und Zeitlimit beweisen

**Warum jetzt:** Das Bündeln ist der Zweck des Dienstes (S4: höchstens 100 Transaktionen). Die
Einstellung ist heikel (Spezifikation 3.1: Zeitlimit für das ganze Paket, Prefetch). Deshalb hat sie
einen eigenen Test statt nur einer Konfigurationszeile.

- Falls die Tests es zeigen: `BATCH_SIZE`, `BATCH_TIMEOUT_MS` in die Fabrik verdrahten,
  `setBatchReceiveTimeout` ergänzen.

**Test:** `BatchingTest` — (a) Listener angehalten, 1000 Nachrichten in die Queue, Listener gestartet →
alle 1000 gespeichert, dafür **höchstens 100** Transaktionen (S4). (b) Eine Nachricht alle 50 ms
für 4 s: jede steht **nach spätestens 1 s** in der Datenbank. Das beweist, dass das Zeitlimit für das
ganze Paket gilt und nicht nur zwischen zwei Nachrichten.

**Commit:** `feat(batch-writer): Paketgroesse und Zeitlimit fuer das ganze Paket`

## Aufgabe 7 — Giftnachrichten: Dead-Letter-Queue und Einzelweg (F3, F4)

**Warum jetzt:** Sie setzt den Normalfall voraus (Aufgabe 5) und die Bündelung (Aufgabe 6), denn der
Einzelweg ist eine Ausnahme *von* der Bündelung.

- Ungültige Nachricht sofort ablehnen (ohne Wiedereinreihen).
- Lehnt die Datenbank eine Zeile ab: dasselbe Paket Zeile für Zeile, nur die kaputte Zeile ablehnen.

**Test:** `PoisonMessageTest` — Paket aus 10 Nachrichten, davon eine kein JSON → 9 gespeichert, 1 in
`chat.dlq` · Paket mit unbekanntem Raum und zu langem Absender → die übrigen gespeichert, die zwei
kaputten in `chat.dlq` · `chat.persist` am Ende leer.

**Commit:** `feat(batch-writer): kaputte Nachrichten in chat.dlq, Rest des Pakets bleibt erhalten`

## Aufgabe 8 — Datenbank fällt aus (F2, S7)

**Warum jetzt:** Der schwierigste Fall, er braucht die Unterscheidung «Zeile kaputt» / «Datenbank weg»
aus Aufgabe 7: er darf weder Nachrichten verlieren (nicht ablehnen) noch den Dienst beenden. Und er
muss stehen, bevor Aufgabe 9 die letzte Auffangstelle auf «ablehnen» umstellt, sonst landeten bei einem
Ausfall gute Nachrichten in der Dead-Letter-Queue.

- Warteschleife mit `DB_RETRY_PAUSE_MS`, unterbrechbar beim Beenden.

**Test:** `DatabaseOutageTest` — Datenbank verweigert alle Verbindungen (`ALLOW_CONNECTIONS false`,
bestehende Verbindungen getrennt), 300 Nachrichten gesendet, 12 s Ausfall bei 4 s Wartepause →
**höchstens 6 Schreibversuche** (eine Endlosschleife käme auf über 10), danach Datenbank wieder frei →
alle 300 in der Tabelle, `chat.dlq` leer, der Listener wurde nicht neu gestartet.

**Commit:** `feat(batch-writer): bei Datenbankausfall warten und wiederholen statt ablehnen`

## Aufgabe 9 — Unerwartete Fehler eingrenzen statt zurückgeben (F8)

*Diese Aufgabe kam nachträglich dazu.* Beim Bau der Datenbank-Warteschleife zeigte eine Messung am Broker,
dass `nack` mit `requeue` das Zustelllimit nicht verbraucht (Spezifikation F2, F8). Der Listener aus
Aufgabe 5, der ein Paket nach einem Fehler zurück in die Queue gab, konnte sich so endlos im Kreis
drehen. Dieser Schritt ersetzt das durch Eingrenzen und Ablehnen.

**Warum jetzt:** Er baut auf dem Einzelweg aus Aufgabe 7 auf. Und erst seit Aufgabe 8 ist «Datenbank
weg» sicher abgefangen. Jetzt darf die letzte Auffangstelle ablehnen statt zurückgeben, ohne dass ein
Ausfall gute Nachrichten in die Dead-Letter-Queue schickt.

- Jede Ausnahme bei einer einzelnen Nachricht (Umwandeln, Schreiben) → diese Nachricht nach `chat.dlq`.
- Ein Fehler beim Schreiben des Pakets, der nicht «Datenbank weg» ist → Einzelweg.
- Letzte Auffangstelle im Listener: alles Offene ablehnen (`requeue=false`), nie zurückgeben.

**Test:** `UnexpectedErrorTest` — das Repository wirft für eine bestimmte Nachricht einen
Programmfehler (`@MockitoSpyBean`). Paket mit 5 guten, der Fehlernachricht und 5 guten → 10 gespeichert,
1 in `chat.dlq`, `chat.persist` leer, Listener läuft weiter.

**Commit:** `fix(batch-writer): unerwartete Fehler auf die Nachricht eingrenzen, nie zurueck in die Queue`

## Aufgabe 10 — Stack: Dockerfiles, Compose, `.env.example`, keine Ports

**Warum jetzt:** Ein Dienst, der nur in Tests läuft, hilft S2 bis S7 nicht. Die Verpackung kommt
zuletzt, weil sie den fertigen Dienst braucht. Der Test dafür ist der echte Start.

- `batch-writer/Dockerfile`, `chat-service/Dockerfile`, `docker-compose.yml`,
  `rabbitmq/healthcheck.sh`, `.env.example`,
  `.env` in `.gitignore`, `chat-service/application.yml` liest Hostnamen und Zugang aus der Umgebung.
- Die bewusste Abweichung aus `docs/betrieb.md` (veröffentlichte Ports) entfällt.

**Test:** frischer Stack aus `.env.example`: `docker compose up -d --build`; alle vier Dienste laufen,
`docker compose ps` zeigt keinen Port nach aussen; eine Nachricht über den `chat-service` steht in
der Tabelle.

**Commit:** `feat(infra): batch-writer und chat-service im Compose-Stack, keine Ports nach aussen`

## Aufgabe 11 — Kommentarregeln als Test (S8)

**Warum jetzt:** Sobald der Code steht, lässt sich die Regel «Kommentar über jeder Klasse und
Methode» prüfen und nachziehen. Vorher würde der Test bei jedem Zwischenstand fehlschlagen. Er steht
vor dem Abnahmeskript, weil dessen S8 ihn aufruft.

**Test:** `CommentRulesTest` — durchsucht `batch-writer/src`, meldet jede Klasse und Methode ohne
Kommentar davor sowie jedes `stream()`. Erst rot (fehlende Kommentare), dann grün.

**Commit:** `test(batch-writer): Kommentarregeln und Stream-Verbot automatisch pruefen`

## Aufgabe 12 — Abnahmeskript für S2 bis S8

**Warum jetzt:** Die Szenarien der Spezifikation (Abschnitt 5) werden ausführbar. Erst am fertigen
Stack lassen sie sich ehrlich messen, und S8 im Skript ruft den Regeltest aus Aufgabe 11 auf. Das Skript
ersetzt «bei mir geht es» durch eine Tabelle.

**Test:** `scripts/abnahme.sh` auf einem frischen Stack → S2 bis S8 alle `OK`. Die Messwerte kommen in
den Abschnitt «Messwerte» unten.

**Commit:** `test: Abnahmeskript fuer die Szenarien S2 bis S8`

## Aufgabe 13 — Doku nachführen

**Warum zuletzt:** Beschreibt den Endstand. README-Tabelle «Stand», `docs/betrieb.md` (neuer Start
ohne veröffentlichte Ports), Messwerte in diesem Plan.

**Test:** die Befehle in `docs/betrieb.md` einmal von Hand ausführen.

**Commit:** `docs: Stand, Betrieb und Messwerte nachfuehren`

---

## Messwerte

Gemessen am 30.09.2026 auf dem Stack aus `.env.example` mit `scripts/abnahme.sh` (S2 bis S8) und
`mvn clean test` (S1). Der Stack lief in einer Docker-Umgebung ohne feste Ports.

| Nr | Szenario | Gemessen | Erwartet | |
|---|---|---|---|---|
| S1 | `mvn clean test` im Wurzelverzeichnis | 37 Tests (4 `chat-service`, 33 `batch-writer`), 0 Fehler, 61 s | ein Lauf, alles grün | ✓ |
| S2 | frischer Stack aus `.env.example` | 4 Dienste laufen, 0 veröffentlichte Ports | alle laufen, kein Port | ✓ |
| S3 | 1000 Nachrichten über `POST` | alle 1000 nach 17 s (Senden 15 s), `chat.persist` = 0 | ≤ 60 s, Queue leer | ✓ |
| S4 | Schreiber gestoppt, 1000 gesendet, gestartet | genau 1000 neu, **12** Transaktionen (gezählt mit `xact_commit`, inklusive der Messabfragen selbst; der Test `BatchingTest` zählt die schreibenden Transaktionen allein: **2**) | ≤ 100 | ✓ |
| S5 | dieselbe Nachricht zweimal, nur `content_type` | 1 Zeile, `chat.dlq` unverändert | 1 Zeile, DLQ leer | ✓ |
| S6 | `--scale batch-writer=2`, 1000 Nachrichten | 2 Konsumenten, 1000 neu, 3000 Zeilen = 3000 verschiedene IDs | beide an der Queue, keine doppelt | ✓ |
| S7 | Postgres 15 s aus, 300 Nachrichten | alle 300 nach 7 s, **kein** Neustart des Schreibers | ≤ 90 s, ohne Neustart | ✓ |
| S8 | Quelltext | 0 Stream-Treffer, `.env` nicht im Repo, `CommentRulesTest` grün | Regeln aus `CLAUDE.md` | ✓ |

Zusätzlich von Hand am laufenden Stack geprüft, weil sie in der Spezifikation stehen:

| Fall | Ergebnis |
|---|---|
| F7 — Broker startet neu, Schreiber läuft | beide Konsumenten hängen danach wieder an der Queue, 100 von 100 Nachrichten angekommen |
| Broker startet neu, während 200 Nachrichten in der Queue liegen | Queue-Inhalt bleibt erhalten (Quorum-Queue + Volume), 200 von 200 angekommen |
| F10 — Dienst wird beendet, während er auf die Datenbank wartet | Stopp nach 7 s (Exit-Code 143), 50 von 50 angekommen, `chat.dlq` leer |

Und die Messungen, die eine Annahme der Planung widerlegt oder bestätigt haben:

| Frage | Messung |
|---|---|
| Reicht `setReceiveTimeout(200)` für «500 oder 200 ms»? | **Nein.** Bei einer Nachricht alle 50 ms waren nach 2 s noch 0 von 65 geschrieben. Mit `setBatchReceiveTimeout` sind es 32 von 64 |
| Schreibt `reWriteBatchedInserts` ein Paket wirklich als einen `INSERT`? | **Ja**, auch mit `ON CONFLICT`. Im Statement-Log: 4 Zeilen → ein `INSERT … VALUES (…),(…),(…),(…)` und ein `COMMIT`. Ohne die Option 4 einzelne Statements |
| Verbraucht `nack` mit `requeue` das `x-delivery-limit`? | **Nein.** 29 Zustellungen derselben Nachricht, nichts in `chat.dlq`. Ein Kanal, der ohne ACK schliesst, verbraucht es: nach 3 Zustellungen liegt die Nachricht in `chat.dlq` |
| Legt der Broker den Benutzer aus `RABBITMQ_DEFAULT_USER` an, wenn er beim Start Definitionen lädt? | **Nein** («Will not seed default virtual host and user»). Deshalb der Import nach dem Start |
| Wie viele Schreibversuche macht der Dienst in 12 s Datenbankausfall? | Mit Warteschleife (4 s Pause) höchstens 6. Ohne (Paket sofort wieder einreihen) 10 |

## Abweichungen vom ersten Plan

Der Plan wurde beim Bauen an drei Stellen geändert. Jede Änderung steht als eigener `docs:`-Commit im
`git log`, vor dem Code, der sie umsetzt:

1. **Definitionen nach dem Start einspielen** statt `load_definitions` (Aufgabe 2, Commit
   «Definitionen nach dem Broker-Start einspielen»).
2. **Neue Aufgabe 9** (unerwartete Fehler eingrenzen), und Reihenfolge der Aufgaben 8 und 9 getauscht,
   nachdem gemessen war, dass `nack` mit `requeue` das Zustelllimit nicht verbraucht.
3. **Kommentarregeln (Aufgabe 11) vor das Abnahmeskript (Aufgabe 12)**, weil das Skript den Regeltest
   aufruft.

Dazu kam ein Test-Commit ohne eigene Aufgabe: `IntegrationTest` als gemeinsame Basisklasse, damit nur ein
Listener an `chat.persist` liest (Vorgabe «Testisolation» oben).
