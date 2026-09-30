package ch.benedict.m321.batchwriter.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht so, wie sie aus der Queue kommt und in die Datenbank geschrieben wird.
 * Ein "record" ist eine Kurzform fuer eine Klasse, die nur Daten haelt. Die Felder entsprechen
 * den fuenf Spalten der Tabelle message. Id und Sendezeit hat der chat-service vergeben,
 * der batch-writer aendert sie nie.
 */
public record IncomingMessage(
        UUID id,
        UUID roomId,
        String sender,
        String text,
        Instant sentAt) {
}
