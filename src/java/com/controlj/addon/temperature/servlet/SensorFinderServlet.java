package com.controlj.addon.temperature.servlet;

import com.controlj.addon.temperature.util.JsonResponse;
import com.controlj.green.addonsupport.InvalidConnectionRequestException;
import com.controlj.green.addonsupport.access.DirectAccess;
import com.controlj.green.addonsupport.access.Location;
import com.controlj.green.addonsupport.access.ReadAction;
import com.controlj.green.addonsupport.access.SystemAccess;
import com.controlj.green.addonsupport.access.SystemConnection;
import com.controlj.green.addonsupport.access.SystemTree;
import com.controlj.green.addonsupport.access.Tree;
import com.controlj.green.addonsupport.access.UnresolvableException;
import com.controlj.green.addonsupport.access.aspect.TrendSource;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Поиск аналоговых трендов по маскам имён в географическом дереве.
 *
 * <pre>
 * GET /sensors/find?path=%23building&amp;masks=zone_temp&amp;match=exact
 * </pre>
 *
 * <p>Режимы {@code match}:</p>
 * <ul>
 *   <li>{@code exact} (по умолчанию) — имя точки точно равно маске;</li>
 *   <li>{@code contains} — имя содержит маску (старое поведение);</li>
 *   <li>{@code regex} — регулярное выражение (поиск подстроки).</li>
 * </ul>
 *
 * <p>Точное совпадение по умолчанию убирает главную проблему прежней версии: маска
 * {@code zone_temp} находила ещё и {@code zone_temp_sp}, {@code zone_temp_setpoint},
 * {@code zone_temp_avg} — уставки и средние попадали в карту температур как измерения.</p>
 *
 * <p>Поиск идёт под соединением оператора (а не root, как раньше): список датчиков и
 * данные читаются с одинаковыми правами, и аддон не показывает локации, закрытые для
 * оператора.</p>
 */
public class SensorFinderServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MASK = "zone_temp";

    /** Ограничение на количество найденных точек, чтобы не отдать браузеру мегабайты. */
    private static final int MAX_RESULTS = 2000;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = trimToNull(request.getParameter("path"));
        String masksText = trimToNull(request.getParameter("masks"));
        String matchMode = trimToNull(request.getParameter("match"));
        if (path == null) {
            path = "#";
        }
        if (masksText == null) {
            masksText = DEFAULT_MASK;
        }
        if (matchMode == null) {
            matchMode = NameMatcher.MODE_EXACT;
        }

        List<String> masks = new ArrayList<String>();
        for (String part : masksText.split(",")) {
            String mask = part.trim();
            if (!mask.isEmpty()) {
                masks.add(mask);
            }
        }
        if (masks.isEmpty()) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Пустой список масок: укажите имена точек, например zone_temp");
            return;
        }

        final NameMatcher matcher;
        try {
            matcher = new NameMatcher(masks, matchMode);
        } catch (PatternSyntaxException e) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Неверное регулярное выражение «" + e.getPattern() + "»: " + e.getDescription());
            return;
        }

        final String startPath = path;
        final List<Sensor> found = new ArrayList<Sensor>();

        try {
            SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);
            connection.runReadAction(new ReadAction() {
                @Override
                public void execute(SystemAccess access) throws Exception {
                    Tree geographic = access.getTree(SystemTree.Geographic);
                    Location start = "#".equals(startPath) || startPath.isEmpty()
                            ? geographic.getRoot()
                            : geographic.resolve(startPath);
                    walk(start, "", "", new ArrayList<Ancestor>(), matcher, found);
                }
            });
        } catch (InvalidConnectionRequestException e) {
            JsonResponse.error(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Нет сессии оператора WebCTRL — откройте аддон из интерфейса WebCTRL");
            return;
        } catch (Exception e) {
            // Ветка дерева разрешается внутри read-действия, поэтому UnresolvableException
            // приходит обёрнутой в ActionExecutionException — ищем её в цепочке причин.
            if (hasCause(e, UnresolvableException.class)) {
                JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Ветка дерева не найдена: " + startPath);
                return;
            }
            log("Не удалось выполнить поиск датчиков", e);
            JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Ошибка поиска датчиков", e);
            return;
        }

        buildLabels(found);

        boolean truncated = found.size() >= MAX_RESULTS;

        JSONArray sensorsJson = new JSONArray();
        for (Sensor sensor : found) {
            sensorsJson.put(sensor.toJson());
        }

        JSONObject body = new JSONObject();
        body.put("sensors", sensorsJson);
        body.put("count", found.size());
        body.put("truncated", truncated);
        body.put("match", matcher.mode());
        body.put("masks", new JSONArray(masks));
        JsonResponse.ok(response, body);
    }

    /**
     * Рекурсивный обход дерева.
     *
     * @param location    текущая локация
     * @param refPath     путь из reference-имён до родителя текущей локации (без '#')
     * @param displayPath отображаемый путь до родителя текущей локации
     * @param ancestors   предки текущей локации (для сборки дерева в интерфейсе)
     */
    private void walk(Location location, String refPath, String displayPath,
                      List<Ancestor> ancestors, NameMatcher matcher, List<Sensor> found) {
        if (found.size() >= MAX_RESULTS) {
            return;
        }

        String referenceName = location.getReferenceName();
        String displayName = location.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = referenceName;
        }

        String childRefPath = refPath;
        String childDisplayPath = displayPath;
        List<Ancestor> childAncestors = ancestors;

        // Корень дерева может отдавать в качестве имени "#" — в путь его включать нельзя,
        // иначе получится "##/building" и Tree.resolve() его не найдёт.
        boolean named = referenceName != null
                && !referenceName.trim().isEmpty()
                && !"#".equals(referenceName.trim());

        if (named) {
            childRefPath = refPath.isEmpty() ? referenceName : refPath + "/" + referenceName;
            childDisplayPath = displayPath.isEmpty()
                    ? displayName
                    : displayPath + " / " + displayName;

            if (matcher.matches(referenceName) && hasAnalogTrend(location)) {
                found.add(new Sensor(location, referenceName, displayName,
                        childRefPath, childDisplayPath, ancestors));
            }

            childAncestors = new ArrayList<Ancestor>(ancestors);
            childAncestors.add(new Ancestor("#" + childRefPath, displayName, childDisplayPath));
        }

        for (Location child : location.getChildren()) {
            walk(child, childRefPath, childDisplayPath, childAncestors, matcher, found);
        }
    }

    private boolean hasAnalogTrend(Location location) {
        if (!location.hasAspect(TrendSource.class)) {
            return false;
        }
        try {
            TrendSource source = location.getAspect(TrendSource.class);
            return source != null && source.getType() == TrendSource.Type.Analog;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Подписи строк: одна точка в комнате — подписываем комнатой, несколько — «комната · точка».
     * Так строка тепловой карты остаётся «комнатой», но второй датчик в комнате не теряется.
     */
    private void buildLabels(List<Sensor> sensors) {
        Map<String, Integer> perRoom = new HashMap<String, Integer>();
        for (Sensor sensor : sensors) {
            Integer count = perRoom.get(sensor.roomPath);
            perRoom.put(sensor.roomPath, count == null ? 1 : count + 1);
        }
        for (Sensor sensor : sensors) {
            String point = sensor.pointName == null || sensor.pointName.trim().isEmpty()
                    ? sensor.name
                    : sensor.pointName.trim();
            String room = sensor.roomName == null ? "" : sensor.roomName.trim();
            Integer count = perRoom.get(sensor.roomPath);
            if (room.isEmpty()) {
                sensor.label = point;
            } else if (count != null && count > 1) {
                sensor.label = room + " · " + point;
            } else {
                sensor.label = room;
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Матчер имён точек. */
    private static final class NameMatcher {

        static final String MODE_EXACT = "exact";
        static final String MODE_CONTAINS = "contains";
        static final String MODE_REGEX = "regex";

        private final String mode;
        private final List<String> values = new ArrayList<String>();
        private final List<Pattern> patterns = new ArrayList<Pattern>();

        private NameMatcher(List<String> masks, String requestedMode) {
            String normalized = requestedMode.toLowerCase(Locale.ROOT);
            if (MODE_CONTAINS.equals(normalized)) {
                mode = MODE_CONTAINS;
                for (String mask : masks) {
                    values.add(mask.toLowerCase(Locale.ROOT));
                }
            } else if (MODE_REGEX.equals(normalized)) {
                mode = MODE_REGEX;
                for (String mask : masks) {
                    patterns.add(Pattern.compile(mask));
                }
            } else {
                mode = MODE_EXACT;
                for (String mask : masks) {
                    values.add(mask);
                }
            }
        }

        private String mode() {
            return mode;
        }

        private boolean matches(String name) {
            if (name == null) {
                return false;
            }
            if (MODE_REGEX.equals(mode)) {
                for (Pattern pattern : patterns) {
                    if (pattern.matcher(name).find()) {
                        return true;
                    }
                }
                return false;
            }
            if (MODE_CONTAINS.equals(mode)) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (String value : values) {
                    if (lower.contains(value)) {
                        return true;
                    }
                }
                return false;
            }
            for (String value : values) {
                if (value.equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Узел дерева над датчиком. */
    private static final class Ancestor {

        private final String path;
        private final String label;
        private final String displayPath;

        private Ancestor(String path, String label, String displayPath) {
            this.path = path;
            this.label = label;
            this.displayPath = displayPath;
        }

        private JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("path", path);
            json.put("label", label == null ? JSONObject.NULL : label);
            json.put("displayPath", displayPath == null ? JSONObject.NULL : displayPath);
            return json;
        }
    }

    /** Найденный датчик. */
    private static final class Sensor {

        private final String name;
        private final String pointName;
        private final String path;
        private final String displayPath;
        private final String roomPath;
        private final String roomDisplayPath;
        private final String roomName;
        private final boolean trendEnabled;
        private final List<Ancestor> ancestors;
        private String label;

        private Sensor(Location location, String name, String pointName, String refPath,
                       String displayPath, List<Ancestor> ancestors) {
            this.name = name;
            this.pointName = pointName;
            this.path = "#" + refPath;
            this.displayPath = displayPath;

            Ancestor room = ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1);
            this.roomPath = room == null ? "" : room.path;
            this.roomDisplayPath = room == null ? "" : room.displayPath;
            this.roomName = room == null ? "" : room.label;

            // В дереве интерфейса датчик должен лежать под папками выше комнаты:
            // сама комната уже есть в подписи датчика.
            this.ancestors = ancestors.isEmpty()
                    ? new ArrayList<Ancestor>()
                    : new ArrayList<Ancestor>(ancestors.subList(0, ancestors.size() - 1));

            this.trendEnabled = isTrendEnabled(location);
            this.label = name;
        }

        private static boolean isTrendEnabled(Location location) {
            try {
                TrendSource source = location.getAspect(TrendSource.class);
                return source != null && source.isEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", path);
            json.put("path", path);
            json.put("name", name);
            json.put("pointName", pointName == null ? JSONObject.NULL : pointName);
            json.put("displayPath", displayPath);
            json.put("roomPath", roomPath);
            json.put("roomDisplayPath", roomDisplayPath);
            json.put("roomName", roomName);
            json.put("label", label);
            json.put("trendEnabled", trendEnabled);

            JSONArray ancestorsJson = new JSONArray();
            for (Ancestor ancestor : ancestors) {
                ancestorsJson.put(ancestor.toJson());
            }
            json.put("ancestors", ancestorsJson);
            return json;
        }
    }
}
