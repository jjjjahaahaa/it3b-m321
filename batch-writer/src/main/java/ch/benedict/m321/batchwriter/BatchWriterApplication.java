package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer. Der Dienst hat keinen Webserver und keinen Port: er haengt
 * nur als Konsument an der Queue chat.persist und schreibt, was dort ankommt, in die Datenbank.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Uebergibt die Startklasse an Spring Boot. Von hier aus werden alle Klassen dieses
     * Pakets und der Unterpakete gefunden und zusammengebaut.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
