#!/bin/sh
# Wird von Docker regelmaessig im RabbitMQ-Container aufgerufen (siehe healthcheck in docker-compose.yml).
# Der Broker gilt erst als gesund, wenn er laeuft UND die Queuen chat.persist und chat.dlq existieren.
# Erst dann starten chat-service und batch-writer (depends_on: service_healthy). So gibt es kein Zeitfenster,
# in dem der chat-service schon Nachrichten annimmt, waehrend die Queue noch fehlt. Ein Exchange ohne
# Queue wirft Nachrichten still weg.
#
# Warum der Import hier und nicht beim Start (load_definitions)? Mit load_definitions legt RabbitMQ den
# Benutzer aus RABBITMQ_DEFAULT_USER nicht mehr an. Der Import nach dem Start laesst ihn in Ruhe,
# und ein zweiter Import ist harmlos, weil sich nichts aendert.

# Ohne laufenden Broker gibt es nichts zu pruefen. Ein Fehler hier heisst "noch nicht gesund".
rabbitmq-diagnostics -q check_running || exit 1

# Alle Queuen auflisten, jede in einer eigenen Zeile.
existing_queues=$(rabbitmqctl -q list_queues name) || exit 1

for wanted_queue in chat.persist chat.dlq; do
    if ! echo "$existing_queues" | grep -qx "$wanted_queue"; then
        # Fehlt eine Queue, Definitionen einspielen. Der Import laeuft im Broker asynchron. Deshalb
        # melden wir diesmal "nicht gesund". Beim naechsten Aufruf ist die Queue da.
        rabbitmqctl -q import_definitions /etc/rabbitmq/definitions.json
        exit 1
    fi
done
