package ch.benedict.m321.chat.live;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt die Live-Queue DIESER Instanz an (docs/spec-live-zustellung.md, 3.1 und 3.2). Jede Instanz
 * bekommt ihre eigene Queue, damit jede eine eigene Kopie jeder Nachricht erhaelt. Teilten sich zwei
 * Instanzen eine Queue, wuerden sie sich die Nachrichten aufteilen wie an einem gemeinsamen Briefkasten.
 *
 * Die Queue steht bewusst nicht in rabbitmq/definitions.json: ihr Name entsteht erst beim Start.
 */
@Configuration
public class LiveQueueConfiguration {

    /** Vorne im Namen jeder Live-Queue. Dahinter kommt die UUID der Instanz. */
    public static final String QUEUE_PREFIX = "chat.live.";

    /** Hoechstzahl wartender Nachrichten in der Live-Queue (Spec 3.3, Szenario S6). */
    private static final int MAX_WAITING_MESSAGES = 1000;

    /**
     * Die Live-Queue mit einem Namen aus chat.live. und einer zufaelligen UUID. Die UUID wird bei jedem
     * Start neu gezogen, nicht aus dem Hostnamen genommen: der bliebe nach einem Neustart gleich.
     *
     * durable = false: nach einem Neustart des Brokers muss es sie nicht mehr geben.
     * exclusive = true: sie gehoert zur Verbindung dieser Instanz, und RabbitMQ loescht sie, sobald
     *   diese Verbindung weg ist (Szenario S4).
     * autoDelete = false: sie bleibt, auch wenn der Listener einmal kurz nicht liest. Geloescht
     *   wird sie nur ueber die Verbindung.
     *
     * Dazu zwei Grenzen (Spec 3.3): x-max-length = 1000 wartende Nachrichten, und x-overflow = drop-head
     * heisst, dass bei der 1001. die AELTESTE rausfliegt und nicht die neue abgelehnt wird. Fuer die
     * Anzeige ist die neueste wichtiger. Nachrichten, die schon abgeholt, aber noch nicht bestaetigt
     * sind, zaehlen nicht mit. Eine TTL pro Nachricht gibt es bewusst nicht (Spec 2.3).
     */
    @Bean
    public Queue liveQueue() {
        String queueName = QUEUE_PREFIX + UUID.randomUUID();
        Map<String, Object> limits = new HashMap<>();
        limits.put("x-max-length", MAX_WAITING_MESSAGES);
        limits.put("x-overflow", "drop-head");
        return new Queue(queueName, false, true, false, limits);
    }

    /**
     * Bindet die Live-Queue an den Fanout-Exchange chat.messages. Erst damit legt RabbitMQ jede
     * publizierte Nachricht auch in diese Queue. Spring meldet Queue und Bindung beim ersten
     * Verbindungsaufbau selbst beim Broker an, und nach einer Neuverbindung wieder.
     */
    @Bean
    public Binding liveQueueBinding(Queue liveQueue, FanoutExchange chatExchange) {
        return BindingBuilder.bind(liveQueue).to(chatExchange);
    }

    /**
     * Eine eigene Listener-Fabrik nur fuer die Live-Queue. Sie wandelt nichts um (SimpleMessageConverter),
     * damit der Listener das JSON genau so bekommt, wie es am Exchange angekommen ist.
     *
     * Mit dem JSON-Konverter aus RabbitConfiguration versuchte Spring, die Nachricht anhand des Headers
     * __TypeId__ in eine Klasse umzuwandeln, und lehnte das aus Sicherheitsgruenden ab. Der Konverter
     * wird hier bewusst NICHT als eigene Bean angelegt: gaebe es zwei, wuesste der RabbitTemplate beim
     * Senden nicht mehr, welchen er nehmen soll.
     *
     * Genau ein Konsument, genau ein Thread: die Nachrichten kommen in der Reihenfolge der Queue an (Spec 2.2).
     */
    @Bean
    public SimpleRabbitListenerContainerFactory liveListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }
}
