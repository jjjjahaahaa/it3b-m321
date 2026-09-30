package ch.benedict.m321.batchwriter.rabbit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import ch.benedict.m321.batchwriter.support.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Fehlerfaelle F3 und F4 (docs/spec-batch-writer.md, Abschnitt 3.2): eine kaputte
 * Nachricht darf nie die guten Nachrichten im selben Paket mitreissen. Sie landet in chat.dlq,
 * alle anderen stehen in der Datenbank.
 */
@SpringBootTest
class PoisonMessageTest {

    /** Sagt der gestarteten Anwendung, wo Datenbank und Broker der Testcontainer erreichbar sind. */
    @DynamicPropertySource
    static void connectToTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerWith(registry);
    }

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

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
     * Legt alle Nachrichten in die Queue, waehrend der Listener angehalten ist, und startet ihn dann.
     * So liegen sie sicher zusammen in EINEM Paket. Ohne das Anhalten koennte der Listener die ersten
     * Nachrichten schon abholen, bevor die letzten da sind, und der Test prueft dann nicht mehr,
     * was er pruefen soll.
     */
    private void publishAsOnePacket(List<String> bodies) throws Exception {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("persistListener");
        container.stop();
        try {
            TestInfrastructure.publishJson("chat.messages", "", bodies);
        } finally {
            container.start();
        }
    }

    /**
     * F3: zehn Nachrichten, die fuenfte ist kein JSON. Die neun guten muessen gespeichert werden,
     * die kaputte liegt unveraendert in chat.dlq, und chat.persist ist danach leer.
     */
    @Test
    void messageThatIsNotJsonGoesToDeadLetterQueueAndTheOthersAreStored() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        List<String> bodies = new ArrayList<>();
        for (int number = 0; number < 10; number++) {
            if (number == 4) {
                bodies.add("das ist kein json");
            } else {
                bodies.add(TestMessages.json(roomId, "Nachricht " + number));
            }
        }

        publishAsOnePacket(bodies);

        long stored = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), 9, 30);
        assertThat(stored).isEqualTo(9);
        assertThat(TestInfrastructure.waitUntilMessageCountIs("chat.dlq", 1, 15)).isEqualTo(1);
        assertThat(TestInfrastructure.takeOneBody("chat.dlq")).isEqualTo("das ist kein json");
        assertThat(TestInfrastructure.waitUntilQueueIsEmpty("chat.persist", 30)).isZero();
    }

    /**
     * F4: zehn gute Nachrichten, dazu eine fuer einen unbekannten Raum und eine mit zu langem Absender.
     * Die Datenbank lehnt beide ab und rollt das ganze Paket zurueck. Der Einzelweg muss danach die
     * zehn guten trotzdem speichern und nur die beiden kaputten in chat.dlq legen.
     */
    @Test
    void rowsRejectedByTheDatabaseGoToDeadLetterQueueAndTheOthersAreStored() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        UUID unknownRoom = UUID.randomUUID();
        String tooLongSender = "x".repeat(101);

        List<String> bodies = new ArrayList<>();
        for (int number = 0; number < 5; number++) {
            bodies.add(TestMessages.json(roomId, "vorher " + number));
        }
        bodies.add(TestMessages.json(UUID.randomUUID(), unknownRoom, "tester", "Raum gibt es nicht"));
        bodies.add(TestMessages.json(UUID.randomUUID(), roomId, tooLongSender, "Absender zu lang"));
        for (int number = 0; number < 5; number++) {
            bodies.add(TestMessages.json(roomId, "nachher " + number));
        }

        publishAsOnePacket(bodies);

        long stored = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), 10, 30);
        assertThat(stored).isEqualTo(10);
        assertThat(TestInfrastructure.waitUntilMessageCountIs("chat.dlq", 2, 15)).isEqualTo(2);
        assertThat(TestInfrastructure.waitUntilQueueIsEmpty("chat.persist", 30)).isZero();
    }
}
