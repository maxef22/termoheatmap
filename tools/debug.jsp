<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%--
    Отладочная страница: сырые сэмплы тренда и почасовые средние по одному датчику.

    ВАЖНО: файл лежит в tools/ и в поставку аддона НЕ входит. Чтобы воспользоваться им
    на стенде, скопируйте его в webapp аддона (например, в <addon>/debug.jsp)
    и откройте по адресу http://<server>/<addon>/debug.jsp.

    Часовой пояс: всё время приходит с сервера уже в зоне аддона (поля lt/h),
    поэтому страница не делает собственных пересчётов и не может «съехать» на 3 часа,
    как это было в версии 1.1.1.
--%>
<!DOCTYPE html>
<html>
<head>
    <title>Отладка трендов — сырые данные</title>
    <meta charset="UTF-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <style>
        body { background: #1a1a1a; color: #f0f0f0; font-family: 'Courier New', monospace; margin: 20px; }
        h1 { color: #ffd700; text-align: center; font-size: 18px; }
        .controls {
            background: #2a2a2a; padding: 12px; border-radius: 8px; margin-bottom: 16px;
            display: flex; gap: 10px; flex-wrap: wrap; align-items: center; justify-content: center;
        }
        .controls input, .controls select, .controls button {
            padding: 6px 10px; border-radius: 4px; border: none; background: #3a3a3a;
            color: white; font-family: 'Courier New', monospace; font-size: 12px;
        }
        .controls button { background: #ffd700; color: #1a1a1a; font-weight: bold; cursor: pointer; }
        .tree-container {
            max-height: 220px; overflow-y: auto; background: #2a2a2a; padding: 10px;
            border-radius: 8px; margin: 10px 0; font-size: 12px;
        }
        .tree-container div { padding: 2px 4px; cursor: pointer; }
        .tree-container div:hover { background: #3a3a3a; }
        .tree-container .selected { background: #ffd700; color: #1a1a1a; }
        pre {
            background: #0d0d0d; padding: 12px; border-radius: 8px; overflow: auto;
            font-size: 12px; max-height: 520px; white-space: pre-wrap; word-wrap: break-word;
        }
        .stats { background: #2a2a2a; padding: 10px; border-radius: 8px; margin: 10px 0; font-size: 12px; }
        .stats b { color: #ffd700; }
        .error { color: #f92672; text-align: center; padding: 8px; min-height: 16px; }
        .tabs { display: flex; gap: 4px; margin-bottom: 8px; flex-wrap: wrap; }
        .tab-btn {
            background: #3a3a3a; color: #aaa; border: none; padding: 6px 14px;
            cursor: pointer; border-radius: 4px 4px 0 0; font-size: 12px;
        }
        .tab-btn.active { background: #ffd700; color: #1a1a1a; font-weight: bold; }
        .tab-content { display: none; }
        .tab-content.active { display: block; }
        table { border-collapse: collapse; font-size: 11px; width: 100%; background: #0d0d0d; }
        th, td { padding: 3px 8px; border-bottom: 1px solid #333; text-align: left; }
        th { background: #2a2a2a; color: #ffd700; position: sticky; top: 0; }
        td.time { color: #66d9ef; }
        td.hour { color: #ffd700; }
        td.value { color: #a6e22e; }
        tr.future td { opacity: 0.35; }
        .scroll { max-height: 420px; overflow: auto; }
        .note { color: #8a8a8a; font-size: 11px; }
    </style>
</head>
<body>
<h1>🔍 Отладка трендов — сырые данные</h1>

<div class="controls">
    <select id="rootSelect"><option value="">— выберите область —</option></select>
    <input type="text" id="treePath" value="" size="24" placeholder="#building"/>
    <input type="text" id="masks" value="zone_temp" size="14"/>
    <select id="matchMode">
        <option value="exact">имя = маска</option>
        <option value="contains">имя содержит</option>
        <option value="regex">regex</option>
    </select>
    <button id="findBtn" type="button">Найти</button>
    <input type="date" id="targetDate"/>
    <button id="loadBtn" type="button" style="background:#ff4444;color:#fff;">Загрузить RAW</button>
</div>

<div id="sensorTree" class="tree-container" style="display:none;"></div>
<div id="errorMsg" class="error"></div>
<div id="statsPanel" class="stats" style="display:none;"></div>

<div class="tabs">
    <button class="tab-btn active" type="button" data-tab="tab-raw">RAW JSON</button>
    <button class="tab-btn" type="button" data-tab="tab-samples">Сырые сэмплы</button>
    <button class="tab-btn" type="button" data-tab="tab-hourly">По часам</button>
</div>

<div id="tab-raw" class="tab-content active"><pre id="rawOutput">Нажмите «Загрузить RAW»</pre></div>
<div id="tab-samples" class="tab-content"><div id="samplesOutput" class="note">—</div></div>
<div id="tab-hourly" class="tab-content"><div id="hourlyOutput" class="note">—</div></div>

<script type="text/javascript">
var selectedSensorId = null;

function byId(id) { return document.getElementById(id); }

function trimText(value) {
    return value === null || value === undefined ? '' : String(value).replace(/^\s+|\s+$/g, '');
}

function showError(message) { byId('errorMsg').textContent = message || ''; }

function getJson(url, onSuccess, onFailure) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) { return; }
        var data = null;
        if (xhr.responseText) {
            try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
        }
        if (xhr.status >= 200 && xhr.status < 300 && data) { onSuccess(data); }
        else if (data && data.error) { onFailure(data.error); }
        else { onFailure('HTTP ' + xhr.status); }
    };
    xhr.send(null);
}

function switchTab(tabId) {
    var contents = document.getElementsByClassName('tab-content');
    for (var i = 0; i < contents.length; i++) {
        contents[i].className = 'tab-content';
    }
    var buttons = document.getElementsByClassName('tab-btn');
    for (var b = 0; b < buttons.length; b++) {
        buttons[b].className = 'tab-btn';
    }
    byId(tabId).className = 'tab-content active';
    var all = document.getElementsByTagName('button');
    for (var k = 0; k < all.length; k++) {
        if (all[k].getAttribute('data-tab') === tabId) {
            all[k].className = 'tab-btn active';
        }
    }
}

function loadRoots() {
    getJson('tree/roots', function (json) {
        var select = byId('rootSelect');
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
    }, function (message) { showError('Дерево: ' + message); });
}

function findSensors() {
    var path = trimText(byId('treePath').value);
    if (!path) { showError('Укажите ветку дерева'); return; }
    if (path.charAt(0) !== '#') { path = '#' + path; }
    var masks = trimText(byId('masks').value) || 'zone_temp';
    var match = byId('matchMode').value || 'exact';
    var container = byId('sensorTree');
    container.style.display = '';
    container.innerHTML = 'Поиск…';
    showError('');

    var url = 'sensors/find?path=' + encodeURIComponent(path) +
        '&masks=' + encodeURIComponent(masks) + '&match=' + encodeURIComponent(match);

    getJson(url, function (json) {
        var sensors = json.sensors || [];
        container.innerHTML = '';
        if (!sensors.length) { container.innerHTML = 'Датчики не найдены'; return; }
        for (var i = 0; i < sensors.length; i++) {
            var item = document.createElement('div');
            item.textContent = sensors[i].label + '   [' + sensors[i].id + ']';
            item.setAttribute('data-id', sensors[i].id);
            item.onclick = (function (element, id) {
                return function () { selectSensor(element, id); };
            })(item, sensors[i].id);
            container.appendChild(item);
        }
    }, function (message) { container.innerHTML = 'Ошибка: ' + message; });
}

function selectSensor(element, id) {
    var selected = byId('sensorTree').getElementsByTagName('div');
    for (var i = 0; i < selected.length; i++) {
        selected[i].className = '';
    }
    element.className = 'selected';
    selectedSensorId = id;
    showError('Выбран: ' + id);
}

function loadRawData() {
    var id = selectedSensorId;
    var date = trimText(byId('targetDate').value);
    if (!id) { showError('Выберите датчик в дереве'); return; }
    if (!date) { showError('Выберите дату'); return; }
    showError('');
    byId('rawOutput').textContent = 'Загрузка…';

    var url = 'temperature/data?id=' + encodeURIComponent(id) + '&date=' + encodeURIComponent(date) + '&debug=1';
    getJson(url, function (json) {
        var sensor = (json.sensors && json.sensors[0]) || null;
        byId('rawOutput').textContent = JSON.stringify(json, null, 2);
        if (!sensor) { showError('Сервер не вернул данные датчика'); return; }
        renderStats(json, sensor);
        renderSamples(sensor, json);
        renderHourly(sensor, json);
    }, function (message) {
        byId('rawOutput').textContent = 'Ошибка: ' + message;
        showError(message);
    });
}

function renderStats(json, sensor) {
    var values = [];
    var filled = 0;
    for (var h = 0; h < sensor.hourly.length; h++) {
        var value = sensor.hourly[h].value;
        if (value !== null && value !== undefined) {
            values.push(value);
            filled++;
        }
    }
    var min = null, max = null, sum = 0;
    for (var i = 0; i < values.length; i++) {
        if (min === null || values[i] < min) { min = values[i]; }
        if (max === null || values[i] > max) { max = values[i]; }
        sum += values[i];
    }
    var average = values.length ? sum / values.length : null;

    var panel = byId('statsPanel');
    panel.innerHTML = '';
    function add(label, text) {
        var line = document.createElement('div');
        var strong = document.createElement('b');
        strong.textContent = text;
        line.appendChild(document.createTextNode(label + ': '));
        line.appendChild(strong);
        panel.appendChild(line);
    }
    add('Датчик', sensor.id);
    add('Дата', json.date + (json.isToday ? ' (сегодня)' : ''));
    add('Часовой пояс', json.timezone);
    add('Текущий час сервера', json.currentHour + ':00');
    add('Часов с данными', filled + '/24');
    add('Средняя по часам', average === null ? '—' : average.toFixed(2) + ' °C');
    add('Min / Max', (min === null ? '—' : min.toFixed(2)) + ' / ' + (max === null ? '—' : max.toFixed(2)) + ' °C');
    add('95-й процентиль', sensor.percentile95 === null ? '—' : Number(sensor.percentile95).toFixed(2) + ' °C');
    add('Сэмплов', String(sensor.sampleCount));
    if (sensor.error) { add('Ошибка', sensor.error); }
    panel.style.display = 'block';
}

function renderSamples(sensor, json) {
    var container = byId('samplesOutput');
    var samples = sensor.samples;
    if (!samples || !samples.length) {
        container.textContent = 'Сырые сэмплы не пришли (data+debug=1 должен их возвращать).';
        return;
    }
    var html = '<div class="note">Всего сэмплов: ' + samples.length +
        ', время в зоне ' + json.timezone + ' (приходит с сервера полем lt)</div>';
    html += '<div class="scroll"><table><thead><tr>' +
        '<th>#</th><th>Время</th><th>Час</th><th>Значение</th></tr></thead><tbody>';
    for (var i = 0; i < samples.length; i++) {
        var sample = samples[i];
        var future = json.isToday && sample.h >= json.currentHour;
        html += '<tr' + (future ? ' class="future"' : '') + '>' +
            '<td>' + (i + 1) + '</td>' +
            '<td class="time">' + sample.lt + '</td>' +
            '<td class="hour">' + sample.h + ':00</td>' +
            '<td class="value">' + Number(sample.v).toFixed(2) + ' °C</td></tr>';
    }
    html += '</tbody></table></div>';
    container.innerHTML = html;
}

function renderHourly(sensor, json) {
    var container = byId('hourlyOutput');
    var html = '<table><thead><tr><th>Час</th><th>Значение</th><th>Статус</th></tr></thead><tbody>';
    for (var h = 0; h < 24; h++) {
        var value = sensor.hourly[h].value;
        var future = json.isToday && h >= json.currentHour;
        var status = future ? 'будущий час' : (value === null || value === undefined ? 'нет данных' : 'есть данные');
        html += '<tr' + (future ? ' class="future"' : '') + '>' +
            '<td class="hour">' + h + ':00</td>' +
            '<td class="value">' + (value === null || value === undefined ? '—' : Number(value).toFixed(2) + ' °C') + '</td>' +
            '<td>' + status + '</td></tr>';
    }
    html += '</tbody></table>';
    container.innerHTML = html;
}

document.addEventListener('DOMContentLoaded', function () {
    byId('findBtn').onclick = findSensors;
    byId('loadBtn').onclick = loadRawData;
    byId('rootSelect').onchange = function () {
        if (this.value) {
            byId('treePath').value = this.value;
            findSensors();
        }
    };
    var buttons = document.getElementsByClassName('tab-btn');
    for (var i = 0; i < buttons.length; i++) {
        buttons[i].onclick = function () { switchTab(this.getAttribute('data-tab')); };
    }
    loadRoots();
});
</script>
</body>
</html>
