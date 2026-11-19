package ch.benedict.m321.chat.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

/**
 * Ersetzt in den Tests den batch-writer: haengt eine eigene Queue an chat.messages und schreibt jede
 * Nachricht so in die Tabelle message, wie es der batch-writer tut (INSERT ... ON CONFLICT DO NOTHING).
 * Das gehoert nur in den Testcode. Der chat-service selbst schreibt nie in die Tabelle.
 *
 * Den Namen der Queue vergibt der Broker, und sie ist exklusiv. So hat jeder Test seine eigene, und sie
 * verschwindet, sobald stop() die Verbindung schliesst.
 */
public class TestPersistWriter {

    private static final String INSERT_SQL =
            "INSERT INTO message (id, room_id, sender, text, sent_at) VALUES (?, ?, ?, ?, ?) "
          + "ON CONFLICT (id) DO NOTHING";

    private final ObjectMapper json = new ObjectMapper();
    private final String jdbcUrl;
    private com.rabbitmq.client.Connection brokerConnection;

    /** Merkt sich, in welche Datenbank geschrieben wird. Gestartet wird erst mit start(). */
    public TestPersistWriter(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Verbindet sich mit dem Test-Broker, legt die eigene Queue an, bindet sie an chat.messages und
     * beginnt zu lesen. Der Exchange muss schon existieren, das erledigt eine laufende Instanz.
     */
    public void start() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(TestInfrastructure.brokerHost());
        factory.setPort(TestInfrastructure.brokerPort());
        factory.setUsername(TestInfrastructure.brokerUser());
        factory.setPassword(TestInfrastructure.brokerPassword());
        brokerConnection = factory.newConnection();
        Channel channel = brokerConnection.createChannel();

        // Leerer Name: der Broker vergibt einen eindeutigen. Nicht dauerhaft, exklusiv, automatisch geloescht.
        String queueName = channel.queueDeclare("", false, true, true, null).getQueue();
        channel.queueBind(queueName, "chat.messages", "");

        DeliverCallback writeToTable = (consumerTag, delivery) -> write(delivery.getBody());
        channel.basicConsume(queueName, true, writeToTable, consumerTag -> { });
    }

    /** Schreibt eine Nachricht in die Tabelle. Klappt das nicht, wird es ausgegeben und die Nachricht uebersprungen. */
    private void write(byte[] body) {
        try (Connection database = DriverManager.getConnection(jdbcUrl, TestInfrastructure.databaseUser(),
                TestInfrastructure.databasePassword());
             PreparedStatement statement = database.prepareStatement(INSERT_SQL)) {
            JsonNode message = json.readTree(new String(body, StandardCharsets.UTF_8));
            statement.setObject(1, UUID.fromString(message.get("id").asText()));
            statement.setObject(2, UUID.fromString(message.get("roomId").asText()));
            statement.setString(3, message.get("sender").asText());
            statement.setString(4, message.get("text").asText());
            statement.setTimestamp(5, Timestamp.from(Instant.parse(message.get("sentAt").asText())));
            statement.executeUpdate();
        } catch (SQLException | IOException | RuntimeException problem) {
            System.out.println("TestPersistWriter konnte nicht schreiben: " + problem.getMessage());
        }
    }

    /** Schliesst die Verbindung. Die Queue verschwindet damit. */
    public void stop() throws IOException {
        brokerConnection.close();
    }
}
