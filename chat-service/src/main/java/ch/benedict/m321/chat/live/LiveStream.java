package ch.benedict.m321.chat.live;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Ein offener Stream zusammen mit dem Raum, fuer den der Client ihn geoeffnet hat, und mit seiner
 * Warteliste fuer das Nachholen (Spec 2.4).
 *
 * Auf einen Stream schreiben zwei Threads: der Web-Thread beim Nachholen und der Listener-Thread mit den
 * Live-Nachrichten. Damit nie beide gleichzeitig schreiben, sind alle Methoden, die schreiben oder den
 * Zustand aendern, synchronized. Ein synchronized auf einer Methode sperrt das Objekt selbst (this), also
 * genau diesen einen Stream. Andere Streams sind davon nicht betroffen.
 *
 * Waehrend des Nachholens schreibt der Listener nicht selbst, sondern legt seine Nachrichten in die
 * Warteliste. Erst finishCatchUp() schickt sie ab, ohne die IDs, die schon nachgeholt wurden.
 */
public class LiveStream {

    private static final Logger log = LoggerFactory.getLogger(LiveStream.class);

    /** Das data: des reload-Ereignisses, genau so wie in Spec 2.6. */
    private static final String RELOAD_DATA = "{\"reason\":\"unknown-last-event-id\"}";

    private final SseEmitter emitter;
    private final UUID roomId;

    /** true, solange nachgeholt wird. Dann kommen Live-Nachrichten in die Warteliste statt in den Stream. */
    private boolean catchingUp = false;

    /** Live-Nachrichten, die waehrend des Nachholens angekommen sind, in der Reihenfolge ihrer Ankunft. */
    private final List<WaitingMessage> waitlist = new ArrayList<>();

    /** Die IDs der nachgeholten Nachrichten. Damit erkennt finishCatchUp(), was schon gesendet ist. */
    private final Set<String> caughtUpIds = new HashSet<>();

    /** Merkt sich den Emitter und den Raum. Der Stream beginnt ohne Warteliste und schickt alles direkt. */
    public LiveStream(SseEmitter emitter, UUID roomId) {
        this.emitter = emitter;
        this.roomId = roomId;
    }

    /** Der Emitter von Spring, ueber den die Ereignisse zum Client gehen. */
    public SseEmitter emitter() {
        return emitter;
    }

    /** Der Raum, fuer den der Client den Stream geoeffnet hat. */
    public UUID roomId() {
        return roomId;
    }

    /**
     * Schaltet die Warteliste ein. Muss aufgerufen werden, bevor der Stream angemeldet wird. Sonst
     * koennte der Listener noch direkt schreiben, waehrend der Web-Thread schon nachholt.
     */
    public synchronized void startWaitlist() {
        catchingUp = true;
    }

    /**
     * Schickt eine nachgeholte Nachricht aus der Datenbank und merkt sich ihre ID. Die Sperre gilt nur fuer
     * diese eine Nachricht. Gibt false zurueck, wenn der Client nicht mehr erreichbar ist.
     */
    public synchronized boolean sendCaughtUp(String messageId, String messageJson) {
        caughtUpIds.add(messageId);
        return send(messageId, messageJson);
    }

    /**
     * Schickt das Ereignis reload (Spec 2.6): der Client weiss dann, dass ihm vielleicht etwas fehlt, und
     * laedt den Verlauf neu. Es hat absichtlich keine id: sonst wuerde der Browser sie sich als letzte
     * gesehene Nachricht merken. Gibt false zurueck, wenn der Client nicht mehr erreichbar ist.
     */
    public synchronized boolean sendReload() {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name("reload")
                .data(RELOAD_DATA, MediaType.TEXT_PLAIN);
        return sendEvent(event);
    }

    /**
     * Wird vom Listener fuer jede Live-Nachricht des Raums aufgerufen. Waehrend des Nachholens kommt die
     * Nachricht in die Warteliste, sonst geht sie direkt raus. Gibt false zurueck, wenn der Client nicht
     * mehr erreichbar ist.
     */
    public synchronized boolean deliverLive(String messageId, String messageJson) {
        if (catchingUp) {
            waitlist.add(new WaitingMessage(messageId, messageJson));
            return true;
        }
        return send(messageId, messageJson);
    }

    /**
     * Beendet das Nachholen: schickt die Warteliste ohne die IDs, die schon nachgeholt wurden, und schaltet
     * sie aus. Ab dann schickt deliverLive() direkt. Weil das alles unter derselben Sperre passiert, kann
     * keine Live-Nachricht dazwischen rutschen und vor den wartenden ankommen.
     * Gibt false zurueck, wenn der Client nicht mehr erreichbar ist.
     */
    public synchronized boolean finishCatchUp() {
        boolean reachable = true;
        for (WaitingMessage waiting : waitlist) {
            boolean alreadySent = caughtUpIds.contains(waiting.id());
            if (!alreadySent && reachable) {
                reachable = send(waiting.id(), waiting.json());
            }
        }
        log.debug("Warteliste mit {} Nachrichten abgearbeitet, {} waren schon nachgeholt", waitlist.size(), caughtUpIds.size());
        // Die Liste und das Set werden nicht mehr gebraucht. Leeren gibt den Speicher frei.
        waitlist.clear();
        caughtUpIds.clear();
        catchingUp = false;
        return reachable;
    }

    /**
     * Beendet den Stream. Unter derselben Sperre, damit das Beenden nicht mitten in ein Senden faellt.
     */
    public synchronized void complete() {
        emitter.complete();
    }

    /**
     * Schreibt eine Nachricht: die id bei id:, das JSON unveraendert bei data:. Wird nur aus den
     * synchronized-Methoden oben aufgerufen, haelt die Sperre also schon. Gibt false zurueck, wenn der
     * Client nicht mehr erreichbar ist.
     */
    private boolean send(String messageId, String messageJson) {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(messageId)
                .data(messageJson, MediaType.TEXT_PLAIN);
        return sendEvent(event);
    }

    /**
     * Schreibt ein fertiges Ereignis in den Stream. Klappt das nicht, ist der Client weg, und es kommt
     * false zurueck. Wer den Stream dann austraegt, entscheidet der Aufrufer.
     */
    private boolean sendEvent(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException clientGone) {
            log.debug("Stream ist nicht mehr erreichbar: {}", clientGone.getMessage());
            return false;
        }
    }
}
