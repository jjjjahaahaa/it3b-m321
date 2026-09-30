# Baut den chat-service und verpackt ihn in ein schlankes Laufzeit-Image.
# Der Build-Kontext ist das Wurzelverzeichnis des Repositories (siehe docker-compose.yml),
# weil der Dienst den Eltern-POM braucht.

# --- Stufe 1: bauen. Dieses Image ist gross (Maven, JDK) und wird am Ende weggeworfen. ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Zuerst nur die pom.xml kopieren und die Abhaengigkeiten laden. Solange sich die pom.xml nicht
# aendert, bleibt dieser Schritt im Zwischenspeicher, und nur Codeaenderungen bauen neu.
# Der Eltern-POM kennt auch batch-writer, deshalb braucht Maven dessen pom.xml, sonst bricht es ab.
COPY pom.xml ./
COPY chat-service/pom.xml chat-service/
COPY batch-writer/pom.xml batch-writer/
RUN mvn -B -q -pl chat-service -am dependency:resolve

# Jetzt der Quelltext und das eigentliche Bauen. Die Tests laufen hier nicht (-DskipTests):
# sie laufen mit "mvn clean test" auf dem Rechner.
COPY chat-service/src chat-service/src
RUN mvn -B -q -pl chat-service -am -DskipTests package

# --- Stufe 2: laufen. Nur ein JRE und das fertige JAR. curl bringt das Image mit, wir brauchen es
# fuer den Healthcheck und um von innen eine Nachricht zu senden. ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/chat-service/target/*.jar app.jar

# Nicht als root laufen. Die Nummer genuegt, der Dienst braucht keinen Benutzernamen.
USER 1000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
