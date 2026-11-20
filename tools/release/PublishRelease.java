import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Публикация релиза аддона на GitHub без gh CLI и без curl.
 *
 * <p>Зачем отдельная утилита: в некоторых окружениях (например, в песочнице агента)
 * TLS-стек Windows (Schannel) недоступен — и curl, и .NET падают с
 * {@code SEC_E_NO_CREDENTIALS}, а Java ходит своим TLS и работает.</p>
 *
 * <p>Токен берётся из переменной окружения {@code GH_TOKEN} (никогда не из аргументов,
 * чтобы не осесть в истории команд).</p>
 *
 * <p>Использование:</p>
 * <pre>
 * javac -cp json-20251224.jar -d build/release-tool tools/release/PublishRelease.java
 * set GH_TOKEN=...            (Windows; в bash — export GH_TOKEN=...)
 * java -cp "build/release-tool;json-20251224.jar" PublishRelease ^
 *      maxef22 termoheatmap v1.1.2 "Termoheatmap 1.1.2" docs/release-v1.1.2.md dist/termoheatmap.addon
 * </pre>
 *
 * <p>Повторный запуск безопасен: если релиз и файл уже есть, утилита ничего не дублирует.</p>
 */
public final class PublishRelease {

    private static final String API = "https://api.github.com";
    private static final String UPLOADS = "https://uploads.github.com";
    private static final String UA = "termoheatmap-release";

    private PublishRelease() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Использование: PublishRelease <owner> <repo> <tag> <name> <notes.md> <asset>");
            System.exit(2);
        }
        String owner = args[0];
        String repo = args[1];
        String tag = args[2];
        String name = args[3];
        Path notes = Paths.get(args[4]);
        Path asset = Paths.get(args[5]);

        String token = System.getenv("GH_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            System.err.println("Не задан GH_TOKEN");
            System.exit(2);
        }
        if (!Files.isReadable(asset)) {
            System.err.println("Нет файла для прикрепления: " + asset.toAbsolutePath());
            System.exit(2);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JSONObject release = findRelease(client, token, owner, repo, tag);
        if (release == null) {
            JSONObject payload = new JSONObject();
            payload.put("tag_name", tag);
            payload.put("name", name);
            payload.put("body", new String(Files.readAllBytes(notes), StandardCharsets.UTF_8));
            payload.put("draft", false);
            payload.put("prerelease", false);

            release = send(client, token, "POST",
                    API + "/repos/" + owner + "/" + repo + "/releases",
                    payload.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            System.out.println("Релиз создан:  " + release.optString("html_url"));
        } else {
            System.out.println("Релиз уже был: " + release.optString("html_url"));
        }

        long releaseId = release.getLong("id");
        String assetName = asset.getFileName().toString();

        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject existing = assets.getJSONObject(i);
            if (assetName.equals(existing.optString("name"))) {
                System.out.println("Файл уже есть: " + existing.optString("browser_download_url"));
                return;
            }
        }

        HttpRequest upload = HttpRequest.newBuilder(URI.create(
                        UPLOADS + "/repos/" + owner + "/" + repo + "/releases/" + releaseId
                                + "/assets?name=" + assetName))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", UA)
                .header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofFile(asset))
                .build();

        HttpResponse<String> uploadResponse = client.send(upload, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (uploadResponse.statusCode() != 201) {
            System.err.println("Не удалось прикрепить файл: HTTP " + uploadResponse.statusCode());
            System.err.println(uploadResponse.body());
            System.exit(1);
        }

        JSONObject assetJson = new JSONObject(uploadResponse.body());
        System.out.println("Файл прикреплён: " + assetJson.optString("browser_download_url"));
        System.out.println("Размер:          " + assetJson.optLong("size") + " байт");
        if (assetJson.has("digest")) {
            System.out.println("Digest:          " + assetJson.optString("digest"));
        }
    }

    private static JSONObject findRelease(HttpClient client, String token, String owner, String repo, String tag)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        API + "/repos/" + owner + "/" + repo + "/releases/tags/" + tag))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", UA)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            System.err.println("GitHub ответил " + response.statusCode() + ": " + response.body());
            System.exit(1);
        }
        return new JSONObject(response.body());
    }

    private static JSONObject send(HttpClient client, String token, String method, String url,
                                   byte[] body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", UA)
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            System.err.println("GitHub ответил " + response.statusCode() + ": " + response.body());
            System.exit(1);
        }
        return new JSONObject(response.body());
    }
}
