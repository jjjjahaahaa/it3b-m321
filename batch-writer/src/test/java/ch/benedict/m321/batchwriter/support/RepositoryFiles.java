package ch.benedict.m321.batchwriter.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Findet Dateien des Repositories, egal ob ein Test aus dem Modulverzeichnis (Maven) oder aus dem
 * Wurzelverzeichnis (IDE) gestartet wurde. Steht bewusst getrennt von TestInfrastructure, denn dort
 * startet schon das blosse Laden der Klasse die Docker-Container.
 */
public final class RepositoryFiles {

    /** Privat, damit niemand ein Objekt erzeugt: die Klasse hat nur statische Hilfsmethoden. */
    private RepositoryFiles() {
        // Nichts zu tun.
    }

    /**
     * Sucht eine Datei oder ein Verzeichnis des Repositories. Dazu geht sie vom aktuellen Verzeichnis
     * so lange nach oben, bis der gesuchte relative Pfad dort existiert.
     */
    public static Path find(String relativePath) {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Datei nicht gefunden: " + relativePath);
    }
}
