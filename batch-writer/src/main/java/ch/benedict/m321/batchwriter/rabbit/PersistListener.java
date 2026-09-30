package ch.benedict.m321.batchwriter.rabbit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ch.benedict.m321.batchwriter.message.BadDataException;
import ch.benedict.m321.batchwriter.message.IncomingMessage;
import ch.benedict.m321.batchwriter.message.InvalidMessageException;
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
 *
 * Kaputte Nachrichten werden einzeln abgelehnt (NACK ohne Wiedereinreihen). RabbitMQ leitet sie dann
 * ueber die Dead-Letter-Einstellung der Queue nach chat.dlq weiter. Die guten im selben Paket bleiben
 * davon unberuehrt.
 */
@Component
public class PersistListener {

    private static final Logger log = LoggerFactory.getLogger(PersistListener.class);

    private final MessageParser parser;
    private final MessageRepository repository;

    /**
     * Eine Nachricht zusammen mit ihrer Lieferungsnummer. Die Nummer braucht man, um genau diese
     * eine Nachricht spaeter bestaetigen oder ablehnen zu koennen.
     */
    private record Delivery(long deliveryTag, IncomingMessage message) {
    }

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

    /** Ablauf eines Pakets: Unbrauchbares aussortieren, den Rest schreiben und bestaetigen. */
    private void handleBatch(List<Message> rawMessages, Channel channel) throws IOException {
        List<Delivery> deliveries = parseOrReject(rawMessages, channel);
        writeAndAcknowledge(deliveries, channel);
    }

    /**
     * Wandelt jede Nachricht um. Eine, die sich nicht umwandeln laesst, wird sofort abgelehnt und
     * kommt nicht in die Liste. Wiederholen wuerde nie helfen, das Ergebnis bliebe gleich.
     */
    private List<Delivery> parseOrReject(List<Message> rawMessages, Channel channel) throws IOException {
        List<Delivery> deliveries = new ArrayList<>();

        for (Message rawMessage : rawMessages) {
            long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
            try {
                IncomingMessage message = parser.parse(rawMessage.getBody());
                deliveries.add(new Delivery(deliveryTag, message));
            } catch (InvalidMessageException invalid) {
                log.warn("Nachricht {} ist unbrauchbar und geht nach chat.dlq: {}", deliveryTag, invalid.getMessage());
                reject(channel, deliveryTag);
            }
        }
        return deliveries;
    }

    /**
     * Schreibt das Paket in EINER Transaktion und bestaetigt danach alle. Lehnt die Datenbank eine
     * Zeile wegen ihrer Daten ab, war die ganze Transaktion umsonst (Rollback). Dann wird dasselbe Paket
     * Zeile fuer Zeile geschrieben, damit nur die wirklich kaputte Zeile verloren geht.
     */
    private void writeAndAcknowledge(List<Delivery> deliveries, Channel channel) throws IOException {
        List<IncomingMessage> messages = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            messages.add(delivery.message());
        }

        try {
            // Erst wenn diese Zeile durch ist, hat die Datenbank das Paket committet.
            repository.insertBatch(messages);
        } catch (BadDataException brokenRow) {
            log.warn("Das Paket enthaelt eine Zeile, die die Datenbank ablehnt. Schreibe es Zeile fuer Zeile: {}",
                    brokenRow.getMessage());
            writeOneByOne(deliveries, channel);
            return;
        }

        // Erst jetzt bestaetigen. Vorher ein ACK, und ein Absturz haette die Nachrichten fuer immer verloren.
        for (Delivery delivery : deliveries) {
            channel.basicAck(delivery.deliveryTag(), false);
        }
        log.info("Paket mit {} Nachrichten geschrieben und bestaetigt", messages.size());
    }

    /**
     * Der Einzelweg nach einem gescheiterten Paket: jede Nachricht in ihrer eigenen Transaktion.
     * Gelingt es, wird bestaetigt. Lehnt die Datenbank genau diese Zeile ab, wird sie abgelehnt.
     */
    private void writeOneByOne(List<Delivery> deliveries, Channel channel) throws IOException {
        for (Delivery delivery : deliveries) {
            try {
                repository.insertOne(delivery.message());
                channel.basicAck(delivery.deliveryTag(), false);
            } catch (BadDataException brokenRow) {
                log.warn("Nachricht {} wird von der Datenbank abgelehnt und geht nach chat.dlq: {}",
                        delivery.message().id(), brokenRow.getMessage());
                reject(channel, delivery.deliveryTag());
            }
        }
    }

    /**
     * Lehnt genau eine Nachricht ab. requeue = false heisst: nicht wieder einreihen. Wegen der
     * Dead-Letter-Einstellung von chat.persist leitet RabbitMQ sie stattdessen nach chat.dlq weiter,
     * wo man sie ansehen kann.
     */
    private void reject(Channel channel, long deliveryTag) throws IOException {
        channel.basicNack(deliveryTag, false, false);
    }

    /**
     * Gibt alles zurueck, was in diesem Paket noch offen ist. Ein einziges basicNack mit der hoechsten
     * Nummer und multiple = true genuegt: es betrifft nur noch unbestaetigte Nachrichten bis zu dieser
     * Nummer, bereits Bestaetigtes oder Abgelehntes bleibt unberuehrt. requeue = true stellt sie
     * wieder in die Queue.
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
