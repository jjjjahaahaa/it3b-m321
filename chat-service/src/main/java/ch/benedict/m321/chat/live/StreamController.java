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

    /**
     * So lange wartet das Nachholen nach dem Anmelden, bevor es die Datenbank liest (Plan, Aenderung 3).
     * Der batch-writer speichert eine Nachricht spaetestens nach 200 ms. Nach einer Sekunde ist also auch
     * gespeichert, was kurz vor dem Anmelden live kam und deshalb nicht in der Warteliste ist.
     */
    private static final long WAIT_FOR_STORAGE_MILLIS = 1000L;

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
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openStream(@RequestParam UUID roomId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        LiveStream stream = new LiveStream(emitter, roomId);
        if (lastEventId == null) {
            // Nichts nachzuholen: der Stream wird angemeldet und bekommt ab jetzt alles direkt.
            streamRegistry.add(stream);
        } else {
            openWithCatchUp(stream, roomId, lastEventId);
        }
        log.info("Stream fuer Raum {} geoeffnet, jetzt {} offen", roomId, streamRegistry.openStreams());
        return emitter;
    }

    /**
     * Meldet einen Stream an und holt nach, was der Client verpasst hat, ohne dass etwas fehlt oder doppelt
     * kommt (Spec 2.4). Die Reihenfolge ist wichtig:
     * 1. Warteliste einschalten und anmelden. Ab jetzt landet jede Live-Nachricht in der Warteliste.
     * 2. Eine Sekunde warten, bis auch gespeichert ist, was kurz vor dem Anmelden live kam.
     * 3. Aus der Datenbank nachholen. Jede Nachricht, die erst danach gespeichert wird, ist sicher schon
     *    in der Warteliste, weil der Stream ja schon angemeldet ist.
     * 4. Die Warteliste schicken, ohne was schon nachgeholt wurde, und sie ausschalten.
     * Andersrum (zuerst lesen, dann anmelden) fiele eine Nachricht, die genau dazwischen kommt, ganz weg.
     */
    private void openWithCatchUp(LiveStream stream, UUID roomId, String lastEventId) {
        stream.startWaitlist();
        streamRegistry.add(stream);
        waitUntilStored();
        boolean reachable;
        try {
            reachable = sendMissedMessages(stream, roomId, lastEventId);
        } catch (RuntimeException problem) {
            // Zum Beispiel ist die Datenbank nicht erreichbar. Der Stream ist schon angemeldet und wuerde
            // sonst fuer immer Nachrichten in seiner Warteliste sammeln. Deshalb zuerst wieder austragen.
            streamRegistry.remove(stream.emitter());
            throw problem;
        }
        if (reachable) {
            reachable = stream.finishCatchUp();
        }
        if (!reachable) {
            streamRegistry.remove(stream.emitter());
        }
    }

    /**
     * Wartet WAIT_FOR_STORAGE_MILLIS. Gemessen in Schritt 8: ohne diese Pause fehlten Nachrichten, die
     * vor dem Anmelden live durchkamen, aber beim Lesen noch nicht in der Tabelle standen. Gewartet wird
     * ohne Sperre, der Listener legt neue Nachrichten in dieser Zeit einfach in die Warteliste.
     */
    private void waitUntilStored() {
        try {
            Thread.sleep(WAIT_FOR_STORAGE_MILLIS);
        } catch (InterruptedException interrupted) {
            // Jemand will diesen Thread beenden (zum Beispiel beim Herunterfahren). Das Signal wird wieder
            // gesetzt, damit es nicht verloren geht, und es wird ohne weitere Pause nachgeholt.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Schickt die Nachrichten, die der Client nach lastEventId verpasst hat, die aelteste zuerst. Ist
     * lastEventId keine UUID, gilt sie als unbekannt, und es wird nichts nachgeholt.
     * Die Datenbankabfrage laeuft ohne Sperre. Gesperrt wird der Stream nur kurz fuer jede einzelne
     * Nachricht (sendCaughtUp). So wartet der Listener-Thread nie auf die Datenbank.
     * Gibt false zurueck, wenn der Client unterdessen nicht mehr erreichbar ist.
     */
    private boolean sendMissedMessages(LiveStream stream, UUID roomId, String lastEventId) {
        UUID lastMessageId;
        try {
            lastMessageId = UUID.fromString(lastEventId.trim());
        } catch (IllegalArgumentException notAUuid) {
            log.warn("Last-Event-ID {} ist keine UUID, es wird nichts nachgeholt", lastEventId);
            return true;
        }

        List<Message> missed = messageService.loadMissed(roomId, lastMessageId);
        for (Message message : missed) {
            String messageJson = toJson(message);
            boolean reachable = stream.sendCaughtUp(message.id().toString(), messageJson);
            if (!reachable) {
                return false;
            }
        }
        log.info("{} Nachrichten fuer Raum {} nachgeholt", missed.size(), roomId);
        return true;
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
