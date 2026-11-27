package ch.benedict.m321.chat.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.stereotype.Component;

/**
 * Beendet alle offenen Streams, wenn die Verbindung zu RabbitMQ weg war und neu aufgebaut wird
 * (docs/spec-live-zustellung.md, 2.3, Szenario S6). Waehrend der Unterbrechung ist die exklusive
 * Live-Queue mit allem darin verschwunden. Die Clients haben also vielleicht etwas verpasst. Endet ihr
 * Stream, verbinden sie sich neu und holen es mit Last-Event-ID nach.
 *
 * Beendet wird erst NACH der Neuverbindung und nicht schon beim Abbruch: dann ist die neue Live-Queue
 * schon da, wenn die Clients zurueckkommen. Queue und Bindung legt Spring selbst wieder an.
 */
@Component
public class ReconnectStreamCloser implements ConnectionListener {

    private static final Logger log = LoggerFactory.getLogger(ReconnectStreamCloser.class);

    private final StreamRegistry streamRegistry;

    /** Wird true, sobald die allererste Verbindung nach dem Start aufgebaut ist. */
    private boolean firstConnectionSeen = false;

    /**
     * Spring reicht die ConnectionFactory und die Liste der Streams herein. Der Konstruktor meldet diese
     * Klasse bei der ConnectionFactory an: ab dann ruft sie onCreate bei jeder neuen Verbindung auf.
     */
    public ReconnectStreamCloser(ConnectionFactory connectionFactory, StreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
        connectionFactory.addConnectionListener(this);
    }

    /**
     * Wird bei jeder neuen Verbindung zu RabbitMQ aufgerufen. Bei der ersten nach dem Start passiert
     * nichts, es gibt noch nichts zu verpassen. Jede weitere ist eine Neuverbindung, und dann werden alle
     * Streams beendet. synchronized, weil Verbindungen in einem anderen Thread entstehen koennen.
     */
    @Override
    public synchronized void onCreate(Connection connection) {
        if (!firstConnectionSeen) {
            firstConnectionSeen = true;
            return;
        }
        log.warn("Verbindung zu RabbitMQ wurde neu aufgebaut, alle offenen Streams werden beendet");
        streamRegistry.completeAll();
    }
}
