package ch.benedict.m321.batchwriter.message;

/**
 * Sagt: die Datenbank hat eine Zeile abgelehnt, weil mit den DATEN etwas nicht stimmt
 * (Text zu lang, unbekannter Raum, unzulaessiges Zeichen). Wiederholen hilft nie, dieselbe Zeile
 * scheitert immer wieder. Deshalb wird sie abgelehnt und landet in chat.dlq.
 * Im Gegensatz dazu steht DatabaseUnavailableException: da liegt es nicht an der Zeile.
 */
public class BadDataException extends RuntimeException {

    /** Haelt fest, was die Datenbank gemeldet hat, samt der urspruenglichen SQL-Ausnahme als Ursache. */
    public BadDataException(String description, Throwable cause) {
        super(description, cause);
    }
}
