package ch.benedict.m321.batchwriter.message;

/**
 * Sagt: diese Nachricht aus der Queue ist als Nachricht unbrauchbar (kein JSON, Feld fehlt,
 * falsches Format). Wiederholen hilft nie, deshalb wird sie abgelehnt und landet in chat.dlq.
 * Die Ausnahme ist "unchecked" (RuntimeException), damit sie nicht in jeder Signatur stehen muss.
 * Wer sie erwartet, faengt sie bewusst ab.
 */
public class InvalidMessageException extends RuntimeException {

    /** Haelt fest, was genau an der Nachricht nicht stimmt. Der Text erscheint im Log. */
    public InvalidMessageException(String description) {
        super(description);
    }

    /** Wie oben, zusaetzlich mit der urspruenglichen Ausnahme (z. B. dem JSON-Fehler) als Ursache. */
    public InvalidMessageException(String description, Throwable cause) {
        super(description, cause);
    }
}
