package ch.benedict.m321.chat.live;

import java.time.Instant;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.rabbit.RabbitConfiguration;
import ch.benedict.m321.chat.support.IntegrationTest;
import ch.benedict.m321.chat.support.TestInfrastructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft Schritt 5 aus docs/plan-live-zustellung.md: die Grenze der Live-Queue (Spec 3.3) und den
 * Heartbeat von 15 Sekunden (Spec 2.3 und 3.2).
 *
 * Die Live-Queue ist exklusiv: keine andere Verbindung darf sie lesen, auch die Management-API nicht.
 * Ihren Inhalt liest der Test deshalb ueber die eigene Verbindung der Instanz (RabbitTemplate). Nur
 * Argumente und Heartbeat kommen aus der Management-API, die nur Angaben ueber die Queue zeigt.
 */
class LiveQueueLimitTest extends IntegrationTest {

    @Autowired
    private Queue liveQueue;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    private final ObjectMapper json = new ObjectMapper();

    /** Zaehlt die wartenden Nachrichten der Live-Queue ueber die eigene Verbindung der Instanz. */
    private int waitingMessages() {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(liveQueue.getName()).getMessageCount());
    }

    /**
     * Die Queue dieser Instanz hat x-max-length 1000 und x-overflow drop-head. Und jede Verbindung des
     * chat-service zum Broker hat einen Heartbeat von 15 Sekunden statt der ueblichen 60.
     */
    @Test
    void liveQueueHasLimitAndConnectionHasHeartbeatOfFifteenSeconds() throws Exception {
        rabbitTemplate.execute(channel -> null);

        JsonNode arguments = TestInfrastructure.askBroker("queues/%2F/" + liveQueue.getName()).get("arguments");
        assertThat(arguments.get("x-max-length").asInt()).isEqualTo(1000);
        assertThat(arguments.get("x-overflow").asText()).isEqualTo("drop-head");

        JsonNode connections = TestInfrastructure.askBroker("connections");
        assertThat(connections.size()).isGreaterThan(0);
        for (JsonNode connection : connections) {
            assertThat(connection.get("timeout").asInt()).isEqualTo(15);
        }
    }

    /**
     * Der Listener steht, 1100 Nachrichten kommen an. In der Queue warten danach genau 1000, und die
     * vorderste ist die 101. Daran sieht man, dass wirklich die aeltesten 100 rausgeflogen sind und nicht
     * die neuesten abgelehnt wurden. Am Ende laeuft der Listener wieder, damit andere Tests nichts merken.
     */
    @Test
    void fullQueueDropsTheOldestMessages() throws Exception {
        MessageListenerContainer listener = listenerRegistry.getListenerContainer("liveListener");
        listener.stop();
        try {
            UUID roomId = UUID.randomUUID();
            for (int number = 1; number <= 1100; number++) {
                Message message = new Message(UUID.randomUUID(), roomId, "schritt5", String.valueOf(number), Instant.now());
                rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);
            }

            // Das Publizieren ist asynchron. Warten, bis der Broker alle 1100 verarbeitet hat.
            int waiting = waitingMessages();
            for (int attempt = 0; attempt < 100 && waiting != 1000; attempt++) {
                Thread.sleep(100);
                waiting = waitingMessages();
            }
            assertThat(waiting).isEqualTo(1000);

            org.springframework.amqp.core.Message front = rabbitTemplate.receive(liveQueue.getName(), 5000);
            String frontText = json.readTree(front.getBody()).get("text").asText();
            assertThat(frontText).isEqualTo("101");
        } finally {
            rabbitTemplate.execute(channel -> channel.queuePurge(liveQueue.getName()));
            listener.start();
        }
    }
}
