package com.controlj.addon.temperature.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Единая точка настройки времени в аддоне.
 *
 * <p>Раньше аддон был жёстко привязан к «UTC+3»: смещение прибавлялось к метке времени
 * при раскладке по часам, а дата при этом разбиралась в часовом поясе JVM. Такой код
 * даёт правильный результат только если JVM сервера стоит ровно в Europe/Moscow.
 * Здесь зона задаётся один раз и используется и для разбора даты, и для определения часа,
 * и для «сегодня». По умолчанию — Europe/Moscow (историческое поведение аддона);
 * переопределяется системным свойством {@code -Dtermoheatmap.timezone=Europe/Kaliningrad}
 * (или любым другим идентификатором зоны IANA).</p>
 */
public final class AddonTime {

    /** Системное свойство, которым можно переопределить зону аддона. */
    public static final String ZONE_PROPERTY = "termoheatmap.timezone";

    private static final String DEFAULT_ZONE = "Europe/Moscow";

    /** Строгий разбор даты: «2026-02-30» отвергается, а не превращается в 2 марта. */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /** Формат метки времени для отладочной выдачи (уже в зоне аддона). */
    private static final DateTimeFormatter STAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private static volatile ZoneId zone;

    private AddonTime() {
    }

    /** Часовой пояс аддона. */
    public static ZoneId zone() {
        ZoneId current = zone;
        if (current == null) {
            String configured = System.getProperty(ZONE_PROPERTY);
            if (configured == null || configured.trim().isEmpty()) {
                configured = DEFAULT_ZONE;
            }
            try {
                current = ZoneId.of(configured.trim());
            } catch (RuntimeException e) {
                // Неверный идентификатор зоны не должен ронять аддон.
                current = ZoneId.systemDefault();
            }
            zone = current;
        }
        return current;
    }

    /** Идентификатор зоны для выдачи в JSON и для подсказок в интерфейсе. */
    public static String zoneId() {
        return zone().getId();
    }

    /**
     * Разбор параметра {@code date} (yyyy-MM-dd).
     *
     * @throws DateTimeParseException если строка не является корректной датой
     */
    public static LocalDate parseDate(String text) {
        return LocalDate.parse(text.trim(), DATE_FORMAT);
    }

    public static LocalDate today() {
        return LocalDate.now(zone());
    }

    public static boolean isToday(LocalDate day) {
        return day != null && day.equals(today());
    }

    /** Начало суток в зоне аддона (включительно). */
    public static Date startOfDay(LocalDate day) {
        return Date.from(day.atStartOfDay(zone()).toInstant());
    }

    /** Начало следующих суток в зоне аддона (исключительно). */
    public static Date startOfNextDay(LocalDate day) {
        return Date.from(day.plusDays(1).atStartOfDay(zone()).toInstant());
    }

    /** Номер часа (0-23) в зоне аддона для метки времени в миллисекундах. */
    public static int hourOf(long epochMillis) {
        return toZoned(epochMillis).getHour();
    }

    /** Дата в зоне аддона для метки времени в миллисекундах. */
    public static LocalDate dateOf(long epochMillis) {
        return toZoned(epochMillis).toLocalDate();
    }

    /** Начало текущего часа в зоне аддона — отсечка «будущих» сэмплов за сегодня. */
    public static long startOfCurrentHour(long nowMillis) {
        return toZoned(nowMillis).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
    }

    /** Метка времени в зоне аддона, формат yyyy-MM-dd HH:mm:ss. */
    public static String format(long epochMillis) {
        return toZoned(epochMillis).format(STAMP_FORMAT);
    }

    private static ZonedDateTime toZoned(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone());
    }
}
