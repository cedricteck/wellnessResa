package com.wellness.resa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application de réservation automatique des cours collectifs Wellness Sport Club.
 *
 * Mode normal : l'application reste en vie et le planificateur ({@link BookingScheduler})
 * se déclenche à minuit (heure de Paris) pour réserver les cours de J+3.
 *
 * Modes de test ponctuels (voir {@link CliRunner}) :
 *   --dry-run            -> connexion + planning de J+3, sans réserver
 *   --book-now=<id>      -> réserve immédiatement une séance (test réel)
 *   --unbook=<id>        -> annule une séance
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ResaProperties.class)
public class ResaBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResaBotApplication.class, args);
    }
}
