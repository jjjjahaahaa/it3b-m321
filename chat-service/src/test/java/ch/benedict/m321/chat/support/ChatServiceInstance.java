package ch.benedict.m321.chat.support;

import ch.benedict.m321.chat.ChatServiceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Startet eine eigene Instanz des chat-service fuer einen Test, verbunden mit den Testcontainern, auf
 * einem freien Zufallsport. So kann ein Test mehrere Instanzen gleichzeitig haben oder einer Instanz
 * etwas antun (zum Beispiel ihre Verbindung trennen), ohne andere Tests zu stoeren.
 */
public final class ChatServiceInstance {

    /** Privat, damit niemand ein Objekt erzeugt: die Klasse hat nur statische Hilfsmethoden. */
    private ChatServiceInstance() {
        // Nichts zu tun.
    }

    /**
     * Startet die Instanz. server.port = 0 heisst: das Betriebssystem waehlt einen freien Port.
     * Die Werte gehen als Startargumente mit (--name=wert), nicht ueber properties(): die waeren nur
     * Standardwerte, und application.yml wuerde sie mit Port 8080 und localhost ueberschreiben.
     * Wer die Instanz startet, muss sie am Ende mit close() wieder beenden.
     */
    public static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChatServiceApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + TestInfrastructure.jdbcUrl(),
                        "--spring.datasource.username=" + TestInfrastructure.databaseUser(),
                        "--spring.datasource.password=" + TestInfrastructure.databasePassword(),
                        "--spring.rabbitmq.host=" + TestInfrastructure.brokerHost(),
                        "--spring.rabbitmq.port=" + TestInfrastructure.brokerPort(),
                        "--spring.rabbitmq.username=" + TestInfrastructure.brokerUser(),
                        "--spring.rabbitmq.password=" + TestInfrastructure.brokerPassword());
    }

    /** Die Adresse einer Instanz mit dem Port, den das Betriebssystem ihr gegeben hat. */
    public static String baseAddress(ConfigurableApplicationContext instance) {
        ServletWebServerApplicationContext webContext = (ServletWebServerApplicationContext) instance;
        int port = webContext.getWebServer().getPort();
        return "http://localhost:" + port;
    }
}
