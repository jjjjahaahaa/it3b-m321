package ch.benedict.m321.batchwriter.rabbit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ch.benedict.m321.batchwriter.message.IncomingMessage;
import ch.benedict.m321.batchwriter.message.MessageParser;
import ch.benedict.m321.batchwriter.message.MessageRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Das Herz des Dienstes: nimmt ein Paket Nachrichten aus chat.persist entgegen, schreibt es in einer
 * Transaktion in die Datenbank und bestaetigt es erst danach bei RabbitMQ (ACK).
 *
 * Dieser Ablauf ist der Grund fuer "mindestens einmal": stuerzt der Dienst zwischen Commit und ACK ab,
 * kommt das Paket erneut, und ON CONFLICT DO NOTHING in der Datenbank macht daraus keine Dublette.
 */
@Component
public class PersistListener {

    private static final Logger log = LoggerFactory.getLogger(PersistListener.class);

    private final MessageParser parser;
    private final MessageRepository repository;

    /** Spring reicht Parser und Repository herein (Konstruktor-Injektion). */
    public PersistListener(MessageParser parser, MessageRepository repository) {
        this.parser = parser;
        this.repository = repository;
    }

    /**
     * Wird von Spring aufgerufen, sobald ein Paket bereit ist: 500 Nachrichten oder das Zeitlimit.
     * Faengt jeden unerwarteten Fehler ab und gibt das Paket dann von Hand an die Queue zurueck,
     * weil das Framework bei manueller Bestaetigung nichts zurueckgibt. Ohne diese Rueckgabe blieben
     * die Nachrichten unbestaetigt haengen und der Konsument stuende still.
     */
    @RabbitListener(id = "persistListener", queues = RabbitConfiguration.PERSIST_QUEUE,
            containerFactory = "batchContainerFactory")
    public void onBatch(List<Message> rawMessages, Channel channel) throws IOException {
        try {
            handleBatch(rawMessages, channel);
        } catch (RuntimeException unexpected) {
            log.error("Unerwarteter Fehler, das Paket mit {} Nachrichten geht zurueck in die Queue",
                    rawMessages.size(), unexpected);
            returnBatchToQueue(rawMessages, channel);
        }
    }

    /** Der Normalfall: umwandeln, in einer Transaktion schreiben, danach bestaetigen. */
    private void handleBatch(List<Message> rawMessages, Channel channel) throws IOException {
        List<Long> deliveryTags = new ArrayList<>();
        List<IncomingMessage> messages = new ArrayList<>();

        for (Message rawMessage : rawMessages) {
            long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
            IncomingMessage message = parser.parse(rawMessage.getBody());
            deliveryTags.add(deliveryTag);
            messages.add(message);
        }

        // Erst wenn diese Zeile durch ist, hat die Datenbank das Paket committet.
        repository.insertBatch(messages);

        // Erst jetzt bestaetigen. Vorher ein ACK, und ein Absturz haette die Nachrichten fuer immer verloren.
        for (long deliveryTag : deliveryTags) {
            channel.basicAck(deliveryTag, false);
        }
        log.info("Paket mit {} Nachrichten geschrieben und bestaetigt", messages.size());
    }

    /**
     * Gibt alles zurueck, was in diesem Paket noch offen ist. Ein einziges basicNack mit der hoechsten
     * Nummer und multiple = true genuegt: es betrifft nur noch unbestaetigte Nachrichten bis zu dieser
     * Nummer, bereits Bestaetigtes bleibt unberuehrt. requeue = true stellt sie wieder in die Queue.
     */
    private void returnBatchToQueue(List<Message> rawMessages, Channel channel) throws IOException {
        long highestDeliveryTag = 0;
        for (Message rawMessage : rawMessages) {
            long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
            if (deliveryTag > highestDeliveryTag) {
                highestDeliveryTag = deliveryTag;
            }
        }
        channel.basicNack(highestDeliveryTag, true, true);
    }
}
