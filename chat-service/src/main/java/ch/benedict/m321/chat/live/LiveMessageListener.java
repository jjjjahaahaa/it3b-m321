package ch.benedict.m321.chat.live;

import ch.benedict.m321.chat.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Liest die Live-Queue dieser Instanz. In diesem Schritt schreibt er jede Nachricht nur ins Log.
 * So sieht man, dass eine Nachricht bis in die Instanz kommt, bevor ein Client angeschlossen ist.
 */
@Component
public class LiveMessageListener {

    private static final Logger log = LoggerFactory.getLogger(LiveMessageListener.class);

    /**
     * Wird fuer jede Nachricht in der Live-Queue aufgerufen. Der JSON-Koerper wird von Spring mit dem
     * Konverter aus RabbitConfiguration wieder in ein Message-Objekt umgewandelt.
     *
     * "#{liveQueue.name}" fragt Spring nach dem Namen der Bean liveQueue, denn der Name steht erst beim
     * Start fest und kann nicht als fester Text dastehen. concurrency = "1" heisst: genau ein Thread
     * liest die Queue, die Nachrichten kommen also in der Reihenfolge der Queue an (Spec 2.2).
     */
    @RabbitListener(queues = "#{liveQueue.name}", concurrency = "1")
    public void onMessage(Message message) {
        log.info("Live-Nachricht {} empfangen", message.id());
    }
}
