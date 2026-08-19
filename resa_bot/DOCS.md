# Resa Bot — add-on Home Assistant

Réservation automatique des cours collectifs Wellness Sport Club (Resamania / DWR),
avec une interface de gestion du planning intégrée à la barre latérale Home Assistant.

## Installation

**Nécessite Home Assistant OS ou Supervised** : les add-ons n'existent pas sur les
installations HA Container ni HA Core.

### Par dépôt (recommandé)

1. **Paramètres → Modules complémentaires → Boutique → ⋮ → Dépôts**
2. Ajouter `https://github.com/cedricteck/wellnessResa`
3. Installer **Resa Bot** dans la liste qui apparaît, puis **Démarrer**.

L'image est construite sur l'appareil : le premier démarrage télécharge Maven et les
dépendances Spring (quelques minutes, davantage sur Raspberry Pi).

> L'add-on compile les sources **depuis GitHub**, pas depuis ta copie locale. Toute
> modification du code doit donc être poussée sur la branche `master` avant de
> reconstruire. Pour forcer une reconstruction, incrémente `version` dans
> `config.yaml` puis relance la mise à jour de l'add-on.

### En add-on local

Copier le dossier `resa_bot/` dans le partage `/addons` de Home Assistant (via
l'add-on Samba ou SSH), puis **Boutique → ⋮ → Vérifier les mises à jour**. Utile pour
tester une variante de `config.yaml` sans toucher au dépôt.

## Configuration

| Option | Défaut | Rôle |
| --- | --- | --- |
| `email` | — | Identifiant de connexion Resamania (obligatoire) |
| `password` | — | Mot de passe Resamania (obligatoire, masqué) |
| `timezone` | `Europe/Paris` | Fuseau des heures de cours et d'ouverture |
| `booking_opens_days_before` | `3` | Ouverture des réservations J-N |
| `retry_window_seconds` | `180` | Durée des tentatives après l'instant d'ouverture |
| `retry_interval_ms` | `2000` | Délai entre deux tentatives |
| `pre_open_lead_seconds` | `30` | Connexion anticipée avant l'ouverture |
| `log_level` | `info` | Verbosité de l'application |

Ces options ne contiennent **pas** le planning : il s'édite dans l'interface.

## Utilisation

Après démarrage, **Resa Bot** apparaît dans la barre latérale (icône calendrier).
L'accès passe par l'ingress : l'authentification est celle de Home Assistant, et
aucun port n'est publié sur le réseau local.

- **Planning auto** — les créneaux réservés automatiquement. Chaque modification est
  enregistrée puis les déclencheurs hebdomadaires sont reprogrammés immédiatement,
  sans redémarrage de l'add-on.
- **Planning du club** — le planning réel pour une date : réserver ou annuler une
  séance, ou cliquer « Suivre » pour ajouter le cours au planning auto (le libellé
  exact est recopié, ce qui évite les erreurs de nom).

## Données et sauvegardes

Le planning vit dans `/data/schedule.json`, que le Supervisor persiste et **inclut
dans les sauvegardes Home Assistant**. Rien d'autre n'est stocké : pas de base de
données, pas d'historique.

Au premier démarrage, le fichier est initialisé avec le planning par défaut de
`application.yml`. Ensuite, c'est lui qui fait foi.

## Derrière un proxy (add-on « NGINX Home Assistant SSL proxy »)

Rien à configurer dans Resa Bot. Le proxy se place devant Home Assistant, à la racine :
les URL d'ingress le traversent inchangées, et l'add-on ne publie aucun port.

Deux détails traités côté application, utiles à connaître en cas de souci :

- les redirections sont **relatives** (`Location: /api/hassio_ingress/<token>/`) : pas
  d'URL absolue reconstruite avec l'adresse interne du conteneur, ni de retour en
  `http://` derrière la terminaison TLS ;
- le suivi de session passe uniquement par cookie, sans `;jsessionid=` dans les URL.

Côté Home Assistant, ce proxy exige la configuration habituelle dans
`configuration.yaml` (indépendante de cet add-on, mais si l'interface se comporte mal,
c'est le premier point à vérifier) :

```yaml
http:
  use_x_forwarded_for: true
  trusted_proxies:
    - 172.30.33.0/24
```

## Dépannage

- **L'add-on ne démarre pas** : `email` et `password` sont obligatoires ; le
  Supervisor refuse de démarrer tant qu'ils sont vides. Le journal l'indique.
- **« Impossible de charger le planning »** dans l'écran *Planning du club* : problème
  réseau ou identifiants refusés. Le journal de l'add-on donne la cause exacte.
- **Une réservation échoue** : le club refuse tant que l'ouverture n'est pas atteinte.
  Le bouton « Réserver » est un test immédiat ; le mode normal, c'est le planning auto.
- **Page blanche ou style absent** : signe que le préfixe d'ingress n'est pas pris en
  compte — vérifier que l'en-tête `X-Ingress-Path` arrive bien (voir
  `IngressForwardedPrefixFilter`).
