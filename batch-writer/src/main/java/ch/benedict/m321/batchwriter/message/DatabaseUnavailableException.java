package ch.benedict.m321.batchwriter.message;

/**
 * Sagt: das Schreiben ist gescheitert, aber es liegt nicht sicher an der Zeile. Typisch ist eine
 * Datenbank, die nicht erreichbar ist oder gerade neu startet. Die Nachricht selbst ist in Ordnung,
 * der Aufrufer soll deshalb warten und es spaeter nochmals versuchen, statt sie abzulehnen.
 * Auch jeder UNBEKANNTE Fehler gehoert hierher: lieber warten als eine gute Nachricht verlieren.
 */
public class DatabaseUnavailableException extends RuntimeException {

    /** Haelt fest, was die Datenbank gemeldet hat, samt der urspruenglichen SQL-Ausnahme als Ursache. */
    public DatabaseUnavailableException(String description, Throwable cause) {
        super(description, cause);
    }
}
