# Baut den batch-writer und verpackt ihn in ein schlankes Laufzeit-Image.
# Der Build-Kontext ist das Wurzelverzeichnis des Repositories (siehe docker-compose.yml),
# weil der Dienst den Eltern-POM braucht.

# --- Stufe 1: bauen. Dieses Image ist gross (Maven, JDK) und wird am Ende weggeworfen. ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Zuerst nur die pom.xml kopieren und die Abhaengigkeiten laden. Solange sich die pom.xml nicht
# aendert, bleibt dieser Schritt im Zwischenspeicher, und nur Codeaenderungen bauen neu.
# Der Eltern-POM kennt auch chat-service, deshalb braucht Maven dessen pom.xml, sonst bricht es ab.
COPY pom.xml ./
COPY chat-service/pom.xml chat-service/
COPY batch-writer/pom.xml batch-writer/
RUN mvn -B -q -pl batch-writer -am dependency:resolve

# Jetzt der Quelltext und das eigentliche Bauen. Die Tests laufen hier nicht (-DskipTests):
# sie brauchen Docker und laufen mit "mvn clean test" auf dem Rechner.
COPY batch-writer/src batch-writer/src
RUN mvn -B -q -pl batch-writer -am -DskipTests package

# --- Stufe 2: laufen. Nur ein JRE und das fertige JAR. ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/batch-writer/target/*.jar app.jar

# Nicht als root laufen. Die Nummer genuegt, der Dienst braucht keinen Benutzernamen.
USER 1000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
