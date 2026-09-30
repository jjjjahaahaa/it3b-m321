package ch.benedict.m321.batchwriter.rabbit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import ch.benedict.m321.batchwriter.support.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testet den ganzen Weg mit echtem RabbitMQ und echter PostgreSQL: Nachricht in die Queue legen,
 * der Listener liest sie, schreibt sie und bestaetigt sie. Das ist der Normalfall aus
 * docs/spec-batch-writer.md, Abschnitt 3.1, dazu das Duplikat aus Szenario S5.
 */
@SpringBootTest
class PersistListenerTest {

    /** Sagt der gestarteten Anwendung, wo Datenbank und Broker der Testcontainer erreichbar sind. */
    @DynamicPropertySource
    static void connectToTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerWith(registry);
    }

    /** Leert die Dead-Letter-Queue, damit die Zaehlung in jedem Test bei null beginnt. */
    @BeforeEach
    void emptyDeadLetterQueue() throws Exception {
        TestInfrastructure.purgeQueue("chat.dlq");
    }

    /** Zaehlt die Nachrichten eines Raums in der Datenbank. */
    private String countInRoom(UUID roomId) {
        return "SELECT count(*) FROM message WHERE room_id = '" + roomId + "'";
    }

    /**
     * Drei Nachrichten, wie der chat-service sie ueber den Exchange schickt: alle drei muessen in
     * der Datenbank stehen, und die Queue muss danach leer sein. Leer heisst auch "alles bestaetigt".
     */
    @Test
    void writesMessagesFromTheQueueAndAcknowledgesThem() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        List<String> bodies = new ArrayList<>();
        bodies.add(TestMessages.json(roomId, "eins"));
        bodies.add(TestMessages.json(roomId, "zwei"));
        bodies.add(TestMessages.json(roomId, "drei"));

        TestInfrastructure.publishJson("chat.messages", "", bodies);

        long written = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), 3, 30);
        assertThat(written).isEqualTo(3);
        assertThat(TestInfrastructure.waitUntilQueueIsEmpty("chat.persist", 30)).isZero();
    }

    /**
     * Szenario S5: dieselbe Nachricht zweimal direkt in chat.persist, nur mit dem Header
     * content_type (kein __TypeId__). Erwartet: genau eine Zeile und nichts in chat.dlq.
     */
    @Test
    void sameMessageTwiceIsStoredOnceAndNothingLandsInTheDeadLetterQueue() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        String message = TestMessages.json(UUID.randomUUID(), roomId, "tester", "zweimal gesendet");

        TestInfrastructure.publishJson("", "chat.persist", List.of(message, message));

        // Erst warten, bis beide Kopien verarbeitet und bestaetigt sind, dann zaehlen.
        assertThat(TestInfrastructure.waitUntilQueueIsEmpty("chat.persist", 30)).isZero();
        long rows = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), 1, 10);
        assertThat(rows).isEqualTo(1);
        assertThat(TestInfrastructure.countMessages("chat.dlq")).isZero();
    }
}
