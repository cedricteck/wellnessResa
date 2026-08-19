#!/usr/bin/with-contenv bashio
# Point d'entrée de l'add-on : traduit les options Home Assistant en
# configuration Spring, puis lance l'application.
set -e

bashio::config.require 'email'
bashio::config.require 'password'

# Fuseau utilisé aussi bien par la JVM que par le calcul des heures d'ouverture.
export TZ
TZ="$(bashio::config 'timezone')"

# Options de l'add-on -> propriétés Spring. On passe par jq plutôt que par une
# concaténation de chaînes : l'échappement JSON du mot de passe (guillemets,
# accents, antislashs) est alors correct par construction.
# - server.address 0.0.0.0 : l'ingress du Supervisor proxifie depuis l'extérieur
#   du conteneur ; l'écoute locale de application.yml ne suffirait pas. Aucun port
#   n'étant publié, l'IHM reste inaccessible depuis le réseau.
# - resa.schedule-file dans /data : persisté par le Supervisor et inclus dans les
#   sauvegardes Home Assistant.
SPRING_APPLICATION_JSON="$(jq -c '{
  server: { address: "0.0.0.0", port: 8080 },
  resa: {
    email: .email,
    password: .password,
    timezone: .timezone,
    "booking-opens-days-before": .booking_opens_days_before,
    "retry-window-seconds": .retry_window_seconds,
    "retry-interval-ms": .retry_interval_ms,
    "pre-open-lead-seconds": .pre_open_lead_seconds,
    "schedule-file": "/data/schedule.json"
  },
  logging: { level: { "com.wellness.resa": .log_level } }
}' /data/options.json)"
export SPRING_APPLICATION_JSON

bashio::log.info "Fuseau : ${TZ} — planning : /data/schedule.json"
bashio::log.info "IHM disponible dans la barre latérale Home Assistant (ingress)."

# MaxRAMPercentage : la JVM respecte la limite mémoire du conteneur au lieu de
# viser la RAM de la machine entière (utile sur Raspberry Pi).
exec java -XX:MaxRAMPercentage=75 -jar /opt/resa-bot/app.jar
