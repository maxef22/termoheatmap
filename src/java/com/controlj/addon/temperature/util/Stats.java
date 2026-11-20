package com.controlj.addon.temperature.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Простая статистика для тепловой карты.
 *
 * <p>Раньше расчёт процентиля был скопирован в двух сервлетах и третьим экземпляром
 * в JavaScript, причём серверный вариант при отсутствии данных возвращал «магическое»
 * число 25.0, которое потом попадало в легенду как настоящее значение. Здесь:
 * нет данных — {@code null}, и это честно видно в ответе.</p>
 */
public final class Stats {

    private Stats() {
    }

    /**
     * 95-й процентиль методом ближайшего ранга.
     *
     * @return значение или {@code null}, если данных нет
     */
    public static Double percentile95(Collection<Double> values) {
        return percentile(values, 95.0);
    }

    /**
     * Процентиль методом ближайшего ранга (nearest rank).
     *
     * @param values значения (могут быть {@code null})
     * @param percent процент от 0 до 100
     * @return значение или {@code null}, если данных нет
     */
    public static Double percentile(Collection<Double> values, double percent) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }
        return sorted.get(index);
    }
}
