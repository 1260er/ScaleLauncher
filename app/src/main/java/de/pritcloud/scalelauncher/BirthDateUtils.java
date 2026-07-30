package de.pritcloud.scalelauncher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

final class BirthDateUtils {
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

    private BirthDateUtils() {}

    static LocalDate parseIso(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String toIso(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    static String toDisplay(LocalDate date) {
        return date == null ? "" : date.format(DISPLAY_FORMAT);
    }

    static int ageOn(LocalDate birthDate, long timestampMs) {
        if (birthDate == null) return -1;
        LocalDate measurementDate = Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        if (birthDate.isAfter(measurementDate)) return -1;
        return Period.between(birthDate, measurementDate).getYears();
    }

    static int ageToday(LocalDate birthDate) {
        if (birthDate == null || birthDate.isAfter(LocalDate.now())) return -1;
        return Period.between(birthDate, LocalDate.now()).getYears();
    }
}
