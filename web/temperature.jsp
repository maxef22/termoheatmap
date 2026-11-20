<%@ page contentType="text/html;charset=UTF-8" language="java"
         import="com.controlj.addon.temperature.util.AddonTime" %>
<%--
    Тепловая карта температур по помещениям.

    Версия 1.1.2. Что изменилось в интерфейсе:
      * «сегодня» и «текущий час» приходят с сервера — раньше они считались по UTC и
        в Москве с 00:00 до 03:00 показывали вчерашнюю дату;
      * дерево датчиков строится по путям локаций, а не по отображаемым именам —
        второй датчик в комнате больше не теряется, одноимённые комнаты не сливаются;
      * все выбранные комнаты читаются одним запросом (было — по запросу на комнату);
      * цветовая шкала и легенда строятся из одного массива порогов;
      * JavaScript в стиле ES5 + XMLHttpRequest — работает и в старых браузерах.
--%>
<%
    String todayText;
    String zoneText;
    try {
        todayText = AddonTime.today().toString();
        zoneText = AddonTime.zoneId();
    } catch (RuntimeException e) {
        todayText = "";
        zoneText = "";
    }
%>
<!DOCTYPE html>
<html>
<head>
    <title>Температурный мониторинг</title>
    <meta charset="UTF-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { background: #1a1a1a; color: #f0f0f0; font-family: Arial, sans-serif; margin: 20px; }
        h1 { color: #4CAF50; text-align: center; font-size: 20px; margin: 0 0 15px 0; }

        .controls {
            background: #2a2a2a;
            padding: 10px 15px;
            border-radius: 8px;
            margin-bottom: 15px;
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
            align-items: center;
            justify-content: center;
        }
        .controls label { color: #4CAF50; font-size: 12px; }
        .controls input, .controls select, .controls button {
            padding: 5px 10px;
            border-radius: 4px;
            border: none;
            background: #3a3a3a;
            color: white;
            font-size: 12px;
        }
        .controls button { background: #4CAF50; color: white; font-weight: bold; cursor: pointer; }
        .controls button:hover { background: #45a049; }
        .hint { color: #8a8a8a; font-size: 10px; }

        table { border-collapse: collapse; margin: 20px auto; background: #0d0d0d; font-size: 11px; }
        th, td { border: 1px solid #333; padding: 5px 6px; text-align: center; }
        th { background: #2a2a2a; color: #4CAF50; }
        th.future { color: #555; }
        .room-col { background: #2a2a2a; font-weight: bold; text-align: left; white-space: nowrap; }
        .future { background: #1f1f1f !important; color: #555 !important; }

        .legend { width: 520px; margin: 15px auto 0 auto; background: #2a2a2a; padding: 6px 12px; border-radius: 20px; }
        .legend-bar { height: 18px; border-radius: 4px; }
        .legend-ticks { position: relative; width: 520px; height: 16px; margin: 0 auto; }
        .legend-tick { position: absolute; top: 2px; font-size: 9px; color: #ccc; white-space: nowrap; }
        .legend-note { text-align: center; font-size: 10px; color: #aaa; margin-top: 2px; }

        .footer { text-align: center; margin-top: 15px; font-size: 10px; color: #888; }
        .error { color: #ff6b6b; text-align: center; margin: 10px; font-size: 12px; min-height: 16px; }

        .tree { list-style: none; padding-left: 0; margin: 0; }
        .tree-children { list-style: none; padding-left: 16px; margin: 0; }
        .tree li { margin: 2px 0; }
        .tree-row { display: flex; align-items: center; }
        .tree .node {
            display: inline-block;
            padding: 2px 5px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 12px;
        }
        .tree .node:hover { background-color: #3a3a3a; }
        .tree .folder-icon::before { content: "\1F4C1  "; }
        .tree .leaf-icon::before { content: "\1F321  "; }
        .tree .selected { background-color: #4CAF50; color: #1a1a1a; }
        .tree-checkbox { margin-right: 4px; cursor: pointer; width: 12px; height: 12px; }
        .tree-container {
            max-height: 300px;
            overflow-y: auto;
            background: #2a2a2a;
            padding: 8px;
            border-radius: 8px;
            margin: 0 0 15px 0;
        }

        .selection-bar {
            background: #2a2a2a;
            padding: 6px 12px;
            border-radius: 8px;
            margin-bottom: 15px;
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
            align-items: center;
            justify-content: space-between;
        }
        .selection-info { color: #4CAF50; font-size: 11px; }
        .rooms-btn {
            background: #4CAF50;
            color: white;
            border: none;
            border-radius: 4px;
            padding: 5px 12px;
            cursor: pointer;
            font-weight: bold;
            font-size: 12px;
        }
        .rooms-btn:hover { background: #45a049; }

        .heatmap-container { overflow-x: auto; }
        .heatmap-table th:first-child,
        .heatmap-table td:first-child {
            position: sticky;
            left: 0;
            background: #2a2a2a;
            z-index: 1;
        }
    </style>
</head>
<body>
<h1>🌡️ Температурный мониторинг</h1>

<div class="controls">
    <select id="rootSelect" style="min-width: 150px;">
        <option value="">— выберите область —</option>
    </select>
    <input type="text" id="treePath" value="" size="22" placeholder="#building или ветка дерева"/>
    <input type="text" id="masks" value="zone_temp" size="14" title="Имена точек через запятую"/>
    <select id="matchMode" title="Как сравнивать имя точки с маской">
        <option value="exact">имя = маска</option>
        <option value="contains">имя содержит</option>
        <option value="regex">регулярное выражение</option>
    </select>
    <button id="findBtn" type="button">🔍 Найти</button>
    <button id="treeToggleBtn" type="button">📂 Дерево</button>
    <label for="targetDate">📅</label>
    <input type="date" id="targetDate" style="width: 130px;" value="<%= todayText %>" max="<%= todayText %>"/>
</div>

<div id="treeContainer" class="tree-container" style="display: none;"></div>

<div class="selection-bar">
    <span id="selectionInfo" class="selection-info">📌 Датчики не выбраны</span>
    <div>
        <button id="clearBtn" type="button" style="display:none; background:#555; color:#fff; border:none; border-radius:4px; padding:3px 8px; margin-right:8px; cursor:pointer;">✖ Сброс</button>
        <button id="showAllBtn" class="rooms-btn" type="button">🏨 Показать все номера</button>
    </div>
</div>

<div id="legendPanel"></div>

<div id="errorMsg" class="error"></div>
<div id="heatmapContainer" class="heatmap-container">
    <div class="footer">Выберите датчики в дереве и нажмите «Показать все номера»</div>
</div>

<div class="footer">
    Синий (&lt;18 °C) → зелёный (комфорт 21–22 °C) → жёлтый → оранжевый → красный (&gt;26 °C)&nbsp;·&nbsp;
    часовой пояс: <%= zoneText %>
</div>

<script type="text/javascript">
/* ======================= состояние ======================= */

/* Сервер принимает не больше 100 датчиков за запрос (ограничение длины URL),
   поэтому список уходит порциями по 50 — это те же 2 запроса на 100 комнат. */
var REQUEST_CHUNK = 50;

var selectedSensors = [];
var allSensorsList = [];
var sensorsById = {};
var treeVisible = false;

/* ======================= утилиты ======================= */

function byId(elementId) {
    return document.getElementById(elementId);
}

function trimText(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value).replace(/^\s+|\s+$/g, '');
}

function indexOf(array, value) {
    for (var i = 0; i < array.length; i++) {
        if (array[i] === value) {
            return i;
        }
    }
    return -1;
}

function showError(message) {
    byId('errorMsg').textContent = message === null || message === undefined ? '' : message;
}

function setStatus(message) {
    var container = byId('heatmapContainer');
    container.innerHTML = '';
    var div = document.createElement('div');
    div.className = 'footer';
    div.textContent = message;
    container.appendChild(div);
}

/* Единый способ получить JSON: XMLHttpRequest вместо fetch — работает и в старых браузерах. */
function getJson(url, onSuccess, onFailure) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) {
            return;
        }
        var data = null;
        if (xhr.responseText) {
            try {
                data = JSON.parse(xhr.responseText);
            } catch (parseError) {
                data = null;
            }
        }
        if (xhr.status >= 200 && xhr.status < 300 && data) {
            onSuccess(data);
        } else if (data && data.error) {
            onFailure(data.error);
        } else if (xhr.status === 0) {
            onFailure('сервер не отвечает (соединение прервано)');
        } else if (xhr.status === 401) {
            onFailure('нет сессии оператора — откройте аддон из интерфейса WebCTRL');
        } else {
            onFailure('HTTP ' + xhr.status + ', ответ сервера не разобран');
        }
    };
    xhr.send(null);
}

/* ======================= цветовая шкала ======================= */

/* Один массив порогов на цвет ячейки и на легенду — иначе они расходятся. */
var COLOR_STOPS = [
    { t: 16, c: '#1565C0' },
    { t: 18, c: '#2196F3' },
    { t: 21, c: '#4CAF50' },
    { t: 22, c: '#4CAF50' },
    { t: 24, c: '#FFEB3B' },
    { t: 26, c: '#F44336' },
    { t: 28, c: '#B71C1C' }
];
var SCALE_MIN = COLOR_STOPS[0].t;
var SCALE_MAX = COLOR_STOPS[COLOR_STOPS.length - 1].t;

function hexToRgb(hex) {
    return [
        parseInt(hex.substr(1, 2), 16),
        parseInt(hex.substr(3, 2), 16),
        parseInt(hex.substr(5, 2), 16)
    ];
}

function toHex(value) {
    var text = Math.round(value).toString(16).toUpperCase();
    return text.length === 1 ? '0' + text : text;
}

function interpolateColor(from, to, ratio) {
    var a = hexToRgb(from);
    var b = hexToRgb(to);
    return '#' +
        toHex(a[0] + (b[0] - a[0]) * ratio) +
        toHex(a[1] + (b[1] - a[1]) * ratio) +
        toHex(a[2] + (b[2] - a[2]) * ratio);
}

/* Возвращает цвет ячейки или null, если значения нет. */
function getColorForTemperature(temperature) {
    if (temperature === null || temperature === undefined) {
        return null;
    }
    var value = Number(temperature);
    if (isNaN(value)) {
        return null;
    }
    if (value <= SCALE_MIN) {
        return COLOR_STOPS[0].c;
    }
    if (value >= SCALE_MAX) {
        return COLOR_STOPS[COLOR_STOPS.length - 1].c;
    }
    for (var i = 0; i < COLOR_STOPS.length - 1; i++) {
        var lower = COLOR_STOPS[i];
        var upper = COLOR_STOPS[i + 1];
        if (value >= lower.t && value <= upper.t) {
            var ratio = (value - lower.t) / (upper.t - lower.t);
            return interpolateColor(lower.c, upper.c, ratio);
        }
    }
    return COLOR_STOPS[COLOR_STOPS.length - 1].c;
}

/* Цвет текста по яркости фона: на синем/красном чёрный текст не читается. */
function textColorFor(hex) {
    var rgb = hexToRgb(hex);
    var luminance = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2];
    return luminance > 150 ? '#111111' : '#ffffff';
}

function buildGradient() {
    var parts = [];
    for (var i = 0; i < COLOR_STOPS.length; i++) {
        var position = (COLOR_STOPS[i].t - SCALE_MIN) / (SCALE_MAX - SCALE_MIN) * 100;
        parts.push(COLOR_STOPS[i].c + ' ' + position.toFixed(1) + '%');
    }
    return 'linear-gradient(to right, ' + parts.join(', ') + ')';
}

/* ======================= статистика ======================= */

function calculatePercentile(values, percentile) {
    if (!values || values.length === 0) {
        return null;
    }
    var sorted = values.slice(0).sort(function (a, b) { return a - b; });
    var index = Math.ceil(percentile / 100 * sorted.length) - 1;
    if (index < 0) {
        index = 0;
    }
    if (index >= sorted.length) {
        index = sorted.length - 1;
    }
    return sorted[index];
}

/* ======================= легенда ======================= */

function addTick(container, text, positionPercent, side) {
    var tick = document.createElement('span');
    tick.className = 'legend-tick';
    tick.textContent = text;
    if (side === 'left') {
        tick.style.left = '0';
    } else if (side === 'right') {
        tick.style.right = '0';
    } else {
        tick.style.left = positionPercent + '%';
        tick.style.marginLeft = '-12px';
    }
    container.appendChild(tick);
}

function renderLegend(percentile95) {
    var panel = byId('legendPanel');
    panel.innerHTML = '';

    var legend = document.createElement('div');
    legend.className = 'legend';
    var bar = document.createElement('div');
    bar.className = 'legend-bar';
    bar.style.background = buildGradient();
    legend.appendChild(bar);
    panel.appendChild(legend);

    var ticks = document.createElement('div');
    ticks.className = 'legend-ticks';
    addTick(ticks, '≤' + SCALE_MIN, 0, 'left');
    var marks = [18, 21, 22, 24, 26];
    for (var i = 0; i < marks.length; i++) {
        addTick(ticks, String(marks[i]), (marks[i] - SCALE_MIN) / (SCALE_MAX - SCALE_MIN) * 100, 'center');
    }
    addTick(ticks, '≥' + SCALE_MAX, 100, 'right');
    panel.appendChild(ticks);

    var note = document.createElement('div');
    note.className = 'legend-note';
    var text = 'Комфорт 21–22 °C';
    if (percentile95 !== null && percentile95 !== undefined && !isNaN(Number(percentile95))) {
        text += ' · 95-й процентиль: ' + Number(percentile95).toFixed(1) + ' °C';
    }
    note.textContent = text;
    panel.appendChild(note);
}

/* ======================= дерево датчиков ======================= */

function ensureFolder(node, path, label) {
    for (var i = 0; i < node.children.length; i++) {
        var candidate = node.children[i];
        if (!candidate.sensorId && candidate.path === path) {
            return candidate;
        }
    }
    var folder = { path: path, label: label, children: [], sensorId: null };
    node.children.push(folder);
    return folder;
}

function buildTree(sensors) {
    var root = { path: '', label: '', children: [], sensorId: null };
    for (var i = 0; i < sensors.length; i++) {
        var sensor = sensors[i];
        var node = root;
        var ancestors = sensor.ancestors || [];
        for (var a = 0; a < ancestors.length; a++) {
            node = ensureFolder(node, ancestors[a].path, ancestors[a].label);
        }
        node.children.push({
            path: sensor.id,
            label: sensor.label,
            children: [],
            sensorId: sensor.id,
            trendEnabled: sensor.trendEnabled !== false
        });
    }
    return root;
}

function renderTreeList(node, level) {
    var list = document.createElement('ul');
    list.className = level === 0 ? 'tree' : 'tree-children';

    for (var i = 0; i < node.children.length; i++) {
        var child = node.children[i];
        var item = document.createElement('li');
        var row = document.createElement('div');
        row.className = 'tree-row';

        var checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.className = 'tree-checkbox';

        var label = document.createElement('span');
        label.className = 'node ' + (child.sensorId ? 'leaf-icon' : 'folder-icon');
        label.textContent = child.trendEnabled === false
            ? child.label + ' (тренд отключён)'
            : child.label;
        label.title = child.path;

        if (child.sensorId) {
            checkbox.setAttribute('data-id', child.sensorId);
            checkbox.onchange = (function (sensorId) {
                return function () { toggleSensor(sensorId, this.checked); };
            })(child.sensorId);
            label.onclick = (function (sensorId, labelElement) {
                return function () { selectNode(sensorId, labelElement); };
            })(child.sensorId, label);
        } else {
            checkbox.onchange = (function (listItem) {
                return function () { toggleFolderChildren(listItem, this.checked); };
            })(item);
            label.onclick = (function (listItem) {
                return function () {
                    var children = listItem.getElementsByTagName('ul');
                    if (children.length) {
                        children[0].style.display = children[0].style.display === 'none' ? '' : 'none';
                    }
                };
            })(item);
        }

        row.appendChild(checkbox);
        row.appendChild(label);
        item.appendChild(row);

        if (!child.sensorId && child.children.length) {
            item.appendChild(renderTreeList(child, level + 1));
        }
        list.appendChild(item);
    }
    return list;
}

function toggleSensor(sensorId, checked) {
    var index = indexOf(selectedSensors, sensorId);
    if (checked) {
        if (index < 0) {
            selectedSensors.push(sensorId);
        }
    } else if (index >= 0) {
        selectedSensors.splice(index, 1);
    }
    updateSelectionInfo();
}

function toggleFolderChildren(listItem, checked) {
    var checkboxes = listItem.getElementsByTagName('input');
    for (var i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].className !== 'tree-checkbox') {
            continue;
        }
        checkboxes[i].checked = checked;
        var sensorId = checkboxes[i].getAttribute('data-id');
        if (sensorId) {
            toggleSensor(sensorId, checked);
        }
    }
    updateSelectionInfo();
}

function selectNode(sensorId, labelElement) {
    var selected = document.getElementsByClassName
        ? document.getElementsByClassName('selected')
        : [];
    for (var i = selected.length - 1; i >= 0; i--) {
        if (selected[i].className.indexOf('node') >= 0) {
            selected[i].className = selected[i].className.replace(/\s*selected/, '');
        }
    }
    labelElement.className = labelElement.className + ' selected';
    loadSensors([sensorId]);
}

function updateSelectionInfo() {
    var info = byId('selectionInfo');
    var clearButton = byId('clearBtn');
    if (selectedSensors.length === 0) {
        info.textContent = '📌 Датчики не выбраны';
        clearButton.style.display = 'none';
    } else {
        info.textContent = '✅ Выбрано: ' + selectedSensors.length + ' шт.';
        clearButton.style.display = 'inline-block';
    }
}

function clearSelection() {
    var checkboxes = byId('treeContainer').getElementsByTagName('input');
    for (var i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].className === 'tree-checkbox') {
            checkboxes[i].checked = false;
        }
    }
    selectedSensors = [];
    updateSelectionInfo();
}

function toggleTree() {
    var container = byId('treeContainer');
    if (!container) {
        return;
    }
    if (container.style.display === 'none') {
        container.style.display = '';
        treeVisible = true;
    } else {
        container.style.display = 'none';
        treeVisible = false;
    }
}

/* ======================= поиск датчиков ======================= */

function loadRoots() {
    var select = byId('rootSelect');
    getJson('tree/roots', function (json) {
        var roots = json.roots || [];
        select.innerHTML = '';
        var empty = document.createElement('option');
        empty.value = '';
        empty.textContent = '— выберите область —';
        select.appendChild(empty);
        for (var i = 0; i < roots.length; i++) {
            var option = document.createElement('option');
            option.value = roots[i].path;
            option.textContent = roots[i].displayName || roots[i].name;
            select.appendChild(option);
        }
        if (roots.length === 1) {
            select.value = roots[0].path;
            byId('treePath').value = roots[0].path;
        }
    }, function (message) {
        showError('Не удалось прочитать дерево локаций: ' + message);
    });
}

function findSensors() {
    var path = trimText(byId('treePath').value);
    var masks = trimText(byId('masks').value) || 'zone_temp';
    var matchMode = byId('matchMode').value || 'exact';
    var container = byId('treeContainer');

    if (!path) {
        showError('Укажите ветку дерева: выберите область в списке или введите путь вида #building');
        return;
    }
    if (path.charAt(0) !== '#') {
        path = '#' + path;
    }
    showError('');
    container.style.display = '';
    treeVisible = true;
    setStatus('Поиск датчиков…');

    var url = 'sensors/find?path=' + encodeURIComponent(path) +
        '&masks=' + encodeURIComponent(masks) +
        '&match=' + encodeURIComponent(matchMode);

    getJson(url, function (json) {
        var sensors = json.sensors || [];
        allSensorsList = sensors;
        sensorsById = {};
        for (var i = 0; i < sensors.length; i++) {
            sensorsById[sensors[i].id] = sensors[i];
        }
        container.innerHTML = '';
        if (!sensors.length) {
            var empty = document.createElement('div');
            empty.className = 'footer';
            empty.textContent = 'Датчики не найдены: проверьте маску «' + masks + '» и режим сравнения.';
            container.appendChild(empty);
            selectedSensors = [];
            updateSelectionInfo();
            return;
        }
        var summary = document.createElement('div');
        summary.className = 'footer';
        summary.textContent = 'Найдено датчиков: ' + sensors.length;
        container.appendChild(summary);
        container.appendChild(renderTreeList(buildTree(sensors), 0));
        if (json.truncated) {
            var warning = document.createElement('div');
            warning.className = 'footer';
            warning.textContent = 'Показаны первые ' + sensors.length + ' датчиков — сузьте ветку дерева.';
            container.appendChild(warning);
        }
        selectedSensors = [];
        updateSelectionInfo();
    }, function (message) {
        container.innerHTML = '';
        var error = document.createElement('div');
        error.className = 'footer';
        error.textContent = 'Ошибка поиска: ' + message;
        container.appendChild(error);
    });
}

/* ======================= тепловая карта ======================= */

function readHour(hourly, hour) {
    if (!hourly) {
        return null;
    }
    for (var i = 0; i < hourly.length; i++) {
        if (hourly[i].hour === hour) {
            return hourly[i].value;
        }
    }
    return null;
}

function renderHeatmap(rows, isToday, currentHour) {
    var container = byId('heatmapContainer');
    var table = document.createElement('table');
    table.className = 'heatmap-table';

    var thead = document.createElement('thead');
    var headRow = document.createElement('tr');
    var corner = document.createElement('th');
    corner.textContent = 'Комната / Час';
    headRow.appendChild(corner);
    for (var h = 0; h < 24; h++) {
        var th = document.createElement('th');
        th.textContent = h + ':00';
        if (isToday && h >= currentHour) {
            th.className = 'future';
        }
        headRow.appendChild(th);
    }
    thead.appendChild(headRow);
    table.appendChild(thead);

    var tbody = document.createElement('tbody');
    for (var r = 0; r < rows.length; r++) {
        var row = rows[r];
        var tr = document.createElement('tr');

        var nameCell = document.createElement('td');
        nameCell.className = 'room-col';
        nameCell.textContent = row.label;
        if (row.error) {
            nameCell.title = row.error;
            nameCell.textContent = row.label + ' ⚠';
        }
        tr.appendChild(nameCell);

        for (var hour = 0; hour < 24; hour++) {
            var cell = document.createElement('td');
            var isFuture = isToday && hour >= currentHour;
            var value = readHour(row.hourly, hour);
            if (isFuture) {
                cell.className = 'future';
                cell.title = 'Будущий час';
            } else if (value === null || value === undefined) {
                cell.textContent = '—';
                cell.title = row.error || 'Нет данных';
            } else {
                var background = getColorForTemperature(value);
                cell.style.backgroundColor = background;
                cell.style.color = textColorFor(background);
                cell.textContent = Number(value).toFixed(1) + '°C';
                cell.title = row.label + ', ' + hour + ':00 — ' + Number(value).toFixed(1) + '°C';
            }
            tr.appendChild(cell);
        }
        tbody.appendChild(tr);
    }
    table.appendChild(tbody);

    container.innerHTML = '';
    container.appendChild(table);
}

function loadSensors(sensorIds) {
    var date = trimText(byId('targetDate').value);
    if (!sensorIds || sensorIds.length === 0) {
        showError('Выберите хотя бы один датчик в дереве');
        return;
    }
    if (!date) {
        showError('Выберите дату');
        return;
    }
    showError('');

    // За один запрос сервер принимает не больше MAX_SENSORS датчиков
    // (ограничение длины URL), поэтому большой список идёт порциями.
    var total = sensorIds.length;
    var received = {};
    var rows = [];
    var percentiles = [];
    var failed = 0;
    var meta = null;
    var offset = 0;

    function prepareRow(sensorId) {
        var data = received[sensorId];
        var info = sensorsById[sensorId];
        var label = (info && info.label) || (data && (data.roomName || data.displayName)) || sensorId;
        var row = {
            label: label,
            hourly: data ? data.hourly : null,
            error: data ? (data.error || null) : 'датчик не найден в дереве'
        };
        if (row.error) {
            failed++;
        } else if (data.percentile95 !== null && data.percentile95 !== undefined) {
            percentiles.push(data.percentile95);
        }
        rows.push(row);
    }

    function finish() {
        renderHeatmap(rows, meta ? meta.isToday === true : false, meta ? meta.currentHour : 24);
        renderLegend(percentiles.length ? calculatePercentile(percentiles, 95) : null);
        if (failed === total) {
            showError('Нет данных ни по одному датчику');
        } else if (failed > 0) {
            showError('Нет данных по ' + failed + ' из ' + total + ' датчиков (подробности — в подсказке к строке)');
        }
    }

    function step() {
        if (offset >= total) {
            finish();
            return;
        }
        var chunk = sensorIds.slice(offset, Math.min(offset + REQUEST_CHUNK, total));
        setStatus('Загрузка: ' + offset + ' из ' + total + '…');
        var url = 'temperature/data?ids=' + encodeURIComponent(chunk.join(',')) +
            '&date=' + encodeURIComponent(date);
        getJson(url, function (json) {
            if (!meta) {
                meta = { isToday: json.isToday, currentHour: json.currentHour };
            }
            var list = json.sensors || [];
            for (var i = 0; i < list.length; i++) {
                received[list[i].id] = list[i];
            }
            for (var k = 0; k < chunk.length; k++) {
                prepareRow(chunk[k]);
            }
            offset += chunk.length;
            step();
        }, function (message) {
            setStatus('Ошибка');
            showError('Ошибка: ' + message);
        });
    }

    step();
}

/* ======================= запуск ======================= */

document.addEventListener('DOMContentLoaded', function () {
    byId('findBtn').onclick = findSensors;
    byId('treeToggleBtn').onclick = toggleTree;
    byId('clearBtn').onclick = clearSelection;
    byId('showAllBtn').onclick = function () {
        loadSensors(selectedSensors.slice(0));
    };
    byId('rootSelect').onchange = function () {
        if (this.value) {
            byId('treePath').value = this.value;
            findSensors();
        }
    };
    byId('targetDate').onchange = function () {
        if (selectedSensors.length) {
            loadSensors(selectedSensors.slice(0));
        }
    };
    byId('treeContainer').style.display = 'none';
    renderLegend(null);
    loadRoots();
});
</script>
</body>
</html>
