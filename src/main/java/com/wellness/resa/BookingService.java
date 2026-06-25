package com.wellness.resa;

import com.wellness.resa.ResaProperties.DesiredClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** Appelé par le planificateur à minuit : réserve les cours de J+3. */
    public void runScheduledBooking() {
        LocalDate target = LocalDate.now(zone()).plusDays(props.bookingOpensDaysBefore());
        List<DesiredClass> wanted = desiredFor(target);
        log.info("Ouverture minuit — cours ciblés le {} ({}) : {}",
                target, target.getDayOfWeek(), labels(wanted));
        if (wanted.isEmpty()) {
            return;
        }
        try {
            DwrClient client = new DwrClient(props);
            client.bootstrap();
            client.authenticate();
            long deadline = System.currentTimeMillis() + props.retryWindowSeconds() * 1000L;
            Map<Long, SessionInfo> planning = client.loadPlanning(midnightMs(target));
            for (DesiredClass w : wanted) {
                attempt(client, planning, target, w, deadline);
            }
        } catch (Exception e) {
            log.error("Échec du run de réservation : {}", e.getMessage(), e);
        }
    }

    private void attempt(DwrClient client, Map<Long, SessionInfo> planning,
                         LocalDate target, DesiredClass w, long deadline) {
        List<Long> matches = findSessions(planning, target, w);
        if (matches.isEmpty()) {
            log.warn("  ✗ Aucune séance pour {} ({}) le {}.", w.label(), w.time(), target);
            return;
        }
        if (matches.size() > 1) {
            log.warn("  ⚠ Plusieurs séances à {} : {} — précise activityId dans la config. "
                    + "Je prends la première.", w.time(), matches);
        }
        long sessionId = matches.get(0);
        log.info("  → {} : séance {}", w.label(), sessionId);

        while (System.currentTimeMillis() < deadline) {
            try {
                if (client.checkAbo(sessionId) && client.book(sessionId)) {
                    log.info("  ✅ RÉSERVÉ : {} (séance {})", w.label(), sessionId);
                    return;
                }
                log.info("  ⏳ Pas encore ouvert pour {}, nouvelle tentative…", w.label());
            } catch (Exception e) {
                log.info("  ⏳ Tentative échouée ({}), on retente…", shorten(e.getMessage()));
            }
            sleep(props.retryIntervalMs());
        }
        log.warn("  ❌ Échec final pour {}.", w.label());
    }

    /** Mode test : connexion + planning de J+3, sans réserver. */
    public void dryRun() throws Exception {
        LocalDate target = LocalDate.now(zone()).plusDays(props.bookingOpensDaysBefore());
        DwrClient client = new DwrClient(props);
        client.bootstrap();
        client.authenticate();
        Map<Long, SessionInfo> planning = client.loadPlanning(midnightMs(target));
        log.info("{} séances trouvées le {} :", planning.size(), target);
        planning.entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        a.getValue().beginMs() == null ? 0 : a.getValue().beginMs(),
                        b.getValue().beginMs() == null ? 0 : b.getValue().beginMs()))
                .forEach(e -> {
                    SessionInfo s = e.getValue();
                    String when = s.beginMs() == null ? "??:??"
                            : ZonedDateTime.ofInstant(
                                    java.time.Instant.ofEpochMilli(s.beginMs()), zone())
                            .toLocalTime().toString();
                    log.info("  {}  session={}  activityId={}", when, e.getKey(), s.activityId());
                });
        log.info("Ce que je RÉSERVERAIS (sans le faire) :");
        for (DesiredClass w : desiredFor(target)) {
            List<Long> matches = findSessions(planning, target, w);
            log.info("  {} ({}) -> {}", w.label(), w.time(),
                    matches.isEmpty() ? "AUCUNE" : matches);
        }
    }

    public void bookNow(long sessionId) throws Exception {
        DwrClient client = new DwrClient(props);
        client.bootstrap();
        client.authenticate();
        boolean ok = client.checkAbo(sessionId) && client.book(sessionId);
        log.info(ok ? "Réservation OK (séance {})" : "Échec réservation (séance {})", sessionId);
    }

    public void unbook(long sessionId) throws Exception {
        DwrClient client = new DwrClient(props);
        client.bootstrap();
        client.authenticate();
        client.unbook(sessionId);
        log.info("Annulation OK (séance {})", sessionId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private List<DesiredClass> desiredFor(LocalDate target) {
        if (props.schedule() == null) {
            return List.of();
        }
        return props.schedule().getOrDefault(target.getDayOfWeek(), List.of());
    }

    private List<Long> findSessions(Map<Long, SessionInfo> planning, LocalDate target, DesiredClass w) {
        LocalTime t = LocalTime.parse(w.time());
        long targetMs = ZonedDateTime.of(target, t, zone()).toInstant().toEpochMilli();
        boolean filterActivity = w.activityId() != null && w.activityId() != 0;
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, SessionInfo> e : planning.entrySet()) {
            SessionInfo s = e.getValue();
            if (s.beginMs() == null) {
                continue;
            }
            if (Math.abs(s.beginMs() - targetMs) <= 60_000) { // tolérance 60 s
                if (!filterActivity || Objects.equals(s.activityId(), w.activityId())) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
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
