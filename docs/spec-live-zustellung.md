# Spezifikation — Live-Zustellung

**Modul M321 · Klasse IT3b · Bewertung 2 · <Name> · Stand 12.11.2026**

## 1. Vertrag

### 1.1 Was am Exchange ankommt

**Frage:** Was macht der chat-service mit einer Nachricht, bevor sie beim Client ist? An welche Stellen geht sie, und welche Angaben stecken darin?

**Antwort:**

Der `chat-service` leitet eine Nachricht weiter. Er gibt ihr eine ID und einen Zeitstempel und
publiziert sie **einmal** auf den Exchange `chat.messages`. Weil das ein Fanout-Exchange ist, macht
RabbitMQ daraus Kopien, für jede angehängte Queue eine. Eine Kopie kommt in die Live-Queue
`chat.live.<instanz>` jeder `chat-service`-Instanz und wird von dort gesendet. Eine kommt in
`chat.persist` und wird dort nur gespeichert. In der Nachricht steht, wer es war (`sender`), wo
(`roomId`) und was als Text steht (`text`), dazu die `id` und der Sendezeitpunkt `sentAt`.

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

Ein Client fragt eine Nachricht ab. In `id:` steht die ID der Nachricht, eine UUID. Die merkt sich
der Client, damit er nachholen kann. In `data:` steht das gleiche JSON, das auch in `chat.persist`
ankommt. Und der Client bekommt nur Nachrichten aus seinem Raum.

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
`chat.live.<instanz>` hat nur einen Leser, ihre eigene Instanz. Das heisst, die anderen Nachrichten
werden in einer Queue gegeben. Genau, dann wird jede Nachricht in der richtigen Reihenfolge gesendet.

### 2.3 Hängende Instanz

**Frage:** Eine Instanz liest nicht mehr weiter. Was passiert mit ihrer Queue, und was macht RabbitMQ?

**Antwort:**

Also die Nachrichten sind ja limitiert auf 1000 und was nach der 1000 kommt, ist dann in einer
Queue. Und dann checkt RabbitMQ, checkt dann, ob diese Instanz noch existiert, ob sie noch lebt,
und wenn sie nicht mehr lebt, dann trennt RabbitMQ die Verbindung und löscht ihre Queue.

### 2.4 Nachholen nach einem Abbruch

**Frage:** Ein Client verliert kurz die Verbindung. Wie bekommt er die verpassten Nachrichten?

**Antwort:**

Also, die Nachrichten sind ja alle mit einer ID, einer UUID, beschriftet. Und wenn man eine
Nachricht schickt und man einen Verbindungsfehler hat, dann verschickt es diese Nachricht nicht.
Man schickt dann aber 50 andere Nachrichten, die ankommen. Dann schaut auch der Client danach,
welche ID diese Nachricht hatte, sucht dann diese auch in der richtigen Reihenfolge und dann nimmt
er sie wieder, dann verschickt er sie live.

### 2.5 Datenbank weg (S5)

**Frage:** Die Datenbank ist ausgefallen. Warum kommt die Nachricht trotzdem an?

**Antwort:**

Warum die Nachricht trotzdem ankommt: Weil die Nachrichten über `chat.persist` und
`chat.live.<instanz>` laufen. Bei `chat.persist` bleiben sie liegen, bis die Datenbank wieder da
ist, dann trägt der `batch-writer` sie nach. Und bei `chat.live.<instanz>` werden sie gesendet, und
dafür braucht es keine Datenbank. Dann sieht man die Nachrichten direkt und sie gehen nicht verloren.

### 2.6 Unbekannte ID

**Frage:** Ein Client will ab einer ID nachholen, die es nicht mehr gibt. Was soll dann passieren?

**Antwort:**

Was soll dann passieren? Also eine Lösung wäre, dass er sie nicht mehr nachholt, weil sie auch
vielleicht zu alt ist und diese ID dann nicht mehr finden kann. Genau, also das heisst jetzt
einfach lieber die Nachricht gar nicht jetzt finden oder suchen und vielleicht eine Warnung geben.

## 3. Queues

### 3.1 Warum jede Instanz eine eigene Queue hat

**Frage:** Warum hat jede Instanz eine eigene Queue?

**Antwort:**

Weil wären sie auf einem gemeinsamen Queue, also auf einem Briefkasten, dann könnte man es nicht
unterscheiden, was wem gehört. Das heisst, Person A sieht nur die eine Hälfte, also die in einer
Queue sind, und die andere Person B sieht auch nur seine andere Hälfte. Das heisst, sie sehen nur
die eigene Seite und nicht alle Nachrichten. Deswegen sollen sie lieber aufgeteilt sein, damit
jeder jede Nachricht lesen kann.

### 3.2 Lebensdauer

**Frage:** Was passiert mit der Queue einer Instanz, wenn die Instanz stoppt, und warum?

**Antwort:**

Wenn die Instanz stoppt, dann wird ihre Queue `chat.live.<instanz>` automatisch gelöscht, weil sich
sonst immer mehr Nachrichten in der Queue ansammeln bei RabbitMQ. Deswegen wird sie gelöscht.

### 3.3 Grenze

**Frage:** Wie viele Nachrichten darf eine Queue höchstens haben, und was passiert mit der nächsten?

**Antwort:**

Die Queue darf höchstens 1000 Nachrichten haben, und wenn ich jetzt z.B. 1010 oder 1001
Nachrichten schicke, dann kommt diese 1001, die eins, kommt rein, und die älteste Nachricht von
den anderen 1000, die wird einfach weggeworfen, weil für einen Live-Chat, z.B. wie auf WhatsApp,
ist die älteste Nachricht nicht so wichtig wie die neuste.

## 4. Abnahmekriterien

**Frage:** Wie prüft man, ob alles stimmt?

**Antwort:**

Man zählt, was wirklich passiert ist, und vergleicht es mit dem, was der Lehrer will. Wenn es
gleich ist, ist der Test bestanden. Das macht ein Skript automatisch, so wie `scripts/abnahme.sh`
in Bewertung 1.
