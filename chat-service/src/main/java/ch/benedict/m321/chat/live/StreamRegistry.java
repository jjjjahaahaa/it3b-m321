package ch.benedict.m321.chat.live;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Die Liste aller offenen Streams dieser Instanz, jeder mit seinem Raum. Der Web-Thread traegt einen
 * Stream ein, wenn sich ein Client verbindet. Der Listener-Thread verteilt jede Live-Nachricht an die
 * Streams, deren Raum zur roomId der Nachricht passt.
 *
 * Weil zwei verschiedene Threads die Liste gleichzeitig benutzen, laeuft jeder Zugriff auf sie unter
 * derselben Sperre (synchronized). Ohne die Sperre koennte der Listener die Liste lesen, waehrend ein
 * Web-Thread gerade etwas hinzufuegt, und das gibt in Java einen Fehler oder einen verlorenen Eintrag.
 */
@Component
public class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

    private final List<LiveStream> streams = new ArrayList<>();

    /**
     * Traegt einen neuen Stream fuer einen Raum ein. Geht der Stream zu (Client schliesst, Fehler,
     * Zeitlimit), traegt er sich ueber die drei Rueckrufe selbst wieder aus, damit die Liste nicht mit
     * toten Streams waechst.
     */
    public void add(SseEmitter emitter, UUID roomId) {
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(problem -> remove(emitter));
        LiveStream stream = new LiveStream(emitter, roomId);
        synchronized (streams) {
            streams.add(stream);
        }
    }

    /** Traegt den Stream mit diesem Emitter aus. Ist er schon weg, passiert nichts. */
    public void remove(SseEmitter emitter) {
        synchronized (streams) {
            for (int index = 0; index < streams.size(); index++) {
                if (streams.get(index).emitter() == emitter) {
                    streams.remove(index);
                    return;
                }
            }
        }
    }

    /**
     * Beendet alle offenen Streams (complete) und leert die Liste. Gebraucht nach einer Neuverbindung zu
     * RabbitMQ: waehrend der Unterbrechung haben die Clients vielleicht etwas verpasst. Beendet sich ihr
     * Stream, verbinden sie sich neu und holen es mit Last-Event-ID nach (Spec 2.3).
     * Die Liste wird unter der Sperre kopiert und geleert, beendet wird danach ohne Sperre.
     */
    public void completeAll() {
        List<LiveStream> toComplete;
        synchronized (streams) {
            toComplete = new ArrayList<>(streams);
            streams.clear();
        }
        for (LiveStream stream : toComplete) {
            stream.emitter().complete();
        }
        log.info("{} offene Streams beendet", toComplete.size());
    }

    /** Wie viele Streams gerade offen sind. Gebraucht von den Tests. */
    public int openStreams() {
        synchronized (streams) {
            return streams.size();
        }
    }

    /**
     * Schickt eine Nachricht an alle offenen Streams ihres Raums: die id bei id:, das JSON unveraendert
     * bei data:. Streams anderer Raeume bekommen sie nicht (Spec 2.1). Gesendet wird an eine Kopie der
     * passenden Streams. So haelt der Listener die Sperre nur kurz und blockiert keinen Web-Thread,
     * waehrend er an viele Streams schreibt.
     */
    public void sendToRoom(UUID roomId, String messageId, String messageJson) {
        List<SseEmitter> receivers = new ArrayList<>();
        synchronized (streams) {
            for (LiveStream stream : streams) {
                if (stream.roomId().equals(roomId)) {
                    receivers.add(stream.emitter());
                }
            }
        }
        for (SseEmitter receiver : receivers) {
            send(receiver, messageId, messageJson);
        }
    }

    /**
     * Schreibt ein einzelnes Ereignis in einen Stream. Klappt das nicht, ist der Client weg. Dann wird
     * der Stream ausgetragen, statt dass der Fehler die anderen Streams aufhaelt. Oeffentlich, weil auch
     * das Nachholen (StreamController) einzelne Ereignisse schickt.
     */
    public void send(SseEmitter stream, String messageId, String messageJson) {
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
