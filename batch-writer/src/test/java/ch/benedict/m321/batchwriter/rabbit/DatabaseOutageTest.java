package ch.benedict.m321.batchwriter.rabbit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.message.MessageRepository;
import ch.benedict.m321.batchwriter.support.IntegrationTest;
import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import ch.benedict.m321.batchwriter.support.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

/**
 * Szenario S7 (docs/spec-batch-writer.md, F2): die Datenbank faellt aus, waehrend Nachrichten
 * ankommen. Erwartet: nichts geht verloren, nichts landet in chat.dlq, und der Dienst laeuft nach
 * der Rueckkehr der Datenbank von selbst weiter, ohne Neustart.
 */
@TestPropertySource(properties = "batch.db-retry-pause-ms=4000")
class DatabaseOutageTest extends IntegrationTest {

    /**
     * Das echte Repository, aber mit Zaehler: der Test kann nachschauen, wie oft der Dienst
     * waehrend des Ausfalls zu schreiben versucht hat.
     */
    @MockitoSpyBean
    private MessageRepository repository;

    /**
     * 300 Nachrichten treffen ein, waehrend die Datenbank 12 Sekunden lang nicht zu erreichen ist.
     * In dieser Zeit darf nichts abgelehnt werden, und der Dienst darf nicht im Kreis rennen:
     * bei 4 Sekunden Wartepause sind es etwa 4 Versuche, hoechstens 6 sind erlaubt. Eine
     * Endlosschleife (Paket sofort wieder einreihen) kaeme auf ueber 10. Danach muessen alle 300
     * ohne weiteres Zutun in der Tabelle stehen.
     */
    @Test
    void messagesSurviveADatabaseOutageWithoutRestartingTheService() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        List<String> bodies = new ArrayList<>();
        for (int number = 0; number < 300; number++) {
            bodies.add(TestMessages.json(roomId, "Ausfall " + number));
        }

        TestInfrastructure.startDatabaseOutage();
        try {
            TestInfrastructure.publishJson("chat.messages", "", bodies);
            Mockito.clearInvocations(repository);

            // Mehrere Wiederholungsrunden lang darf die Datenbank weg sein.
            Thread.sleep(12_000);
            verify(repository, atMost(6)).insertBatch(anyList());
            assertThat(TestInfrastructure.countMessages("chat.dlq")).isZero();
        } finally {
            TestInfrastructure.endDatabaseOutage();
        }

        String countInRoom = "SELECT count(*) FROM message WHERE room_id = '" + roomId + "'";
        long stored = TestInfrastructure.waitUntilNumberIs(countInRoom, 300, 60);

        assertThat(stored).isEqualTo(300);
        assertThat(TestInfrastructure.countMessages("chat.dlq")).isZero();
        assertThat(TestInfrastructure.waitUntilQueueIsEmpty("chat.persist", 30)).isZero();
        assertThat(persistContainer().isRunning()).isTrue();
    }
}
