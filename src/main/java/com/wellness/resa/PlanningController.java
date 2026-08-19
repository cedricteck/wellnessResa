package com.wellness.resa;

import com.wellness.resa.ResaProperties.DesiredClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * IHM d'administration (Thymeleaf), deux écrans :
 *
 * <ul>
 *   <li><b>/</b> — le planning SOUHAITÉ : les créneaux que le bot réservera automatiquement.
 *       Ajout / modification / suppression, avec l'instant de la prochaine ouverture calculé.</li>
 *   <li><b>/planning</b> — le planning RÉEL du club pour une date : réservation ou annulation
 *       immédiate d'une séance, et ajout d'un cours au planning souhaité en un clic.</li>
 * </ul>
 *
 * <p>Aucune authentification : le serveur n'écoute que sur 127.0.0.1 (voir application.yml).
 */
@Controller
public class PlanningController {

    private static final Logger log = LoggerFactory.getLogger(PlanningController.class);
    private static final Locale FR = Locale.FRENCH;
    private static final DateTimeFormatter OPENING_FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", FR);
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", FR);

    private final BookingService bookingService;
    private final ScheduleStore store;
    private final ResaProperties props;

    public PlanningController(BookingService bookingService, ScheduleStore store, ResaProperties props) {
        this.bookingService = bookingService;
        this.store = store;
        this.props = props;
    }

    /** Un créneau souhaité, prêt à afficher (index = position dans la liste triée du jour). */
    public record SlotView(int index, String time, String label, String activityId, String nextOpening) {}

    /** Un jour de la semaine avec ses créneaux souhaités. */
    public record DayView(DayOfWeek day, String label, List<SlotView> slots) {}

    // ------------------------------------------------------------------
    // Écran 1 : planning souhaité
    // ------------------------------------------------------------------

    @GetMapping("/")
    public String schedule(Model model) {
        List<DayView> days = new ArrayList<>();
        int total = 0;
        for (DayOfWeek day : DayOfWeek.values()) {
            List<DesiredClass> slots = store.forDay(day);
            List<SlotView> views = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                DesiredClass s = slots.get(i);
                views.add(new SlotView(i, s.time(), s.label(),
                        s.activityId() == null || s.activityId() == 0 ? "" : String.valueOf(s.activityId()),
                        nextOpeningLabel(day, s)));
            }
            total += views.size();
            days.add(new DayView(day, dayLabel(day), views));
        }
        model.addAttribute("days", days);
        model.addAttribute("total", total);
        model.addAttribute("daysBefore", props.bookingOpensDaysBefore());
        model.addAttribute("zone", props.timezone());
        model.addAttribute("active", "schedule");
        return "schedule";
    }

    @PostMapping("/schedule/add")
    public String add(@RequestParam DayOfWeek day,
                      @RequestParam String time,
                      @RequestParam String label,
                      @RequestParam(required = false) String activityId,
                      RedirectAttributes flash) {
        return apply(flash, "Créneau ajouté.",
                () -> store.add(day, slot(time, label, activityId)));
    }

    @PostMapping("/schedule/update")
    public String update(@RequestParam DayOfWeek day,
                         @RequestParam int index,
                         @RequestParam String time,
                         @RequestParam String label,
                         @RequestParam(required = false) String activityId,
                         RedirectAttributes flash) {
        return apply(flash, "Créneau modifié.",
                () -> store.update(day, index, slot(time, label, activityId)));
    }

    @PostMapping("/schedule/delete")
    public String delete(@RequestParam DayOfWeek day,
                         @RequestParam int index,
                         RedirectAttributes flash) {
        return apply(flash, "Créneau supprimé.", () -> store.remove(day, index));
    }

    // ------------------------------------------------------------------
    // Écran 2 : planning réel du club
    // ------------------------------------------------------------------

    @GetMapping("/planning")
    public String planning(@RequestParam(required = false) String date,
                           @RequestParam(defaultValue = "false") boolean refresh,
                           Model model) {
        LocalDate day = parseDate(date);
        model.addAttribute("date", day);
        model.addAttribute("dateLabel", DAY_FMT.format(day));
        model.addAttribute("prev", day.minusDays(1));
        model.addAttribute("next", day.plusDays(1));
        model.addAttribute("openingDay", LocalDate.now(props.zone()).plusDays(props.bookingOpensDaysBefore()));
        model.addAttribute("active", "planning");
        try {
            model.addAttribute("entries", bookingService.planningFor(day, refresh));
        } catch (Exception e) {
            log.warn("Chargement du planning du {} impossible : {}", day, e.getMessage());
            model.addAttribute("entries", List.of());
            model.addAttribute("error", "Impossible de charger le planning : " + e.getMessage());
        }
        return "planning";
    }

    @PostMapping("/planning/book")
    public String book(@RequestParam long sessionId, @RequestParam String date, RedirectAttributes flash) {
        try {
            bookingService.bookNow(sessionId);
            flash.addFlashAttribute("ok", "Demande de réservation envoyée (séance " + sessionId
                    + "). Vérifie les logs : le club refuse tant que l'ouverture n'est pas atteinte.");
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Échec de la réservation : " + e.getMessage());
        }
        return "redirect:/planning?date=" + date;
    }

    @PostMapping("/planning/unbook")
    public String unbook(@RequestParam long sessionId, @RequestParam String date, RedirectAttributes flash) {
        try {
            bookingService.unbook(sessionId);
            flash.addFlashAttribute("ok", "Demande d'annulation envoyée (séance " + sessionId + ").");
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Échec de l'annulation : " + e.getMessage());
        }
        return "redirect:/planning?date=" + date;
    }

    /** Ajoute le cours cliqué au planning souhaité, sur le jour de semaine de la date affichée. */
    @PostMapping("/planning/track")
    public String track(@RequestParam String date,
                        @RequestParam String time,
                        @RequestParam String label,
                        RedirectAttributes flash) {
        LocalDate day = parseDate(date);
        try {
            store.add(day.getDayOfWeek(), slot(time, label, null));
            flash.addFlashAttribute("ok", "« " + label + " » ajouté au planning auto ("
                    + dayLabel(day.getDayOfWeek()) + " " + time + ").");
        } catch (RuntimeException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/planning?date=" + day;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Exécute une mutation du planning et transforme l'échec éventuel en message d'erreur. */
    private String apply(RedirectAttributes flash, String okMessage, Runnable action) {
        try {
            action.run();
            flash.addFlashAttribute("ok", okMessage);
        } catch (RuntimeException e) {
            flash.addFlashAttribute("error", "Opération refusée : " + e.getMessage());
        }
        return "redirect:/";
    }

    /** Valide et normalise les champs du formulaire en créneau souhaité. */
    private static DesiredClass slot(String time, String label, String activityId) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("le nom du cours est obligatoire");
        }
        LocalTime parsed;
        try {
            parsed = LocalTime.parse(time.trim()); // accepte "HH:mm" et "HH:mm:ss"
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("heure invalide : \"" + time + "\" (format attendu HH:MM)");
        }
        Integer id = null;
        if (activityId != null && !activityId.isBlank()) {
            try {
                id = Integer.valueOf(activityId.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("activityId invalide : \"" + activityId + "\"");
            }
        }
        return new DesiredClass(String.format("%02d:%02d", parsed.getHour(), parsed.getMinute()),
                id, label.trim());
    }

    /**
     * Prochaine ouverture réelle pour ce créneau : J-N à l'heure du cours, en sautant à la semaine
     * suivante si l'ouverture de cette semaine est déjà passée.
     */
    private String nextOpeningLabel(DayOfWeek day, DesiredClass slot) {
        LocalDate courseDate = bookingService.nextOccurrence(day);
        Instant opening = bookingService.openingInstant(courseDate, slot);
        if (opening.isBefore(Instant.now())) {
            opening = bookingService.openingInstant(courseDate.plusWeeks(1), slot);
        }
        return OPENING_FMT.format(ZonedDateTime.ofInstant(opening, props.zone()));
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(props.zone());
        }
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException e) {
            return LocalDate.now(props.zone());
        }
    }

    private static String dayLabel(DayOfWeek day) {
        String name = day.getDisplayName(TextStyle.FULL, FR);
        return name.substring(0, 1).toUpperCase(FR) + name.substring(1);
    }
}
