package com.wellness.resa;

import com.wellness.resa.ResaProperties.DesiredClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Orchestration de la réservation : recherche de la séance dans le planning,
 * puis tentatives répétées de réservation jusqu'à expiration de la fenêtre.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final ResaProperties props;

    public BookingService(ResaProperties props) {
        this.props = props;
    }

    private ZoneId zone() {
        return props.zone();
    }

    /** Ouvre une session DWR authentifiée (cookie + connexion) prête à charger le planning / réserver. */
    private DwrClient connect() throws Exception {
        DwrClient client = new DwrClient(props);
        client.bootstrap();
        client.authenticate();
        return client;
    }

    /**
     * Réserve UN cours précis, déclenché à l'instant d'ouverture exact (J-N à l'heure du cours).
     *
     * <p>La tâche est programmée {@code preOpenLeadSeconds} avant l'ouverture : on se connecte
     * et on charge le planning pendant cette avance, puis on attend l'instant exact avant de
     * tenter la réservation en boucle (jusqu'à {@code retryWindowSeconds} après l'ouverture).
     */
    public void bookSingleCourse(LocalDate courseDate, DesiredClass w, Instant opening) {
        log.info("Préparation : {} ({}) le {} — ouverture exacte à {}",
                w.label(), w.time(), courseDate, ZonedDateTime.ofInstant(opening, zone()));
        try {
            DwrClient client = connect();
            Map<Long, SessionInfo> planning = client.loadPlanning(midnightMs(courseDate));
            Long sessionId = resolveSession(planning, courseDate, w);
            if (sessionId == null) {
                return;
            }

            // Attente jusqu'à l'instant d'ouverture exact (on est arrivé en avance).
            long openMs = opening.toEpochMilli();
            long waitMs = openMs - System.currentTimeMillis();
            if (waitMs > 0) {
                log.info("  ⏱ Connecté (séance {}). Ouverture dans {} s.", sessionId, waitMs / 1000);
                sleep(waitMs);
            }
            bookWithRetry(client, w, sessionId, openMs + props.retryWindowSeconds() * 1000L);
        } catch (Exception e) {
            log.error("Échec réservation {} : {}", w.label(), e.getMessage(), e);
        }
    }

    /**
     * Instant d'ouverture des réservations pour un cours : J-N exactement à l'heure de début
     * (même heure locale, N jours plus tôt — gère l'heure d'été via {@link ZonedDateTime}).
     */
    public Instant openingInstant(LocalDate courseDate, DesiredClass w) {
        LocalTime t = LocalTime.parse(w.time());
        return ZonedDateTime.of(courseDate, t, zone())
                .minusDays(props.bookingOpensDaysBefore())
                .toInstant();
    }

    /** Mode test immédiat (--book-now) : réserve sans attendre l'ouverture, les cours de J+N. */
    public void runScheduledBooking() {
        LocalDate target = LocalDate.now(zone()).plusDays(props.bookingOpensDaysBefore());
        List<DesiredClass> wanted = desiredFor(target);
        log.info("Ouverture minuit — cours ciblés le {} ({}) : {}",
                target, target.getDayOfWeek(), labels(wanted));
        if (wanted.isEmpty()) {
            return;
        }
        try {
            DwrClient client = connect();
            long deadline = System.currentTimeMillis() + props.retryWindowSeconds() * 1000L;
            Map<Long, SessionInfo> planning = client.loadPlanning(midnightMs(target));
            for (DesiredClass w : wanted) {
                Long sessionId = resolveSession(planning, target, w);
                if (sessionId != null) {
                    log.info("  → {} : séance {}", w.label(), sessionId);
                    bookWithRetry(client, w, sessionId, deadline);
                }
            }
        } catch (Exception e) {
            log.error("Échec du run de réservation : {}", e.getMessage(), e);
        }
    }

    /**
     * Mode test : pour CHAQUE créneau du planning configuré (tous les jours de {@code resa.schedule}),
     * résout la prochaine occurrence du jour, charge le planning réel et identifie la séance qui
     * SERAIT réservée (matching par label) — sans jamais réserver. Reflète exactement ce que le
     * planificateur ferait le moment venu.
     */
    public void dryRun() throws Exception {
        if (props.schedule() == null || props.schedule().isEmpty()) {
            log.info("Aucun créneau configuré dans resa.schedule.");
            return;
        }
        DwrClient client = connect();

        log.info("=== DRY-RUN : séances qui SERAIENT réservées (aucune réservation effectuée) ===");
        Map<LocalDate, Map<Long, SessionInfo>> planningByDate = new HashMap<>();
        List<DayOfWeek> days = new ArrayList<>(props.schedule().keySet());
        days.sort(Comparator.comparing(this::nextOccurrence)); // ordre chronologique des occurrences

        for (DayOfWeek courseDay : days) {
            LocalDate courseDate = nextOccurrence(courseDay);
            Map<Long, SessionInfo> planning = planningByDate.get(courseDate);
            if (planning == null) {
                try {
                    planning = client.loadPlanning(midnightMs(courseDate));
                } catch (Exception ex) {
                    log.warn("  Impossible de charger le planning du {} : {}", courseDate, ex.getMessage());
                    continue;
                }
                planningByDate.put(courseDate, planning);
            }
            for (DesiredClass w : props.schedule().get(courseDay)) {
                Long sessionId = resolveSession(planning, courseDate, w);
                if (sessionId == null) {
                    continue;
                }
                SessionInfo s = planning.get(sessionId);
                Instant opening = openingInstant(courseDate, w);
                log.info("  ✓ {} {} ({}) -> séance {} \"{}\" | ouverture {}",
                        courseDate, w.time(), w.label(), sessionId, s.activityName(),
                        ZonedDateTime.ofInstant(opening, zone()));
            }
        }
    }

    /** Prochaine date (aujourd'hui inclus) tombant sur le jour de semaine demandé. */
    private LocalDate nextOccurrence(DayOfWeek day) {
        LocalDate d = LocalDate.now(zone());
        while (d.getDayOfWeek() != day) {
            d = d.plusDays(1);
        }
        return d;
    }

    public void bookNow(long sessionId) throws Exception {
        DwrClient client = connect();
        boolean ok = client.checkAbo(sessionId) && client.book(sessionId);
        log.info(ok ? "Réservation OK (séance {})" : "Échec réservation (séance {})", sessionId);
    }

    public void unbook(long sessionId) throws Exception {
        DwrClient client = connect();
        boolean ok = client.unbook(sessionId);
        log.info(ok ? "Annulation OK (séance {})" : "Échec annulation (séance {})", sessionId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Résout LA séance à réserver pour un créneau (matching par label), avec les logs associés
     * (aucune séance / plusieurs séances au même créneau). Renvoie l'id de séance, ou {@code null}
     * si rien ne correspond.
     */
    private Long resolveSession(Map<Long, SessionInfo> planning, LocalDate date, DesiredClass w) {
        List<Long> matches = findSessions(planning, date, w);
        if (matches.isEmpty()) {
            log.warn("  ✗ Aucune séance pour {} ({}) le {}. Cours à cette heure : {}",
                    w.label(), w.time(), date, candidatesAt(planning, date, w));
            return null;
        }
        if (matches.size() > 1) {
            log.warn("  ⚠ Plusieurs séances pour {} à {} : {} — précise activityId dans la config. "
                    + "Je prends la première.", w.label(), w.time(), matches);
        }
        return matches.get(0);
    }

    /**
     * Tente la réservation en boucle jusqu'à {@code deadline} (instant epoch ms), en respectant
     * {@code retryIntervalMs} entre deux essais. Renvoie {@code true} dès que la séance est réservée.
     */
    private boolean bookWithRetry(DwrClient client, DesiredClass w, long sessionId, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            try {
                if (client.checkAbo(sessionId) && client.book(sessionId)) {
                    log.info("  ✅ RÉSERVÉ : {} (séance {})", w.label(), sessionId);
                    return true;
                }
                log.info("  ⏳ Pas encore ouvert pour {}, nouvelle tentative…", w.label());
            } catch (Exception e) {
                log.info("  ⏳ Tentative échouée ({}), on retente…", shorten(e.getMessage()));
            }
            sleep(props.retryIntervalMs());
        }
        log.warn("  ❌ Échec final pour {}.", w.label());
        return false;
    }

    public List<DesiredClass> desiredFor(LocalDate target) {
        if (props.schedule() == null) {
            return List.of();
        }
        return props.schedule().getOrDefault(target.getDayOfWeek(), List.of());
    }

    /**
     * Séances correspondant au créneau souhaité : d'abord par heure (±60 s), puis départagées
     * par LABEL (nom du cours) — c'est ce qui permet de choisir le bon cours quand plusieurs
     * séances tombent au même horaire, sans dépendre de l'activityId (instable de semaine en
     * semaine). L'activityId reste un filtre optionnel additionnel si renseigné dans la config.
     */
    private List<Long> findSessions(Map<Long, SessionInfo> planning, LocalDate target, DesiredClass w) {
        LocalTime t = LocalTime.parse(w.time());
        long targetMs = ZonedDateTime.of(target, t, zone()).toInstant().toEpochMilli();
        boolean filterActivity = w.activityId() != null && w.activityId() != 0;
        boolean filterLabel = w.label() != null && !w.label().isBlank();
        String wantLabel = filterLabel ? normalize(w.label()) : null;
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, SessionInfo> e : planning.entrySet()) {
            SessionInfo s = e.getValue();
            if (s.beginMs() == null || Math.abs(s.beginMs() - targetMs) > 60_000) {
                continue; // mauvaise heure (tolérance 60 s)
            }
            if (filterActivity && !Objects.equals(s.activityId(), w.activityId())) {
                continue;
            }
            if (filterLabel && !wantLabel.equals(normalize(s.activityName()))) {
                continue;
            }
            out.add(e.getKey());
        }
        return out;
    }

    /** Liste lisible des cours présents dans la fenêtre horaire (diagnostic quand rien ne matche). */
    private String candidatesAt(Map<Long, SessionInfo> planning, LocalDate target, DesiredClass w) {
        LocalTime t = LocalTime.parse(w.time());
        long targetMs = ZonedDateTime.of(target, t, zone()).toInstant().toEpochMilli();
        String list = planning.values().stream()
                .filter(s -> s.beginMs() != null && Math.abs(s.beginMs() - targetMs) <= 60_000)
                .map(s -> "\"" + s.activityName() + "\" (activityId=" + s.activityId() + ")")
                .collect(Collectors.joining(", "));
        return list.isEmpty() ? "aucun" : list;
    }

    /** Normalise un libellé pour comparaison : sans casse ni espaces superflus. */
    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private long midnightMs(LocalDate d) {
        return d.atStartOfDay(zone()).toInstant().toEpochMilli();
    }

    private static String labels(List<DesiredClass> wanted) {
        if (wanted.isEmpty()) {
            return "aucun ce jour-là";
        }
        return String.join(", ", wanted.stream().map(DesiredClass::label).toList());
    }

    private static String shorten(String s) {
        if (s == null) {
            return "erreur";
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
