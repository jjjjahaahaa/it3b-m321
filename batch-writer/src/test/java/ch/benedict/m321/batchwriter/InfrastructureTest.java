package ch.benedict.m321.batchwriter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Grundlage, auf der alle anderen Tests stehen: dass der Broker beim Start wirklich
 * Exchange, Queues und Bindung aus rabbitmq/definitions.json anlegt und dass die Datenbank die
 * Tabelle message aus db/01-schema.sql hat. Stimmt hier etwas nicht, sind alle weiteren
 * Testergebnisse wertlos.
 */
class InfrastructureTest {

    /**
     * chat.persist muss eine Quorum-Queue sein, drei Zustellungen erlauben und abgelehnte
     * Nachrichten ueber den Standard-Exchange an chat.dlq weiterreichen.
     */
    @Test
    void persistQueueIsQuorumQueueWithDeliveryLimitAndDeadLetter() throws Exception {
        JsonNode queue = TestInfrastructure.askBroker("queues/%2F/chat.persist");

        assertThat(queue.get("type").asText()).isEqualTo("quorum");
        assertThat(queue.get("durable").asBoolean()).isTrue();

        JsonNode arguments = queue.get("arguments");
        assertThat(arguments.get("x-delivery-limit").asInt()).isEqualTo(3);
        assertThat(arguments.get("x-dead-letter-exchange").asText()).isEmpty();
        assertThat(arguments.get("x-dead-letter-routing-key").asText()).isEqualTo("chat.dlq");
    }

    /** chat.dlq muss es geben und sie muss dauerhaft sein, sonst gingen abgelehnte Nachrichten verloren. */
    @Test
    void deadLetterQueueExistsAndIsDurable() throws Exception {
        JsonNode queue = TestInfrastructure.askBroker("queues/%2F/chat.dlq");

        assertThat(queue.get("durable").asBoolean()).isTrue();
        assertThat(TestInfrastructure.countMessages("chat.dlq")).isZero();
    }

    /** Was der chat-service auf den Exchange publiziert, muss in chat.persist ankommen. */
    @Test
    void persistQueueIsBoundToTheFanoutExchange() throws Exception {
        JsonNode exchange = TestInfrastructure.askBroker("exchanges/%2F/chat.messages");
        assertThat(exchange.get("type").asText()).isEqualTo("fanout");
        assertThat(exchange.get("durable").asBoolean()).isTrue();

        JsonNode bindings = TestInfrastructure.askBroker("exchanges/%2F/chat.messages/bindings/source");
        List<String> targets = new ArrayList<>();
        for (JsonNode binding : bindings) {
            targets.add(binding.get("destination").asText());
        }
        assertThat(targets).contains("chat.persist");
    }

    /** Die Tabelle message hat genau die fuenf Spalten aus PLANUNG.md, Abschnitt 3. */
    @Test
    void messageTableHasTheFiveColumns() throws Exception {
        String sql = "SELECT column_name FROM information_schema.columns "
                   + "WHERE table_name = 'message' ORDER BY column_name";
        List<String> columns = new ArrayList<>();

        try (Connection connection = TestInfrastructure.openDatabaseConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                columns.add(result.getString("column_name"));
            }
        }

        assertThat(columns).containsExactlyInAnyOrder("id", "room_id", "sender", "text", "sent_at");
    }
}
