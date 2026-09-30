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
 * Szenario S7 (docs/spec-batch-writer.md, F2): die Datenbank faellt aus, waehrend Nachrichten
 * ankommen. Erwartet: nichts geht verloren, nichts landet in chat.dlq, und der Dienst laeuft nach
 * der Rueckkehr der Datenbank von selbst weiter, ohne Neustart.
 */
class DatabaseOutageTest extends IntegrationTest {

    /**
     * 300 Nachrichten treffen ein, waehrend die Datenbank 10 Sekunden lang nicht zu erreichen ist.
     * In dieser Zeit darf nichts geschrieben und nichts abgelehnt werden. Danach muessen alle 300
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

            // Mehrere Wiederholungsrunden lang (Pause 2 s) darf die Datenbank weg sein.
            Thread.sleep(10_000);
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
