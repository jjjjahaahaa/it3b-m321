package ch.benedict.m321.chat.live;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Die Liste aller offenen Streams dieser Instanz. Der Web-Thread traegt einen Stream ein, wenn sich
 * ein Client verbindet. Der Listener-Thread verteilt jede Live-Nachricht an alle eingetragenen Streams.
 *
 * Weil zwei verschiedene Threads die Liste gleichzeitig benutzen, laeuft jeder Zugriff auf sie unter
 * derselben Sperre (synchronized). Ohne die Sperre koennte der Listener die Liste lesen, waehrend ein
 * Web-Thread gerade etwas hinzufuegt, und das gibt in Java einen Fehler oder einen verlorenen Eintrag.
 */
@Component
public class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

    private final List<SseEmitter> streams = new ArrayList<>();

    /**
     * Traegt einen neuen Stream ein. Geht der Stream zu (Client schliesst, Fehler, Zeitlimit), traegt
     * er sich ueber die drei Rueckrufe selbst wieder aus, damit die Liste nicht mit toten Streams waechst.
     */
    public void add(SseEmitter stream) {
        stream.onCompletion(() -> remove(stream));
        stream.onTimeout(() -> remove(stream));
        stream.onError(problem -> remove(stream));
        synchronized (streams) {
            streams.add(stream);
        }
    }

    /** Traegt einen Stream aus. Ist er schon weg, passiert nichts. */
    public void remove(SseEmitter stream) {
        synchronized (streams) {
            streams.remove(stream);
        }
    }

    /** Wie viele Streams gerade offen sind. Gebraucht von den Tests. */
    public int openStreams() {
        synchronized (streams) {
            return streams.size();
        }
    }

    /**
     * Schickt eine Nachricht an alle offenen Streams: die id bei id:, das JSON unveraendert bei data:.
     * Gesendet wird an eine Kopie der Liste. So haelt der Listener die Sperre nur kurz und blockiert
     * keinen Web-Thread, waehrend er an viele Streams schreibt.
     */
    public void sendToAll(String messageId, String messageJson) {
        List<SseEmitter> receivers;
        synchronized (streams) {
            receivers = new ArrayList<>(streams);
        }
        for (SseEmitter stream : receivers) {
            send(stream, messageId, messageJson);
        }
    }

    /**
     * Schreibt ein einzelnes Ereignis in einen Stream. Klappt das nicht, ist der Client weg. Dann wird
     * der Stream ausgetragen, statt dass der Fehler die anderen Streams aufhaelt.
     */
    private void send(SseEmitter stream, String messageId, String messageJson) {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(messageId)
                .data(messageJson, MediaType.TEXT_PLAIN);
        try {
            stream.send(event);
        } catch (IOException | IllegalStateException clientGone) {
            log.debug("Stream ist nicht mehr erreichbar und wird ausgetragen: {}", clientGone.getMessage());
            remove(stream);
        }
    }
}
