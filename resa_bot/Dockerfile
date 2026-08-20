# Image de l'add-on, construite HORS de la machine Home Assistant puis publiée sur
# un registre — voir publish.sh, qui est le point d'entrée.
#
# Le jar est compilé sur le poste de développement (proxy et cache Maven déjà en
# place) puis déposé ici sous le nom app.jar : ce Dockerfile ne fait plus que
# l'emballer. La machine Home Assistant ne compile plus rien, elle tire l'image.
#
# Équivalent manuel, depuis ce dossier :
#   mvn -B -DskipTests package -f ../pom.xml && cp ../target/resa-bot-*.jar app.jar
#   docker build --build-arg BUILD_FROM=ghcr.io/home-assistant/amd64-base-debian:bookworm -t <image> .
ARG BUILD_FROM=ghcr.io/home-assistant/amd64-base-debian:bookworm
FROM ${BUILD_FROM}

# JRE recopié depuis l'image officielle Adoptium plutôt qu'installé via apt : le
# proxy d'entreprise renvoie un 403 sur le .deb d'openjdk-17-jre-headless, alors
# que les pulls de registre passent. Effet de bord bienvenu : le build ne dépend
# plus des dépôts Debian, et l'image de base Home Assistant est conservée (donc
# s6 et bashio, nécessaires à run.sh et au Supervisor).
COPY --from=eclipse-temurin:17-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:${PATH}

COPY app.jar /opt/resa-bot/app.jar
COPY run.sh /
RUN chmod a+x /run.sh

CMD [ "/run.sh" ]

LABEL io.hass.name="Resa Bot" \
      io.hass.description="Réservation automatique des cours Wellness Sport Club" \
      io.hass.type="addon" \
      io.hass.version="1.0.5"
