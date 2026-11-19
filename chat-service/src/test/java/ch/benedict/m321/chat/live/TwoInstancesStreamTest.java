package ch.benedict.m321.chat.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import ch.benedict.m321.chat.ChatServiceApplication;
import ch.benedict.m321.chat.support.StreamClient;
import ch.benedict.m321.chat.support.TestInfrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pflichttest fuer zwei Instanzen (Schritt 3 in docs/plan-live-zustellung.md): der chat-service laeuft
 * zweimal am selben RabbitMQ und an derselben Datenbank, jede Instanz auf einem eigenen Zufallsport.
 * Eine Nachricht, die ueber Instanz A hereinkommt, muss bei den Streams BEIDER Instanzen ankommen.
 * Das geht nur, wenn jede Instanz ihre eigene Live-Queue am Fanout-Exchange hat.
 */
class TwoInstancesStreamTest {

    private static final String ROOM_ID = "11111111-1111-1111-1111-111111111111";

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Startet eine Instanz des chat-service, verbunden mit den Testcontainern. server.port = 0 heisst:
     * das Betriebssystem waehlt einen freien Port, damit sich die beiden Instanzen nicht in die Quere kommen.
     *
     * Die Werte gehen als Startargumente mit (--name=wert), nicht ueber properties(): die waeren nur
     * Standardwerte, und application.yml wuerde sie mit Port 8080 und localhost ueberschreiben.
     */
    private static ConfigurableApplicationContext startInstance() {
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

    /** Startet beide Instanzen einmal fuer alle Tests dieser Klasse. */
    @BeforeAll
    static void startTwoInstances() {
        instanceA = startInstance();
        instanceB = startInstance();
    }

    /** Die Instanzen hat der Test selbst gestartet, also macht er sie am Ende auch wieder zu. */
    @AfterAll
    static void stopTwoInstances() {
        instanceA.close();
        instanceB.close();
    }

    /** Die Adresse einer Instanz mit dem Port, den das Betriebssystem ihr gegeben hat. */
    private static String baseAddress(ConfigurableApplicationContext instance) {
        ServletWebServerApplicationContext webContext = (ServletWebServerApplicationContext) instance;
        int port = webContext.getWebServer().getPort();
        return "http://localhost:" + port;
    }

    /** Wartet, bis eine Instanz die erwartete Zahl offener Streams kennt, hoechstens 10 Sekunden. */
    private void waitForOpenStreams(ConfigurableApplicationContext instance, int expected) throws InterruptedException {
        StreamRegistry registry = instance.getBean(StreamRegistry.class);
        for (int attempt = 0; attempt < 200 && registry.openStreams() < expected; attempt++) {
            Thread.sleep(50);
        }
    }

    /** Sendet eine Nachricht ueber POST /api/messages an eine Instanz und gibt den Antwort-Text zurueck. */
    private String send(ConfigurableApplicationContext instance, String text) throws Exception {
        String body = "{\"roomId\":\"" + ROOM_ID + "\",\"sender\":\"schritt3\",\"text\":\"" + text + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseAddress(instance) + "/api/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return response.body();
    }

    /**
     * Eine Nachricht ueber Instanz A kommt bei den Streams an A UND an B an, mit derselben id. Bei
     * data: steht genau das JSON, das der chat-service publiziert hat. Es ist dasselbe wie in der
     * Antwort auf den POST und wie in chat.persist.
     */
    @Test
    void messageSentToInstanceAReachesStreamsAtBothInstances() throws Exception {
        StreamClient streamAtA = new StreamClient(baseAddress(instanceA) + "/stream?roomId=" + ROOM_ID);
        StreamClient streamAtB = new StreamClient(baseAddress(instanceB) + "/stream?roomId=" + ROOM_ID);
        waitForOpenStreams(instanceA, 1);
        waitForOpenStreams(instanceB, 1);

        String published = send(instanceA, "an beide");
        String messageId = json.readTree(published).get("id").asText();

        assertThat(streamAtA.waitForLine("data:" + published, 10)).isTrue();
        assertThat(streamAtB.waitForLine("data:" + published, 10)).isTrue();
        assertThat(streamAtA.lines()).contains("id:" + messageId);
        assertThat(streamAtB.lines()).contains("id:" + messageId);

        streamAtA.close();
        streamAtB.close();
    }

    /**
     * Z1 aus der Spezifikation: der Content-Type ist text/event-stream, und ein Ereignis besteht aus
     * id:, data: und einer Leerzeile, die das Ereignis abschliesst.
     */
    @Test
    void eventHasIdDataAndBlankLine() throws Exception {
        StreamClient stream = new StreamClient(baseAddress(instanceB) + "/stream?roomId=" + ROOM_ID);
        waitForOpenStreams(instanceB, 1);

        String published = send(instanceA, "Format");
        String messageId = json.readTree(published).get("id").asText();
        assertThat(stream.waitForLine("data:" + published, 10)).isTrue();

        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentType()).startsWith("text/event-stream");
        List<String> lines = stream.lines();
        int idLine = lines.indexOf("id:" + messageId);
        assertThat(idLine).isGreaterThanOrEqualTo(0);
        assertThat(lines.get(idLine + 1)).isEqualTo("data:" + published);
        assertThat(lines.get(idLine + 2)).isEmpty();

        stream.close();
    }

    /** Ohne roomId kann der Stream keinem Raum zugeordnet werden, deshalb 400. */
    @Test
    void streamWithoutRoomIdIsRejectedWith400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseAddress(instanceA) + "/stream")).GET().build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
