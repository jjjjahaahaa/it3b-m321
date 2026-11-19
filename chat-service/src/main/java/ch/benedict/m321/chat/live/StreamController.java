package ch.benedict.m321.chat.live;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Der Endpunkt GET /stream?roomId=... (docs/spec-live-zustellung.md, 1.3). Ein Client verbindet sich
 * einmal, die Antwort bleibt offen, und jede neue Nachricht kommt als eigenes Ereignis in dieselbe
 * Antwort. Server-Sent Events (SSE) heisst genau das: der Server schreibt, der Client liest nur.
 *
 * Der Endpunkt braucht keine Datenbank (Spec 2.5): er prueft auch nicht, ob es den Raum gibt.
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

    /** Spring reicht die Liste der offenen Streams herein (Konstruktor-Injektion). */
    public StreamController(StreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    /**
     * Oeffnet einen Stream fuer einen Raum. Fehlt roomId oder ist sie keine UUID, antwortet Spring
     * selbst mit 400, weil der Parameter Pflicht ist. Der Raum wird in Schritt 4 zum Filtern gebraucht.
     * produces sorgt fuer den Content-Type text/event-stream.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openStream(@RequestParam UUID roomId) {
        SseEmitter stream = new SseEmitter(NO_TIMEOUT);
        streamRegistry.add(stream);
        log.info("Stream fuer Raum {} geoeffnet, jetzt {} offen", roomId, streamRegistry.openStreams());
        return stream;
    }
}
