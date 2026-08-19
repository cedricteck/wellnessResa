# Resa-Bot (Spring Boot) — Réservation automatique Wellness Sport Club

Application Spring Boot qui réserve automatiquement les cours collectifs sur la
plateforme Resamania (ancienne interface « onlineV2 » en DWR), à l'ouverture des
réservations (minuit, 3 jours avant le cours, heure de Paris).

Elle rejoue directement les appels DWR relevés dans la capture HAR de ton compte :
`RightRemote.authenticate`, `OnlineRemote.initializePlanning`,
`OnlineRemote.checkAboForBooking`, `OnlineRemote.bookForCustomer`.

## Prérequis

- Java 17+
- Maven 3.9+

## Configuration

1. Identifiants par variables d'environnement (jamais en dur) :

   ```bash
   export RESA_EMAIL="ton.email@exemple.com"
   export RESA_PASSWORD="ton_mot_de_passe"
   ```

2. Ton planning dans `src/main/resources/application.yml`, section `resa.schedule`.
   Jours en MAJUSCULES (`MONDAY`..`SUNDAY`), heure `"HH:MM"`. `activity-id` est
   optionnel (sert seulement à départager deux cours à la même heure).

   Ce bloc n'est que la valeur **initiale** : dès la première modification depuis
   l'IHM, le planning vit dans `data/schedule.json` (chemin réglable via
   `resa.schedule-file`) et c'est ce fichier qui fait foi. Supprime-le pour
   repartir du YAML.

## Interface graphique

L'application sert une IHM Thymeleaf sur <http://localhost:8080> (écoute
restreinte à `127.0.0.1`, cf. `server.address` — **aucune authentification**, ne
l'expose pas sur le réseau en l'état).

- **Planning auto** (`/`) — les créneaux réservés automatiquement : ajout,
  modification, suppression, avec l'instant de la prochaine ouverture calculé pour
  chacun. Chaque changement est persisté puis les déclencheurs hebdomadaires sont
  reprogrammés à chaud, sans redémarrage.
- **Planning du club** (`/planning`) — le planning réel renvoyé par Resamania pour
  une date : réserver ou annuler une séance immédiatement, ou cliquer
  « Suivre » pour ajouter le cours au planning auto (le libellé exact est recopié,
  ce qui évite toute erreur de nom). Une lecture = une connexion DWR complète,
  donc le résultat est mis en cache 2 minutes ; le bouton « Rafraîchir » force le
  rechargement.

## Build

```bash
mvn clean package
```

Produit `target/resa-bot-1.0.0.jar`.

## Add-on Home Assistant

Le dépôt est aussi un dépôt d'add-ons Home Assistant (`repository.yaml` +
dossier `resa_bot/`). Sur **HAOS ou Supervised** : Paramètres → Modules
complémentaires → Boutique → ⋮ → Dépôts → `https://github.com/cedricteck/wellnessResa`.

- L'IHM s'affiche dans la barre latérale via l'**ingress** : authentification Home
  Assistant, aucun port publié sur le réseau local.
- Les identifiants et les réglages de réservation sont des **options de l'add-on**
  (onglet Configuration), traduites en propriétés Spring par `resa_bot/run.sh`.
- Le planning reste un JSON, dans `/data/schedule.json` : persisté par le Supervisor
  et inclus dans les sauvegardes Home Assistant. Ni base de données, ni historique.

L'add-on compile les sources **depuis GitHub** (le contexte de build d'un add-on est
son propre dossier, il ne peut pas atteindre `../src`) : pousse tes modifications sur
`master` avant de reconstruire, et incrémente `version` dans `resa_bot/config.yaml`
pour déclencher la mise à jour. Détails dans [`resa_bot/DOCS.md`](resa_bot/DOCS.md).

## Derrière un proxy d'entreprise

`DwrClient` utilise `ProxySelector.getDefault()` : le proxy doit donc être passé en
propriétés système, sinon les appels vers Resamania partent en direct et échouent en
`HTTP connect timed out`.

```bash
java -Dhttp.proxyHost=localhost -Dhttp.proxyPort=3128 \
     -Dhttps.proxyHost=localhost -Dhttps.proxyPort=3128 \
     -Dhttp.nonProxyHosts='localhost|127.0.0.1' \
     -jar target/resa-bot-1.0.0.jar
```

Côté IntelliJ, la configuration de lancement **ResaBot (proxy)**
(`.idea/runConfigurations/`) porte déjà ces options dans ses *VM options*.

## Tester AVANT de compter dessus

L'application n'a pas pu être testée contre le site en direct. Procède dans l'ordre :

1. **Dry-run** — connexion + planning de J+3, sans réserver. Valide tes identifiants
   et t'affiche les `activityId` (utile en cas de doublon d'horaire) :

   ```bash
   java -jar target/resa-bot-1.0.0.jar --dry-run
   ```

2. **Réservation réelle de test** — en journée, prends un `session=` affiché par le
   dry-run, réserve puis annule :

   ```bash
   java -jar target/resa-bot-1.0.0.jar --book-now=<sessionId>
   java -jar target/resa-bot-1.0.0.jar --unbook=<sessionId>
   ```

> Si le `--dry-run` échoue à la connexion alors que tes identifiants sont bons,
> le point à inspecter est le `scriptSessionId` du protocole DWR
> (`DwrClient.newScriptSessionId`).

## Mode normal (planifié)

Sans argument, l'application reste en vie, sert l'IHM et programme un déclencheur
hebdomadaire par créneau, réveillé **J-3 à l'heure exacte du cours** (moins
`pre-open-lead-seconds` pour être déjà connecté), heure de Paris :

```bash
java -jar target/resa-bot-1.0.0.jar
```

## Déploiement fiable pour la course à minuit

Fais-la tourner en continu sur une machine allumée la nuit (petit serveur, VPS,
Raspberry Pi). Évite GitHub Actions : son cron peut avoir plusieurs minutes de
retard, fatal pour une ouverture à la seconde près. Le fuseau Europe/Paris
(changements d'heure inclus) est géré par l'annotation `@Scheduled`.

Exemple de service `systemd` (`/etc/systemd/system/resa-bot.service`) :

```ini
[Unit]
Description=Resa-Bot Wellness Sport Club
After=network-online.target

[Service]
Environment=RESA_EMAIL=ton.email@exemple.com
Environment=RESA_PASSWORD=ton_mot_de_passe
ExecStart=/usr/bin/java -jar /opt/resa-bot/resa-bot-1.0.0.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now resa-bot
journalctl -u resa-bot -f      # suivre les logs
```

## Sécurité

- Ne committe jamais tes identifiants ni le fichier HAR (qui contient ton mot de
  passe en clair). Pense à changer ton mot de passe si le HAR a circulé.
- Réserver via un automate peut être contraire aux CGU du club — à toi de juger.

## Structure

```
src/main/java/com/wellness/resa/
  ResaBotApplication.java   # point d'entrée (@EnableScheduling)
  ResaProperties.java       # config liée à application.yml
  DwrClient.java            # appels DWR bas niveau (auth, planning, book…)
  PlanningParser.java       # extraction des séances depuis la réponse DWR
  SessionInfo.java          # modèle d'une séance (réponse DWR)
  PlanningEntry.java        # modèle d'une séance pour l'IHM
  ScheduleStore.java        # planning souhaité, persisté dans data/schedule.json
  ScheduleChangedEvent.java # signal de reprogrammation à chaud
  BookingService.java       # orchestration + retry + lecture du planning
  BookingScheduler.java     # un cron hebdo par créneau (ouverture J-N à l'heure du cours)
  PlanningController.java   # IHM Thymeleaf (/ et /planning)
  IngressForwardedPrefixFilter.java  # support de l'ingress Home Assistant
  CliRunner.java            # modes --dry-run / --book-now / --unbook
src/main/resources/
  application.yml
  templates/                # schedule.html, planning.html, fragments.html
  static/css/app.css
repository.yaml             # dépôt d'add-ons Home Assistant
resa_bot/                   # l'add-on : config.yaml, build.yaml, Dockerfile, run.sh, DOCS.md
```
