package com.wellness.resa;

import com.wellness.resa.ResaProperties.DesiredClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Programme, au démarrage, un déclencheur hebdomadaire (cron) par créneau de la configuration.
 *
 * <p>L'ouverture des réservations se fait J-N exactement à l'heure de début du cours. Comme chaque
 * créneau a un jour de semaine et une heure fixes, son instant d'ouverture tombe lui aussi sur un
 * jour/heure fixes chaque semaine (ex. cours lundi 19:15, N=3 -> ouverture vendredi 19:15). On
 * enregistre donc une fois pour toutes un {@link CronTrigger} par créneau, déclenché
 * {@code preOpenLeadSeconds} avant l'ouverture pour être connecté et prêt.
 */
@Component
public class BookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingScheduler.class);

    private final BookingService bookingService;
    private final ResaProperties props;
    private final TaskScheduler taskScheduler;

    public BookingScheduler(BookingService bookingService, ResaProperties props, TaskScheduler taskScheduler) {
        this.bookingService = bookingService;
        this.props = props;
        this.taskScheduler = taskScheduler;
    }

    /** Au démarrage : un déclencheur hebdomadaire par créneau configuré. */
    @EventListener(ApplicationReadyEvent.class)
    public void scheduleAll() {
        if (props.schedule() == null) {
            return;
        }
        ZoneId zone = props.zone();
        for (Map.Entry<DayOfWeek, List<DesiredClass>> e : props.schedule().entrySet()) {
            DayOfWeek courseDay = e.getKey();
            for (DesiredClass w : e.getValue()) {
                scheduleOne(courseDay, w, zone);
            }
        }
    }

    /** Enregistre le cron hebdomadaire correspondant à l'ouverture de ce créneau (moins le lead). */
    private void scheduleOne(DayOfWeek courseDay, DesiredClass w, ZoneId zone) {
        LocalTime courseTime = LocalTime.parse(w.time());
        // Instant d'ouverture ramené à un jour/heure de semaine, puis avancé du lead.
        // Le calcul sur une date de référence gère proprement les passages de minuit / semaine.
        LocalDateTime fire = LocalDateTime.of(referenceDate(courseDay), courseTime)
                .minusDays(props.bookingOpensDaysBefore())
                .minusSeconds(props.preOpenLeadSeconds());
        String cron = String.format("%d %d %d * * %s",
                fire.getSecond(), fire.getMinute(), fire.getHour(),
                fire.getDayOfWeek().name().substring(0, 3));

        taskScheduler.schedule(() -> runBooking(courseDay, w, zone), new CronTrigger(cron, zone));
        log.info("Programmé (hebdo) : {} ({}) le {} — cron \"{}\" [{}].",
                w.label(), w.time(), courseDay, cron, zone);
    }

    /** Exécuté à chaque déclenchement : calcule le cours visé (prochaine occurrence du jour) et réserve. */
    private void runBooking(DayOfWeek courseDay, DesiredClass w, ZoneId zone) {
        LocalDate courseDate = LocalDate.now(zone);
        while (courseDate.getDayOfWeek() != courseDay) {
            courseDate = courseDate.plusDays(1);
        }
        Instant opening = bookingService.openingInstant(courseDate, w);
        bookingService.bookSingleCourse(courseDate, w, opening);
    }

    /** Une date quelconque tombant sur le jour de semaine demandé (référence pour le calcul cron). */
    private static LocalDate referenceDate(DayOfWeek day) {
        // 2024-01-01 est un lundi ; on décale du nombre de jours voulu (MONDAY=1 .. SUNDAY=7).
        return LocalDate.of(2024, 1, 1).plusDays(day.getValue() - DayOfWeek.MONDAY.getValue());
    }
}
