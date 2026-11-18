# Spezifikation — Live-Zustellung

**Modul M321 · Klasse IT3b · Bewertung 2 · [Name] · Stand 18.11.2026**

## 1. Vertrag

### 1.1 Was am Exchange ankommt

**Frage:** Was macht der chat-service mit einer Nachricht, bevor sie beim Client ist? An welche Stellen geht sie, und welche Angaben stecken darin?

**Antwort:**

Der `chat-service` leitet eine Nachricht weiter. Er gibt ihr eine ID und einen Zeitstempel und
publiziert sie **einmal** auf den Exchange `chat.messages`. Weil das ein Fanout-Exchange ist, macht
RabbitMQ daraus Kopien, für jede angehängte Queue eine. Eine Kopie kommt in die Live-Queue
`chat.live.{uuid}` jeder `chat-service`-Instanz und wird von dort gesendet (Name siehe 3.1). Eine
kommt in `chat.persist` und wird dort nur gespeichert. In der Nachricht steht, wer es war
(`sender`), wo (`roomId`) und was als Text steht (`text`), dazu die `id` und der Sendezeitpunkt
`sentAt`.

### 1.2 Woher ich das weiss

**Frage:** In welcher Datei und in welchen Zeilen im Code sieht man das?

**Antwort:**

Man sieht das in `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageService.java`.
In Zeile 55 wird die Nachricht gebaut, in Zeile 63 wird sie publiziert:

```java
rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);
```

Das ist der einzige Aufruf. Einen zweiten für die Live-Queue gibt es nicht. Der Name `chat.messages`
steht in `RabbitConfiguration.java`, Zeile 20. In Zeile 31 wird der Exchange als Fanout angelegt,
ebenso in `rabbitmq/definitions.json`, Zeile 6. Dass `chat.persist` am Exchange hängt, steht in
`rabbitmq/definitions.json` ab Zeile 36 (`bindings`). Die Bindung der Live-Queue gibt es noch nicht,
die kommt mit der Live-Zustellung dazu.

Dass das JSON so aussieht, ist aus Bewertung 1 belegt: `docs/spec-batch-writer.md`, Abschnitt 2.1,
und die mitgeschnittene Nachricht `batch-writer/src/test/resources/chat-service-message.json`.

### 1.3 Was am Stream hinausgeht

**Frage:** Was bekommt ein Client über den Stream, und welche Nachrichten bekommt er?

**Antwort:**

Der Client fragt nicht nach jeder einzelnen Nachricht. Er öffnet **einmal** eine Verbindung, und
der `chat-service` schiebt jede neue Nachricht seines Raums hinein, sobald sie da ist. Das Verfahren
heisst Server-Sent Events (SSE).

**Adresse und Parameter.** Der Client ruft `GET /stream` auf und gibt als Parameter `roomId` die
UUID seines Raums mit:

```
GET /stream?roomId=11111111-1111-1111-1111-111111111111
```

Später läuft das über das Gateway: `http://localhost:8080/stream?roomId=...`. Solange es das Gateway
noch nicht gibt, erreicht man den Endpunkt nur im Docker-Netz. Das Prüfskript macht es darum so wie
das Abnahmeskript aus Bewertung 1, aus dem `chat-service`-Container heraus:

```bash
docker compose exec -T chat-service curl -N "http://localhost:8080/stream?roomId=11111111-1111-1111-1111-111111111111"
```

`-N` schaltet den Puffer von curl aus, sonst sähe man die Ereignisse erst am Schluss. Fehlt `roomId`
oder ist sie keine UUID, antwortet der Dienst mit `400`, genau wie beim Verlauf.

**Content-Type und Verbindung.** Die Antwort hat `Content-Type: text/event-stream` und ist in UTF-8
kodiert. Die Verbindung **bleibt offen**: Es ist eine einzige HTTP-Antwort, in die der Server jedes
neue Ereignis hineinschreibt. Sie endet erst, wenn der Client sie schliesst oder die Verbindung
abbricht. Der `chat-service` setzt kein eigenes Zeitlimit. Das muss beim `SseEmitter` von Spring
ausdrücklich so eingestellt werden, sonst beendet der Webserver die Antwort nach seinem eigenen
Standard-Zeitlimit. Reisst die Verbindung doch ab, verbindet sich der Browser (`EventSource`) von
selbst neu (siehe 2.4).

**Ein Ereignis.** In `id:` steht die ID der Nachricht, eine UUID. In `data:` steht genau das JSON,
das auch in der Queue ankommt, auf einer einzigen Zeile. Die Leerzeile am Schluss gehört dazu:
Erst sie sagt dem Client, dass das Ereignis fertig ist. Beispiel für «Hallo» von «lernende1»:

```
id: 3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47
data: {"id":"3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47","roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende1","text":"Hallo","sentAt":"2026-11-17T08:15:30.123456789Z"}

```

**Welche Nachrichten.** Nur die seines Raums. Die Live-Queue bekommt zwar alle Nachrichten aller
Räume, denn ein Fanout-Exchange filtert nicht, und der Routing-Key ist leer. Der `chat-service` liest
aber bei jeder Nachricht das Feld `roomId` aus dem JSON und schickt sie nur in die Streams, die mit
genau diesem Raum geöffnet wurden.

## 2. Verhalten

### 2.1 Normalfall: nur der eigene Raum

**Frage:** Mehrere Clients sind in verschiedenen Räumen. Wer bekommt welche Nachricht?

**Antwort:**

Die Clients gehören alle zu einem Raum. Zum Beispiel gibt es drei Clients: Client 1, Client 2,
Client 3. Zwei Clients wollen in Raum A, also in einem Chat XY, und der dritte Client will zu einem
anderen Raum. Das heisst, jeder Client gehört zu einem Raum, und so vermischen sich die Nachrichten
nicht.

### 2.2 Reihenfolge

**Frage:** Warum kommen die Nachrichten in der richtigen Reihenfolge an?

**Antwort:**

Er nimmt also, es funktioniert so, dass jede Nachricht der Reihe nach genommen wird. Jede Live-Queue
`chat.live.{uuid}` hat nur einen Leser, ihre eigene Instanz. Das heisst, die anderen Nachrichten
werden in einer Queue gegeben. Genau, dann wird jede Nachricht in der richtigen Reihenfolge gesendet.

### 2.3 Hängende Instanz (S6)

**Frage:** Eine Instanz liest nicht mehr weiter. Was passiert mit ihrer Queue, und was macht RabbitMQ?

**Antwort:**

**Solange RabbitMQ nichts merkt**, warten neue Nachrichten in ihrer Queue. Mehr als 1000 werden es
nie: Kommt die 1001., fliegt die älteste raus (3.3). Liest die Instanz wieder weiter, liefert sie
die wartenden Nachrichten verspätet, aber in der richtigen Reihenfolge aus.

**Woran RabbitMQ erkennt, ob die Instanz noch lebt: am Heartbeat.** Über jede Verbindung schicken
sich Broker und Instanz regelmässig ein kleines Lebenszeichen, auch wenn sonst nichts zu tun ist.
Wir stellen den Heartbeat-Timeout im `chat-service` auf **15 Sekunden**
(`spring.rabbitmq.requested-heartbeat: 15s`). Dann geht etwa alle 7,5 Sekunden ein Lebenszeichen.
Fehlen zwei hintereinander, also nach rund 15 Sekunden, gilt die Instanz als tot, und RabbitMQ
schliesst ihre Verbindung. Ohne diese Einstellung gälte der Standard von 60 Sekunden, und das wäre
für S4 zu langsam (3.2).

**Was in S6 passiert, wenn die Instanz länger als 15 Sekunden angehalten ist:**

1. RabbitMQ bekommt keine Lebenszeichen mehr und schliesst die Verbindung.
2. Weil die Queue exklusiv ist (3.2), löscht RabbitMQ sie zusammen mit der Verbindung, samt den
   Nachrichten darin und ihrer Bindung an `chat.messages`.
3. Ab jetzt verteilt der Exchange nur noch an die anderen Live-Queues und an `chat.persist`. Für die
   hängende Instanz sammelt sich nichts mehr an. Die anderen Instanzen und der `batch-writer` merken
   davon nichts.
4. Endgültig verloren ist keine Nachricht: Jede liegt auch in `chat.persist` und kommt in die
   Datenbank.

**Was die Instanz nach dem Fortsetzen tun muss, damit sie wieder zustellt:**

1. Merken, dass ihre Verbindung weg ist, und neu verbinden. Spring AMQP versucht das von selbst alle
   5 Sekunden.
2. Ihre Queue neu anlegen und wieder an `chat.messages` binden. Das macht Spring beim neuen Verbinden
   selbst, weil Queue und Bindung als Beans angemeldet sind (`RabbitAdmin`). Der Name bleibt
   derselbe, die alte Queue gibt es ja nicht mehr.
3. **Alle offenen Streams beenden.** Ihre Clients haben während der Pause Nachrichten verpasst,
   merken das aber nicht, weil ihr Stream nie abgerissen ist. Beendet die Instanz die Streams,
   verbinden sich die Clients neu, schicken `Last-Event-ID` mit und holen die Lücke aus der
   Datenbank nach (2.4).

Ist die Instanz kürzer als 15 Sekunden angehalten, bleibt die Verbindung bestehen. Dann geht nichts
verloren, die Nachrichten kommen nach dem Fortsetzen einfach verspätet.

**Lebensdauer pro Nachricht (TTL): nein.** `PLANUNG.md` sieht `x-message-ttl: 30000` vor. Ich lasse
sie weg:

- In S6 bringt sie nichts. Hängt die Instanz länger als 15 Sekunden, ist die Queue ohnehin weg.
  Hängt sie kürzer, ist keine Nachricht älter als 30 Sekunden.
- Wirken würde sie nur, wenn die Instanz lebt (der Heartbeat läuft in einem eigenen Thread weiter),
  aber zu langsam liest. Dann sind die Streams ihrer Clients noch offen. Eine verfallene Nachricht
  würde still fehlen, und weil der Stream nicht abreisst, holt der Client sie auch nicht nach. Spät
  ist hier besser als gar nicht.
- Den Speicher im Broker begrenzt schon `x-max-length: 1000`.

### 2.4 Nachholen nach einem Abbruch (S7)

**Frage:** Ein Client verliert kurz die Verbindung. Wie bekommt er die verpassten Nachrichten?

**Antwort:**

**In welchem Header die letzte ID ankommt.** Im Header `Last-Event-ID`. Der Browser merkt sich von
jedem Ereignis die `id:` und schickt beim Neuverbinden die letzte von selbst mit:

```
Last-Event-ID: 3c9a2f1e-7b4d-4e8a-9f21-5d6c8b0a1e47
```

Fehlt der Header, ist es ein erster Verbindungsaufbau, und es gibt nichts nachzuholen.

**Woher die verpassten Nachrichten kommen: aus der Datenbank.**

- Der Client landet beim Neuverbinden vielleicht auf einer anderen Instanz, oder seine Instanz wurde
  neu gestartet oder hat selbst eine Lücke (S6). Ein Speicher im `chat-service` wäre dann leer oder
  unvollständig. Die Datenbank hat jede Nachricht, egal welche Instanz sie gesehen hat.
- Den Verlauf lesen kann der `chat-service` schon (`MessageRepository`, `GET /api/messages`). Ein
  zweiter Verlauf im Speicher wäre doppelte Arbeit und müsste wieder begrenzt werden.
- Der Preis: Der `batch-writer` schreibt bis zu 200 ms später. Die allerneuesten Nachrichten stehen
  beim Nachholen vielleicht noch nicht drin. Die kommen dafür über den Live-Weg (siehe «Doppelt»).

**Was «danach» heisst.** Eine UUID ist zufällig, man kann nicht sagen, welche grösser ist. Deshalb
geht es in zwei Schritten:

1. Die Nachricht mit der ID aus `Last-Event-ID` in der Tabelle `message` suchen und ihren
   Zeitpunkt `sent_at` lesen. Gibt es sie nicht oder gehört sie zu einem anderen Raum, gilt 2.6.
2. Alle Nachrichten desselben Raums lesen, die später gesendet wurden, die älteste zuerst:

   ```sql
   SELECT id, room_id, sender, text, sent_at
   FROM message
   WHERE room_id = ? AND sent_at > ?
   ORDER BY sent_at
   ```

   Der vorhandene Index `idx_message_room_time` (Raum, Zeit) deckt diese Abfrage ab.

Grenze: Hätte eine andere Nachricht auf die Mikrosekunde genau denselben Zeitpunkt wie die letzte,
würde sie fehlen. Das ist praktisch ausgeschlossen, und wir nehmen es bewusst in Kauf.

**Doppelt: wenn eine Nachricht während des Nachholens auch live ankommt.** Beim Verbinden mit
`Last-Event-ID` geht der `chat-service` in dieser Reihenfolge vor:

1. Den Stream **zuerst** für Live-Nachrichten anmelden, aber im Zustand «holt nach». Live-Nachrichten
   für diesen Stream kommen in eine Warteliste (`ArrayList`), statt hinauszugehen.
2. Die verpassten Nachrichten aus der Datenbank lesen und senden. Die ID jeder gesendeten Nachricht
   kommt in eine Menge (`HashSet`).
3. Die Warteliste der Reihe nach senden und dabei jede Nachricht überspringen, deren ID schon in der
   Menge steht. Dann auf «live» umschalten. Auch spätere Live-Nachrichten werden noch gegen die
   Menge geprüft, denn eine Kopie kann noch in der Queue gesteckt haben.

Warum zuerst anmelden und erst dann lesen: Andersherum entstünde eine Lücke. Eine Nachricht, die nach
dem Lesen, aber vor dem Anmelden ankommt, stünde noch nicht in der Datenbank und käme auch nicht live.
So kommt sie schlimmstenfalls doppelt, und das Doppelte filtert die Menge heraus.

Auf denselben Stream greifen dabei zwei Threads zu: der Web-Thread, der nachholt, und der Thread des
RabbitMQ-Listeners, der Live-Nachrichten verteilt. Warteliste, Menge und das Umschalten auf «live»
sind deshalb mit `synchronized` geschützt. Sonst könnte genau beim Umschalten eine Nachricht in die
Warteliste fallen, die niemand mehr leert.

**Datenbank nicht erreichbar.** Dann holt der Dienst nichts nach, schreibt eine Warnung ins Log und
schaltet den Stream direkt auf «live». Der Live-Weg braucht keine Datenbank (2.5).

### 2.5 Datenbank weg (S5)

**Frage:** Die Datenbank ist ausgefallen. Warum kommt die Nachricht trotzdem an?

**Antwort:**

Warum die Nachricht trotzdem ankommt: Weil die Nachrichten über `chat.persist` und
`chat.live.{uuid}` laufen. Bei `chat.persist` bleiben sie liegen, bis die Datenbank wieder da ist,
dann trägt der `batch-writer` sie nach. Und bei `chat.live.{uuid}` werden sie gesendet, und dafür
braucht es keine Datenbank. Dann sieht man die Nachrichten direkt und sie gehen nicht verloren. Nur
das Nachholen (2.4) geht in dieser Zeit nicht. Der Stream läuft trotzdem live weiter.

### 2.6 Unbekannte ID

**Frage:** Ein Client will ab einer ID nachholen, die es nicht mehr gibt. Was soll dann passieren?

**Antwort:**

Was soll dann passieren? Also eine Lösung wäre, dass er sie nicht mehr nachholt, weil sie auch
vielleicht zu alt ist und diese ID dann nicht mehr finden kann. Genau, also das heisst jetzt
einfach lieber die Nachricht gar nicht jetzt finden oder suchen und vielleicht eine Warnung geben.

Ergänzung: «Nicht gefunden» kann auch heissen «noch nicht geschrieben», denn der `batch-writer`
braucht bis zu 200 ms. Der Dienst sucht deshalb nach einer Sekunde ein zweites Mal. Findet er die ID
dann immer noch nicht, oder gehört sie zu einem anderen Raum, holt er nichts nach, schreibt eine
Warnung ins Log und schaltet den Stream direkt auf «live». Den ganzen Verlauf kann der Client
jederzeit mit `GET /api/messages` neu laden.

## 3. Queues

### 3.1 Warum jede Instanz eine eigene Queue hat

**Frage:** Warum hat jede Instanz eine eigene Queue?

**Antwort:**

Weil wären sie auf einem gemeinsamen Queue, also auf einem Briefkasten, dann könnte man es nicht
unterscheiden, was wem gehört. Das heisst, Person A sieht nur die eine Hälfte, also die in einer
Queue sind, und die andere Person B sieht auch nur seine andere Hälfte. Das heisst, sie sehen nur
die eigene Seite und nicht alle Nachrichten. Deswegen sollen sie lieber aufgeteilt sein, damit
jeder jede Nachricht lesen kann.

**Name der Queue.** `chat.live.` und dahinter eine UUID, zum Beispiel
`chat.live.5b0c2f9e-1d7a-4c3e-9a8b-2f6e4d1c0a93`. In diesem Dokument steht dafür kurz
`chat.live.{uuid}`.

**Woher die Instanz die UUID nimmt.** Sie erzeugt sie **einmal beim Start** mit `UUID.randomUUID()`,
genau so, wie der `chat-service` schon die IDs der Nachrichten vergibt. Eine solche UUID hat 122
zufällige Bits. Dass zwei Instanzen dieselbe ziehen, ist praktisch ausgeschlossen.

Warum nicht der Hostname des Containers: Nach einem Neustart hätte die Instanz wieder denselben
Namen. Hätte RabbitMQ die alte Verbindung noch nicht als tot erkannt, gehörte die Queue noch der
alten Verbindung, und RabbitMQ würde der neuen den Zugriff verweigern (`RESOURCE_LOCKED`). Mit einer
neuen UUID bei jedem Start kann das nicht passieren. RabbitMQ empfiehlt für exklusive Queues
ebenfalls Namen, die garantiert nur einmal vorkommen.

Die Live-Queue legt der `chat-service` selbst an, nicht der Broker über `rabbitmq/definitions.json`
wie bei `chat.persist`. Ihr Name ist erst beim Start der Instanz bekannt, und sie soll auch nur so
lange leben wie die Instanz.

### 3.2 Lebensdauer (S4)

**Frage:** Was passiert mit der Queue einer Instanz, wenn die Instanz stoppt, und warum?

**Antwort:**

Wenn die Instanz stoppt, wird ihre Queue automatisch gelöscht. Sonst würde der Exchange weiter
Kopien in eine Queue legen, die niemand mehr liest, und die Nachrichten sammelten sich bei RabbitMQ
an.

**Die Eigenschaft, die dafür sorgt: `exclusive`.** Eine exklusive Queue gehört der Verbindung, die
sie angelegt hat. Nur diese Verbindung darf sie benutzen, und RabbitMQ löscht sie, sobald diese
Verbindung geschlossen wird oder verloren geht. Dazu kommt `durable: false`: Eine Live-Queue muss
einen Neustart des Brokers nicht überleben. Diese Kombination empfiehlt auch RabbitMQ 4. Nicht
dauerhafte Queues, die *nicht* exklusiv sind, lassen sich ab Version 4.3 standardmässig gar nicht
mehr anlegen. `auto-delete` brauchen wir nicht, `exclusive` löscht schon.

**Warum sie spätestens nach 30 Sekunden weg ist:**

- Normaler Stopp (`docker compose stop chat-service`): Spring schliesst beim Herunterfahren die
  Verbindung zu RabbitMQ, und die Queue ist **sofort** weg. Muss Docker den Prozess nach 10 Sekunden
  hart beenden, schliesst das Betriebssystem die Verbindung, und RabbitMQ merkt es ebenfalls sofort.
- Kann die Verbindung nicht sauber zugehen (Instanz eingefroren, Netz weg), merkt RabbitMQ es am
  fehlenden Heartbeat nach rund 15 Sekunden (2.3) und löscht die Queue dann. Bis 30 Sekunden bleibt
  damit Luft, falls ein Lebenszeichen knapp zu spät kommt.
- Mit dem Standard-Heartbeat von 60 Sekunden würde S4 in diesem Fall scheitern. Deshalb stellen wir
  ihn ausdrücklich auf 15 Sekunden.

### 3.3 Grenze (S6)

**Frage:** Wie viele Nachrichten darf eine Queue höchstens haben, und was passiert mit der nächsten?

**Antwort:**

Die Queue darf höchstens 1000 Nachrichten haben, und wenn ich jetzt z.B. 1010 oder 1001
Nachrichten schicke, dann kommt diese 1001, die eins, kommt rein, und die älteste Nachricht von
den anderen 1000, die wird einfach weggeworfen, weil für einen Live-Chat, z.B. wie auf WhatsApp,
ist die älteste Nachricht nicht so wichtig wie die neuste.

**Die Einstellungen dazu** sind zwei Argumente beim Anlegen der Queue:

- `x-max-length: 1000` begrenzt die Queue auf 1000 Nachrichten. Gezählt werden nur Nachrichten, die
  in der Queue warten. Die bis zu 250, die Spring schon abgeholt, aber noch nicht bestätigt hat
  (Prefetch), zählen nicht dazu.
- `x-overflow: drop-head` sorgt dafür, dass die älteste Nachricht rausfliegt. Das ist zwar der
  Standard von RabbitMQ, wir setzen es trotzdem ausdrücklich, damit man die Regel im Code und in der
  Management-UI sieht. Die andere Möglichkeit, `reject-publish`, würde stattdessen die neue Nachricht
  abweisen, und das wäre für einen Live-Chat genau falsch herum.

### 3.4 Alle Einstellungen auf einen Blick

| Was | Wert | Wo |
|---|---|---|
| Name | `chat.live.` und eine UUID, beim Start erzeugt | Queue-Bean im `chat-service` |
| `durable` | `false` | Queue-Bean |
| `exclusive` | `true` | Queue-Bean |
| `auto-delete` | `false` | Queue-Bean |
| `x-max-length` | `1000` | Argument der Queue |
| `x-overflow` | `drop-head` | Argument der Queue |
| `x-message-ttl` | keine | bewusst weggelassen (2.3) |
| Bindung | an `chat.messages`, Routing-Key leer | Binding-Bean im `chat-service` |
| Heartbeat | 15 Sekunden | `spring.rabbitmq.requested-heartbeat` in `chat-service/src/main/resources/application.yml` |

## 4. Abnahmekriterien

**Frage:** Wie prüft man, ob alles stimmt?

**Antwort:**

Man zählt, was wirklich passiert ist, und vergleicht es mit dem, was der Lehrer will. Wenn es
gleich ist, ist der Test bestanden. Das macht ein Skript automatisch, so wie `scripts/abnahme.sh`
in Bewertung 1.

## 5. Abweichungen von `PLANUNG.md`

| `PLANUNG.md` | Diese Spezifikation | Grund |
|---|---|---|
| Live-Queue «chat.live» plus Instanz, ohne festen Namen | `chat.live.` und eine UUID, beim Start erzeugt | eindeutig, auch nach einem Neustart (3.1) |
| `x-message-ttl: 30000` auf den Live-Queues | keine TTL | eine verfallene Nachricht würde bei offenem Stream still fehlen (2.3) |
| Heartbeat nicht erwähnt | 15 Sekunden statt Standard 60 | Queue in S4 spätestens nach 30 Sekunden weg (3.2) |
| Offener Punkt 8: nach dem Neuverbinden lädt der Client den Verlauf mit `GET /api/messages` | der Server holt über `Last-Event-ID` selbst nach | der Browser schickt den Header von selbst, der Client muss nichts tun (2.4) |
