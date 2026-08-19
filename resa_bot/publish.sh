#!/usr/bin/env bash
# Compile le jar, construit l'image de l'add-on et la publie sur le registre.
#
# À lancer depuis le POSTE DE DÉVELOPPEMENT : la machine Home Assistant ne compile
# plus rien (ni Maven, ni image), elle se contente de tirer l'image publiée. C'est
# ce qui met le build hors de portée de la VM VirtualBox instable.
#
#   ./resa_bot/publish.sh              # amd64 (défaut)
#   ./resa_bot/publish.sh aarch64      # Raspberry Pi / ARM 64 bits
#
# Prérequis, une seule fois :
#   1. docker login ghcr.io -u <login-github>   (jeton perso avec write:packages)
#   2. après le premier push, rendre le paquet PUBLIC sur GitHub, sinon le
#      Supervisor ne pourra pas le tirer (il n'a pas tes identifiants).
#   3. pour aarch64 depuis une machine x86, enregistrer qemu une fois :
#      docker run --privileged --rm tonistiigi/binfmt --install arm64
set -euo pipefail

cd "$(dirname "$0")/.."
ADDON_DIR="resa_bot"
REGISTRY="${REGISTRY:-ghcr.io/cedricteck}"
ARCH="${1:-amd64}"

case "$ARCH" in
    amd64)   BASE="ghcr.io/home-assistant/amd64-base-debian:bookworm";   PLATFORM="linux/amd64" ;;
    aarch64) BASE="ghcr.io/home-assistant/aarch64-base-debian:bookworm"; PLATFORM="linux/arm64" ;;
    *) echo "Architecture non gérée : $ARCH (amd64 ou aarch64)" >&2; exit 1 ;;
esac

# Le tag de l'image DOIT être la version de config.yaml : c'est exactement ce que
# le Supervisor va chercher (<image>:<version>).
VERSION="$(sed -n 's/^version: *"\(.*\)"/\1/p' "$ADDON_DIR/config.yaml")"
[ -n "$VERSION" ] || { echo "version introuvable dans $ADDON_DIR/config.yaml" >&2; exit 1; }
IMAGE="$REGISTRY/resa-bot-$ARCH:$VERSION"

echo "==> Compilation du jar"
mvn -B -q -DskipTests package

echo "==> Préparation du contexte de build"
mapfile -t jars < <(ls -1 target/resa-bot-*.jar)
if [ "${#jars[@]}" -ne 1 ]; then
    echo "Attendu un seul jar dans target/, trouvé ${#jars[@]} — lance un 'mvn clean'." >&2
    exit 1
fi
cp "${jars[0]}" "$ADDON_DIR/app.jar"

# Derrière un proxy d'entreprise qui écoute sur la boucle locale, le conteneur de
# build ne peut l'atteindre qu'en partageant le réseau de l'hôte.
build_opts=()
if [ -n "${http_proxy:-}" ]; then
    echo "==> Proxy détecté ($http_proxy) : build sur le réseau de l'hôte"
    build_opts+=(--network host
                 --build-arg "http_proxy=$http_proxy"
                 --build-arg "https_proxy=${https_proxy:-$http_proxy}"
                 --build-arg "no_proxy=${no_proxy:-}")
fi

echo "==> Construction de $IMAGE"
docker build --platform "$PLATFORM" --build-arg "BUILD_FROM=$BASE" \
    "${build_opts[@]}" -t "$IMAGE" "$ADDON_DIR"

echo "==> Publication"
docker push "$IMAGE"

cat <<EOF

Publié : $IMAGE

Dans Home Assistant, l'add-on n'a plus besoin d'être reconstruit : une fois la
version $VERSION visible dans la boutique, « Mettre à jour » tire cette image.
EOF
