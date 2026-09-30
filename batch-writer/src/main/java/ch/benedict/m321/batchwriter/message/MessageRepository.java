package ch.benedict.m321.batchwriter.message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Schreibt Nachrichten in die Tabelle message. Bewusst mit reinem JDBC und ohne ORM, damit man
 * jeden Schritt sieht: Verbindung holen, Transaktion eroeffnen, Zeilen sammeln, senden, committen.
 *
 * Ein Paket ist genau EINE Transaktion. Das spart gegenueber einzelnen INSERTs den Commit und
 * (mit reWriteBatchedInserts in der JDBC-Adresse) auch die Netzwerk-Roundtrips.
 */
@Repository
public class MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    /**
     * ON CONFLICT (id) DO NOTHING macht das Schreiben wiederholbar: kommt dieselbe Nachricht ein
     * zweites Mal (nach einem Absturz oder weil sie doppelt gesendet wurde), wird sie einfach
     * uebersprungen. Die Datenbank entscheidet das atomar ueber den Primaerschluessel.
     */
    private static final String INSERT_SQL =
            "INSERT INTO message (id, room_id, sender, text, sent_at) "
          + "VALUES (?, ?, ?, ?, ?) "
          + "ON CONFLICT (id) DO NOTHING";

    private final DataSource dataSource;

    /** Spring reicht den Verbindungspool herein (Konstruktor-Injektion). Im Test ist es eine einfachere Variante. */
    public MessageRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Schreibt alle Nachrichten in einer einzigen Transaktion: entweder stehen alle in der Datenbank
     * oder keine. Wirft BadDataException, wenn die Datenbank eine Zeile wegen ihrer Daten ablehnt,
     * und DatabaseUnavailableException bei jedem anderen Fehler.
     */
    public void insertBatch(List<IncomingMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            writeInOneTransaction(connection, messages);
        } catch (SQLException problem) {
            throw translate(problem);
        }
        log.debug("{} Nachrichten in einer Transaktion geschrieben", messages.size());
    }

    /**
     * Sammelt alle Zeilen in einem JDBC-Batch, sendet sie und bestaetigt mit commit. Scheitert etwas,
     * wird zurueckgerollt, damit keine halbe Transaktion in der Datenbank haengen bleibt.
     */
    private void writeInOneTransaction(Connection connection, List<IncomingMessage> messages) throws SQLException {
        // Ohne diese Zeile wuerde jede einzelne Zeile sofort committet.
        connection.setAutoCommit(false);

        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (IncomingMessage message : messages) {
                fillParameters(statement, message);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException problem) {
            rollbackQuietly(connection);
            throw problem;
        }
    }

    /** Setzt die fuenf Fragezeichen des INSERT in der Reihenfolge der Spalten. */
    private void fillParameters(PreparedStatement statement, IncomingMessage message) throws SQLException {
        statement.setObject(1, message.id());
        statement.setObject(2, message.roomId());
        statement.setString(3, message.sender());
        statement.setString(4, message.text());
        statement.setTimestamp(5, Timestamp.from(message.sentAt()));
    }

    /** Rollt die Transaktion zurueck. Klappt das nicht (Verbindung ist schon weg), ist das egal: die Datenbank verwirft sie dann selbst. */
    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException problem) {
            log.debug("Rollback nicht moeglich, Verbindung ist vermutlich schon weg: {}", problem.getMessage());
        }
    }

    /**
     * Ordnet einen SQL-Fehler ein. Nur die SQLSTATE-Klassen 22 (Datenfehler) und 23 (Verletzung einer
     * Regel wie dem Fremdschluessel) heissen sicher "diese Zeile ist kaputt". ALLES andere gilt als
     * "Datenbank hat ein Problem" und wird wiederholt. Das ist die sichere Richtung: im Zweifel
     * warten wir und verlieren nichts.
     */
    private RuntimeException translate(SQLException problem) {
        String sqlState = findSqlState(problem);
        boolean rowIsBroken = sqlState != null && (sqlState.startsWith("22") || sqlState.startsWith("23"));

        if (rowIsBroken) {
            return new BadDataException("Datenbank lehnt die Zeile ab (SQLSTATE " + sqlState + ")", problem);
        }
        return new DatabaseUnavailableException("Schreiben nicht moeglich (SQLSTATE " + sqlState + ")", problem);
    }

    /**
     * Sucht den SQLSTATE-Code. Bei einem JDBC-Batch steckt er manchmal nicht in der Ausnahme selbst,
     * sondern in der Folge-Ausnahme, deshalb wird die Kette abgelaufen.
     */
    private String findSqlState(SQLException problem) {
        SQLException current = problem;
        while (current != null) {
            if (current.getSQLState() != null) {
                return current.getSQLState();
            }
            current = current.getNextException();
        }
        return null;
    }
}
