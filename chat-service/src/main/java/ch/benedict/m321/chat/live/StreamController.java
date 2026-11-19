package ch.benedict.m321.chat.live;

import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.message.MessageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Der Endpunkt GET /stream?roomId=... (docs/spec-live-zustellung.md, 1.3). Ein Client verbindet sich
 * einmal, die Antwort bleibt offen, und jede neue Nachricht kommt als eigenes Ereignis in dieselbe
 * Antwort. Server-Sent Events (SSE) heisst genau das: der Server schreibt, der Client liest nur.
 *
 * Schickt der Client beim Neuverbinden die Kopfzeile Last-Event-ID mit, holt der Endpunkt zuerst nach,
 * was er verpasst hat (Spec 2.4). Ohne diese Kopfzeile braucht er keine Datenbank (Spec 2.5).
 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    /**
     * 0 heisst: kein Zeitlimit. Ohne diese Angabe beendet der Webserver eine offene Antwort nach seiner
     * eigenen Vorgabe (bei Tomcat 30 Sekunden), und der Client muesste sich staendig neu verbinden.
     */
    private static final long NO_TIMEOUT = 0L;

    private final StreamRegistry streamRegistry;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    /** Spring reicht die Liste der Streams, den MessageService und den JSON-Schreiber herein (Konstruktor-Injektion). */
    public StreamController(StreamRegistry streamRegistry, MessageService messageService, ObjectMapper objectMapper) {
        this.streamRegistry = streamRegistry;
        this.messageService = messageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Oeffnet einen Stream fuer einen Raum. Fehlt roomId oder ist sie keine UUID, antwortet Spring
     * selbst mit 400, weil der Parameter Pflicht ist. produces sorgt fuer den Content-Type
     * text/event-stream. Last-Event-ID ist freiwillig: der Browser schickt sie nur beim Neuverbinden.
     *
     * Zuerst wird nachgeholt, danach wird der Stream fuer Live-Nachrichten angemeldet. Kommt genau
     * dazwischen eine Live-Nachricht, ginge sie verloren. Das behebt erst Schritt 8 (Warteliste).
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openStream(@RequestParam UUID roomId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter stream = new SseEmitter(NO_TIMEOUT);
        if (lastEventId != null) {
            sendMissedMessages(stream, roomId, lastEventId);
        }
        streamRegistry.add(stream, roomId);
        log.info("Stream fuer Raum {} geoeffnet, jetzt {} offen", roomId, streamRegistry.openStreams());
        return stream;
    }

    /**
     * Schickt die Nachrichten, die der Client nach lastEventId verpasst hat, die aelteste zuerst. Ist
     * lastEventId keine UUID, gilt sie als unbekannt, und es wird nichts nachgeholt.
     */
    private void sendMissedMessages(SseEmitter stream, UUID roomId, String lastEventId) {
        UUID lastMessageId;
        try {
            lastMessageId = UUID.fromString(lastEventId.trim());
        } catch (IllegalArgumentException notAUuid) {
            log.warn("Last-Event-ID {} ist keine UUID, es wird nichts nachgeholt", lastEventId);
            return;
        }

        List<Message> missed = messageService.loadMissed(roomId, lastMessageId);
        for (Message message : missed) {
            String messageJson = toJson(message);
            streamRegistry.send(stream, message.id().toString(), messageJson);
        }
        log.info("{} Nachrichten fuer Raum {} nachgeholt", missed.size(), roomId);
    }

    /**
     * Wandelt eine Nachricht aus der Datenbank in dasselbe JSON um, das der chat-service publiziert:
     * derselbe ObjectMapper, dieselben Felder in derselben Reihenfolge. Einziger Unterschied: die Datenbank
     * speichert sentAt nur auf Mikrosekunden, nachgeholte Nachrichten haben also weniger Nachkommastellen.
     */
    private String toJson(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException("Nachricht " + message.id() + " laesst sich nicht als JSON schreiben", problem);
        }
    }
}
