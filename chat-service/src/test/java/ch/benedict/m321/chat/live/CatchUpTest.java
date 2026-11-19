package ch.benedict.m321.chat.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.support.ChatServiceInstance;
import ch.benedict.m321.chat.support.StreamClient;
import ch.benedict.m321.chat.support.TestInfrastructure;
import ch.benedict.m321.chat.support.TestPersistWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft Schritt 7 aus docs/plan-live-zustellung.md: das Nachholen mit Last-Event-ID (Spec 2.4) und dass
 * ein Stream ohne Last-Event-ID keine Datenbank braucht (Spec 2.5, Szenario S5).
 *
 * Jeder Test startet seine eigene Instanz. Weil im Test kein batch-writer laeuft, schreibt ein
 * TestPersistWriter die Nachrichten in die Tabelle.
 */
class CatchUpTest {

    private final ObjectMapper json = new ObjectMapper();

    /** Legt einen neuen Raum an und gibt seine ID zurueck. Jede Nachricht braucht einen Raum (Fremdschluessel). */
    private String createRoom(ConfigurableApplicationContext instance) {
        String roomId = UUID.randomUUID().toString();
        JdbcTemplate jdbcTemplate = instance.getBean(JdbcTemplate.class);
        jdbcTemplate.update("INSERT INTO room (id, name, created_by, created_at) VALUES (?::uuid, 'Testraum', 'test', now())", roomId);
        return roomId;
    }

    /** Sendet eine Nachricht ueber POST /api/messages und gibt die id aus der Antwort zurueck. */
    private String send(ConfigurableApplicationContext instance, String roomId, String text) throws Exception {
        String body = "{\"roomId\":\"" + roomId + "\",\"sender\":\"schritt7\",\"text\":\"" + text + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ChatServiceInstance.baseAddress(instance) + "/api/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).get("id").asText();
    }

    /** Wartet hoechstens 30 Sekunden, bis so viele Nachrichten des Raums in der Tabelle stehen. */
    private void waitForRowsInRoom(ConfigurableApplicationContext instance, String roomId, int expected) throws InterruptedException {
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

    /** Wartet hoechstens die angegebene Zeit, bis ein Stream mindestens so viele data:-Zeilen hat. */
    private void waitForDataLines(StreamClient stream, int expected, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (texts(stream).size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
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

    /** Wartet, bis die Instanz mindestens so viele offene Streams kennt, hoechstens 10 Sekunden. */
    private void waitForOpenStreams(ConfigurableApplicationContext instance, int expected) throws InterruptedException {
        StreamRegistry registry = instance.getBean(StreamRegistry.class);
        for (int attempt = 0; attempt < 200 && registry.openStreams() < expected; attempt++) {
            Thread.sleep(50);
        }
    }

    /**
     * Beendet erst alle Streams und dann die Instanz. Sonst wartet Spring beim Herunterfahren bis zu
     * 30 Sekunden auf die offenen Streams, weil es sie als laufende Anfragen ansieht.
     */
    private void stop(ConfigurableApplicationContext instance) {
        instance.getBean(StreamRegistry.class).completeAll();
        instance.close();
    }

    /**
     * Szenario S7 ohne gleichzeitige Live-Nachrichten: eine Nachricht empfangen, trennen, 50 senden,
     * warten bis alle in der Tabelle sind, mit Last-Event-ID neu verbinden. Es kommen genau die 50 in der
     * richtigen Reihenfolge, und danach laeuft der Stream live weiter.
     */
    @Test
    void reconnectWithLastEventIdDeliversExactlyTheMissedMessagesInOrder() throws Exception {
        ConfigurableApplicationContext instance = ChatServiceInstance.start();
        TestPersistWriter writer = new TestPersistWriter(TestInfrastructure.jdbcUrl());
        writer.start();
        try {
            String roomId = createRoom(instance);
            String streamAddress = ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId;

            StreamClient first = new StreamClient(streamAddress);
            waitForOpenStreams(instance, 1);
            String lastId = send(instance, roomId, "0");
            assertThat(first.waitForLine("id:" + lastId, 10)).isTrue();
            first.close();

            List<String> expected = new ArrayList<>();
            for (int number = 1; number <= 50; number++) {
                send(instance, roomId, String.valueOf(number));
                expected.add(String.valueOf(number));
            }
            waitForRowsInRoom(instance, roomId, 51);

            StreamClient second = new StreamClient(streamAddress, lastId);
            waitForDataLines(second, 50, 10);
            assertThat(texts(second)).isEqualTo(expected);

            String liveId = send(instance, roomId, "live");
            assertThat(second.waitForLine("id:" + liveId, 10)).isTrue();
            second.close();
        } finally {
            writer.stop();
            stop(instance);
        }
    }

    /**
     * Zwei Nachrichten mit genau gleichem sent_at. Mit der ersten als Last-Event-ID muss die zweite
     * ankommen. Deshalb heisst es in Spec 2.4 "gleich oder spaeter" und nicht nur "spaeter".
     */
    @Test
    void messageWithTheSameSentAtIsNotLost() throws Exception {
        ConfigurableApplicationContext instance = ChatServiceInstance.start();
        try {
            String roomId = createRoom(instance);
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            Timestamp sameMoment = Timestamp.from(Instant.parse("2026-11-19T10:00:00.123456Z"));
            JdbcTemplate jdbcTemplate = instance.getBean(JdbcTemplate.class);
            String insert = "INSERT INTO message (id, room_id, sender, text, sent_at) VALUES (?, ?::uuid, 'schritt7', ?, ?)";
            jdbcTemplate.update(insert, firstId, roomId, "erste", sameMoment);
            jdbcTemplate.update(insert, secondId, roomId, "zweite", sameMoment);

            String streamAddress = ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId;
            StreamClient stream = new StreamClient(streamAddress, firstId.toString());

            assertThat(stream.waitForLine("id:" + secondId, 10)).isTrue();
            assertThat(texts(stream)).containsExactly("zweite");
            stream.close();
        } finally {
            stop(instance);
        }
    }

    /**
     * Szenario S5: die Datenbank ist weg. Ein Stream ohne Last-Event-ID geht trotzdem auf und bekommt eine
     * Nachricht, die ueber POST /api/messages kommt. Dafuer hat dieser Test seine eigene PostgreSQL, die er
     * anhaelt, damit die gemeinsame Test-Datenbank der anderen Tests weiterlaeuft.
     */
    @Test
    void streamWithoutLastEventIdWorksWhileTheDatabaseIsDown() throws Exception {
        PostgreSQLContainer<?> ownDatabase = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("chat")
                .withUsername(TestInfrastructure.databaseUser())
                .withPassword(TestInfrastructure.databasePassword())
                .withCopyFileToContainer(
                        MountableFile.forHostPath(ch.benedict.m321.chat.support.RepositoryFiles.find("db/01-schema.sql")),
                        "/docker-entrypoint-initdb.d/01-schema.sql");
        ownDatabase.start();
        ConfigurableApplicationContext instance = ChatServiceInstance.start(ownDatabase.getJdbcUrl());
        try {
            ownDatabase.stop();

            String roomId = UUID.randomUUID().toString();
            StreamClient stream = new StreamClient(ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId);
            waitForOpenStreams(instance, 1);
            String messageId = send(instance, roomId, "ohne Datenbank");

            assertThat(stream.waitForLine("id:" + messageId, 5)).isTrue();
            stream.close();
        } finally {
            stop(instance);
        }
    }
}
