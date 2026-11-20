import com.controlj.addon.temperature.util.AddonTime;
import com.controlj.addon.temperature.util.JsonResponse;
import com.controlj.addon.temperature.util.Stats;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Автономная проверка чистой логики аддона (без WebCTRL).
 *
 * <p>Запуск (Windows):</p>
 * <pre>
 * javac -encoding UTF-8 -d build/selftest -cp "..\json-20251224.jar;build\classes" tools\selftest\SelfTest.java
 * java -cp "build\selftest;build\classes;..\json-20251224.jar" SelfTest
 * java -Dtermoheatmap.timezone=Europe/Kaliningrad -cp ... SelfTest --zone
 * </pre>
 */
public final class SelfTest {

    private static int checks;
    private static int failures;

    private SelfTest() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--zone".equals(args[0])) {
            System.out.println("zone=" + AddonTime.zoneId());
            return;
        }

        checkZone();
        checkDateParsing();
        checkHourMapping();
        checkDayBoundaries();
        checkCurrentHour();
        checkPercentile();
        checkErrorText();

        System.out.println();
        System.out.println("Проверок: " + checks + ", ошибок: " + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void checkZone() {
        section("Часовой пояс");
        check("по умолчанию Europe/Moscow", "Europe/Moscow", AddonTime.zoneId());
        check("зона IANA", true, AddonTime.zone().getRules().isFixedOffset()
                || "Europe/Moscow".equals(AddonTime.zoneId()));
    }

    private static void checkDateParsing() {
        section("Разбор даты (строгий)");
        check("обычная дата", LocalDate.of(2026, 11, 20), AddonTime.parseDate("2026-11-20"));
        check("с пробелами", LocalDate.of(2026, 11, 20), AddonTime.parseDate("  2026-11-20  "));
        check("30 февраля отвергается", true, throwsParse("2026-02-30"));
        check("13-й месяц отвергается", true, throwsParse("2026-13-01"));
        check("точки вместо дефисов", true, throwsParse("20.11.2026"));
        check("пустая строка", true, throwsParse(""));
        check("мусор", true, throwsParse("not-a-date"));
    }

    private static void checkHourMapping() {
        section("Раскладка по часам (Europe/Moscow)");
        long noon = instant(2026, 11, 20, 12, 34, 56);
        check("12:34:56 → час 12", 12, AddonTime.hourOf(noon));
        check("формат в зоне аддона", "2026-11-20 12:34:56", AddonTime.format(noon));

        long midnight = instant(2026, 11, 20, 0, 0, 0);
        check("00:00 → час 0", 0, AddonTime.hourOf(midnight));
        check("дата от 00:00", LocalDate.of(2026, 11, 20), AddonTime.dateOf(midnight));

        long lateNight = instant(2026, 11, 20, 23, 59, 59);
        check("23:59:59 → час 23", 23, AddonTime.hourOf(lateNight));

        // 21:00 UTC = 00:00 Москвы: раньше именно здесь и «съезжали» часы на 3.
        long utc2100 = LocalDateTime.of(2026, 11, 19, 21, 0, 0)
                .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        check("21:00 UTC → час 0 по Москве", 0, AddonTime.hourOf(utc2100));
    }

    private static void checkDayBoundaries() {
        section("Границы суток");
        LocalDate day = LocalDate.of(2026, 11, 20);
        long start = AddonTime.startOfDay(day).getTime();
        long next = AddonTime.startOfNextDay(day).getTime();
        check("сутки = 24 часа (Москва без перехода)", 24L * 3600L * 1000L, next - start);
        check("начало суток = 00:00 местного", 0, AddonTime.hourOf(start));
        check("следующие сутки = 00:00 местного", 0, AddonTime.hourOf(next));
        check("дата следующего дня", LocalDate.of(2026, 11, 21), AddonTime.dateOf(next));
    }

    private static void checkCurrentHour() {
        section("Отсечка текущего часа");
        long now = instant(2026, 11, 20, 15, 47, 12);
        long cutOff = AddonTime.startOfCurrentHour(now);
        check("15:47 → 15:00", instant(2026, 11, 20, 15, 0, 0), cutOff);
        check("сэмпл 14:59 попадает", true, instant(2026, 11, 20, 14, 59, 0) < cutOff);
        check("сэмпл 15:00 отсекается", true, instant(2026, 11, 20, 15, 0, 0) >= cutOff);
        check("граница часов не сдвигается", 0L, cutOff % 3600000L);
    }

    private static void checkPercentile() {
        section("Процентиль");
        List<Double> hundred = new ArrayList<Double>();
        for (int i = 1; i <= 100; i++) {
            hundred.add((double) i);
        }
        check("95 из 1..100", 95.0, Stats.percentile95(hundred));
        check("один элемент", 21.5, Stats.percentile95(Collections.singletonList(21.5)));
        check("пусто → null", null, Stats.percentile95(Collections.<Double>emptyList()));
        check("null → null", null, Stats.percentile95(null));
        check("не упорядочен", 30.0, Stats.percentile95(Arrays.asList(30.0, 10.0, 20.0)));
        check("процентиль 0", 10.0, Stats.percentile(Arrays.asList(10.0, 20.0, 30.0), 0));
    }

    private static void checkErrorText() {
        section("Текст ошибок и JSON");
        check("исключение без сообщения", "RuntimeException",
                JsonResponse.describe(new RuntimeException()));
        check("исключение с сообщением", "IllegalStateException: сломалось",
                JsonResponse.describe(new IllegalStateException(" сломалось ")));
        check("null", "неизвестная ошибка", JsonResponse.describe(null));

        // Ровно та ошибка, из-за которой клиент получал {} вместо текста.
        JSONObject withNull = new JSONObject();
        withNull.put("error", (String) null);
        check("put(key, null) удаляет ключ (поведение org.json)", false, withNull.has("error"));
        check("JSONObject.NULL сохраняет ключ", true, keepsNullKey());
    }

    private static boolean keepsNullKey() {
        JSONObject json = new JSONObject();
        json.put("error", JSONObject.NULL);
        return json.has("error");
    }

    // ---------- вспомогательное ----------

    private static long instant(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli();
    }

    private static boolean throwsParse(String text) {
        try {
            AddonTime.parseDate(text);
            return false;
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + name + ": ожидалось [" + expected + "], получено [" + actual + "]");
        } else {
            System.out.println("  ok    " + name);
        }
    }
}
