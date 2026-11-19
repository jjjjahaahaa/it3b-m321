package ch.benedict.m321.chat;

import ch.benedict.m321.chat.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Prueft, dass Spring alle Klassen zusammenbauen kann. Der Test hat absichtlich keinen
 * Rumpf: faellt beim Hochfahren irgendwo eine Bean weg oder ist eine Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand die Anwendung startet.
 *
 * Er erbt von IntegrationTest und startet damit gegen die Testcontainer statt gegen localhost.
 * Sonst sucht der Live-Listener ein RabbitMQ, das es dort nicht gibt (Plan, Aenderung 1).
 */
class ChatServiceApplicationTest extends IntegrationTest {

    /** Besteht, sobald Spring alle Klassen des chat-service ohne Fehler zusammenbauen kann. */
    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
