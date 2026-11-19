package ch.benedict.m321.chat.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ein einfacher Client fuer GET /stream, so wie ein Browser ihn oeffnet. Er liest die Antwort Zeile
 * fuer Zeile in einem eigenen Thread und sammelt alle Zeilen, damit der Test sie danach pruefen kann.
 *
 * Die Antwort wird bewusst als InputStream gelesen und nicht mit BodyHandlers.ofLines(): das liefert
 * einen Java-Stream, und Streams sind im Projekt verboten (CLAUDE.md, CommentRulesTest).
 *
 * Geoeffnet wird asynchron: der Server schickt die Kopfzeilen erst mit dem ersten Ereignis. Wuerde der
 * Konstruktor auf sie warten, warteten Test und Server aufeinander, und der Test haenge fuer immer.
 */
public class StreamClient {

    /** So lange wartet der Test hoechstens auf die Antwort-Kopfzeilen, danach gilt der Stream als kaputt. */
    private static final int HEADER_TIMEOUT_SECONDS = 20;

    private final List<String> lines = new ArrayList<>();
    private final CompletableFuture<HttpResponse<InputStream>> pendingResponse;
    private final Thread reader;

    /**
     * Schickt die Anfrage ab, ohne auf die Antwort zu warten. Ein eigener Thread wartet auf sie und
     * liest danach jede ankommende Zeile mit, bis der Stream zugeht oder close() aufgerufen wird.
     */
    public StreamClient(String address) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)).GET().build();
        pendingResponse = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        reader = new Thread(this::readAllLines);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Wartet auf die Antwort-Kopfzeilen, hoechstens HEADER_TIMEOUT_SECONDS. Kommen sie nicht, bricht
     * der Test mit einer klaren Meldung ab, statt fuer immer zu warten.
     */
    private HttpResponse<InputStream> response() {
        try {
            return pendingResponse.get(HEADER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException problem) {
            throw new IllegalStateException("Keine Antwort vom Stream", problem);
        }
    }

    /** Liest Zeile fuer Zeile, bis der Server den Stream schliesst oder die Verbindung abbricht. */
    private void readAllLines() {
        HttpResponse<InputStream> response;
        try {
            response = response();
        } catch (IllegalStateException noAnswer) {
            return;
        }
        InputStreamReader decoder = new InputStreamReader(response.body(), StandardCharsets.UTF_8);
        try (BufferedReader input = new BufferedReader(decoder)) {
            String line = input.readLine();
            while (line != null) {
                // Der Test-Thread liest die Liste gleichzeitig, deshalb unter derselben Sperre.
                synchronized (lines) {
                    lines.add(line);
                }
                line = input.readLine();
            }
        } catch (IOException closed) {
            // Der Stream wurde geschlossen. Das ist am Ende jedes Tests so gewollt.
        }
    }

    /** HTTP-Status der Antwort. Wartet, falls die Kopfzeilen noch nicht da sind. */
    public int status() {
        return response().statusCode();
    }

    /** Content-Type der Antwort, leer, wenn keiner gesetzt ist. Wartet, falls die Kopfzeilen noch nicht da sind. */
    public String contentType() {
        Optional<String> contentType = response().headers().firstValue("Content-Type");
        if (contentType.isPresent()) {
            return contentType.get();
        }
        return "";
    }

    /** Eine Kopie aller bisher empfangenen Zeilen, in der Reihenfolge ihrer Ankunft. */
    public List<String> lines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    /**
     * Wartet hoechstens die angegebene Zeit, bis eine Zeile genau diesen Text hat. Gibt zurueck, ob
     * sie angekommen ist. Gefragt wird alle 50 Millisekunden.
     */
    public boolean waitForLine(String wanted, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (lines().contains(wanted)) {
                return true;
            }
            Thread.sleep(50);
        }
        return lines().contains(wanted);
    }

    /**
     * Schliesst den Stream von der Client-Seite, so wie ein Browser, dessen Tab zugeht. Ist noch gar
     * keine Antwort da, wird die Anfrage abgebrochen.
     */
    public void close() throws IOException {
        if (pendingResponse.isDone() && !pendingResponse.isCompletedExceptionally()) {
            response().body().close();
        } else {
            pendingResponse.cancel(true);
        }
    }
}
