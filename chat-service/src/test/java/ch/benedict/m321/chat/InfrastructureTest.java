package ch.benedict.m321.chat;

import ch.benedict.m321.chat.support.IntegrationTest;
import ch.benedict.m321.chat.support.TestInfrastructure;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Grundlage, auf der alle weiteren Tests des chat-service stehen: der Dienst ist mit
 * einer echten PostgreSQL und einem echten RabbitMQ in Containern verbunden. Stimmt das nicht,
 * sagen alle anderen Testergebnisse nichts.
 */
class InfrastructureTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * Der Dienst haengt an der Test-Datenbank, und dort steht die Tabelle message aus
     * db/01-schema.sql, also aus derselben Datei wie im Compose-Stack.
     */
    @Test
    void serviceIsConnectedToTheDatabaseContainerWithTheSchema() {
        String sql = "SELECT count(*) FROM information_schema.tables WHERE table_name = 'message'";

        Integer tables = jdbcTemplate.queryForObject(sql, Integer.class);

        assertThat(tables).isEqualTo(1);
    }

    /**
     * Sobald der Dienst sich mit dem Broker verbindet, meldet er den Exchange chat.messages an.
     * Die Management-API muss ihn danach als dauerhaften Fanout-Exchange zeigen.
     */
    @Test
    void exchangeChatMessagesExistsAsFanout() throws Exception {
        // Spring meldet Exchange und Queues erst beim ersten Verbindungsaufbau an. Dieser Aufruf
        // oeffnet die Verbindung, ohne etwas zu senden.
        rabbitTemplate.execute(channel -> null);

        JsonNode exchange = TestInfrastructure.askBroker("exchanges/%2F/chat.messages");

        assertThat(exchange.get("type").asText()).isEqualTo("fanout");
        assertThat(exchange.get("durable").asBoolean()).isTrue();
    }
}
