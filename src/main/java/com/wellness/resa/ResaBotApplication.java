package com.wellness.resa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Application de réservation automatique des cours collectifs Wellness Sport Club.
 *
 * Mode normal : l'application reste en vie et le planificateur ({@link BookingScheduler})
 * programme une tâche par cours, déclenchée à l'instant d'ouverture EXACT
 * (J-3 à l'heure de début du cours, et non à minuit).
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

    /**
     * Planificateur pour les tâches one-shot programmées à l'instant d'ouverture.
     * Plusieurs cours peuvent ouvrir au même moment : on prévoit donc plusieurs threads.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("resa-open-");
        scheduler.setDaemon(false);
        return scheduler;
    }
}
