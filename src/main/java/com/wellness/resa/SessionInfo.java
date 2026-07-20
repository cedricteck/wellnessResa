package com.wellness.resa;

/**
 * Une séance du planning : date/heure de début (epoch ms), identifiant d'activité
 * et nom de l'activité (libellé du cours, résolu depuis la table d'activités de la réponse).
 */
public record SessionInfo(Long beginMs, Integer activityId, String activityName) {}
