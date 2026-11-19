package ch.benedict.m321.chat.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Startet fuer alle Tests des chat-service zusammen EINE echte PostgreSQL und EIN echtes RabbitMQ
 * in Docker. Es sind dieselben Images wie in docker-compose.yml, und das Schema kommt aus derselben
 * Datei db/01-schema.sql. So testen wir gegen dasselbe, was im Stack laeuft.
 *
 * Die Container starten beim ersten Zugriff auf diese Klasse und laufen, bis die Test-JVM endet.
 * Das spart pro Testklasse mehrere Sekunden.
 */
public final class TestInfrastructure {

    private static final String DATABASE_NAME = "chat";
    private static final String DATABASE_USER = "chat";
    private static final String DATABASE_PASSWORD = "chat";

    /** Standardbenutzer des Test-Brokers. Der Broker im Compose-Stack nutzt die Werte aus .env. */
    private static final String BROKER_USER = "guest";
    private static final String BROKER_PASSWORD = "guest";

    private static final PostgreSQLContainer<?> POSTGRES;
    private static final RabbitMQContainer RABBIT;

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        // Das Schema wird beim ersten Start automatisch ausgefuehrt, genau wie im Compose-Stack
        // ueber das Verzeichnis docker-entrypoint-initdb.d. Es gibt keine zweite Kopie davon.
        Path schemaFile = RepositoryFiles.find("db/01-schema.sql");
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName(DATABASE_NAME)
                .withUsername(DATABASE_USER)
                .withPassword(DATABASE_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(schemaFile),
                        "/docker-entrypoint-initdb.d/01-schema.sql");
        POSTGRES.start();

        // Das Image mit Management-Plugin, weil spaetere Tests ueber die Management-API
        // nachsehen, welche Queues es gibt und wer daran haengt.
        RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");
        RABBIT.start();
    }

    /** Privat, damit niemand ein Objekt erzeugt: die Klasse hat nur statische Hilfsmethoden. */
    private TestInfrastructure() {
        // Nichts zu tun.
    }

    /**
     * Traegt Datenbank und Broker der Testcontainer in eine Spring-Anwendung ein. Im Betrieb liest
     * der chat-service dieselben Einstellungen aus Umgebungsvariablen (application.yml).
     */
    public static void registerWith(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestInfrastructure::databaseUser);
        registry.add("spring.datasource.password", TestInfrastructure::databasePassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", TestInfrastructure::brokerUser);
        registry.add("spring.rabbitmq.password", TestInfrastructure::brokerPassword);
    }

    /** JDBC-Adresse der Test-Datenbank, fuer Instanzen, die ein Test selbst startet. */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** Rechnername des Test-Brokers, von hier aus gesehen. */
    public static String brokerHost() {
        return RABBIT.getHost();
    }

    /** AMQP-Port des Test-Brokers. Docker vergibt ihn zufaellig. */
    public static int brokerPort() {
        return RABBIT.getAmqpPort();
    }

    /** Benutzername der Test-Datenbank. */
    public static String databaseUser() {
        return DATABASE_USER;
    }

    /** Passwort der Test-Datenbank. */
    public static String databasePassword() {
        return DATABASE_PASSWORD;
    }

    /** Benutzername des Test-Brokers. */
    public static String brokerUser() {
        return BROKER_USER;
    }

    /** Passwort des Test-Brokers. */
    public static String brokerPassword() {
        return BROKER_PASSWORD;
    }

    /**
     * Fragt die Management-API des Brokers und gibt die Antwort als JSON zurueck, zum Beispiel fuer
     * den Pfad "exchanges/%2F/chat.messages". So sieht der Test dieselben Angaben wie die
     * Management-Oberflaeche.
     */
    public static JsonNode askBroker(String apiPath) throws IOException, InterruptedException {
        String address = "http://" + RABBIT.getHost() + ":" + RABBIT.getHttpPort() + "/api/" + apiPath;
        String login = BROKER_USER + ":" + BROKER_PASSWORD;
        String encodedLogin = Base64.getEncoder().encodeToString(login.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(address))
                .header("Authorization", "Basic " + encodedLogin)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Broker antwortet mit " + response.statusCode() + " auf " + address);
        }
        return JSON.readTree(response.body());
    }
}
