package ch.benedict.m321.chat.live;

import java.util.UUID;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Ein offener Stream zusammen mit dem Raum, fuer den der Client ihn geoeffnet hat. Den Raum braucht
 * es beim Verteilen: ein Stream bekommt nur Nachrichten, deren roomId zu seinem Raum passt (Spec 2.1).
 */
public record LiveStream(SseEmitter emitter, UUID roomId) {
}
