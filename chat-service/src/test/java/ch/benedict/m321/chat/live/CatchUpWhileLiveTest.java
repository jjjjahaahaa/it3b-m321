package ch.benedict.m321.chat.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.support.ChatServiceInstance;
import ch.benedict.m321.chat.support.StreamClient;
import ch.benedict.m321.chat.support.TestInfrastructure;
import ch.benedict.m321.chat.support.TestPersistWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft Schritt 8 aus docs/plan-live-zustellung.md: Nachholen, waehrend gleichzeitig live weitere
 * Nachrichten kommen (Szenario S7 ganz). Erwartet wird: zuerst die 50 verpassten in der richtigen
 * Reihenfolge, danach alle Live-Nachrichten, keine ID doppelt und keine fehlt.
 *
 * Ein solcher Fehler haengt davon ab, in welchem Moment eine Live-Nachricht ankommt. Deshalb laeuft der
 * Test 20 Mal hintereinander. Die Instanz und der TestPersistWriter laufen fuer alle 20 Runden, jede
 * Runde hat ihren eigenen Raum. So dauert eine Runde ein paar Sekunden statt einen ganzen Neustart.
 */
class CatchUpWhileLiveTest {

    private static ConfigurableApplicationContext instance;
    private static TestPersistWriter writer;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Die Texte, die der zweite Thread schon gesendet hat, in der Reihenfolge des Sendens. */
    private final List<String> sentLiveTexts = new ArrayList<>();

    /**
     * Solange true, sendet der zweite Thread weiter. volatile, damit der Sende-Thread sofort sieht,
     * wenn der Test-Thread den Wert auf false setzt, und nicht mit einer alten Kopie weiterlaeuft.
     */
    private volatile boolean keepSending;

    /** Falls der zweite Thread beim Senden einen Fehler bekommt, steht er hier, damit der Test ihn meldet. */
    private volatile Exception senderProblem;

    /** Startet eine Instanz und den TestPersistWriter, einmal fuer alle Runden. */
    @BeforeAll
    static void startInstance() throws Exception {
        instance = ChatServiceInstance.start();
        writer = new TestPersistWriter(TestInfrastructure.jdbcUrl());
        writer.start();
    }

    /**
     * Haelt den TestPersistWriter und die Instanz an. Vorher werden alle Streams beendet, sonst wartet
     * Spring beim Herunterfahren bis zu 30 Sekunden auf sie.
     */
    @AfterAll
    static void stopInstance() throws Exception {
        writer.stop();
        instance.getBean(StreamRegistry.class).completeAll();
        instance.close();
    }

    /** Legt einen neuen Raum an und gibt seine ID zurueck. Jede Nachricht braucht einen Raum (Fremdschluessel). */
    private String createRoom() {
        String roomId = UUID.randomUUID().toString();
        JdbcTemplate jdbcTemplate = instance.getBean(JdbcTemplate.class);
        jdbcTemplate.update("INSERT INTO room (id, name, created_by, created_at) VALUES (?::uuid, 'Testraum', 'test', now())", roomId);
        return roomId;
    }

    /**
     * Sendet eine Nachricht ueber POST /api/messages und gibt die id aus der Antwort zurueck. Wartet auf
     * die Antwort 202, so wie es ein Absender laut Spec 2.2 tut, bevor er die naechste schickt.
     */
    private String send(String roomId, String text) throws Exception {
        String body = "{\"roomId\":\"" + roomId + "\",\"sender\":\"schritt8\",\"text\":\"" + text + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ChatServiceInstance.baseAddress(instance) + "/api/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).get("id").asText();
    }

    /**
     * Die Arbeit des zweiten Threads: sendet live-1, live-2, ... bis keepSending false wird. Jeder
     * gesendete Text kommt in sentLiveTexts. Die Liste ist unter einer Sperre, weil der Test-Thread sie
     * gleichzeitig liest.
     */
    private void sendLiveUntilStopped(String roomId) {
        int number = 1;
        while (keepSending) {
            String text = "live-" + number;
            try {
                send(roomId, text);
            } catch (Exception problem) {
                senderProblem = problem;
                return;
            }
            synchronized (sentLiveTexts) {
                sentLiveTexts.add(text);
            }
            number++;
        }
    }

    /** Wie viele Live-Nachrichten der zweite Thread bisher gesendet hat. */
    private int sentLiveCount() {
        synchronized (sentLiveTexts) {
            return sentLiveTexts.size();
        }
    }

    /** Wartet hoechstens 10 Sekunden, bis der zweite Thread mindestens so viele Nachrichten gesendet hat. */
    private void waitForSentLive(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (sentLiveCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Wartet hoechstens 30 Sekunden, bis so viele Nachrichten des Raums in der Tabelle stehen. */
    private void waitForRowsInRoom(String roomId, int expected) throws InterruptedException {
        JdbcTemplate jdbcTemplate = instance.getBean(JdbcTemplate.class);
        String sql = "SELECT count(*) FROM message WHERE room_id = ?::uuid";
        for (int attempt = 0; attempt < 300; attempt++) {
            Integer rows = jdbcTemplate.queryForObject(sql, Integer.class, roomId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(100);
        }
    }

    /** Wartet, bis die Instanz mindestens so viele offene Streams kennt, hoechstens 10 Sekunden. */
    private void waitForOpenStreams(int expected) throws InterruptedException {
        StreamRegistry registry = instance.getBean(StreamRegistry.class);
        for (int attempt = 0; attempt < 200 && registry.openStreams() < expected; attempt++) {
            Thread.sleep(50);
        }
    }

    /** Wartet hoechstens die angegebene Zeit, bis ein Stream mindestens so viele data:-Zeilen hat. */
    private void waitForDataLines(StreamClient stream, int expected, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (texts(stream).size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /** Die Texte aller Nachrichten am Stream, in der Reihenfolge ihrer Ankunft. */
    private List<String> texts(StreamClient stream) throws Exception {
        List<String> texts = new ArrayList<>();
        for (String line : stream.lines()) {
            if (line.startsWith("data:")) {
                JsonNode message = json.readTree(line.substring("data:".length()));
                texts.add(message.get("text").asText());
            }
        }
        return texts;
    }

    /** Die IDs aller Ereignisse am Stream (die id:-Zeilen), in der Reihenfolge ihrer Ankunft. */
    private List<String> ids(StreamClient stream) {
        List<String> ids = new ArrayList<>();
        for (String line : stream.lines()) {
            if (line.startsWith("id:")) {
                ids.add(line.substring("id:".length()));
            }
        }
        return ids;
    }

    /**
     * Wie der erste Test aus Schritt 7: eine Nachricht empfangen, trennen, 50 senden, warten bis alle in
     * der Tabelle sind. Dann aber startet ein zweiter Thread, der laufend live sendet, und genau in dieser
     * Zeit verbindet sich der Client mit Last-Event-ID neu. Der Sender laeuft weiter, bis 20 Nachrichten
     * nach dem Nachholen gesendet sind. Am Schluss muss jede gesendete Nachricht genau einmal angekommen
     * sein, und zwar in der Reihenfolge des Sendens.
     */
    @RepeatedTest(20)
    void catchUpWhileLiveMessagesArriveLosesNothingAndRepeatsNothing() throws Exception {
        StreamRegistry registry = instance.getBean(StreamRegistry.class);
        String roomId = createRoom();
        String streamAddress = ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId;
        Thread sender = new Thread(() -> sendLiveUntilStopped(roomId));
        try {
            StreamClient first = new StreamClient(streamAddress);
            waitForOpenStreams(1);
            String lastId = send(roomId, "0");
            assertThat(first.waitForLine("id:" + lastId, 10)).isTrue();
            first.close();

            List<String> expected = new ArrayList<>();
            for (int number = 1; number <= 50; number++) {
                send(roomId, String.valueOf(number));
                expected.add(String.valueOf(number));
            }
            waitForRowsInRoom(roomId, 51);

            // Der zweite Thread laeuft schon, bevor der Client neu verbindet. So kommen Live-Nachrichten
            // vor, waehrend und nach dem Nachholen an.
            keepSending = true;
            sender.start();
            waitForSentLive(5);

            StreamClient second = new StreamClient(streamAddress, lastId);
            waitForDataLines(second, 50, 10);
            int sentAfterCatchUp = sentLiveCount();
            waitForSentLive(sentAfterCatchUp + 20);
            keepSending = false;
            sender.join();
            assertThat(senderProblem).isNull();

            synchronized (sentLiveTexts) {
                expected.addAll(sentLiveTexts);
            }
            waitForDataLines(second, expected.size(), 10);

            assertThat(ids(second)).doesNotHaveDuplicates();
            assertThat(texts(second)).isEqualTo(expected);
            second.close();
        } finally {
            keepSending = false;
            sender.join(10_000L);
            // Jede Runde beginnt ohne offene Streams, damit waitForOpenStreams(1) richtig zaehlt.
            registry.completeAll();
        }
    }
}
