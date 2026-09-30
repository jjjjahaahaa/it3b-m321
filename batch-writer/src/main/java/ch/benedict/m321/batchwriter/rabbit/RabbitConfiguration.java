package ch.benedict.m321.batchwriter.rabbit;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stellt ein, WIE der batch-writer die Queue liest: in Paketen, mit einem einzigen Konsumenten
 * und mit Bestaetigung von Hand. Alle Werte stehen in docs/spec-batch-writer.md, Abschnitt 3.1.
 */
@Configuration
public class RabbitConfiguration {

    /** Name der Queue, aus der geschrieben wird. Sie wird vom Broker angelegt (rabbitmq/definitions.json). */
    public static final String PERSIST_QUEUE = "chat.persist";

    /**
     * Baut die Fabrik, aus der Spring den Listener-Container macht. Ein "Container" ist der Teil,
     * der sich mit dem Broker verbindet, Nachrichten abholt und an unsere Methode uebergibt.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${batch.size}") int batchSize,
            @Value("${batch.timeout-ms}") long batchTimeoutMillis) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        // Die Methode bekommt eine LISTE von Nachrichten statt einer einzelnen.
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);

        // Prefetch = Paketgroesse. Liesse RabbitMQ weniger unbestaetigte Nachrichten zu, koennte ein
        // Paket nie voll werden und liefe jedes Mal ins Zeitlimit.
        factory.setPrefetchCount(batchSize);

        // Zwei verschiedene Zeitlimits, beide auf denselben Wert:
        // receiveTimeout    = so lange wartet der Container auf die NAECHSTE Nachricht.
        // batchReceiveTimeout = so lange darf das GANZE Paket sammeln, gemessen ab der ersten Nachricht.
        // Nur das erste allein waere ein Fehler: kaeme alle 150 ms eine Nachricht, wuerde es nie
        // ausloesen, und das Paket fuellte sich erst bei 500 Stueck (siehe BatchingTest).
        factory.setReceiveTimeout(batchTimeoutMillis);
        factory.setBatchReceiveTimeout(batchTimeoutMillis);

        // Wir bestaetigen selbst, und zwar erst NACH dem Commit der Datenbank.
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // Genau ein Konsument pro Instanz. Zwei Instanzen ergeben zwei Konsumenten an der Queue.
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);

        // Fehlt die Queue kurz (zum Beispiel weil der Broker neu startet), soll der Dienst weiter
        // versuchen, statt sich zu beenden.
        factory.setMissingQueuesFatal(false);

        return factory;
    }
}
