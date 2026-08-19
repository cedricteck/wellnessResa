ARG BUILD_FROM=ghcr.io/home-assistant/amd64-base-debian:bookworm

########################################
# Stage 1 : build du jar
########################################
# Le contexte de build d'un add-on est CE dossier : impossible de copier ../src.
# On récupère donc les sources depuis GitHub. Le ADD sur l'API GitHub sert de
# cache-buster : dès que la branche avance, son contenu change et Docker refait
# le clone au lieu de resservir une couche périmée.
FROM maven:3.9-eclipse-temurin-17 AS build

ARG RESA_REPO=https://github.com/cedricteck/wellnessResa.git
ARG RESA_REF=master

ADD https://api.github.com/repos/cedricteck/wellnessResa/commits/${RESA_REF} /tmp/resa-ref.json
RUN git clone --depth 1 --branch "${RESA_REF}" "${RESA_REPO}" /src \
    && mvn -B -f /src/pom.xml -DskipTests package \
    && cp /src/target/resa-bot-*.jar /app.jar

########################################
# Stage 2 : image de l'add-on
########################################
FROM ${BUILD_FROM}

# JRE seul (pas de JDK) : l'image reste raisonnable sur un Raspberry Pi.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app.jar /opt/resa-bot/app.jar
COPY run.sh /
RUN chmod a+x /run.sh

CMD [ "/run.sh" ]

LABEL io.hass.name="Resa Bot" \
      io.hass.description="Réservation automatique des cours Wellness Sport Club" \
      io.hass.type="addon" \
      io.hass.version="1.0.0"
