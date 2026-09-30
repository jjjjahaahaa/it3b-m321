package ch.benedict.m321.batchwriter.message;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Wandelt den Koerper einer Queue-Nachricht (Bytes) in eine IncomingMessage um.
 *
 * Absichtlich von Hand und ohne Umwandlung durch das Framework: das Framework wuerde dem Header
 * __TypeId__ vertrauen, der eine Klasse des chat-service nennt, die es hier nicht gibt. Ausserdem
 * setzt niemand diesen Header, der eine Nachricht von Hand in die Queue legt. So liest der Parser
 * nur den Koerper und meldet bei jedem Fehler genau, welches Feld nicht stimmt.
 */
@Component
public class MessageParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Liest die fuenf Pflichtfelder aus dem JSON-Koerper. Wirft InvalidMessageException, sobald
     * etwas nicht stimmt. Unbekannte Zusatzfelder werden nicht beachtet.
     */
    public IncomingMessage parse(byte[] body) {
        JsonNode root = readJsonObject(body);

        UUID id = readUuid(root, "id");
        UUID roomId = readUuid(root, "roomId");
        String sender = readText(root, "sender");
        String text = readText(root, "text");
        Instant sentAt = readInstant(root, "sentAt");

        return new IncomingMessage(id, roomId, sender, text, sentAt);
    }

    /** Liest die Bytes als JSON und prueft, dass es ein Objekt ist (also mit geschweiften Klammern). */
    private JsonNode readJsonObject(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException problem) {
            throw new InvalidMessageException("Koerper ist kein gueltiges JSON", problem);
        }

        if (root == null || !root.isObject()) {
            throw new InvalidMessageException("Koerper ist kein JSON-Objekt");
        }
        return root;
    }

    /** Holt ein Pflichtfeld, das als Text im JSON steht. Fehlt es oder ist es kein Text, ist die Nachricht ungueltig. */
    private String readText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);

        if (field == null || field.isNull()) {
            throw new InvalidMessageException("Feld fehlt: " + fieldName);
        }
        if (!field.isTextual()) {
            throw new InvalidMessageException("Feld ist kein Text: " + fieldName);
        }
        return field.asText();
    }

    /** Liest ein Textfeld und wandelt es in eine UUID um. */
    private UUID readUuid(JsonNode root, String fieldName) {
        String text = readText(root, fieldName);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException problem) {
            throw new InvalidMessageException("Feld ist keine UUID: " + fieldName, problem);
        }
    }

    /**
     * Liest ein Textfeld und wandelt es in einen Zeitpunkt um. Das Format ist ISO-8601 in UTC,
     * zum Beispiel 2026-09-30T06:54:53.879436190Z, mit bis zu neun Nachkommastellen.
     */
    private Instant readInstant(JsonNode root, String fieldName) {
        String text = readText(root, fieldName);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException problem) {
            throw new InvalidMessageException("Feld ist kein ISO-8601-Zeitpunkt: " + fieldName, problem);
        }
    }
}
