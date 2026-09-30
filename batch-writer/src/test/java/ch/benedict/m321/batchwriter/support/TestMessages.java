package ch.benedict.m321.batchwriter.support;

import java.time.Instant;
import java.util.UUID;

/**
 * Baut Nachrichtenkoerper genau in dem Format, das der chat-service in die Queue legt
 * (siehe docs/spec-batch-writer.md, Abschnitt 2 und die Beispieldatei chat-service-message.json).
 */
public final class TestMessages {

    private TestMessages() {
        // Nur statische Hilfsmethoden, es gibt nichts zu erzeugen.
    }

    /** Eine Nachricht mit frischer ID und der aktuellen Zeit als Sendezeit. */
    public static String json(UUID roomId, String text) {
        return json(UUID.randomUUID(), roomId, "tester", text);
    }

    /** Eine Nachricht mit allen fuenf Feldern frei waehlbar (ausser der Zeit: jetzt). */
    public static String json(UUID id, UUID roomId, String sender, String text) {
        return "{\"id\":\"" + id + "\","
             + "\"roomId\":\"" + roomId + "\","
             + "\"sender\":\"" + sender + "\","
             + "\"text\":\"" + text + "\","
             + "\"sentAt\":\"" + Instant.now() + "\"}";
    }
}
