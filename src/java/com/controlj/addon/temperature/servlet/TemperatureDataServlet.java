package com.controlj.addon.temperature.servlet;

import com.controlj.addon.temperature.util.AddonTime;
import com.controlj.addon.temperature.util.JsonResponse;
import com.controlj.addon.temperature.util.Stats;
import com.controlj.green.addonsupport.InvalidConnectionRequestException;
import com.controlj.green.addonsupport.access.DirectAccess;
import com.controlj.green.addonsupport.access.Location;
import com.controlj.green.addonsupport.access.ReadAction;
import com.controlj.green.addonsupport.access.SystemAccess;
import com.controlj.green.addonsupport.access.SystemConnection;
import com.controlj.green.addonsupport.access.SystemTree;
import com.controlj.green.addonsupport.access.Tree;
import com.controlj.green.addonsupport.access.UnresolvableException;
import com.controlj.green.addonsupport.access.aspect.AnalogTrendSource;
import com.controlj.green.addonsupport.access.aspect.TrendSource;
import com.controlj.green.addonsupport.access.trend.TrendAnalogSample;
import com.controlj.green.addonsupport.access.trend.TrendData;
import com.controlj.green.addonsupport.access.trend.TrendRangeFactory;
import com.controlj.green.addonsupport.access.trend.TrendType;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Почасовые средние температуры за сутки.
 *
 * <pre>
 * GET /temperature/data?id=&lt;path&gt;&amp;date=yyyy-MM-dd[&amp;debug=1]
 * GET /temperature/data?ids=&lt;path1,path2,...&gt;&amp;date=yyyy-MM-dd
 * </pre>
 *
 * <p>Несколько датчиков читаются одним запросом и в одном {@code runReadAction}: раньше
 * интерфейс делал отдельный HTTP-запрос (и отдельное чтение тренда) на каждую комнату,
 * из-за чего «Показать все номера» на сотне комнат занимало минуты.</p>
 *
 * <p>Что изменилось по сравнению с 1.1.1:</p>
 * <ul>
 *   <li>в расчёт попадают только сэмплы {@link TrendType#DATA} — служебные метки
 *       (связи нет, неизвестное значение, отказ) больше не превращаются в температуру;</li>
 *   <li>весь расчёт — в зоне {@link AddonTime} (по умолчанию Europe/Moscow), без «+3 часа»;</li>
 *   <li>правый край суток исключается, сэмпл следующего дня не попадает в час 0;</li>
 *   <li>нет {@code SimpleDateFormat} в поле сервлета — нет гонки между запросами;</li>
 *   <li>ошибка одного датчика не роняет остальные;</li>
 *   <li>сырые сэмплы отдаются только по запросу {@code debug=1}.</li>
 * </ul>
 */
public class TemperatureDataServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * Сколько датчиков разрешено запросить за один раз. Ограничение — длина строки запроса:
     * идентификатор точки вида {@code #hotel/1/101/zone_temp} в URL занимает ~40 байт,
     * а Tomcat по умолчанию принимает строку запроса не длиннее 8 КБ.
     */
    private static final int MAX_SENSORS = 100;

    private static final long HOURS_IN_DAY = 24;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String dateText = request.getParameter("date");
        String[] ids = collectIds(request);

        if (ids.length == 0) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Не задан датчик: нужен параметр id или ids");
            return;
        }
        if (ids.length > MAX_SENSORS) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Слишком много датчиков за один запрос: " + ids.length + " (максимум " + MAX_SENSORS + ")");
            return;
        }
        if (dateText == null || dateText.trim().isEmpty()) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Не задана дата: нужен параметр date в формате yyyy-MM-dd");
            return;
        }

        final LocalDate day;
        try {
            day = AddonTime.parseDate(dateText);
        } catch (DateTimeParseException e) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Неверная дата «" + dateText.trim() + "»: ожидается yyyy-MM-dd");
            return;
        }

        final long startMillis = AddonTime.startOfDay(day).getTime();
        final long endMillis = AddonTime.startOfNextDay(day).getTime();
        final boolean today = AddonTime.isToday(day);
        // За сегодня показываем только полностью закрытые часы: текущий час ещё неполный.
        final long cutOffMillis = today ? AddonTime.startOfCurrentHour(System.currentTimeMillis()) : Long.MAX_VALUE;
        final boolean withRawSamples = "1".equals(request.getParameter("debug"));

        final List<SensorReading> readings = new ArrayList<SensorReading>();

        try {
            SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);
            connection.runReadAction(new ReadAction() {
                @Override
                public void execute(SystemAccess access) throws Exception {
                    Tree geographic = access.getTree(SystemTree.Geographic);
                    for (String id : ids) {
                        readings.add(readSensor(geographic, id, startMillis, endMillis, cutOffMillis,
                                withRawSamples));
                    }
                }
            });
        } catch (InvalidConnectionRequestException e) {
            JsonResponse.error(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Нет сессии оператора WebCTRL — откройте аддон из интерфейса WebCTRL");
            return;
        } catch (Exception e) {
            log("Не удалось прочитать тренды температур", e);
            JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Ошибка чтения трендов", e);
            return;
        }

        JSONObject body = new JSONObject();
        body.put("date", day.toString());
        body.put("today", AddonTime.today().toString());
        body.put("isToday", today);
        body.put("currentHour", AddonTime.hourOf(System.currentTimeMillis()));
        body.put("timezone", AddonTime.zoneId());

        JSONArray sensors = new JSONArray();
        for (SensorReading reading : readings) {
            sensors.put(reading.toJson());
        }
        body.put("sensors", sensors);

        JsonResponse.ok(response, body);
    }

    /** Достаёт список идентификаторов из параметров {@code ids} (приоритет) или {@code id}. */
    private String[] collectIds(HttpServletRequest request) {
        List<String> ids = new ArrayList<String>();
        String idsParam = request.getParameter("ids");
        if (idsParam != null) {
            for (String part : idsParam.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            String idParam = request.getParameter("id");
            if (idParam != null && !idParam.trim().isEmpty()) {
                ids.add(idParam.trim());
            }
        }
        return ids.toArray(new String[ids.size()]);
    }

    /**
     * Читает один датчик. Любая проблема у конкретной точки возвращается в поле
     * {@code error} этого датчика и не мешает остальным.
     */
    private SensorReading readSensor(Tree geographic, String id, long startMillis, long endMillis,
                                     long cutOffMillis, boolean withRawSamples) {
        SensorReading reading = new SensorReading(id);

        try {
            Location location = geographic.resolve(id);
            reading.displayName = location.getDisplayName();
            reading.referenceName = location.getReferenceName();
            if (location.hasParent()) {
                try {
                    Location parent = location.getParent();
                    if (parent != null) {
                        reading.roomName = parent.getDisplayName();
                    }
                } catch (UnresolvableException ignored) {
                    // Имя комнаты нужно только для подписи строки — не критично.
                }
            }

            if (!location.hasAspect(TrendSource.class)) {
                reading.error = "у точки нет тренда";
                return reading;
            }
            TrendSource source = location.getAspect(TrendSource.class);
            if (source == null) {
                reading.error = "у точки нет тренда";
                return reading;
            }
            if (source.getType() != TrendSource.Type.Analog) {
                reading.error = "тренд не аналоговый (" + source.getType() + ")";
                return reading;
            }
            if (!source.isEnabled()) {
                reading.error = "сбор тренда отключён";
                return reading;
            }

            Map<Integer, double[]> buckets = new HashMap<Integer, double[]>();
            List<Double> values = new ArrayList<Double>();
            JSONArray rawSamples = withRawSamples ? new JSONArray() : null;

            TrendData<TrendAnalogSample> trend = ((AnalogTrendSource) source)
                    .getTrendData(TrendRangeFactory.byDateRange(new Date(startMillis), new Date(endMillis)));
            Iterator<TrendAnalogSample> samples = trend.getSamples();

            while (samples.hasNext()) {
                TrendAnalogSample sample = samples.next();
                if (sample == null) {
                    continue;
                }
                // Служебные метки тренда («связи нет», «неизвестное значение», отказ и т. п.)
                // не являются измерением и в расчёт попадать не должны.
                if (sample.getType() != TrendType.DATA) {
                    continue;
                }
                float special = sample.getSpecialValue();
                if (special != 0.0f && !Float.isNaN(special)) {
                    continue;
                }
                long time = sample.getTimeInMillis();
                if (time < startMillis || time >= endMillis) {
                    continue;
                }
                if (time >= cutOffMillis) {
                    continue;
                }

                double value;
                try {
                    value = sample.doubleValue();
                } catch (RuntimeException e) {
                    continue;
                }
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    continue;
                }

                int hour = AddonTime.hourOf(time);
                double[] accumulator = buckets.get(hour);
                if (accumulator == null) {
                    accumulator = new double[]{0.0, 0.0};
                    buckets.put(hour, accumulator);
                }
                accumulator[0] += value;
                accumulator[1] += 1.0;
                values.add(value);

                if (rawSamples != null) {
                    JSONObject item = new JSONObject();
                    item.put("t", time);
                    item.put("v", value);
                    item.put("lt", AddonTime.format(time));
                    item.put("h", hour);
                    rawSamples.put(item);
                }
            }

            reading.hourly = new Double[(int) HOURS_IN_DAY];
            for (int hour = 0; hour < HOURS_IN_DAY; hour++) {
                double[] accumulator = buckets.get(hour);
                reading.hourly[hour] = accumulator == null || accumulator[1] == 0.0
                        ? null
                        : accumulator[0] / accumulator[1];
            }
            reading.sampleCount = values.size();
            reading.percentile95 = Stats.percentile95(values);
            reading.rawSamples = rawSamples;
            if (values.isEmpty()) {
                reading.error = "нет данных за выбранные сутки";
            }
        } catch (UnresolvableException e) {
            reading.error = "точка не найдена в дереве: " + id;
        } catch (Exception e) {
            reading.error = JsonResponse.describe(e);
        }

        return reading;
    }

    /** Результат чтения одного датчика. */
    private static final class SensorReading {

        private final String id;
        private String referenceName;
        private String displayName;
        private String roomName;
        private String error;
        private Double[] hourly;
        private Double percentile95;
        private int sampleCount;
        private JSONArray rawSamples;

        private SensorReading(String id) {
            this.id = id;
        }

        private JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", referenceName == null ? JSONObject.NULL : referenceName);
            json.put("displayName", displayName == null ? JSONObject.NULL : displayName);
            json.put("roomName", roomName == null ? JSONObject.NULL : roomName);
            json.put("sampleCount", sampleCount);
            json.put("percentile95", percentile95 == null ? JSONObject.NULL : percentile95);

            JSONArray hourlyJson = new JSONArray();
            for (int hour = 0; hour < HOURS_IN_DAY; hour++) {
                JSONObject item = new JSONObject();
                item.put("hour", hour);
                Double value = hourly == null ? null : hourly[hour];
                item.put("value", value == null ? JSONObject.NULL : value);
                hourlyJson.put(item);
            }
            json.put("hourly", hourlyJson);

            if (error != null) {
                json.put("error", error);
            }
            if (rawSamples != null) {
                json.put("samples", rawSamples);
            }
            return json;
        }
    }
}
