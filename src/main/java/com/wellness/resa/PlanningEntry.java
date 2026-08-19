package com.wellness.resa;

import java.time.LocalTime;

/**
 * Une séance du planning réel du club, prête à être affichée par l'IHM.
 *
 * @param sessionId    identifiant de séance côté Resamania (celui qu'on réserve)
 * @param time         heure de début locale
 * @param activityName libellé du cours ({@code null} si non résolu dans la réponse DWR)
 * @param activityId   identifiant d'activité (non stable d'une semaine à l'autre)
 * @param wanted       vrai si ce créneau figure déjà dans le planning souhaité
 */
public record PlanningEntry(long sessionId, LocalTime time, String activityName,
                            Integer activityId, boolean wanted) {

    /** Heure formatée "HH:MM" (l'IHM n'a ainsi besoin d'aucun dialecte Thymeleaf date/heure). */
    public String timeLabel() {
        return time.toString().substring(0, 5);
    }

    /** Libellé affichable, même quand le nom d'activité n'a pas pu être résolu. */
    public String displayName() {
        return trackable() ? activityName : "(cours sans nom, activityId=" + activityId + ")";
    }

    /**
     * Vrai si la séance peut être ajoutée au planning souhaité : sans libellé résolu, le matching
     * par nom serait impossible, donc l'IHM masque le bouton « Suivre ».
     */
    public boolean trackable() {
        return activityName != null && !activityName.isBlank();
    }
}
