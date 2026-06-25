package com.wellness.resa;

/** Une séance du planning : date/heure de début (epoch ms) + identifiant d'activité. */
public record SessionInfo(Long beginMs, Integer activityId) {}
