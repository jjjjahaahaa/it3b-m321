package ch.benedict.m321.chat.live;

import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
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

    /**
     * Die Live-Queue mit einem Namen aus chat.live. und einer zufaelligen UUID. Die UUID wird bei jedem
     * Start neu gezogen, nicht aus dem Hostnamen genommen: der bliebe nach einem Neustart gleich.
     *
     * durable = false: nach einem Neustart des Brokers muss es sie nicht mehr geben.
     * exclusive = true: sie gehoert zur Verbindung dieser Instanz, und RabbitMQ loescht sie, sobald
     *   diese Verbindung weg ist (Szenario S4).
     * autoDelete = false: sie bleibt, auch wenn der Listener einmal kurz nicht liest. Geloescht
     *   wird sie nur ueber die Verbindung.
     */
    @Bean
    public Queue liveQueue() {
        String queueName = QUEUE_PREFIX + UUID.randomUUID();
        return new Queue(queueName, false, true, false);
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
}
