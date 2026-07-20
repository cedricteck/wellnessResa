package com.wellness.resa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Modes de test ponctuels en ligne de commande. Sans argument, ne fait rien :
 * l'application reste en vie et le planificateur prend le relais à minuit.
 *
 *   java -jar resa-bot.jar --dry-run            # simule tout le planning configuré, ne réserve rien
 *   java -jar resa-bot.jar --book-now           # réserve tout de suite les cours configurés de J+N
 *   java -jar resa-bot.jar --book-now=25503294  # réserve tout de suite CETTE séance (par id)
 *   java -jar resa-bot.jar --unbook=25503294    # annule CETTE séance (par id)
 */
@Component
public class CliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final BookingService bookingService;

    public CliRunner(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public void run(String... args) throws Exception {
        for (String a : args) {
            if (a.equals("--dry-run")) {
                bookingService.dryRun();
                System.exit(0);
            } else if (a.startsWith("--book-now=")) {
                bookingService.bookNow(Long.parseLong(a.substring("--book-now=".length())));
                System.exit(0);
            } else if (a.equals("--book-now")) {
                bookingService.runScheduledBooking();
                System.exit(0);
            } else if (a.startsWith("--unbook=")) {
                bookingService.unbook(Long.parseLong(a.substring("--unbook=".length())));
                System.exit(0);
            }
        }
        log.info("Aucun mode test demandé — en attente des déclenchements programmés");
    }
}
