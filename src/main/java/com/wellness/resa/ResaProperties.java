package com.wellness.resa;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Configuration de l'application, liée au préfixe "resa" dans application.yml.
 * Les constantes supplierId / dataPoolId / customerId proviennent de la capture
 * HAR de ton compte et sont stables.
 *
 * <p>{@code schedule} n'est que la valeur INITIALE du planning souhaité : dès qu'il a été
 * édité depuis l'IHM, c'est {@link ScheduleStore} (fichier {@code scheduleFile}) qui fait foi.
 */
@ConfigurationProperties(prefix = "resa")
public record ResaProperties(
        String baseUrl,
        String timezone,
        int supplierId,
        int dataPoolId,
        long customerId,
        int bookingOpensDaysBefore,
        int retryWindowSeconds,
        long retryIntervalMs,
        int preOpenLeadSeconds,
        String email,
        String password,
        String scheduleFile,
        Map<DayOfWeek, List<DesiredClass>> schedule
) {
    /** Un cours souhaité. activityId est optionnel (null ou 0 = non précisé). */
    public record DesiredClass(String time, Integer activityId, String label) {}

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}
