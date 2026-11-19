package ch.benedict.m321.chat.live;

import java.util.ArrayList;
import java.util.List;

import ch.benedict.m321.chat.support.IntegrationTest;
import ch.benedict.m321.chat.support.TestInfrastructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft Schritt 2 aus docs/plan-live-zustellung.md: jede Instanz legt beim Start ihre eigene
 * Live-Queue an (Spec 3.1 und 3.2), und ein Listener liest sie. Die Tests schauen immer nur auf die
 * Queue DIESER Instanz. Andere Test-Kontexte im Zwischenspeicher haben ihre eigenen Queues am
 * gleichen Broker, deshalb wird nie "genau eine Live-Queue" geprueft.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LiveQueueTest extends IntegrationTest {

    @Autowired
    private Queue liveQueue;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Fragt die Management-API so lange, bis die Queue einen Leser hat, hoechstens 15 Sekunden.
     * Der Listener startet kurz nach der Anwendung, und die API rechnet ihre Zahlen nicht sofort neu.
     */
    private JsonNode waitForQueueWithConsumer(String queueName) throws Exception {
        JsonNode queue = TestInfrastructure.askBroker("queues/%2F/" + queueName);
        for (int attempt = 0; attempt < 30 && queue.path("consumers").asInt() < 1; attempt++) {
            Thread.sleep(500);
            queue = TestInfrastructure.askBroker("queues/%2F/" + queueName);
        }
        return queue;
    }

    /**
     * Die Queue dieser Instanz heisst chat.live. plus eine UUID, ist exklusiv und nicht dauerhaft,
     * haengt an chat.messages und hat genau einen Leser.
     */
    @Test
    void instanceHasItsOwnExclusiveLiveQueueBoundToTheExchange() throws Exception {
        String queueName = liveQueue.getName();
        assertThat(queueName).matches("chat\\.live\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        JsonNode queue = waitForQueueWithConsumer(queueName);
        assertThat(queue.get("exclusive").asBoolean()).isTrue();
        assertThat(queue.get("durable").asBoolean()).isFalse();
        assertThat(queue.get("consumers").asInt()).isEqualTo(1);

        JsonNode bindings = TestInfrastructure.askBroker("queues/%2F/" + queueName + "/bindings");
        List<String> sources = new ArrayList<>();
        for (JsonNode binding : bindings) {
            sources.add(binding.get("source").asText());
        }
        assertThat(sources).contains("chat.messages");
    }

    /**
     * Eine Nachricht ueber POST /api/messages kommt ueber den Exchange in der Live-Queue an, und der
     * Listener schreibt ihre id ins Log. Damit ist der Weg bis in die Instanz bewiesen.
     */
    @Test
    void messageSentOverPostAppearsWithItsIdInTheLog(CapturedOutput output) throws Exception {
        waitForQueueWithConsumer(liveQueue.getName());
        String body = "{\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"sender\":\"schritt2\",\"text\":\"Hallo Live\"}";

        MvcResult result = mockMvc.perform(post("/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        String messageId = json.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // Der Listener laeuft in einem eigenen Thread, die Zeile erscheint also etwas spaeter. Gewartet
        // wird auf den ganzen Text: die id allein steht schon vorher im Log, wenn der MessageService publiziert.
        String expectedLine = "Live-Nachricht " + messageId;
        for (int attempt = 0; attempt < 50 && !output.getAll().contains(expectedLine); attempt++) {
            Thread.sleep(100);
        }
        assertThat(output.getAll()).contains(expectedLine);
    }
}
