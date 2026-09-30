package ch.benedict.m321.batchwriter.message;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.batchwriter.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testet das Schreiben gegen eine echte PostgreSQL (siehe TestInfrastructure). Geprueft wird
 * genau das, worauf der Rest des Dienstes baut: ein Paket ist eine Transaktion, eine Dublette
 * schadet nie, und jeder Fehler wird richtig eingeordnet (Zeile kaputt oder Datenbank weg).
 */
class MessageRepositoryTest {

    private final MessageRepository repository = new MessageRepository(realDataSource());

    /** Verbindet sich mit der Test-Datenbank, ohne Pool: jeder Aufruf oeffnet eine frische Verbindung. */
    private DriverManagerDataSource realDataSource() {
        return new DriverManagerDataSource(
                TestInfrastructure.jdbcUrl(),
                TestInfrastructure.databaseUser(),
                TestInfrastructure.databasePassword());
    }

    /** Baut eine gueltige Nachricht mit frischer ID fuer den angegebenen Raum. */
    private IncomingMessage newMessage(UUID roomId, String text) {
        return new IncomingMessage(UUID.randomUUID(), roomId, "tester", text, Instant.now());
    }

    /** Zaehlt die Nachrichten eines Raums in der Datenbank. */
    private long countInRoom(UUID roomId) throws SQLException {
        return TestInfrastructure.queryNumber("SELECT count(*) FROM message WHERE room_id = '" + roomId + "'");
    }

    /**
     * Fragt die aktuelle Transaktionsnummer der Datenbank. Jeder Aufruf verbraucht selbst eine.
     * Liegen zwischen zwei Aufrufen genau (Differenz minus 1) schreibende Transaktionen,
     * so laesst sich zaehlen, wie viele Transaktionen ein Paket gebraucht hat.
     */
    private long currentTransactionNumber() throws SQLException {
        return TestInfrastructure.queryNumber("SELECT txid_current()");
    }

    /** Ein Paket aus 500 Nachrichten muss genau eine Transaktion sein, nicht 500. Das ist der Zweck des Dienstes. */
    @Test
    void writesFiveHundredMessagesInExactlyOneTransaction() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        List<IncomingMessage> batch = new ArrayList<>();
        for (int number = 0; number < 500; number++) {
            batch.add(newMessage(roomId, "Nachricht " + number));
        }

        long before = currentTransactionNumber();
        repository.insertBatch(batch);
        long after = currentTransactionNumber();

        assertThat(countInRoom(roomId)).isEqualTo(500);
        long transactionsUsedByTheBatch = after - before - 1;
        assertThat(transactionsUsedByTheBatch).isEqualTo(1);
    }

    /** Eine leere Liste ist erlaubt und macht nichts. Es wird nicht einmal eine Transaktion eroeffnet. */
    @Test
    void emptyBatchDoesNothing() throws Exception {
        long before = currentTransactionNumber();
        repository.insertBatch(List.of());
        long after = currentTransactionNumber();

        assertThat(after - before - 1).isZero();
    }

    /** Dieselbe ID in einem spaeteren Paket aendert nichts: die zuerst gespeicherte Fassung bleibt. */
    @Test
    void duplicateInLaterBatchIsIgnoredAndFirstVersionStays() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        UUID sameId = UUID.randomUUID();
        IncomingMessage first = new IncomingMessage(sameId, roomId, "tester", "erste Fassung", Instant.now());
        IncomingMessage second = new IncomingMessage(sameId, roomId, "tester", "zweite Fassung", Instant.now());

        repository.insertBatch(List.of(first));
        repository.insertBatch(List.of(second));

        assertThat(countInRoom(roomId)).isEqualTo(1);
        long unchanged = TestInfrastructure.queryNumber(
                "SELECT count(*) FROM message WHERE id = '" + sameId + "' AND text = 'erste Fassung'");
        assertThat(unchanged).isEqualTo(1);
    }

    /** Dieselbe ID darf sogar zweimal im selben Paket stehen (Szenario S5: zwei gleiche Nachrichten hintereinander). */
    @Test
    void duplicateInsideTheSameBatchIsWrittenOnce() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        IncomingMessage message = newMessage(roomId, "doppelt");

        repository.insertBatch(List.of(message, message));

        assertThat(countInRoom(roomId)).isEqualTo(1);
    }

    /**
     * Ein unbekannter Raum verletzt den Fremdschluessel (SQLSTATE 23503). Das ist ein Datenfehler.
     * Wichtig: das ganze Paket wird zurueckgerollt, auch die gueten Nachrichten davor.
     */
    @Test
    void unknownRoomIsBadDataAndRollsBackTheWholeBatch() throws Exception {
        UUID goodRoom = TestInfrastructure.createRoom();
        UUID unknownRoom = UUID.randomUUID();
        List<IncomingMessage> batch = List.of(
                newMessage(goodRoom, "gut 1"),
                newMessage(unknownRoom, "kaputt"),
                newMessage(goodRoom, "gut 2"));

        assertThatThrownBy(() -> repository.insertBatch(batch))
                .isInstanceOf(BadDataException.class);

        assertThat(countInRoom(goodRoom)).isZero();
    }

    /** Ein Absender ueber 100 Zeichen ist zu lang fuer die Spalte (SQLSTATE 22001), also ein Datenfehler. */
    @Test
    void tooLongSenderIsBadData() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        String tooLong = "x".repeat(101);
        IncomingMessage message = new IncomingMessage(UUID.randomUUID(), roomId, tooLong, "text", Instant.now());

        assertThatThrownBy(() -> repository.insertBatch(List.of(message)))
                .isInstanceOf(BadDataException.class);
    }

    /** Das Nullzeichen darf PostgreSQL nicht speichern (SQLSTATE 22021). Auch das ist ein Datenfehler. */
    @Test
    void nulCharacterInTextIsBadData() throws Exception {
        UUID roomId = TestInfrastructure.createRoom();
        IncomingMessage message = newMessage(roomId, "vor\u0000nach");

        assertThatThrownBy(() -> repository.insertBatch(List.of(message)))
                .isInstanceOf(BadDataException.class);
    }

    /** Ist die Datenbank nicht erreichbar, ist das KEIN Fehler der Nachricht: der Aufrufer soll warten und es nochmals versuchen. */
    @Test
    void unreachableDatabaseIsReportedAsUnavailable() {
        DriverManagerDataSource nobodyListensHere = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:1/chat?connectTimeout=2", "chat", "chat");
        MessageRepository brokenRepository = new MessageRepository(nobodyListensHere);
        IncomingMessage message = newMessage(UUID.randomUUID(), "wartet");

        assertThatThrownBy(() -> brokenRepository.insertBatch(List.of(message)))
                .isInstanceOf(DatabaseUnavailableException.class);
    }
}
