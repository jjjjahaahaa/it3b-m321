package ch.benedict.m321.chat.live;

/**
 * Eine Live-Nachricht in der Warteliste eines Streams: ihre id und das JSON genau so, wie es aus RabbitMQ
 * kam. Mehr braucht es nicht, um sie spaeter zu schicken oder als schon nachgeholt zu erkennen.
 */
public record WaitingMessage(String id, String json) {
}
