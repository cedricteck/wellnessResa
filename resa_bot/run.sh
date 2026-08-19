#!/usr/bin/with-contenv bashio
# Point d'entrée de l'add-on : traduit les options Home Assistant en
# configuration Spring, puis lance l'application.
set -e

OPTIONS=/data/options.json

# Contrôle des identifiants directement dans les options, sans passer par
# bashio::config.require : celui-ci interroge l'API du Superviseur, ce qui ajoute
# une dépendance au démarrage et rend l'image intestable hors de Home Assistant.
# Le schéma de config.yaml les déclare déjà obligatoires — ceci n'est qu'un
# garde-fou avec un message clair.
for key in email password; do
    if [ -z "$(jq -r --arg k "$key" '.[$k] // ""' "$OPTIONS")" ]; then
        bashio::log.fatal "L'option '$key' est vide : renseigne-la dans l'onglet Configuration de l'add-on."
        exit 1
    fi
done

# Fuseau utilisé aussi bien par la JVM que par le calcul des heures d'ouverture.
export TZ
TZ="$(jq -r '.timezone // "Europe/Paris"' "$OPTIONS")"

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
}' "$OPTIONS")"
export SPRING_APPLICATION_JSON

bashio::log.info "Fuseau : ${TZ} — planning : /data/schedule.json"
bashio::log.info "IHM disponible dans la barre latérale Home Assistant (ingress)."

# Réglages JVM :
# - MaxRAMPercentage : la JVM respecte la limite mémoire du conteneur au lieu de
#   viser la RAM de la machine entière (utile sur Raspberry Pi).
# - TieredStopAtLevel=1 : compilateur C1 uniquement. Le JIT C2 de ce JRE (Debian
#   17.0.20+8) a fait tomber la VM en SIGSEGV dans Node::disconnect_inputs ; se
#   limiter à C1 évite ce chemin de code et réduit nettement le temps de démarrage
#   (23 s observées sur un boîtier peu puissant). L'application dort l'essentiel du
#   temps : la perte d'optimisation à chaud est sans conséquence ici.
# - UseSerialGC : sur une machine à peu de cœurs, G1 coûte des threads et de la
#   mémoire sans rien apporter à une application aussi peu active.
# - ErrorFile dans /data : le rapport de crash de la JVM survit au redémarrage du
#   conteneur (et part dans les sauvegardes), sinon il est perdu avec le conteneur.

# -XX:UseAVX=0 : VirtualBox virtualise mal les instructions vectorielles AVX émises
# par le JIT — c'est le contournement classique des plantages de JVM sous VirtualBox.
# L'option n'existe QUE sur x86 : sur ARM (Raspberry Pi), la JVM refuserait de
# démarrer avec un "Unrecognized VM option".
JVM_ARCH_OPTS=""
if [ "$(uname -m)" = "x86_64" ]; then
    JVM_ARCH_OPTS="-XX:UseAVX=0"
fi

exec java \
    -XX:MaxRAMPercentage=75 \
    -XX:TieredStopAtLevel=1 \
    -XX:+UseSerialGC \
    -XX:ErrorFile=/data/hs_err_%p.log \
    ${JVM_ARCH_OPTS} \
    -jar /opt/resa-bot/app.jar
