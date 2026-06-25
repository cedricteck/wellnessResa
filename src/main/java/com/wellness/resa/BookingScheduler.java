package com.wellness.resa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclenche la réservation à minuit, heure de Paris.
 *
 * À 00:00 du jour X, on réserve les cours du jour X+3 (fenêtre d'ouverture à J-3).
 * Le fuseau est figé sur Europe/Paris (gère automatiquement l'heure d'été).
 * Si tu changes "resa.timezone", pense à aligner la zone ci-dessous.
 */
@Component
public class BookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingScheduler.class);

    private final BookingService bookingService;

    public BookingScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Paris")
    public void atMidnight() {
        log.info("Déclenchement planifié à minuit (Europe/Paris).");
        bookingService.runScheduledBooking();
    }
}
