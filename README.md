# Termoheatmap — тепловая карта температур для WebCTRL

Аддон для Automated Logic WebCTRL: показывает температуру по помещениям за выбранные сутки —
почасовые средние в виде тепловой карты (строки — комнаты, столбцы — часы 0–23).

Java 11, Servlet 3.0, Tomcat 9, собирается NetBeans/Ant, ставится как `.addon`.

## Возможности

- поиск датчиков в географическом дереве по имени точки (`zone_temp`) с выбором режима
  сравнения: точное имя, «содержит», регулярное выражение;
- одна комната или сразу все выбранные: таблица «комната × час» за сутки;
- температура за сегодня — только по закрытым часам (текущий час не показывается);
- цветовая шкала: синий (<18 °C) → зелёный (комфорт 21–22 °C) → жёлтый → оранжевый → красный (>26 °C),
  легенда строится из той же таблицы порогов, что и цвет ячеек;
- 95-й процентиль по каждому датчику и по выборке;
- в расчёт идут только реальные измерения тренда: служебные метки «связи нет»,
  «неизвестное значение», отказ и т. п. отбрасываются.

## Готовый аддон

Собранный аддон прикладывается к релизам: **Releases** → `termoheatmap.addon`
(версия и список изменений — `CHANGELOG.md` и `docs/release-v<версия>.md`).
В репозитории бинарников нет: `dist/` и `*.addon` — в `.gitignore`.

## Требования

- WebCTRL (проверялось на Tomcat 9);
- **JDK 11** — выше нельзя (WebCTRL), ниже бессмысленно: сам SDK аддонов
  `directaccess-api-addon-1.7.0.jar` скомпилирован под Java 11 (class file 55), поэтому на
  WebCTRL с JVM 8 аддон не запустится в принципе. Проверено на AdoptOpenJDK 11.0.2 + Tomcat 9.0.122;
- два JAR-файла, в репозиторий не входят (см. `nbproject/project.properties`):
  - `directaccess-api-addon-1.7.0.jar` — SDK аддонов WebCTRL (поставляется Automated Logic,
    **не распространяется**);
  - `json-20251224.jar` — библиотека org.json.

JAR-файлы кладутся в каталог **на уровень выше** папки проекта:

```
<parent>/
  directaccess-api-addon-1.7.0.jar
  json-20251224.jar
  Termoheatmap_1.1.2/
    build.xml  src/  web/  nbproject/
```

## Сборка

NetBeans:

```
ant clean dist        # или «Clean and Build» в NetBeans
```

Результат — `dist/Termoheatmap_1.1.2.war` и его копия `dist/termoheatmap.addon`.

Без Ant (только JDK) — собирается в отдельный каталог `build\stage`, который и упаковывается:

```powershell
# 1. каталог сборки аддона: info.xml и META-INF в корне, приложение в webapp/
New-Item -ItemType Directory -Force build\stage\webapp\WEB-INF\classes, build\stage\webapp\WEB-INF\lib, build\stage\META-INF
Copy-Item web\info.xml            build\stage\info.xml
Copy-Item web\META-INF\context.xml build\stage\META-INF\context.xml
Copy-Item web\temperature.jsp      build\stage\webapp\temperature.jsp
Copy-Item web\WEB-INF\web.xml      build\stage\webapp\WEB-INF\web.xml
Copy-Item ..\json-20251224.jar     build\stage\webapp\WEB-INF\lib\

# 2. компиляция (SDK и org.json — из каталога на уровень выше, servlet-api — из Tomcat)
javac --release 11 -encoding UTF-8 -d build\stage\webapp\WEB-INF\classes `
  -cp "..\directaccess-api-addon-1.7.0.jar;..\json-20251224.jar;<tomcat>\lib\servlet-api.jar" `
  (Get-ChildItem -Recurse src\java -Filter *.java).FullName

# 3. упаковка: содержимое build\stage -> zip -> dist\termoheatmap.addon
```

Важно: паковать нужно **содержимое** `build\stage` (в корне архива — `info.xml` и `webapp/`),
а не каталог `build\web` целиком — иначе в корне архива окажется ещё один `WEB-INF`,
и WebCTRL такой аддон не примет. Сборка через `ant` делает то же самое в `-pre-dist`.

## Установка в WebCTRL

Устанавливать файл **как `termoheatmap.addon`**: имя файла задаёт контекст аддона
(`/termoheatmap`), а JSP обращается к сервлетам относительными путями.

Встраивание на страницу оператора: **Add-on Content** → *Add-on Name*: `termoheatmap`,
*Content Path*: `temperature.jsp`, *Location*: Attached.

Проверка после установки:

```
http://<server>/termoheatmap/info.xml              → версия аддона (1.1.2)
http://<server>/termoheatmap/temperature.jsp       → страница (200)
http://<server>/termoheatmap/tree/roots            → JSON с корневыми локациями
```

## HTTP-эндпоинты

| Метод и путь | Назначение |
|---|---|
| `GET /tree/roots` | корневые локации географического дерева |
| `GET /sensors/find?path=%23building&masks=zone_temp&match=exact` | поиск аналоговых трендов; `match` = `exact` (по умолчанию), `contains`, `regex` |
| `GET /temperature/data?id=<path>&date=yyyy-MM-dd` | почасовые средние по одному датчику |
| `GET /temperature/data?ids=<p1,p2,…>&date=yyyy-MM-dd` | то же по нескольким датчикам одним запросом (до 100; страница отправляет порциями по 50) |
| `GET /temperature/data?id=…&date=…&debug=1` | дополнительно сырые сэмплы (`t`, `v`, `lt`, `h`) |

Ответ `/temperature/data`:

```json
{
  "date": "2026-11-20",
  "today": "2026-11-20",
  "isToday": true,
  "currentHour": 15,
  "timezone": "Europe/Moscow",
  "sensors": [{
    "id": "#hotel/1/101/zone_temp",
    "name": "zone_temp",
    "displayName": "Зона 101",
    "roomName": "101",
    "sampleCount": 1420,
    "percentile95": 24.3,
    "hourly": [{"hour": 0, "value": 21.8}, {"hour": 1, "value": null}],
    "error": "нет данных за выбранные сутки"
  }]
}
```

Все ответы — `application/json; charset=UTF-8` с `Cache-Control: no-store`.
Ошибки приходят как `{"error": "текст"}` с осмысленным HTTP-статусом
(400 — параметры, 401 — нет сессии оператора, 500 — внутренняя ошибка).

## Часовой пояс

Весь расчёт (разбор даты, номер часа, «сегодня», отсечка будущих часов) идёт в одной зоне —
`com.controlj.addon.temperature.util.AddonTime`. По умолчанию `Europe/Moscow`. Для объекта в
другом поясе добавьте в параметры JVM WebCTRL:

```
-Dtermoheatmap.timezone=Europe/Kaliningrad
```

Часовой пояс JVM и браузера на результат не влияет: браузер получает `today`, `currentHour`
и `timezone` от сервера.

## Отладка

`tools/debug.jsp` — страница с сырыми сэмплами и почасовой таблицей по одному датчику.
В поставку аддона она **не входит**: чтобы воспользоваться, скопируйте файл в `webapp`
установленного аддона и откройте `/termoheatmap/debug.jsp`.

## Права

Все запросы выполняются под соединением оператора (`getUserSystemConnection`): аддон видит
ровно те локации, которые доступны открывшему его оператору. Сессии оператора нет —
возвращается 401 и понятное сообщение.

## Структура проекта

```
src/java/com/controlj/addon/temperature/
  servlet/  TreeRootsServlet, SensorFinderServlet, TemperatureDataServlet
  util/     AddonTime (зона и даты), Stats (процентиль), JsonResponse (ответы и ошибки)
web/        temperature.jsp, info.xml, WEB-INF/web.xml, META-INF/context.xml
tools/      debug.jsp (не входит в поставку)
```

История изменений — `CHANGELOG.md`.
