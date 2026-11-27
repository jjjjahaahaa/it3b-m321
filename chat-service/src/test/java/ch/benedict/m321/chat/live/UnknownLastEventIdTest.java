package ch.benedict.m321.chat.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.support.ChatServiceInstance;
import ch.benedict.m321.chat.support.RepositoryFiles;
import ch.benedict.m321.chat.support.StreamClient;
import ch.benedict.m321.chat.support.TestInfrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft Schritt 9 aus docs/plan-live-zustellung.md: eine Last-Event-ID, die der Stream nicht nachholen
 * kann (Spec 2.6, Szenario Z2). Er antwortet trotzdem mit 200, schickt als erstes event: reload und laeuft
 * danach live weiter. Dazu das zweite Suchen nach einer Sekunde (Spec 2.4) und eine Datenbank, die beim
 * Nachholen nicht erreichbar ist (Plan, Aenderung 2).
 *
 * Die ersten drei Tests teilen sich eine Instanz. Der Test ohne Datenbank startet seine eigene.
 */
class UnknownLastEventIdTest {

    private static final String RELOAD_EVENT = "event:reload";
    private static final String RELOAD_DATA = "data:{\"reason\":\"unknown-last-event-id\"}";

    private static ConfigurableApplicationContext instance;

    private final ObjectMapper json = new ObjectMapper();

    /** Startet die gemeinsame Instanz fuer die ersten drei Tests. */
    @BeforeAll
    static void startInstance() {
        instance = ChatServiceInstance.start();
    }

    /**
     * Beendet erst alle Streams und dann die Instanz. Sonst wartet Spring beim Herunterfahren bis zu
     * 30 Sekunden auf die offenen Streams. Ist die Instanz gar nicht gestartet, gibt es nichts zu beenden.
     */
    @AfterAll
    static void stopInstance() {
        if (instance != null) {
            stop(instance);
        }
    }

    /** Nach jedem Test sind alle Streams der gemeinsamen Instanz wieder zu. */
    @AfterEach
    void closeStreams() {
        instance.getBean(StreamRegistry.class).completeAll();
    }

    /** Beendet zuerst die offenen Streams und dann die Instanz. */
    private static void stop(ConfigurableApplicationContext someInstance) {
        someInstance.getBean(StreamRegistry.class).completeAll();
        someInstance.close();
    }

    /** Legt einen neuen Raum an und gibt seine ID zurueck. Jede Nachricht braucht einen Raum (Fremdschluessel). */
    private String createRoom() {
        String roomId = UUID.randomUUID().toString();
        JdbcTemplate jdbcTemplate = instance.getBean(JdbcTemplate.class);
        jdbcTemplate.update("INSERT INTO room (id, name, created_by, created_at) VALUES (?::uuid, 'Testraum', 'test', now())", roomId);
        return roomId;
    }

    /** Schreibt eine Nachricht direkt in die Tabelle, so wie es der batch-writer tun wuerde. */
    private void insertMessage(UUID messageId, String roomId, String text, Instant sentAt) {
        JdbcTemplate jdbcTemplate = instance.getBean(JdbcTemplate.class);
        String sql = "INSERT INTO message (id, room_id, sender, text, sent_at) VALUES (?, ?::uuid, 'schritt9', ?, ?)";
        jdbcTemplate.update(sql, messageId, roomId, text, Timestamp.from(sentAt));
    }

    /** Sendet eine Nachricht ueber POST /api/messages an eine Instanz und gibt die id aus der Antwort zurueck. */
    private String send(ConfigurableApplicationContext someInstance, String roomId, String text) throws Exception {
        String body = "{\"roomId\":\"" + roomId + "\",\"sender\":\"schritt9\",\"text\":\"" + text + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ChatServiceInstance.baseAddress(someInstance) + "/api/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).get("id").asText();
    }

    /**
     * Prueft, dass ein Stream mit reload begonnen hat und danach live weiterlaeuft: Status 200, die ersten
     * zwei Zeilen sind event: reload und sein data:, und eine neue Nachricht im Raum kommt an.
     */
    private void assertReloadThenLive(ConfigurableApplicationContext someInstance, StreamClient stream, String roomId) throws Exception {
        assertThat(stream.waitForLine(RELOAD_EVENT, 15)).isTrue();
        assertThat(stream.status()).isEqualTo(200);
        List<String> lines = stream.lines();
        assertThat(lines.get(0)).isEqualTo(RELOAD_EVENT);
        assertThat(lines.get(1)).isEqualTo(RELOAD_DATA);

        String liveId = send(someInstance, roomId, "live nach reload");
        assertThat(stream.waitForLine("id:" + liveId, 10)).isTrue();
    }

    /**
     * Szenario Z2: Last-Event-ID 00000000-0000-0000-0000-000000000000 gibt es nicht. Erwartet: 200, als
     * erstes event: reload, danach kommt eine Live-Nachricht an.
     */
    @Test
    void unknownIdStartsWithReloadAndThenRunsLive() throws Exception {
        String roomId = createRoom();
        String address = ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId;
        StreamClient stream = new StreamClient(address, "00000000-0000-0000-0000-000000000000");

        assertReloadThenLive(instance, stream, roomId);
        stream.close();
    }

    /**
     * Die Nachricht mit der Last-Event-ID gibt es, aber in einem anderen Raum. Das zaehlt wie eine
     * unbekannte ID: sonst bekaeme der Client Nachrichten ab einem Zeitpunkt, der mit seinem Raum nichts
     * zu tun hat.
     */
    @Test
    void idFromAnotherRoomCountsAsUnknown() throws Exception {
        String roomId = createRoom();
        String otherRoomId = createRoom();
        UUID messageInOtherRoom = UUID.randomUUID();
        insertMessage(messageInOtherRoom, otherRoomId, "anderer Raum", Instant.now().minusSeconds(60));

        String address = ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId;
        StreamClient stream = new StreamClient(address, messageInOtherRoom.toString());

        assertReloadThenLive(instance, stream, roomId);
        stream.close();
    }

    /**
     * Die Nachricht mit der Last-Event-ID landet erst nach anderthalb Sekunden in der Tabelle, also nach der
     * ersten Suche (nach der Pause von einer Sekunde, Plan Aenderung 3) und vor der zweiten. Sie wird beim
     * zweiten Versuch gefunden, und die Nachricht danach wird normal nachgeholt, ohne reload.
     */
    @Test
    void lateStoredIdIsFoundOnTheSecondAttempt() throws Exception {
        String roomId = createRoom();
        UUID lateId = UUID.randomUUID();
        UUID laterId = UUID.randomUUID();
        Instant lateSentAt = Instant.now().minusSeconds(5);
        insertMessage(laterId, roomId, "danach", lateSentAt.plusMillis(1));

        String address = ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + roomId;
        StreamClient stream = new StreamClient(address, lateId.toString());
        // Der batch-writer ist hier "langsam": die Nachricht kommt erst nach 1,5 Sekunden in die Tabelle.
        Thread.sleep(1500);
        insertMessage(lateId, roomId, "spaet gespeichert", lateSentAt);

        assertThat(stream.waitForLine("id:" + laterId, 10)).isTrue();
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.lines()).doesNotContain(RELOAD_EVENT);
        stream.close();
    }

    /**
     * Plan, Aenderung 2: die Datenbank ist beim Nachholen nicht erreichbar. Statt 500 (dann gaebe EventSource
     * auf) kommt dasselbe wie bei einer unbekannten ID: 200, event: reload, danach live. Dafuer hat dieser
     * Test seine eigene PostgreSQL, die er anhaelt, damit die gemeinsame Test-Datenbank weiterlaeuft.
     */
    @Test
    void databaseDownDuringCatchUpStartsWithReloadAndThenRunsLive() throws Exception {
        PostgreSQLContainer<?> ownDatabase = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("chat")
                .withUsername(TestInfrastructure.databaseUser())
                .withPassword(TestInfrastructure.databasePassword())
                .withCopyFileToContainer(
                        MountableFile.forHostPath(RepositoryFiles.find("db/01-schema.sql")),
                        "/docker-entrypoint-initdb.d/01-schema.sql");
        ownDatabase.start();
        ConfigurableApplicationContext ownInstance = ChatServiceInstance.start(ownDatabase.getJdbcUrl());
        try {
            ownDatabase.stop();

            String roomId = UUID.randomUUID().toString();
            String address = ChatServiceInstance.baseAddress(ownInstance) + "/stream?roomId=" + roomId;
            StreamClient stream = new StreamClient(address, UUID.randomUUID().toString());

            assertReloadThenLive(ownInstance, stream, roomId);
            stream.close();
        } finally {
            stop(ownInstance);
        }
    }
}
