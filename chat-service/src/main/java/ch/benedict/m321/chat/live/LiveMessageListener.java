package ch.benedict.m321.chat.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
 * ist, also dasselbe wie in chat.persist (Spec 1.3). Aus dem JSON gelesen wird nur die id.
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
     * Wird fuer jede Nachricht in der Live-Queue aufgerufen und verteilt sie an die Streams.
     *
     * "#{liveQueue.name}" fragt Spring nach dem Namen der Bean liveQueue, denn der Name steht erst beim
     * Start fest und kann nicht als fester Text dastehen. Die Fabrik liveListenerContainerFactory liest
     * mit genau einem Thread und wandelt nichts um (LiveQueueConfiguration).
     */
    @RabbitListener(queues = "#{liveQueue.name}", containerFactory = "liveListenerContainerFactory")
    public void onMessage(Message message) {
        String messageJson = new String(message.getBody(), StandardCharsets.UTF_8);
        String messageId = readId(messageJson);
        if (messageId == null) {
            return;
        }
        log.info("Live-Nachricht {} empfangen", messageId);
        streamRegistry.sendToAll(messageId, messageJson);
    }

    /**
     * Liest die id aus dem JSON. Ist das JSON kaputt oder fehlt die id, gibt sie null zurueck und
     * schreibt eine Warnung. Eine kaputte Nachricht soll nicht den Listener anhalten.
     */
    private String readId(String messageJson) {
        try {
            JsonNode root = objectMapper.readTree(messageJson);
            JsonNode id = root.get("id");
            if (id == null || id.isNull()) {
                log.warn("Live-Nachricht ohne id wird verworfen: {}", messageJson);
                return null;
            }
            return id.asText();
        } catch (IOException brokenJson) {
            log.warn("Live-Nachricht ist kein JSON und wird verworfen: {}", messageJson);
            return null;
        }
    }
}
