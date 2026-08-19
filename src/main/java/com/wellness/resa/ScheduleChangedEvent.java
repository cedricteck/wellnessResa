package com.wellness.resa;

/**
 * Publié par {@link ScheduleStore} après chaque modification du planning souhaité.
 * {@link BookingScheduler} y réagit en reprogrammant ses déclencheurs hebdomadaires.
 */
public record ScheduleChangedEvent() {}
