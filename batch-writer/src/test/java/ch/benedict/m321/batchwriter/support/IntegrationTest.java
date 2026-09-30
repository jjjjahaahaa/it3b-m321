package ch.benedict.m321.batchwriter.support;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gemeinsame Grundlage aller Tests, die den ganzen batch-writer starten (echter Broker, echte Datenbank).
 *
 * Wichtig ist @DirtiesContext: Spring behaelt gestartete Anwendungen normalerweise im Zwischenspeicher,
 * damit die naechste Testklasse schneller startet. Hier wuerde das schaden. Jede alte Anwendung
 * hielte ihren Listener an chat.persist offen und naehme dem Test, der gerade laeuft, Nachrichten weg.
 * Nach jeder Testklasse wird die Anwendung deshalb beendet. Es liest immer nur EIN Listener mit.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTest {

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /** Sagt der gestarteten Anwendung, wo Datenbank und Broker der Testcontainer erreichbar sind. */
    @DynamicPropertySource
    static void connectToTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerWith(registry);
    }

    /** Leert die Dead-Letter-Queue, damit die Zaehlung in jedem Test bei null beginnt. */
    @BeforeEach
    void emptyDeadLetterQueue() throws Exception {
        TestInfrastructure.purgeQueue("chat.dlq");
    }

    /** Holt den Container, der den Listener betreibt, damit ein Test ihn anhalten und starten kann. */
    protected MessageListenerContainer persistContainer() {
        return listenerRegistry.getListenerContainer("persistListener");
    }

    /** Abfrage, die die Nachrichten eines Raums zaehlt. Sie wird an TestInfrastructure uebergeben. */
    protected String countInRoom(UUID roomId) {
        return "SELECT count(*) FROM message WHERE room_id = '" + roomId + "'";
    }

    /**
     * Legt alle Nachrichten in die Queue, waehrend der Listener angehalten ist, und startet ihn dann.
     * So liegen sie sicher zusammen in EINEM Paket. Ohne das Anhalten koennte der Listener die ersten
     * Nachrichten schon abholen, bevor die letzten da sind, und der Test prueft dann nicht mehr,
     * was er pruefen soll.
     */
    protected void publishAsOnePacket(List<String> bodies) throws Exception {
        MessageListenerContainer container = persistContainer();
        container.stop();
        try {
            TestInfrastructure.publishJson("chat.messages", "", bodies);
        } finally {
            container.start();
        }
    }
}
