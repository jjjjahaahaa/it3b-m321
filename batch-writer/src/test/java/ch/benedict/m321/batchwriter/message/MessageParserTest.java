package ch.benedict.m321.batchwriter.message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft den Vertrag mit dem chat-service (docs/spec-batch-writer.md, Abschnitt 2 und 3.4).
 * Die Tests brauchen weder Datenbank noch Broker, weil der Parser nur Bytes in ein Objekt
 * umwandelt. Das erste Beispiel ist eine Nachricht, die am laufenden chat-service beobachtet wurde.
 */
class MessageParserTest {

    private final MessageParser parser = new MessageParser();

    /** Liest die beobachtete Nachricht aus src/test/resources als rohe Bytes. */
    private byte[] readObservedMessage() throws Exception {
        Path file = Path.of(getClass().getResource("/chat-service-message.json").toURI());
        return Files.readAllBytes(file);
    }

    /** Wandelt einen JSON-Text in Bytes um, so wie sie in der Queue laegen (UTF-8). */
    private byte[] bytesOf(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Die echte Nachricht des chat-service muss vollstaendig gelesen werden, auch der Zeitstempel
     * mit neun Nachkommastellen. Der Header __TypeId__ spielt keine Rolle: der Parser sieht nur den Koerper.
     */
    @Test
    void readsTheMessageObservedAtTheChatService() throws Exception {
        IncomingMessage message = parser.parse(readObservedMessage());

        assertThat(message.id()).isEqualTo(UUID.fromString("f696fd36-053b-422d-ae03-7b3f461d17a7"));
        assertThat(message.roomId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(message.sender()).isEqualTo("lernende1");
        assertThat(message.text()).isEqualTo("Hallo Vertrag");
        assertThat(message.sentAt()).isEqualTo(Instant.parse("2026-09-30T06:54:53.879436190Z"));
    }

    /** Umlaute muessen als UTF-8 unversehrt ankommen. */
    @Test
    void keepsUmlautsAndEmojis() {
        String json = "{\"id\":\"f696fd36-053b-422d-ae03-7b3f461d17a7\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":\"Zoë\",\"text\":\"Grüezi 👋\",\"sentAt\":\"2026-09-30T06:54:53Z\"}";

        IncomingMessage message = parser.parse(bytesOf(json));

        assertThat(message.sender()).isEqualTo("Zoë");
        assertThat(message.text()).isEqualTo("Grüezi 👋");
    }

    /** Ein neues Feld im chat-service darf den Schreiber nicht brechen (Spezifikation 2.2). */
    @Test
    void ignoresUnknownExtraFields() {
        String json = "{\"id\":\"f696fd36-053b-422d-ae03-7b3f461d17a7\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":\"a\",\"text\":\"b\",\"sentAt\":\"2026-09-30T06:54:53Z\","
                    + "\"reaction\":\"daumen\"}";

        IncomingMessage message = parser.parse(bytesOf(json));

        assertThat(message.text()).isEqualTo("b");
    }

    /** Kein JSON ist eine ungueltige Nachricht, keine Fehlermeldung des Programms. */
    @Test
    void rejectsBodyThatIsNotJson() {
        assertThatThrownBy(() -> parser.parse(bytesOf("das ist kein json")))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** Eine leere Nachricht ist ungueltig. */
    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** Gueltiges JSON, das aber kein Objekt ist (hier eine Liste), ist ungueltig. */
    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertThatThrownBy(() -> parser.parse(bytesOf("[1,2,3]")))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** Fehlt ein Pflichtfeld, nennt die Meldung das Feld. So findet man den Fehler in der Dead-Letter-Queue. */
    @Test
    void rejectsMissingField() {
        String json = "{\"id\":\"f696fd36-053b-422d-ae03-7b3f461d17a7\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":\"a\",\"sentAt\":\"2026-09-30T06:54:53Z\"}";

        assertThatThrownBy(() -> parser.parse(bytesOf(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("text");
    }

    /** Ein Feld mit dem Wert null zaehlt wie ein fehlendes Feld. */
    @Test
    void rejectsNullField() {
        String json = "{\"id\":\"f696fd36-053b-422d-ae03-7b3f461d17a7\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":null,\"text\":\"b\",\"sentAt\":\"2026-09-30T06:54:53Z\"}";

        assertThatThrownBy(() -> parser.parse(bytesOf(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("sender");
    }

    /** Eine id, die keine UUID ist, wuerde spaeter in der Datenbank scheitern. Besser sofort ablehnen. */
    @Test
    void rejectsIdThatIsNotAUuid() {
        String json = "{\"id\":\"nummer-eins\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":\"a\",\"text\":\"b\",\"sentAt\":\"2026-09-30T06:54:53Z\"}";

        assertThatThrownBy(() -> parser.parse(bytesOf(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("id");
    }

    /** Ein Zeitstempel in einem anderen Format als ISO-8601 ist ungueltig. */
    @Test
    void rejectsBrokenTimestamp() {
        String json = "{\"id\":\"f696fd36-053b-422d-ae03-7b3f461d17a7\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":\"a\",\"text\":\"b\",\"sentAt\":\"gestern Abend\"}";

        assertThatThrownBy(() -> parser.parse(bytesOf(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("sentAt");
    }

    /** Ein Text-Feld, das eine Zahl enthaelt, ist ungueltig und wird nicht stillschweigend umgewandelt. */
    @Test
    void rejectsTextFieldThatIsANumber() {
        String json = "{\"id\":\"f696fd36-053b-422d-ae03-7b3f461d17a7\","
                    + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sender\":\"a\",\"text\":42,\"sentAt\":\"2026-09-30T06:54:53Z\"}";

        assertThatThrownBy(() -> parser.parse(bytesOf(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("text");
    }
}
