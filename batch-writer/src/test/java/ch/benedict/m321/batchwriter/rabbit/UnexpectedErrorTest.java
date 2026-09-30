package ch.benedict.m321.batchwriter.rabbit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.message.IncomingMessage;
import ch.benedict.m321.batchwriter.message.MessageRepository;
import ch.benedict.m321.batchwriter.support.IntegrationTest;
import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import ch.benedict.m321.batchwriter.support.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * Prueft F8 (docs/spec-batch-writer.md): ein Programmfehler, den niemand vorhergesehen hat, darf
 * weder das Paket in einer Endlosschleife zurueck in die Queue werfen noch den Konsumenten still
 * legen. Er wird auf die eine Nachricht eingegrenzt, die ihn ausloest.
 */
class UnexpectedErrorTest extends IntegrationTest {

    private static final String TRIGGER_TEXT = "Programmfehler ausloesen";

    /** Das echte Repository, in das der Test einen Fehler einbauen kann. */
    @MockitoSpyBean
    private MessageRepository repository;

    /**
     * Baut den Fehler ein: enthaelt ein Paket eine Nachricht mit dem Ausloese-Text, wirft das
     * Repository eine IllegalStateException, wie sie bei einem echten Programmfehler kaeme.
     * Alle anderen Pakete schreibt es normal.
     */
    @BeforeEach
    void makeRepositoryFailForTheTriggerText() {
        doAnswer(invocation -> {
            List<IncomingMessage> messages = invocation.getArgument(0);
            for (IncomingMessage message : messages) {
                if (TRIGGER_TEXT.equals(message.text())) {
                    throw new IllegalStateException("absichtlicher Testfehler");
                }
            }
            return invocation.callRealMethod();
        }).when(repository).insertBatch(anyList());
    }

    /**
     * Fuenf gute Nachrichten, eine, die den Fehler ausloest, fuenf gute. Erwartet: die zehn guten sind
     * gespeichert, die eine liegt in chat.dlq, chat.persist ist leer und der Listener laeuft weiter.
     */
    @Test
    void oneMessageThatCausesABugDoesNotTakeItsNeighborsDown() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        List<String> bodies = new ArrayList<>();
        for (int number = 0; number < 5; number++) {
            bodies.add(TestMessages.json(roomId, "vorher " + number));
        }
        bodies.add(TestMessages.json(roomId, TRIGGER_TEXT));
        for (int number = 0; number < 5; number++) {
            bodies.add(TestMessages.json(roomId, "nachher " + number));
        }

        publishAsOnePacket(bodies);

        long stored = TestInfrastructure.waitUntilNumberIs(countInRoom(roomId), 10, 30);
        assertThat(stored).isEqualTo(10);
        assertThat(TestInfrastructure.waitUntilMessageCountIs("chat.dlq", 1, 15)).isEqualTo(1);
        assertThat(TestInfrastructure.waitUntilQueueIsEmpty("chat.persist", 30)).isZero();
        assertThat(persistContainer().isRunning()).isTrue();
    }
}
