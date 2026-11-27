package ch.benedict.m321.chat.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Liest die Live-Queue dieser Instanz und gibt jede Nachricht an alle offenen Streams weiter.
 *
 * Er nimmt die Nachricht absichtlich als rohe Bytes (Message von Spring AMQP) und nicht als
 * umgewandeltes Objekt. So steht bei data: Zeichen fuer Zeichen das JSON, das am Exchange angekommen
 * ist, also dasselbe wie in chat.persist (Spec 1.3). Aus dem JSON gelesen werden nur die id (fuer id:)
 * und die roomId (fuer den Raumfilter). Die Queue selbst bekommt die Nachrichten aller Raeume.
 */
@Component
public class LiveMessageListener {

    private static final Logger log = LoggerFactory.getLogger(LiveMessageListener.class);

    private final StreamRegistry streamRegistry;
    private final ObjectMapper objectMapper;

    /** Spring reicht die Liste der offenen Streams und den JSON-Leser herein (Konstruktor-Injektion). */
    public LiveMessageListener(StreamRegistry streamRegistry, ObjectMapper objectMapper) {
        this.streamRegistry = streamRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Wird fuer jede Nachricht in der Live-Queue aufgerufen und verteilt sie an die Streams ihres Raums.
     *
     * "#{liveQueue.name}" fragt Spring nach dem Namen der Bean liveQueue, denn der Name steht erst beim
     * Start fest und kann nicht als fester Text dastehen. Die Fabrik liveListenerContainerFactory liest
     * mit genau einem Thread und wandelt nichts um (LiveQueueConfiguration). Ueber die id "liveListener"
     * findet ein Test den Listener, um ihn kurz anzuhalten.
     */
    @RabbitListener(id = "liveListener", queues = "#{liveQueue.name}", containerFactory = "liveListenerContainerFactory")
    public void onMessage(Message message) {
        String messageJson = new String(message.getBody(), StandardCharsets.UTF_8);
        JsonNode root = readJson(messageJson);
        if (root == null) {
            return;
        }
        String messageId = readText(root, "id");
        String roomText = readText(root, "roomId");
        if (messageId == null || roomText == null) {
            log.warn("Live-Nachricht ohne id oder roomId wird verworfen: {}", messageJson);
            return;
        }
        log.info("Live-Nachricht {} empfangen", messageId);
        UUID roomId = UUID.fromString(roomText);
        streamRegistry.sendToRoom(roomId, messageId, messageJson);
    }

    /**
     * Liest den Text als JSON. Ist es kein JSON, gibt sie null zurueck und schreibt eine Warnung. Eine
     * kaputte Nachricht soll den Listener nicht anhalten.
     */
    private JsonNode readJson(String messageJson) {
        try {
            return objectMapper.readTree(messageJson);
        } catch (IOException brokenJson) {
            log.warn("Live-Nachricht ist kein JSON und wird verworfen: {}", messageJson);
            return null;
        }
    }

    /** Holt ein Feld als Text aus dem JSON, oder null, wenn es fehlt. */
    private String readText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText();
    }
}
