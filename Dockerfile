# syntax=docker/dockerfile:1

########################################
# Stage 1 : build de l'application
########################################
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# On copie d'abord le pom pour profiter du cache Docker sur les dépendances.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Puis le code source, et on package le jar exécutable.
COPY src ./src
RUN mvn -B clean package -DskipTests

########################################
# Stage 2 : image d'exécution
########################################
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Fuseau horaire nécessaire au bon calcul des heures d'ouverture des réservations.
ENV TZ=Europe/Paris

# Exécution avec un utilisateur non-root. Le planning édité depuis l'IHM est écrit
# dans /app/data : le dossier doit donc appartenir à appuser (monte-le en volume
# pour survivre à une reconstruction de l'image).
RUN useradd --system --create-home --uid 1001 appuser \
    && mkdir -p /app/data && chown appuser /app/data
USER appuser
VOLUME ["/app/data"]

# IHM d'administration. Par défaut l'appli n'écoute que sur 127.0.0.1 : dans un
# conteneur il faut 0.0.0.0 pour que le port publié soit joignable (docker-compose
# le restreint alors à la boucle locale de l'hôte).
EXPOSE 8080

# Récupération du jar buildé au stage précédent.
COPY --from=build /app/target/resa-bot-1.0.0.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]