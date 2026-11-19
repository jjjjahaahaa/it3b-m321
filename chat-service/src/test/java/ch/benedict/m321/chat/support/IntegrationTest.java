package ch.benedict.m321.chat.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gemeinsame Grundlage aller Tests, die den ganzen chat-service starten. Sie verbindet die
 * Anwendung mit der Datenbank und dem Broker aus TestInfrastructure, statt mit localhost.
 */
@SpringBootTest
public abstract class IntegrationTest {

    /** Sagt der gestarteten Anwendung, wo Datenbank und Broker der Testcontainer erreichbar sind. */
    @DynamicPropertySource
    static void connectToTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerWith(registry);
    }
}
