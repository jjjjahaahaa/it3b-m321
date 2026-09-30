package ch.benedict.m321.batchwriter.rabbit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.support.IntegrationTest;
import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import ch.benedict.m321.batchwriter.support.TestMessages;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Fehlerfaelle F3 und F4 (docs/spec-batch-writer.md, Abschnitt 3.2): eine kaputte
 * Nachricht darf nie die guten Nachrichten im selben Paket mitreissen. Sie landet in chat.dlq,
 * alle anderen stehen in der Datenbank.
 */
class PoisonMessageTest extends IntegrationTest {

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
