package ch.benedict.m321.chat.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import ch.benedict.m321.chat.support.ChatServiceInstance;
import ch.benedict.m321.chat.support.StreamClient;
import ch.benedict.m321.chat.support.TestInfrastructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft Schritt 6 aus docs/plan-live-zustellung.md (Spec 2.3, Szenario S6): reisst die Verbindung zu
 * RabbitMQ ab und wird neu aufgebaut, beendet die Instanz alle offenen Streams. Die Clients verbinden
 * sich dann neu. Queue und Bindung legt Spring selbst wieder an.
 *
 * Der Test startet dafuer eine eigene Instanz. Trennte er die Verbindung eines gemeinsamen
 * Test-Kontexts, wuerde er andere Tests stoeren.
 */
class ReconnectTest {

    private static final String ROOM_ID = "11111111-1111-1111-1111-111111111111";

    private ConfigurableApplicationContext instance;

    private final ObjectMapper json = new ObjectMapper();

    /** Startet die eigene Instanz fuer diesen Test. */
    @BeforeEach
    void startInstance() {
        instance = ChatServiceInstance.start();
    }

    /** Der Test hat die Instanz selbst gestartet, also beendet er sie auch wieder. */
    @AfterEach
    void stopInstance() {
        instance.close();
    }

    /** Die Live-Queue dieser Instanz, wie die Management-API sie gerade sieht. */
    private JsonNode liveQueueAtBroker() throws Exception {
        String queueName = instance.getBean("liveQueue", Queue.class).getName();
        return TestInfrastructure.askBroker("queues/%2F/" + queueName);
    }

    /**
     * Wartet hoechstens die angegebene Zeit, bis die Live-Queue beim Broker existiert und genau einen Leser
     * hat. Gibt zurueck, ob es so weit gekommen ist. Solange sie fehlt, antwortet die API mit 404.
     */
    private boolean waitForLiveQueueWithOneConsumer(int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (liveQueueAtBroker().path("consumers").asInt() == 1) {
                    return true;
                }
            } catch (java.io.IOException notThereYet) {
                // Die Queue ist gerade nicht da, zum Beispiel direkt nach dem Trennen.
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** Wartet, bis die Instanz die erwartete Zahl offener Streams kennt, hoechstens 10 Sekunden. */
    private void waitForOpenStreams(int expected) throws InterruptedException {
        StreamRegistry registry = instance.getBean(StreamRegistry.class);
        for (int attempt = 0; attempt < 200 && registry.openStreams() < expected; attempt++) {
            Thread.sleep(50);
        }
    }

    /** Sendet eine Nachricht ueber POST /api/messages an die Instanz und gibt den Antwort-Text zurueck. */
    private String send(String text) throws Exception {
        String body = "{\"roomId\":\"" + ROOM_ID + "\",\"sender\":\"schritt6\",\"text\":\"" + text + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ChatServiceInstance.baseAddress(instance) + "/api/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return response.body();
    }

    /**
     * Ein Stream ist offen, dann trennt der Broker die Verbindung der Instanz. Erwartet: der Stream
     * endet, nach der Neuverbindung gibt es die Queue dieser Instanz wieder mit genau einem Leser, und
     * ein neuer Stream bekommt eine neue Nachricht.
     */
    @Test
    void reconnectEndsOpenStreamsAndLiveDeliveryWorksAgain() throws Exception {
        assertThat(waitForLiveQueueWithOneConsumer(15)).isTrue();
        StreamClient oldStream = new StreamClient(ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + ROOM_ID);
        waitForOpenStreams(1);

        // Zuerst eine Nachricht empfangen. Erst damit sind die Antwort-Kopfzeilen da, und der Stream
        // laeuft wirklich. Sonst gaebe der Test-Client nach 20 Sekunden selbst auf, und das saehe aus,
        // als haette der Server den Stream beendet.
        String before = send("vor dem Trennen");
        String beforeId = json.readTree(before).get("id").asText();
        assertThat(oldStream.waitForLine("id:" + beforeId, 10)).isTrue();

        // Der Besitzer einer exklusiven Queue ist die Verbindung, die sie angelegt hat: die der Instanz.
        String connectionName = liveQueueAtBroker().get("owner_pid_details").get("name").asText();
        TestInfrastructure.closeConnection(connectionName);

        assertThat(oldStream.waitUntilServerClosed(30)).isTrue();
        assertThat(waitForLiveQueueWithOneConsumer(30)).isTrue();

        StreamClient newStream = new StreamClient(ChatServiceInstance.baseAddress(instance) + "/stream?roomId=" + ROOM_ID);
        waitForOpenStreams(1);
        String published = send("nach der Neuverbindung");
        String messageId = json.readTree(published).get("id").asText();
        assertThat(newStream.waitForLine("id:" + messageId, 10)).isTrue();

        newStream.close();
    }
}
