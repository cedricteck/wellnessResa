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

## Build

```bash
mvn clean package
```

Produit `target/resa-bot-1.0.0.jar`.

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

Sans argument, l'application reste en vie et le `@Scheduled` se déclenche à
**00:00 Europe/Paris** chaque jour pour réserver les cours de J+3 :

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
src/main/java/com/wsc/resabot/
  ResaBotApplication.java   # point d'entrée (@EnableScheduling)
  ResaProperties.java       # config liée à application.yml
  DwrClient.java            # appels DWR bas niveau (auth, planning, book…)
  PlanningParser.java       # extraction des séances depuis la réponse DWR
  SessionInfo.java          # modèle d'une séance
  BookingService.java       # orchestration + retry
  BookingScheduler.java     # @Scheduled à minuit
  CliRunner.java            # modes --dry-run / --book-now / --unbook
src/main/resources/application.yml
```
