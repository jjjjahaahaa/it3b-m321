package ch.benedict.m321.batchwriter.rabbit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import ch.benedict.m321.batchwriter.support.TestMessages;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beweist, dass das Buendeln so arbeitet, wie docs/spec-batch-writer.md, Abschnitt 3.1 es verlangt:
 * viele Nachrichten mit wenigen Transaktionen (Szenario S4) und ein Zeitlimit, das fuer das GANZE
 * Paket gilt und nicht nur fuer die Pause zwischen zwei Nachrichten.
 */
@SpringBootTest
class BatchingTest {

    /** Sagt der gestarteten Anwendung, wo Datenbank und Broker der Testcontainer erreichbar sind. */
    @DynamicPropertySource
    static void connectToTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerWith(registry);
    }

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /** Holt den Container, der den Listener betreibt, damit der Test ihn anhalten und starten kann. */
    private MessageListenerContainer persistContainer() {
        return listenerRegistry.getListenerContainer("persistListener");
    }

    /** Zaehlt die Nachrichten eines Raums in der Datenbank. */
    private String countInRoom(UUID roomId) {
        return "SELECT count(*) FROM message WHERE room_id = '" + roomId + "'";
    }

    /**
     * Szenario S4: der Schreiber steht, 1000 Nachrichten stauen sich in der Queue, dann laeuft er an.
     * Alle 1000 muessen ankommen, und zwar mit hoechstens 100 Transaktionen statt mit 1000.
     * Die Transaktionen werden ueber die fortlaufende Transaktionsnummer der Datenbank gezaehlt.
     */
    @Test
    void thousandQueuedMessagesNeedAtMostHundredTransactions() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        List<String> bodies = new ArrayList<>();
        for (int number = 0; number < 1000; number++) {
            bodies.add(TestMessages.json(roomId, "Stau " + number));
        }

        persistContainer().stop();
        long numberBefore;
        long numberAfter;
        try {
            TestInfrastructure.publishJson("chat.messages", "", bodies);
            numberBefore = TestInfrastructure.queryNumber("SELECT txid_current()");
        } finally {
            persistContainer().start();
        }

        long written = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), 1000, 60);
        numberAfter = TestInfrastructure.queryNumber("SELECT txid_current()");

        long transactionsUsed = numberAfter - numberBefore - 1;
        System.out.println("MESSWERT S4: 1000 Nachrichten mit " + transactionsUsed + " Transaktionen geschrieben");
        assertThat(written).isEqualTo(1000);
        assertThat(transactionsUsed).isLessThanOrEqualTo(100);
    }

    /**
     * Eine Nachricht alle 50 Millisekunden, vier Sekunden lang. Weil zwischen zwei Nachrichten nie
     * 200 Millisekunden vergehen, wuerde ein Zeitlimit nur zwischen den Nachrichten NIE ausloesen und
     * das Paket erst bei 500 Stueck (oder nach Ende des Stroms) geschrieben. Gilt das Zeitlimit fuer das
     * ganze Paket, stehen nach zwei Sekunden alle Nachrichten der ersten Sekunde schon in der Datenbank.
     */
    @Test
    void timeLimitAppliesToTheWholeBatchNotOnlyToTheGapBetweenMessages() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        long start = System.currentTimeMillis();
        long visibleAfterTwoSeconds = -1;
        int published = 0;

        while (System.currentTimeMillis() - start < 4000) {
            TestInfrastructure.publishJson("chat.messages", "", List.of(TestMessages.json(roomId, "Tropfen " + published)));
            published++;
            Thread.sleep(50);

            boolean twoSecondsPassed = System.currentTimeMillis() - start >= 2000;
            if (twoSecondsPassed && visibleAfterTwoSeconds < 0) {
                visibleAfterTwoSeconds = TestInfrastructure.queryNumber(countInRoom(roomId));
            }
        }

        // In der ersten Sekunde wurden hoechstens etwa 20 bis 40 Nachrichten gesendet. Mindestens 15
        // davon muessen nach zwei Sekunden bereits geschrieben sein (Luft fuer langsame Rechner).
        System.out.println("MESSWERT Zeitlimit: nach 2 s bereits " + visibleAfterTwoSeconds + " von " + published + " sichtbar");
        assertThat(visibleAfterTwoSeconds).isGreaterThanOrEqualTo(15);

        long written = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), published, 30);
        assertThat(written).isEqualTo(published);
    }
}
