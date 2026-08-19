package com.wellness.resa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellness.resa.ResaProperties.DesiredClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Source de vérité du planning SOUHAITÉ (les créneaux que le bot doit réserver), éditable
 * depuis l'IHM et persistée dans un fichier JSON hors du jar.
 *
 * <p>Au premier démarrage, le fichier n'existe pas : on l'initialise avec {@code resa.schedule}
 * de {@code application.yml}. Ensuite c'est le fichier qui fait foi — le YAML ne sert plus que de
 * jeu de valeurs par défaut (utile pour un déploiement neuf).
 *
 * <p>Toute mutation persiste immédiatement puis publie un {@link ScheduleChangedEvent} pour que
 * {@link BookingScheduler} reprogramme ses crons sans redémarrage.
 */
@Component
public class ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher events;

    /** Planning courant, trié par jour (lundi -> dimanche) puis par heure. */
    private final Map<DayOfWeek, List<DesiredClass>> schedule = new EnumMap<>(DayOfWeek.class);

    public ScheduleStore(ResaProperties props, ApplicationEventPublisher events) {
        this.events = events;
        this.file = Path.of(props.scheduleFile() == null || props.scheduleFile().isBlank()
                ? "data/schedule.json" : props.scheduleFile());
        Map<DayOfWeek, List<DesiredClass>> initial = readFile();
        if (initial == null) {
            initial = props.schedule() == null ? Map.of() : props.schedule();
            log.info("Aucun fichier {} : initialisation du planning depuis application.yml.", file);
        } else {
            log.info("Planning chargé depuis {}.", file);
        }
        initial.forEach((day, slots) -> schedule.put(day, sorted(slots)));
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    /** Vue immuable du planning courant, jours triés, créneaux triés par heure. */
    public synchronized Map<DayOfWeek, List<DesiredClass>> schedule() {
        Map<DayOfWeek, List<DesiredClass>> copy = new TreeMap<>();
        schedule.forEach((day, slots) -> {
            if (!slots.isEmpty()) {
                copy.put(day, List.copyOf(slots));
            }
        });
        return copy;
    }

    /** Créneaux souhaités pour un jour de semaine (liste vide si aucun). */
    public synchronized List<DesiredClass> forDay(DayOfWeek day) {
        return List.copyOf(schedule.getOrDefault(day, List.of()));
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    public synchronized void add(DayOfWeek day, DesiredClass slot) {
        List<DesiredClass> slots = new ArrayList<>(schedule.getOrDefault(day, List.of()));
        slots.add(slot);
        schedule.put(day, sorted(slots));
        commit("ajout %s %s le %s".formatted(slot.time(), slot.label(), day));
    }

    /**
     * Remplace le créneau à l'index donné. L'index provient de la liste TRIÉE renvoyée par
     * {@link #forDay(DayOfWeek)}, soit exactement ce qu'affiche l'IHM.
     */
    public synchronized void update(DayOfWeek day, int index, DesiredClass slot) {
        List<DesiredClass> slots = new ArrayList<>(schedule.getOrDefault(day, List.of()));
        checkIndex(slots, index, day);
        slots.set(index, slot);
        schedule.put(day, sorted(slots));
        commit("modification %s %s le %s".formatted(slot.time(), slot.label(), day));
    }

    public synchronized void remove(DayOfWeek day, int index) {
        List<DesiredClass> slots = new ArrayList<>(schedule.getOrDefault(day, List.of()));
        checkIndex(slots, index, day);
        DesiredClass removed = slots.remove(index);
        schedule.put(day, slots);
        commit("suppression %s %s le %s".formatted(removed.time(), removed.label(), day));
    }

    private void commit(String what) {
        writeFile();
        log.info("Planning modifié ({}) — reprogrammation des déclencheurs.", what);
        events.publishEvent(new ScheduleChangedEvent());
    }

    // ------------------------------------------------------------------
    // Persistance
    // ------------------------------------------------------------------

    /** Contenu du fichier, ou {@code null} s'il n'existe pas encore / est illisible. */
    private Map<DayOfWeek, List<DesiredClass>> readFile() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readAllBytes(file),
                    new TypeReference<Map<DayOfWeek, List<DesiredClass>>>() {});
        } catch (IOException e) {
            // On préfère repartir du YAML plutôt que de démarrer sans planning du tout.
            log.warn("Fichier planning {} illisible ({}) — retour aux valeurs d'application.yml.",
                    file, e.getMessage());
            return null;
        }
    }

    private void writeFile() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(new TreeMap<>(schedule)));
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'écrire le planning dans " + file, e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<DesiredClass> sorted(List<DesiredClass> slots) {
        List<DesiredClass> out = new ArrayList<>(slots);
        out.sort(Comparator.comparing(s -> LocalTime.parse(s.time())));
        return out;
    }

    private static void checkIndex(List<DesiredClass> slots, int index, DayOfWeek day) {
        if (index < 0 || index >= slots.size()) {
            throw new IllegalArgumentException("Créneau introuvable (" + day + ", index " + index + ")");
        }
    }
}
