package ch.benedict.m321.batchwriter.rabbit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ch.benedict.m321.batchwriter.message.BadDataException;
import ch.benedict.m321.batchwriter.message.DatabaseUnavailableException;
import ch.benedict.m321.batchwriter.message.IncomingMessage;
import ch.benedict.m321.batchwriter.message.InvalidMessageException;
import ch.benedict.m321.batchwriter.message.MessageParser;
import ch.benedict.m321.batchwriter.message.MessageRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * Nichts wird je zurueck in die Queue gestellt, ausser beim Beenden des Dienstes. Der Grund: ein NACK
 * mit Wiedereinreihen zaehlt in RabbitMQ 4 nicht gegen das Zustelllimit, eine fehlerausloesende
 * Nachricht kaeme also endlos wieder (siehe docs/spec-batch-writer.md, F2 und F8).
 */
@Component
public class PersistListener {

    private static final Logger log = LoggerFactory.getLogger(PersistListener.class);

    private final MessageParser parser;
    private final MessageRepository repository;
    private final long retryPauseMillis;

    /**
     * Eine Nachricht zusammen mit ihrer Lieferungsnummer. Die Nummer braucht man, um genau diese
     * eine Nachricht spaeter bestaetigen oder ablehnen zu koennen.
     */
    private record Delivery(long deliveryTag, IncomingMessage message) {
    }

    /**
     * Spring reicht Parser und Repository herein (Konstruktor-Injektion) und die Wartepause aus
     * application.yml (Umgebungsvariable DB_RETRY_PAUSE_MS).
     */
    public PersistListener(MessageParser parser, MessageRepository repository,
            @Value("${batch.db-retry-pause-ms}") long retryPauseMillis) {
        this.parser = parser;
        this.repository = repository;
        this.retryPauseMillis = retryPauseMillis;
    }

    /**
     * Wird von Spring aufgerufen, sobald ein Paket bereit ist: 500 Nachrichten oder das Zeitlimit.
     * Letzte Auffangstelle: was hier noch als Fehler ankommt, wurde nirgends sonst abgefangen. Alle
     * noch offenen Nachrichten werden abgelehnt (landen in chat.dlq). Das Framework tut das bei
     * manueller Bestaetigung nicht von selbst. Ohne diese Zeile blieben die Nachrichten
     * unbestaetigt haengen und der Konsument stuende still, ohne dass es jemand merkt.
     *
     * Wird der Dienst beendet, waehrend er auf die Datenbank wartet, unterbricht Spring den Thread.
     * Dann geht das offene Paket ebenfalls zurueck in die Queue, damit es nicht verloren ist.
     */
    @RabbitListener(id = "persistListener", queues = RabbitConfiguration.PERSIST_QUEUE,
            containerFactory = "batchContainerFactory")
    public void onBatch(List<Message> rawMessages, Channel channel) throws IOException {
        try {
            handleBatch(rawMessages, channel);
        } catch (InterruptedException stopping) {
            // Das Unterbrechungszeichen wieder setzen, damit Spring merkt, dass der Thread beendet werden soll.
            Thread.currentThread().interrupt();
            log.info("Dienst wird beendet, das offene Paket mit {} Nachrichten geht zurueck in die Queue",
                    rawMessages.size());
            returnBatchToQueue(rawMessages, channel);
        } catch (RuntimeException unexpected) {
            log.error("Unerwarteter Fehler, die offenen Nachrichten des Pakets ({}) gehen nach chat.dlq",
                    rawMessages.size(), unexpected);
            rejectOpenMessages(rawMessages, channel);
        }
    }

    /** Ablauf eines Pakets: Unbrauchbares aussortieren, den Rest schreiben und bestaetigen. */
    private void handleBatch(List<Message> rawMessages, Channel channel) throws IOException, InterruptedException {
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
            } catch (RuntimeException bug) {
                log.error("Beim Umwandeln von Nachricht {} ist ein unerwarteter Fehler passiert, sie geht nach chat.dlq",
                        deliveryTag, bug);
                reject(channel, deliveryTag);
            }
        }
        return deliveries;
    }

    /**
     * Schreibt das Paket in EINER Transaktion und bestaetigt danach alle. Scheitert das Paket (die
     * Datenbank lehnt eine Zeile ab, oder ein Programmfehler tritt auf), war die ganze Transaktion
     * umsonst. Dann wird dasselbe Paket Zeile fuer Zeile geschrieben, damit nur die Nachricht
     * verloren geht, die wirklich schuld ist. Eine nicht erreichbare Datenbank kommt hier nie an:
     * darauf wartet writePatiently.
     */
    private void writeAndAcknowledge(List<Delivery> deliveries, Channel channel) throws IOException, InterruptedException {
        List<IncomingMessage> messages = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            messages.add(delivery.message());
        }

        try {
            // Erst wenn diese Zeile durch ist, hat die Datenbank das Paket committet.
            writePatiently(messages);
        } catch (RuntimeException failure) {
            // Entweder lehnt die Datenbank eine Zeile ab (BadDataException) oder es ist ein unerwarteter
            // Fehler. In beiden Faellen wissen wir noch nicht, WELCHE Nachricht schuld ist.
            log.warn("Das Paket ging nicht als Ganzes durch. Schreibe es Zeile fuer Zeile: {}", failure.toString());
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
     * Gelingt es, wird bestaetigt. Scheitert genau diese eine Nachricht, wird sie abgelehnt.
     */
    private void writeOneByOne(List<Delivery> deliveries, Channel channel) throws IOException, InterruptedException {
        for (Delivery delivery : deliveries) {
            boolean written = tryToWriteOne(delivery);
            if (written) {
                channel.basicAck(delivery.deliveryTag(), false);
            } else {
                reject(channel, delivery.deliveryTag());
            }
        }
    }

    /**
     * Schreibt eine einzelne Nachricht und sagt, ob es geklappt hat. Bewusst getrennt vom Bestaetigen:
     * scheitert nur das Bestaetigen (die Verbindung ist weg), ist das kein Fehler der Nachricht und
     * darf nicht als solcher gemeldet oder abgelehnt werden.
     */
    private boolean tryToWriteOne(Delivery delivery) throws InterruptedException {
        try {
            writePatiently(List.of(delivery.message()));
            return true;
        } catch (BadDataException brokenRow) {
            log.warn("Nachricht {} wird von der Datenbank abgelehnt und geht nach chat.dlq: {}",
                    delivery.message().id(), brokenRow.getMessage());
            return false;
        } catch (RuntimeException bug) {
            log.error("Nachricht {} loest einen unerwarteten Fehler aus und geht nach chat.dlq",
                    delivery.message().id(), bug);
            return false;
        }
    }

    /**
     * Schreibt die Nachrichten und wartet, so lange die Datenbank nicht antwortet. Die Nachrichten
     * bleiben in dieser Zeit unbestaetigt beim Broker: nichts geht verloren, und die Queue waechst
     * sichtbar. Die Schleife endet nur mit Erfolg, mit einer BadDataException (die Zeile ist kaputt,
     * Warten hilft nicht) oder wenn der Thread beim Beenden unterbrochen wird.
     */
    private void writePatiently(List<IncomingMessage> messages) throws InterruptedException {
        while (true) {
            try {
                repository.insertBatch(messages);
                return;
            } catch (DatabaseUnavailableException unavailable) {
                log.warn("Datenbank antwortet nicht, neuer Versuch in {} ms: {}",
                        retryPauseMillis, unavailable.getMessage());
                // Ohne diese Pause wuerde der Dienst die Datenbank im Sekundentakt bestuermen.
                Thread.sleep(retryPauseMillis);
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
     * Lehnt alles ab, was in diesem Paket noch offen ist, ohne Wiedereinreihen. Ein einziges basicNack
     * mit der hoechsten Nummer und multiple = true genuegt: es betrifft nur noch unbestaetigte
     * Nachrichten bis zu dieser Nummer, bereits Bestaetigtes oder Abgelehntes bleibt unberuehrt.
     */
    private void rejectOpenMessages(List<Message> rawMessages, Channel channel) throws IOException {
        long highestDeliveryTag = highestDeliveryTag(rawMessages);
        channel.basicNack(highestDeliveryTag, true, false);
    }

    /**
     * Gibt alles zurueck, was in diesem Paket noch offen ist. Nur beim Beenden des Dienstes: dann
     * haengt kein Fehler an den Nachrichten, sie sollen einfach spaeter wieder ankommen.
     * requeue = true stellt sie wieder in die Queue.
     */
    private void returnBatchToQueue(List<Message> rawMessages, Channel channel) throws IOException {
        long highestDeliveryTag = highestDeliveryTag(rawMessages);
        channel.basicNack(highestDeliveryTag, true, true);
    }

    /** Sucht die hoechste Lieferungsnummer im Paket. Sie steht fuer "alle bis hierher" bei multiple = true. */
    private long highestDeliveryTag(List<Message> rawMessages) {
        long highest = 0;
        for (Message rawMessage : rawMessages) {
            long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
            if (deliveryTag > highest) {
                highest = deliveryTag;
            }
        }
        return highest;
    }
}
