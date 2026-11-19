package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.rabbit.RabbitConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Fachlogik rund um Nachrichten. Der Controller kennt nur diese Klasse, nicht das
 * Repository und nicht RabbitMQ - so bleibt die Weboberflaeche von der Technik
 * dahinter getrennt.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessageRepository messageRepository;

    /**
     * Spring reicht RabbitTemplate und Repository herein (Konstruktor-Injektion). So kann ein Test
     * beide durch eigene Varianten ersetzen, ohne dass diese Klasse etwas davon merkt.
     */
    public MessageService(RabbitTemplate rabbitTemplate, MessageRepository messageRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageRepository = messageRepository;
    }

    /**
     * Liefert den Verlauf eines Raums. Reines Lesen - hier wird nichts veraendert.
     */
    public List<Message> loadHistory(UUID roomId, int limit) {
        log.debug("Verlauf angefordert: Raum {}, hoechstens {} Nachrichten", roomId, limit);
        return messageRepository.findLatest(roomId, limit);
    }

    /**
     * Liefert die Nachrichten, die ein Client nach der Nachricht lastMessageId verpasst hat, die aelteste
     * zuerst (Spec 2.4). Kennt die Datenbank diese Nachricht nicht, ist die Liste leer: dann laeuft der
     * Stream einfach live weiter. Was in diesem Fall genauer passiert, regelt Schritt 9.
     */
    public List<Message> loadMissed(UUID roomId, UUID lastMessageId) {
        Instant sentAt = messageRepository.findSentAt(lastMessageId);
        if (sentAt == null) {
            log.info("Last-Event-ID {} ist unbekannt, es wird nichts nachgeholt", lastMessageId);
            return new ArrayList<>();
        }
        return messageRepository.findFromSentAt(roomId, sentAt, lastMessageId);
    }

    /**
     * Nimmt eine neue Nachricht an und gibt sie an RabbitMQ weiter.
     *
     * Wichtig: hier wird NICHT in die Datenbank geschrieben. Der chat-service
     * publiziert nur; gespeichert wird spaeter gebuendelt vom batch-service
     * (PLANUNG.md, Abschnitt 2.3).
     */
    public Message sendMessage(NewMessage incoming) {
        // Die ID vergeben WIR, nicht die Datenbank. Nur so kann der batch-service
        // ein Paket gefahrlos wiederholen, ohne Dubletten zu erzeugen.
        UUID id = UUID.randomUUID();

        // Auch die Zeit setzen wir hier: das ist der Moment des SENDENS.
        // Die Datenbank wuerde spaeter den Moment des SCHREIBENS festhalten.
        Instant sentAt = Instant.now();

        Message message = new Message(id, incoming.roomId(), incoming.sender(),
                incoming.text(), sentAt);

        log.info("Nachricht {} von {} fuer Raum {} wird publiziert",
                id, incoming.sender(), incoming.roomId());

        // Zweites Argument ist der Routing-Key. Ein Fanout-Exchange ignoriert ihn,
        // deshalb steht dort der leere String.
        rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);

        log.info("Nachricht {} an Exchange {} uebergeben", id, RabbitConfiguration.EXCHANGE_NAME);
        return message;
    }
}
