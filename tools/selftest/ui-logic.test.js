/*
 * Проверка «чистой» логики интерфейса (без браузера).
 *
 * Запуск:
 *   node tools/selftest/ui-logic.test.js build/temperature.js
 *
 * Скрипт страницы выполняется в песочнице Node с заглушкой document; проверяются
 * цветовая шкала, легенда, процентиль, чтение часа и построение дерева датчиков —
 * то, что раньше давало разрыв цвета на 21 °C, потерю второго датчика в комнате
 * и невидимый датчик верхнего уровня.
 */
'use strict';

var fs = require('fs');
var vm = require('vm');

var target = process.argv[2];
if (!target) {
    console.error('Укажите путь к извлечённому из JSP скрипту');
    process.exit(2);
}

var checks = 0;
var failures = 0;

function check(name, expected, actual) {
    checks++;
    var ok = JSON.stringify(expected) === JSON.stringify(actual);
    if (ok) {
        console.log('  ok    ' + name);
    } else {
        failures++;
        console.log('  FAIL  ' + name + ': ожидалось ' + JSON.stringify(expected)
            + ', получено ' + JSON.stringify(actual));
    }
}

function checkTrue(name, condition) {
    check(name, true, condition === true);
}

/* --- песочница: странице нужен только document.addEventListener на старте --- */
var sandbox = {
    document: {
        addEventListener: function () {
        },
        getElementById: function () {
            return null;
        },
        createElement: function () {
            return {style: {}, appendChild: function () {}};
        },
        getElementsByClassName: function () {
            return [];
        }
    },
    console: console
};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(target, 'utf8'), sandbox, {filename: target});

/* --- цветовая шкала --- */
console.log('\n== Цветовая шкала ==');
check('нет данных → null', null, sandbox.getColorForTemperature(null));
check('ниже шкалы → тёмно-синий', '#1565C0', sandbox.getColorForTemperature(10));
check('18 °C → синий', '#2196F3', sandbox.getColorForTemperature(18));
check('21 °C → зелёный', '#4CAF50', sandbox.getColorForTemperature(21));
check('22 °C → зелёный (комфорт)', '#4CAF50', sandbox.getColorForTemperature(22));
check('24 °C → жёлтый', '#FFEB3B', sandbox.getColorForTemperature(24));
check('26 °C → красный', '#F44336', sandbox.getColorForTemperature(26));
check('выше шкалы → тёмно-красный', '#B71C1C', sandbox.getColorForTemperature(40));

// Раньше на 21.0 °C был скачок «почти жёлтый → зелёный». Проверяем плавность:
// соседние значения не должны менять цвет рывком.
function channelDistance(a, b) {
    var pa = [parseInt(a.substr(1, 2), 16), parseInt(a.substr(3, 2), 16), parseInt(a.substr(5, 2), 16)];
    var pb = [parseInt(b.substr(1, 2), 16), parseInt(b.substr(3, 2), 16), parseInt(b.substr(5, 2), 16)];
    return Math.max(Math.abs(pa[0] - pb[0]), Math.abs(pa[1] - pb[1]), Math.abs(pa[2] - pb[2]));
}

console.log('\n== Плавность шкалы ==');
var before = sandbox.getColorForTemperature(20.99);
var after = sandbox.getColorForTemperature(21.00);
checkTrue('20.99 → 21.00 без скачка (Δ ≤ 5): ' + before + ' → ' + after,
    channelDistance(before, after) <= 5);

var maxJump = 0;
for (var t = 16; t <= 28; t = Math.round((t + 0.05) * 100) / 100) {
    var jump = channelDistance(sandbox.getColorForTemperature(t), sandbox.getColorForTemperature(t + 0.05));
    if (jump > maxJump) {
        maxJump = jump;
    }
}
checkTrue('на всей шкале 16–28 нет ступенек (max Δ ≤ 5): ' + maxJump, maxJump <= 5);

/* --- легенда --- */
console.log('\n== Легенда ==');
var gradient = sandbox.buildGradient();
checkTrue('градиент строится из порогов: ' + gradient, gradient.indexOf('linear-gradient(to right,') === 0);
checkTrue('синий в начале', gradient.indexOf('#1565C0 0.0%') > 0);
checkTrue('комфорт 21 °C на 41.7%', gradient.indexOf('#4CAF50 41.7%') > 0);
checkTrue('комфорт 22 °C на 50.0%', gradient.indexOf('#4CAF50 50.0%') > 0);
checkTrue('тёмно-красный в конце', gradient.indexOf('#B71C1C 100.0%') > 0);

/* --- процентиль и чтение часа --- */
console.log('\n== Процентиль и часы ==');
var hundred = [];
for (var i = 1; i <= 100; i++) {
    hundred.push(i);
}
check('95 из 1..100', 95, sandbox.calculatePercentile(hundred, 95));
check('пусто → null', null, sandbox.calculatePercentile([], 95));
check('один элемент', 21.5, sandbox.calculatePercentile([21.5], 95));

var hourly = [];
for (var h = 0; h < 24; h++) {
    hourly.push({hour: h, value: h === 5 ? 22.5 : null});
}
check('час 5 найден', 22.5, sandbox.readHour(hourly, 5));
check('час 6 пуст', null, sandbox.readHour(hourly, 6));
check('нет массива → null', null, sandbox.readHour(null, 5));

/* --- дерево датчиков --- */
console.log('\n== Дерево датчиков ==');

// Две комнаты с одинаковым отображаемым именем и одна комната с двумя датчиками.
var sensors = [
    {
        id: '#hotel/f1/101/zone_temp', label: '101', name: 'zone_temp',
        ancestors: [{path: '#hotel', label: 'Отель'}, {path: '#hotel/f1', label: 'Этаж 1'}]
    },
    {
        id: '#hotel/f1/101/zone_temp_2', label: '101 · zone_temp_2', name: 'zone_temp_2',
        ancestors: [{path: '#hotel', label: 'Отель'}, {path: '#hotel/f1', label: 'Этаж 1'}]
    },
    {
        // Второй корпус: путь другой, отображаемое имя комнаты то же самое.
        id: '#annex/f1/101/zone_temp', label: '101', name: 'zone_temp',
        ancestors: [{path: '#annex', label: 'Корпус 2'}, {path: '#annex/f1', label: 'Этаж 1'}]
    },
    {
        // Датчик на верхнем уровне: предков нет — раньше он исчезал из дерева.
        id: '#roof/zone_temp', label: 'Кровля', name: 'zone_temp',
        ancestors: []
    }
];

var tree = sandbox.buildTree(sensors);
function countLeaves(node) {
    var total = 0;
    for (var i = 0; i < node.children.length; i++) {
        var child = node.children[i];
        if (child.sensorId) {
            total++;
        } else {
            total += countLeaves(child);
        }
    }
    return total;
}

check('все 4 датчика в дереве (раньше терялись)', 4, countLeaves(tree));
check('корней 3 (отель, корпус 2, датчик верхнего уровня)', 3, tree.children.length);

var hotel = null;
for (var c = 0; c < tree.children.length; c++) {
    if (tree.children[c].path === '#hotel') {
        hotel = tree.children[c];
    }
}
checkTrue('отель найден по пути, а не по имени', hotel !== null);
check('в отеле один этаж', 1, hotel.children.length);
check('на этаже два датчика одной комнаты', 2, hotel.children[0].children.length);

var rooms = {};
for (var r = 0; r < hotel.children[0].children.length; r++) {
    var leaf = hotel.children[0].children[r];
    rooms[leaf.sensorId] = leaf.label;
}
checkTrue('датчик 101 подписан комнатой', rooms['#hotel/f1/101/zone_temp'] === '101');
checkTrue('второй датчик комнаты виден отдельно',
    rooms['#hotel/f1/101/zone_temp_2'] === '101 · zone_temp_2');

/* --- итог --- */
console.log('\nПроверок: ' + checks + ', ошибок: ' + failures);
process.exit(failures > 0 ? 1 : 0);
