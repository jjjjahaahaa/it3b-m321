# batch-writer erklärt — Vorbereitung auf das Code-Review

Diese Seite ist zum Lernen da. Sie führt durch den Code in der Reihenfolge, in der eine Nachricht
ihn durchläuft, und sagt zu jedem Szenario, **welche Stelle** entscheidet, ob es besteht. Die
Begründungen im Detail stehen in der [Spezifikation](spec-batch-writer.md), die Messwerte im
[Plan](plan-batch-writer.md).

Alle Pfade beziehen sich auf `batch-writer/src/main/java/ch/benedict/m321/batchwriter/`.

## 1. Der Weg einer Nachricht

1. Der `chat-service` publiziert die Nachricht auf den Exchange `chat.messages`. Der verteilt sie an
   die Queue `chat.persist`. Angelegt hat sie der Broker selbst (`rabbitmq/definitions.json`,
   eingespielt von `rabbitmq/healthcheck.sh`).
2. Der Listener-Container sammelt Nachrichten, bis **500** da sind oder **200 ms** vergangen sind
   (`rabbit/RabbitConfiguration.java`). Dann ruft er `PersistListener.onBatch` mit der ganzen Liste auf.
3. `parseOrReject` wandelt jede Nachricht mit `message/MessageParser.java` um. Ist eine kaputt,
   wird **nur sie** abgelehnt (`reject`) und landet in `chat.dlq`.
4. `writeAndAcknowledge` schreibt den Rest mit `writePatiently` → `MessageRepository.insertBatch` in
   **einer** Transaktion.
5. **Erst nach dem Commit** kommt das ACK für jede Nachricht. Erst jetzt löscht RabbitMQ sie.

Merksatz: **erst speichern, dann bestätigen.** Umgekehrt wäre eine Nachricht bei einem Absturz
zwischen den beiden Schritten für immer weg.

## 2. Wo sich jedes Szenario entscheidet

| Szenario | Stelle im Code | In einem Satz |
|---|---|---|
| S1 `mvn clean test` | `pom.xml` (Eltern-POM), `batch-writer/src/test/` | Testcontainers startet echte PostgreSQL und echtes RabbitMQ, ein Stack muss nicht laufen |
| S2 keine Ports | `docker-compose.yml` | Kein `ports:`-Eintrag, alles im Netz `chat-net` |
| S3 1000 Nachrichten | `RabbitConfiguration.batchContainerFactory` | Pakete à 500, Zeitlimit für das ganze Paket |
| S4 ≤ 100 Transaktionen | `MessageRepository.writeInOneTransaction` | Ein Paket = ein `commit()`; 1000 Nachrichten = 2 Transaktionen |
| S5 Duplikat | `MessageRepository.INSERT_SQL` und `MessageParser` | `ON CONFLICT (id) DO NOTHING`; der Parser liest keine Header |
| S6 zwei Instanzen | `RabbitConfiguration` (ein Konsument pro Instanz) | RabbitMQ gibt jede Nachricht nur einem Konsumenten; der Primärschlüssel fängt den Rest ab |
| S7 Datenbank weg | `PersistListener.writePatiently` | Kein ACK, kein NACK: warten und alle 2 s neu versuchen |
| S8 Regeln | `CommentRulesTest`, `.gitignore` | Der Test liest den eigenen Quelltext; `.env` ist ignoriert |

## 3. Die Begriffe, nach denen gefragt werden kann

- **ACK / NACK.** `basicAck` = «erledigt, darfst löschen». `basicNack` = «nehme ich nicht».
  Mit `requeue = true` kommt die Nachricht zurück in die Queue, mit `false` geht sie in die
  Dead-Letter-Queue `chat.dlq`.
- **Lieferungsnummer (`deliveryTag`).** RabbitMQ nummeriert jede ausgelieferte Nachricht pro Kanal.
  ACK und NACK sagen über diese Nummer, welche Nachricht gemeint ist.
- **`multiple = true`.** Betrifft alle noch offenen Nachrichten bis und mit dieser Nummer.
  Gebraucht in `rejectOpenMessages` und `returnBatchToQueue`.
- **Prefetch.** Wie viele unbestätigte Nachrichten RabbitMQ einem Konsumenten höchstens gibt. Muss
  mindestens so gross sein wie das Paket, sonst wird es nie voll.
- **Manuelle Bestätigung (`AcknowledgeMode.MANUAL`).** Wir bestätigen selbst, weil nur wir wissen,
  wann die Datenbank committet hat.
- **SQLSTATE.** Fünfstelliger Fehlercode der Datenbank. Klasse 22 (Datenfehler) und 23 (Regel
  verletzt, z. B. Fremdschlüssel) heissen «Zeile kaputt». Alles andere heisst «Datenbank hat ein
  Problem». Entschieden in `MessageRepository.translate`.
- **Quorum-Queue.** Dauerhafte Queue-Art von RabbitMQ, überlebt Neustarts. Kann `x-delivery-limit`.
- **Testcontainers.** Bibliothek, die für die Tests echte Docker-Container startet.

## 4. Wahrscheinliche Fragen und kurze Antworten

**«Warum bestätigst du nicht sofort nach dem Empfang?»**
Weil die Nachricht dann bei einem Absturz vor dem Commit verloren wäre. Zeile: die ACK-Schleife in
`writeAndAcknowledge`, sie steht *nach* `writePatiently`.

**«Was passiert, wenn dieselbe Nachricht zweimal kommt?»**
Die zweite wird von `ON CONFLICT (id) DO NOTHING` übersprungen. Das geht nur, weil die ID vom
`chat-service` kommt und nicht von der Datenbank. Mit `SERIAL` gäbe es jedes Mal eine neue Zeile.

**«Warum fragst du nicht vorher mit SELECT, ob die ID schon existiert?»**
Zwischen Fragen und Schreiben könnte die zweite Instanz dieselbe Zeile schreiben. Der
Primärschlüssel entscheidet das in einem Schritt (atomar).

**«Was macht der Dienst, wenn die Datenbank weg ist?»**
Er wartet in `writePatiently` und versucht es alle `DB_RETRY_PAUSE_MS` neu. Die Nachrichten bleiben
unbestätigt beim Broker. Er lehnt nichts ab und beendet sich nicht.

**«Warum nicht einfach NACK mit requeue, wenn die Datenbank weg ist?»**
Dann käme das Paket sofort wieder, eine Schleife ohne Pause. Gemessen: 10 Versuche in 12 s statt
höchstens 6. Ausserdem zählt ein solches NACK in RabbitMQ 4 nicht gegen `x-delivery-limit`.

**«Eine Nachricht im Paket ist kaputt. Was passiert mit den anderen 499?»**
Kein gültiges JSON: sie wird schon in `parseOrReject` aussortiert. Lehnt die Datenbank eine Zeile ab,
scheitert die ganze Transaktion. Dann schreibt `writeOneByOne` das Paket Zeile für Zeile, und nur die
kaputte geht nach `chat.dlq`.

**«Warum zwei Zeitlimits in RabbitConfiguration?»**
`receiveTimeout` wartet nur auf die *nächste* Nachricht. Kommt alle 150 ms eine, löst es nie aus.
`setBatchReceiveTimeout` begrenzt das *ganze* Paket. Gemessen: ohne nach 2 s 0 von 65 geschrieben,
mit 32 von 64. In `PLANUNG.md` stand nur das erste. Diese Stelle habe ich korrigiert.

**«Warum legt der Broker die Queue an und nicht dein Dienst?»**
Sonst gäbe es beim Start ein Zeitfenster, in dem der `chat-service` schon annimmt, die Queue aber
fehlt. Ein Exchange ohne Queue wirft still weg. Der Broker gilt erst als gesund, wenn die Queue da ist.

**«Warum nicht `load_definitions` beim Start?»**
Ausprobiert: dann legt RabbitMQ den Benutzer aus `.env` nicht an. Deshalb spielt der Healthcheck die
Datei *nach* dem Start ein.

**«Was ist die Schwäche deiner Lösung?»**
Ein sehr langer Datenbankausfall. RabbitMQ wartet höchstens 30 Minuten auf ein ACK
(`consumer_timeout`), dann schliesst es den Kanal und stellt neu zu. Das zählt gegen
`x-delivery-limit: 3`. Nach etwa 90 Minuten lägen gute Nachrichten in `chat.dlq`. Verloren sind sie
dort nicht, aber man müsste sie von Hand zurückholen. Abhilfe: `consumer_timeout` höher setzen.

## 5. Wo ein Vorschlag der KI korrigiert wurde

Der Auftrag fragt danach. Jede dieser Stellen steht als eigener Commit im `git log`:

1. **Zeitlimit für das ganze Paket** (`setBatchReceiveTimeout`): die Planung hatte nur `setReceiveTimeout`.
2. **Definitionen nach dem Start einspielen**: der erste Plan mit `load_definitions` scheiterte am Benutzer.
3. **Nie mit `requeue` zurückgeben**: die erste Fassung gab Pakete nach Fehlern zurück in die Queue.
   Eine Messung zeigte, dass das Zustelllimit dabei nicht greift.
4. **Testisolation**: die ersten Integrationstests liefen mit mehreren Spring-Kontexten gleichzeitig.
   Fremde Listener nahmen den Tests Nachrichten weg, die Tests waren nur zufällig grün.
5. **Ausfall-Test schärfer**: er bestand zuerst auch ohne Warteschleife. Jetzt zählt er die Versuche.
