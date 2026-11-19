# Plan — Live-Zustellung

Modul M321 · Klasse IT3b · Bewertung 2 · Jaro · Stand 19.11.2026

Ich arbeite nach `docs/spec-live-zustellung.md`. Jeder Schritt hört mit einem grünen Test und einem eigenen Commit auf.

## Schritt 1 — Testgrundlage und Regeltest

**Ziel:** Die Tests im chat-service laufen gegen ein echtes RabbitMQ und eine echte PostgreSQL in Containern (Testcontainers). Dazu kommt ein `CommentRulesTest`, der den ganzen Code unter `chat-service/src` prüft.

**Warum an dieser Stelle:** So ist die ganze Testgrundlage an einem Ort, und ab hier prüft der Regeltest jeden Schritt automatisch mit.

**Auftrag an die KI (Wortlaut):**

> «Lies zuerst docs/spec-live-zustellung.md und docs/plan-live-zustellung.md. Wir machen nur Schritt 1.
>
> Richte im chat-service die Testgrundlage mit Testcontainers ein: ein RabbitMQ-Container und ein PostgreSQL-Container, die alle Integrationstests zusammen benutzen. Nimm die gleichen Images wie in docker-compose.yml, bei RabbitMQ eins mit Management-Plugin, weil spätere Tests die Management-API brauchen. Das Schema für PostgreSQL soll aus der gleichen Quelle kommen wie im Stack, mach keine zweite Kopie davon. Schreib einen Test, der zeigt, dass beide Container laufen und chat.messages als Fanout-Exchange da ist.
>
> Dann übernimm den CommentRulesTest aus dem batch-writer für chat-service/src, mit den gleichen Regeln. Lass ihn zuerst laufen und zeig mir, dass er rot ist. Ich erwarte sechs Stellen: die Konstruktoren von MessageService und MessageController, contextLoads in ChatServiceApplicationTest und die drei Testmethoden in MessageControllerTest. Wenn es andere Stellen sind, hör auf und sag es mir. Danach schreib die fehlenden Kommentare, bis er grün ist.
>
> Das gilt für alle Schritte: Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 1: Testgrundlage und Regeltest.»

**Test:** Der `CommentRulesTest` ist zuerst rot, weil an sechs Stellen ein Kommentar fehlt (die Konstruktoren von `MessageService` und `MessageController`, `contextLoads` in `ChatServiceApplicationTest` und die drei Testmethoden in `MessageControllerTest`). Danach ist er grün. Ein weiterer Test zeigt, dass beide Container laufen und `chat.messages` als Fanout da ist. `mvn clean test` ist grün.

**Geprüft und korrigiert:**

Geprüft: Ich habe geschaut, ob es okay ist, dass die Tests ohne definitions.json laufen. Das ist richtig so, weil es sonst im Test auch chat.persist geben würde. Dort läuft kein batch-writer, also würden sich alle Testnachrichten darin sammeln. Ausserdem habe ich geprüft, ob die Test-Kontexte im Zwischenspeicher bleiben dürfen. Das geht, weil jeder Kontext seine eigene exklusive Live-Queue hat. Anders als in Bewertung 1 gibt es keine gemeinsame Queue.

Korrigiert: nichts.

## Schritt 2 — Live-Queue

**Ziel:** Beim Start macht jede Instanz die Queue `chat.live.` plus eine UUID (`exclusive: true`, `durable: false`, an `chat.messages` gebunden). Ein Listener schreibt jede Nachricht ins Log.

**Warum an dieser Stelle:** Ohne Queue kommt bei der Instanz gar nichts an. Weil der Listener zuerst nur ins Log schreibt, sehe ich, ob die Nachricht bis in die Instanz kommt, bevor überhaupt ein Client dran ist.

**Auftrag an die KI (Wortlaut):**

> «Schritt 2 aus docs/plan-live-zustellung.md: die Live-Queue.
>
> Beim Start erzeugt jede Instanz eine UUID mit UUID.randomUUID() und legt die Queue `chat.live.<uuid>` an, mit exclusive: true und durable: false, gebunden an chat.messages (Spec 3.1 und 3.2). Nicht den Hostnamen nehmen. Die Bindung kommt nicht in rabbitmq/definitions.json, weil der Name erst beim Start feststeht. Ein @RabbitListener auf dieser Queue schreibt vorerst nur die id jeder Nachricht ins Log. Er liest mit genau einem Thread (Spec 2.2). x-max-length und den Heartbeat noch nicht einbauen, die kommen in Schritt 5.
>
> Test: Nach dem Start zeigt die Management-API die Queue von dieser Instanz. Den Namen nimmst du aus der Instanz selbst und suchst nicht einfach nach chat.live.. Die Queue ist exklusiv, hängt an chat.messages und hat genau einen Leser. Prüf nicht, dass es nur eine Live-Queue gibt, weil Spring Test-Kontexte im Zwischenspeicher behält und jeder davon seine eigene Queue am gleichen Broker hat. Eine Nachricht über POST /api/messages steht danach mit ihrer id im Log (zum Beispiel mit OutputCaptureExtension).
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 2: Live-Queue.»

**Test:** Nach dem Start zeigt die Management-API die Queue von dieser Instanz. Sie ist exklusiv, hängt an `chat.messages` und hat einen Leser. Ich prüfe nicht «genau eine Queue», weil Spring Test-Kontexte im Zwischenspeicher behält und jeder seine eigene Queue hat. Wenn ich eine Nachricht über `POST /api/messages` schicke, steht sie mit ihrer id im Log.

**Geprüft und korrigiert:**

## Schritt 3 — Stream-Endpunkt mit Test für zwei Instanzen

**Ziel:** `GET /stream?roomId=...` antwortet mit `text/event-stream`, bleibt offen und schickt jede Nachricht als Ereignis mit `id:` und `data:` an alle offenen Streams.

**Warum an dieser Stelle:** Jetzt kommt die Nachricht von der Instanz bis zum Client. Erst ab hier kann ein Test an einem Stream sehen, ob eine Nachricht ankommt. Darum gehört der Test mit zwei Instanzen hierher.

**Auftrag an die KI (Wortlaut):**

> «Schritt 3: der Stream-Endpunkt mit dem Test für zwei Instanzen.
>
> Bau GET /stream?roomId=... mit SseEmitter. Die Antwort hat Content-Type: text/event-stream und bleibt offen, ohne Timeout, bis der Client sie schliesst. Der Listener aus Schritt 2 schickt jetzt jede Nachricht an alle offenen Streams dieser Instanz, mit der Nachrichten-id bei id: und dem JSON bei data: (Format wie in Spec 1.3). Fehlt roomId, kommt 400. Der Endpunkt darf die Datenbank nicht brauchen, also auch nicht prüfen, ob es den Raum gibt (Spec 2.5). Streams, die zugehen oder abbrechen, fliegen aus der Liste. Nach Raum filtern kommt erst in Schritt 4, hier bekommen alle Streams alles.
>
> Test: Starte im Test den chat-service zweimal am gleichen RabbitMQ und an der gleichen Datenbank, jeden auf einem eigenen Zufallsport (zum Beispiel mit SpringApplicationBuilder). Die Instanz, die du im Test selbst startest, machst du am Ende vom Test wieder zu. Öffne an beiden einen Stream, schick eine Nachricht über A, und beide Streams müssen sie mit der gleichen id bekommen. Prüf dazu den Content-Type, das Format mit id:, data: und Leerzeile (Z1) und die 400 ohne roomId.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 3: Stream-Endpunkt.»

**Test:** Der Test startet den chat-service zweimal am gleichen RabbitMQ, macht an beiden Instanzen einen Stream auf und schickt eine Nachricht über Instanz A. Beide Streams bekommen sie mit der gleichen id. Dazu prüfe ich den Content-Type, das Format vom Ereignis mit der Leerzeile (Z1) und dass ohne `roomId` ein 400 kommt.

**Geprüft und korrigiert:**

## Schritt 4 — Raumfilter

**Ziel:** Ein Stream bekommt nur die Nachrichten, bei denen die `roomId` zu seinem Raum passt.

**Warum an dieser Stelle:** Der Filter braucht die Liste mit den offenen Streams aus Schritt 3. Danach ist der Normalfall fertig.

**Auftrag an die KI (Wortlaut):**

> «Schritt 4: der Raumfilter.
>
> Ein Stream bekommt nur Nachrichten, deren roomId zu seinem Raum passt (Spec 1.3 und 2.1). Der Vergleich passiert im chat-service beim Verteilen an die Streams. Die Live-Queue bekommt weiterhin alle Nachrichten.
>
> Test: Bau den Zwei-Instanzen-Test aus Schritt 3 zu S3 aus. An jeder Instanz ein Stream in Raum A, an A dazu einer in Raum B. Raum B legst du im Test in der Datenbank an. Zwei Threads senden gleichzeitig je 200 Nachrichten in Raum A, einer über A als Absender s3-a, einer über B als s3-b, mit den Texten 1 bis 200. Jeder Thread schickt die nächste erst, wenn die 202 für die vorherige da ist (Spec 2.2). Erwartet: Beide Streams in Raum A haben 400 Ereignisse, der in Raum B hat 0, und pro Absender kommen die Texte in beiden Streams als 1 bis 200 an.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 4: Raumfilter.»

**Test:** Ich baue den Test mit zwei Instanzen zu S3 aus: An jeder Instanz ist ein Stream in Raum A, dazu kommt noch einer in Raum B. Dann gehen 200 Nachrichten über A und 200 über B in Raum A. Beide Streams in Raum A müssen 400 haben, der in Raum B 0, und bei jedem Absender muss die Reihenfolge stimmen.

**Geprüft und korrigiert:**

## Schritt 5 — Grenze und Heartbeat

**Ziel:** Die Live-Queue hat `x-max-length: 1000` und `x-overflow: drop-head`, und der chat-service will einen Heartbeat von 15 Sekunden.

**Warum an dieser Stelle:** Der Normalfall läuft, jetzt kommen die Fehlerfälle. Diese zwei sind nur Einstellungen, dafür braucht es keinen eigenen Code.

**Auftrag an die KI (Wortlaut):**

> «Schritt 5: Grenze und Heartbeat.
>
> Die Live-Queue bekommt x-max-length: 1000 und x-overflow: drop-head (Spec 3.3). In application.yml kommt spring.rabbitmq.requested-heartbeat: 15s (Spec 2.3 und 3.2). Sonst kein neuer Code.
>
> Test: Die Management-API zeigt an der Queue von dieser Instanz beide Argumente und an der Verbindung vom chat-service einen Heartbeat (timeout) von 15 Sekunden. Dann stoppst du den Listener über die RabbitListenerEndpointRegistry, publizierst mit RabbitTemplate 1100 Nachrichten mit den Texten 1 bis 1100 direkt an chat.messages und prüfst: In der Queue warten genau 1000, und die vorderste hat den Text 101. Das zweite zeigt, dass wirklich die ältesten rausfliegen und nicht die neuen abgelehnt werden. Am Schluss den Listener wieder starten, damit andere Tests nichts davon merken.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 5: Grenze und Heartbeat.»

**Test:** Die Management-API zeigt an der Live-Queue beide Argumente und an der Verbindung einen Heartbeat von 15 s. Wenn der Listener gestoppt ist und 1100 Nachrichten kommen, warten in der Queue genau 1000, und die vorderste ist die 101. Erst daran sieht man, dass wirklich die ältesten rausfliegen.

**Geprüft und korrigiert:**

## Schritt 6 — Streams beenden nach Neuverbindung

**Ziel:** Wenn die Verbindung zu RabbitMQ abreisst und neu aufgebaut wird, beendet die Instanz alle offenen Streams. Queue und Bindung macht Spring selbst wieder.

**Warum an dieser Stelle:** Das ist das Verhalten aus S6, und dafür braucht es eigene Logik. Es baut auf der Liste mit den offenen Streams aus Schritt 3 und auf dem Heartbeat aus Schritt 5 auf.

**Auftrag an die KI (Wortlaut):**

> «Schritt 6: Streams beenden nach einer Neuverbindung.
>
> Wenn die Verbindung zu RabbitMQ weg war und neu aufgebaut wird, beendet die Instanz alle offenen Streams mit complete(). Dann verbinden sich die Clients neu und holen mit Last-Event-ID nach, was sie verpasst haben (Spec 2.3). Mach das mit einem ConnectionListener an der ConnectionFactory: Beim ersten onCreate nach dem Start passiert nichts, bei jedem weiteren werden alle Streams beendet. Die Streams erst nach der Neuverbindung beenden und nicht schon beim Abbruch, damit die neue Live-Queue schon da ist, wenn die Clients zurückkommen. Queue und Bindung legt Spring selbst neu an, dafür keinen eigenen Code schreiben.
>
> Test: Öffne einen Stream und schliess dann die Verbindung der Instanz über die Management-API (DELETE /api/connections/{name}). Erwartet: Der Stream endet, nach der Neuverbindung gibt es die Queue von dieser Instanz wieder, mit genau einem Leser, und ein neuer Stream bekommt eine neue Nachricht.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 6: Streams beenden nach Neuverbindung.»

**Test:** Der Test macht die Verbindung von der Instanz über die Management-API zu. Danach muss der offene Stream zu sein, und nach der Neuverbindung gibt es die Queue von dieser Instanz wieder, mit einem Leser, und sie stellt eine neue Nachricht zu.

**Geprüft und korrigiert:**

## Schritt 7 — Nachholen

**Ziel:** Wenn `Last-Event-ID` mitkommt, sucht der Stream das `sent_at` von dieser Nachricht. Dann schickt er zuerst alle Nachrichten im gleichen Raum mit gleichem oder späterem `sent_at`, die älteste zuerst, aber ohne die Nachricht selbst.

**Warum an dieser Stelle:** Fürs Nachholen braucht es den fertigen Stream und den Raumfilter. Weil noch keine Live-Nachrichten gleichzeitig dazukommen, sieht man Fehler in der Abfrage für sich allein.

**Auftrag an die KI (Wortlaut):**

> «Schritt 7: Nachholen.
>
> Kommt beim GET /stream der Header Last-Event-ID mit, sucht der Dienst in der Tabelle message das sent_at dieser Nachricht. Dann schickt er zuerst alle Nachrichten im gleichen Raum mit gleichem oder späterem sent_at, die älteste zuerst, ohne die Nachricht mit dieser ID selbst (Spec 2.4). Danach läuft der Stream live weiter. Lies mit dem gleichen Datenbankzugriff wie beim Verlauf und änder das Schema nicht. Ohne den Header greift der Stream nicht auf die Datenbank zu. Die Warteliste gegen doppelte IDs (Schritt 8) und reload mit dem zweiten Versuch nach einer Sekunde (Schritt 9) kommen noch nicht hier rein. Bei einer unbekannten ID läuft der Stream vorerst einfach live weiter.
>
> Im Test läuft kein batch-writer. Häng darum im Testcode eine eigene Queue an chat.messages mit einem Listener, der jede Nachricht so in die Tabelle schreibt wie der batch-writer. Das gehört nur in den Testcode, nicht in den chat-service.
>
> Test: Stream öffnen, eine Nachricht empfangen und ihre id merken, Stream schliessen, 50 Nachrichten senden und warten, bis alle in der Tabelle sind, dann mit Last-Event-ID neu verbinden. Erwartet: genau die 50 in der richtigen Reihenfolge. Zweiter Test: zwei Nachrichten mit genau gleichem sent_at direkt in die Tabelle schreiben, mit der ersten als Last-Event-ID verbinden, und die zweite muss ankommen. Dritter Test (S5): mit einem eigenen PostgreSQL-Container, der gestoppt wird, damit die anderen Tests nichts merken. Ein Stream ohne Header geht trotzdem auf und bekommt eine Nachricht über POST /api/messages. Wenn POST ohne Datenbank nicht geht, sag es mir, weil S5 das verlangt.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 7: Nachholen.»

**Test:** Eine Nachricht empfangen, trennen, 50 senden und mit `Last-Event-ID` neu verbinden: Es müssen genau die 50 in der richtigen Reihenfolge kommen. Zwei Nachrichten mit dem gleichen `sent_at` kommen beide an. Ein Stream ohne Header geht auch, wenn PostgreSQL gestoppt ist (S5). Weil im Test kein batch-writer läuft, schreibt ein Listener im Testcode die Nachrichten in die Tabelle.

**Geprüft und korrigiert:**

## Schritt 8 — Nichts doppelt

**Ziel:** Während dem Nachholen kommen die Live-Nachrichten in eine Warteliste. Danach wird die Warteliste geschickt, aber ohne die IDs, die schon im `HashSet` sind. Das Ganze ist mit `synchronized` geschützt, aber die Sperre gilt nur zum Umschalten und zum Senden, nicht während der Datenbankabfrage.

**Warum an dieser Stelle:** Das ist der einzige Teil, wo zwei Threads gleichzeitig arbeiten. Weil das Nachholen schon läuft, weiss ich bei einem Fehler, dass er von der Gleichzeitigkeit kommt.

**Auftrag an die KI (Wortlaut):**

> «Schritt 8: nichts doppelt.
>
> Bau das Nachholen so um, wie es in Spec 2.4 steht: Zuerst meldet sich der Stream für Live-Nachrichten an, aber die kommen in eine Warteliste statt direkt raus. Dann schickt er die Nachrichten aus der Datenbank und merkt sich ihre IDs in einem HashSet. Zum Schluss schickt er die Warteliste ohne die IDs, die schon im Set sind, und ab dann geht alles direkt raus. Nicht andersrum (zuerst lesen, dann anmelden), sonst kann eine Nachricht genau dazwischen fallen. Der Web-Thread und der RabbitMQ-Listener greifen gleichzeitig auf den Stream zu, schütz das mit synchronized auf dem Stream-Objekt. Die Sperre gilt aber nur kurz: beim Einschalten der Warteliste, beim Senden und beim Ausschalten der Warteliste. Während der Datenbankabfrage hält der Web-Thread sie nicht. Es gibt nur einen Listener-Thread für alle Streams, und wenn der warten muss, bekommen auch alle anderen Clients nichts mehr.
>
> Test: Wie der erste Test aus Schritt 7, aber während dem Neuverbinden schickt ein zweiter Thread laufend weitere Nachrichten als Absender live. Erwartet: zuerst die 50 in der richtigen Reihenfolge, danach alle Live-Nachrichten, keine ID doppelt und keine fehlt (S7). Lass den Test mehrmals laufen (zum Beispiel @RepeatedTest(20)), weil so ein Fehler nicht jedes Mal kommt.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 8: Nichts doppelt.»

**Test:** Wie in Schritt 7, aber während dem Neuverbinden kommen live noch weitere Nachrichten. Zuerst müssen die 50 in der richtigen Reihenfolge kommen, danach die Live-Nachrichten, und keine ID darf doppelt sein. Damit ist S7 ganz abgedeckt.

**Geprüft und korrigiert:**

## Schritt 9 — Unbekannte ID

**Ziel:** Wenn der Stream die ID auch nach einer Sekunde nicht findet oder wenn sie zu einem anderen Raum gehört, antwortet er trotzdem mit 200. Er schickt als erstes `reload`, schreibt eine Warnung ins Log und läuft live weiter.

**Warum an dieser Stelle:** Das ist ein Sonderfall vom Nachholen und macht erst Sinn, wenn das Nachholen funktioniert.

**Auftrag an die KI (Wortlaut):**

> «Schritt 9: unbekannte ID.
>
> Findet der Stream die Last-Event-ID nicht, sucht er nach einer Sekunde nochmals, weil der batch-writer die Nachricht vielleicht noch nicht geschrieben hat. Findet er sie dann immer noch nicht, oder gehört sie zu einem anderen Raum, antwortet er trotzdem mit 200, schickt als erstes event: reload mit data: {"reason":"unknown-last-event-id"}, schreibt eine Warnung ins Log und läuft live weiter (Spec 2.6). Keinen Fehlerstatus schicken, sonst gibt EventSource auf. Während der Sekunde Warten und der zweiten Abfrage hält der Web-Thread die Sperre vom Stream nicht, sonst steht der Listener-Thread und alle anderen Clients bekommen nichts mehr. Live-Nachrichten kommen in dieser Zeit in die Warteliste aus Schritt 8.
>
> Test: Mit Last-Event-ID: 00000000-0000-0000-0000-000000000000 verbinden. Erwartet: Status 200, erstes Ereignis event: reload, danach kommt eine Live-Nachricht an (Z2). Zweiter Test: Eine Nachricht, die erst nach einer halben Sekunde in der Tabelle landet, wird beim zweiten Versuch gefunden und normal nachgeholt.
>
> Zuerst den Test, der rot ist, dann der Code. Keine Java-Streams im chat-service, Kommentare nach den Regeln vom CommentRulesTest. Nichts aus späteren Schritten vorwegnehmen. Am Schluss mvn clean test im Wurzelverzeichnis grün und ein Commit Schritt 9: Unbekannte ID.»

**Test:** Mit `Last-Event-ID: 00000000-0000-0000-0000-000000000000` verbinden: Der Status ist 200, das erste Ereignis ist `event: reload`, und danach kommt eine Live-Nachricht an (Z2).

**Geprüft und korrigiert:**

## Schritt 10 — Abnahmeskript und Doku

**Ziel:** `scripts/abnahme-live.sh` macht S1 bis S8 und Z1, Z2 und schreibt den gemessenen Wert neben den erwarteten. Das README und `docs/betrieb.md` sind auf dem neusten Stand.

**Warum an dieser Stelle:** Die Szenarien kann man erst am fertigen Stack richtig messen, so wie in Bewertung 1.

**Auftrag an die KI (Wortlaut):**

> «Schritt 10: Abnahmeskript und Doku.
>
> Schreib scripts/abnahme-live.sh. Es führt S1 bis S8 und Z1, Z2 genau so aus, wie sie in Spec Abschnitt 4 stehen, mit den Hilfsfunktionen von dort. Bei jedem Szenario schreibt es den gemessenen Wert neben den erwarteten und dazu OK oder FEHLER. Am Schluss kommt eine Zusammenfassung, und der Exit-Code ist nur 0, wenn alles bestanden ist. Bau es auf wie scripts/abnahme.sh aus Bewertung 1, aber änder das alte Skript nicht. S2 läuft in einem frischen Klon in einem temporären Ordner. Nach S4 startet das Skript den Stack wieder mit zwei Instanzen und bestimmt A und B neu. Wenn ein Befehl aus der Spec nicht so funktioniert wie beschrieben, änder nicht einfach den erwarteten Wert, sondern sag es mir.
>
> Dann führ das README und docs/betrieb.md nach: wie man den Stack mit zwei Instanzen startet, was /stream macht und wie man das Skript laufen lässt.
>
> Lass das Skript einmal auf einem frischen Klon laufen und gib mir die ganze Ausgabe, damit ich die Messwerte am Ende vom Plan eintragen kann. Commit Schritt 10: Abnahmeskript und Doku.»

**Test:** Das Skript läuft auf einem frischen Klon durch, und alle Szenarien bestehen. Die Messwerte stehen am Ende von diesem Plan.

**Geprüft und korrigiert:**
