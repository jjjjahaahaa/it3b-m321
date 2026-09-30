package ch.benedict.m321.batchwriter.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Startet fuer alle Tests zusammen EINE echte PostgreSQL und EIN echtes RabbitMQ in Docker.
 * Beide werden mit denselben Dateien eingerichtet wie im Compose-Stack: die Datenbank mit
 * db/01-schema.sql, der Broker mit rabbitmq/definitions.json. So testen wir nicht gegen eine
 * Nachbildung, sondern gegen dasselbe, was spaeter produktiv laeuft.
 *
 * Die Container starten beim ersten Zugriff auf diese Klasse und werden erst beendet, wenn die
 * Test-JVM endet. Das spart pro Testklasse mehrere Sekunden Startzeit.
 */
public final class TestInfrastructure {

    private static final String DATABASE_NAME = "chat";
    private static final String DATABASE_USER = "chat";
    private static final String DATABASE_PASSWORD = "chat";

    /** Standardbenutzer des Test-Brokers. Der Broker im Compose-Stack nutzt andere Werte aus .env. */
    private static final String BROKER_USER = "guest";
    private static final String BROKER_PASSWORD = "guest";

    private static final PostgreSQLContainer<?> POSTGRES;
    private static final RabbitMQContainer RABBIT;

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        // Das Schema aus dem Repository wird beim ersten Start automatisch ausgefuehrt,
        // genau wie im Compose-Stack ueber das Verzeichnis docker-entrypoint-initdb.d.
        Path schemaFile = RepositoryFiles.find("db/01-schema.sql");
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName(DATABASE_NAME)
                .withUsername(DATABASE_USER)
                .withPassword(DATABASE_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(schemaFile),
                        "/docker-entrypoint-initdb.d/01-schema.sql");
        POSTGRES.start();

        // Der Broker bekommt dieselbe definitions.json wie im Compose-Stack. Sie wird erst
        // nach dem Start eingespielt, weil der Broker sonst den Standardbenutzer nicht anlegt.
        Path definitionsFile = RepositoryFiles.find("rabbitmq/definitions.json");
        RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(definitionsFile),
                        "/definitions.json");
        RABBIT.start();
        importDefinitions();
    }

    /**
     * Spielt rabbitmq/definitions.json in den laufenden Broker ein, mit demselben Befehl wie
     * der Healthcheck im Compose-Stack. Der Import laeuft im Broker asynchron. Deshalb wartet
     * diese Methode danach, bis die Queue chat.persist wirklich sichtbar ist.
     */
    private static void importDefinitions() {
        try {
            RABBIT.execInContainer("rabbitmqctl", "import_definitions", "/definitions.json");
            waitUntilQueueExists("chat.persist");
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException("Definitionen konnten nicht eingespielt werden", exception);
        }
    }

    /** Fragt den Broker bis zu 30 Sekunden lang alle halbe Sekunde, ob die Queue schon existiert. */
    private static void waitUntilQueueExists(String queueName) throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                askBroker("queues/%2F/" + queueName);
                return;
            } catch (IOException notThereYet) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Queue " + queueName + " ist nach 30 Sekunden nicht da");
    }

    /** Privat, damit niemand ein Objekt erzeugt: die Klasse hat nur statische Hilfsmethoden. */
    private TestInfrastructure() {
        // Nichts zu tun.
    }

    /** JDBC-Adresse der Test-Datenbank, mit denselben Optionen wie im Betrieb. */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=true";
    }

    /** Benutzername der Test-Datenbank. */
    public static String databaseUser() {
        return DATABASE_USER;
    }

    /** Passwort der Test-Datenbank. */
    public static String databasePassword() {
        return DATABASE_PASSWORD;
    }

    /** Oeffnet eine neue, eigene Verbindung zur Test-Datenbank fuer Pruefungen im Test. */
    public static Connection openDatabaseConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DATABASE_USER, DATABASE_PASSWORD);
    }

    /**
     * Legt einen neuen Raum an und gibt seine ID zurueck. Jede Nachricht braucht einen Raum
     * (Fremdschluessel). Jeder Test nimmt seinen eigenen Raum, damit die Zaehlungen der Tests
     * sich nicht gegenseitig stoeren.
     */
    public static UUID createRoom() throws SQLException {
        UUID roomId = UUID.randomUUID();
        String sql = "INSERT INTO room (id, name, created_by, created_at) VALUES (?, 'Testraum', 'test', now())";

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, roomId);
            statement.executeUpdate();
        }
        return roomId;
    }

    /** Fuehrt eine Abfrage aus, die genau eine Zahl liefert (zum Beispiel count(*)), und gibt diese Zahl zurueck. */
    public static long queryNumber(String sql) throws SQLException {
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * Traegt Datenbank und Broker der Testcontainer in eine Spring-Anwendung ein. Wird von
     * jedem Test aufgerufen, der die ganze Anwendung startet. Die Anwendung liest dieselben
     * Einstellungen sonst aus Umgebungsvariablen.
     */
    public static void registerWith(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", TestInfrastructure::databaseUser);
        registry.add("spring.datasource.password", TestInfrastructure::databasePassword);
        registry.add("spring.rabbitmq.host", TestInfrastructure::brokerHost);
        registry.add("spring.rabbitmq.port", TestInfrastructure::brokerPort);
        registry.add("spring.rabbitmq.username", TestInfrastructure::brokerUser);
        registry.add("spring.rabbitmq.password", TestInfrastructure::brokerPassword);
    }

    /**
     * Laesst die Datenbank "ausfallen": sie nimmt keine neuen Verbindungen mehr an, und alle
     * bestehenden werden getrennt. Fuer den Dienst sieht das aus wie eine Datenbank, die weg ist
     * oder neu startet. Die Verbindung dafuer geht auf die Standard-Datenbank "postgres", weil man
     * sich mit der gesperrten Datenbank selbst nicht mehr verbinden kann.
     */
    public static void startDatabaseOutage() throws SQLException {
        String lock = "ALTER DATABASE " + DATABASE_NAME + " ALLOW_CONNECTIONS false";
        String disconnect = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                          + "WHERE datname = '" + DATABASE_NAME + "' AND pid <> pg_backend_pid()";

        try (Connection connection = openAdminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(lock);
            statement.execute(disconnect);
        }
    }

    /** Beendet den Ausfall: die Datenbank nimmt wieder Verbindungen an. */
    public static void endDatabaseOutage() throws SQLException {
        try (Connection connection = openAdminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE " + DATABASE_NAME + " ALLOW_CONNECTIONS true");
        }
    }

    /** Verbindung zur Standard-Datenbank "postgres" desselben Servers, fuer Eingriffe von aussen. */
    private static Connection openAdminConnection() throws SQLException {
        String adminUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres";
        return DriverManager.getConnection(adminUrl, DATABASE_USER, DATABASE_PASSWORD);
    }

    /** Adresse des Test-Brokers vom Testrechner aus (Docker vergibt den Port zufaellig). */
    public static String brokerHost() {
        return RABBIT.getHost();
    }

    /** AMQP-Port des Test-Brokers auf dem Testrechner. */
    public static int brokerPort() {
        return RABBIT.getAmqpPort();
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
     * Zaehlt, wie viele Nachrichten gerade bereit in einer Queue liegen. Dazu deklariert sie die
     * Queue "passiv" (nur nachfragen, nichts anlegen). Die Antwort ist immer aktuell. Die
     * Management-API dagegen rechnet ihre Zahlen nur alle paar Sekunden neu.
     */
    public static int countMessages(String queueName) throws IOException, TimeoutException {
        try (com.rabbitmq.client.Connection brokerConnection = newBrokerConnection();
             Channel channel = brokerConnection.createChannel()) {
            return channel.queueDeclarePassive(queueName).getMessageCount();
        }
    }

    /**
     * Wartet bis zu der angegebenen Zeit, bis eine Abfrage genau die erwartete Zahl liefert. Es wird
     * alle 100 Millisekunden nachgefragt, nie mit einem festen Schlaf gewartet: der Test ist so
     * schnell wie das System und trotzdem nicht zu ungeduldig. Ist die Zeit um, gibt die Methode den
     * zuletzt gelesenen Wert zurueck, und der Test zeigt dann die echte Abweichung.
     */
    public static long waitUntilNumberIs(String sql, long expected, int timeoutSeconds) throws SQLException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        long lastValue = queryNumber(sql);
        while (lastValue != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            lastValue = queryNumber(sql);
        }
        return lastValue;
    }

    /**
     * Wartet, bis in der Queue weder wartende noch unbestaetigte Nachrichten sind. Dafuer nimmt sie die
     * Management-API (Feld "messages"), denn nur die zaehlt auch unbestaetigte mit. Sie rechnet ihre
     * Zahlen alle paar Sekunden neu, deshalb ist die Frist grosszuegig. Gibt die zuletzt gelesene Zahl zurueck.
     */
    public static int waitUntilQueueIsEmpty(String queueName, int timeoutSeconds) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int lastValue = askBroker("queues/%2F/" + queueName).path("messages").asInt(-1);
        while (lastValue != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            lastValue = askBroker("queues/%2F/" + queueName).path("messages").asInt(-1);
        }
        return lastValue;
    }

    /**
     * Wartet, bis in der Queue genau die erwartete Zahl wartender Nachrichten liegt. Gibt die zuletzt
     * gelesene Zahl zurueck. Gebraucht fuer chat.dlq: das Weiterreichen dorthin geschieht im Broker
     * asynchron und dauert einen kurzen Moment.
     */
    public static int waitUntilMessageCountIs(String queueName, int expected, int timeoutSeconds) throws IOException, TimeoutException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int lastValue = countMessages(queueName);
        while (lastValue != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            lastValue = countMessages(queueName);
        }
        return lastValue;
    }

    /** Holt die vorderste Nachricht aus einer Queue und gibt ihren Koerper als Text zurueck (null, wenn keine da ist). */
    public static String takeOneBody(String queueName) throws IOException, TimeoutException {
        try (com.rabbitmq.client.Connection brokerConnection = newBrokerConnection();
             Channel channel = brokerConnection.createChannel()) {
            com.rabbitmq.client.GetResponse response = channel.basicGet(queueName, true);
            if (response == null) {
                return null;
            }
            return new String(response.getBody(), StandardCharsets.UTF_8);
        }
    }

    /** Leert eine Queue. Tests, die die Dead-Letter-Queue zaehlen, starten damit von null. */
    public static void purgeQueue(String queueName) throws IOException, TimeoutException {
        try (com.rabbitmq.client.Connection brokerConnection = newBrokerConnection();
             Channel channel = brokerConnection.createChannel()) {
            channel.queuePurge(queueName);
        }
    }

    /**
     * Legt Nachrichtenkoerper in eine Queue, so wie es ein Skript von Hand taete: nur mit dem
     * Header content_type = application/json, ohne __TypeId__. Ueber den Standard-Exchange ("")
     * kommt eine Nachricht direkt in die Queue, deren Name der Routing-Key ist.
     */
    public static void publishJson(String exchange, String routingKey, List<String> bodies) throws IOException, TimeoutException {
        AMQP.BasicProperties onlyContentType = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .build();

        try (com.rabbitmq.client.Connection brokerConnection = newBrokerConnection();
             Channel channel = brokerConnection.createChannel()) {
            for (String body : bodies) {
                channel.basicPublish(exchange, routingKey, onlyContentType, body.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** Oeffnet eine Verbindung zum Test-Broker. Der Aufrufer schliesst sie wieder. */
    private static com.rabbitmq.client.Connection newBrokerConnection() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(BROKER_USER);
        factory.setPassword(BROKER_PASSWORD);
        return factory.newConnection();
    }

    /**
     * Fragt die Management-API des Brokers und gibt die Antwort als JSON zurueck.
     * Beispiel fuer den Pfad: "queues/%2F/chat.persist". So sehen wir im Test dieselben
     * Zahlen wie in der Management-Oberflaeche.
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
