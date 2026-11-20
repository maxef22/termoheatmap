package com.controlj.addon.temperature.util;

import org.json.JSONObject;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Единая выдача JSON-ответов.
 *
 * <p>Зачем отдельный класс:</p>
 * <ul>
 *   <li>ответы не кэшируются ({@code Cache-Control: no-store}) — иначе браузер может
 *       показать вчерашнюю карту;</li>
 *   <li>ошибка всегда отдаётся валидным JSON — раньше сообщение склеивалось руками
 *       ({@code {"error":"..."}}), падало на {@code null} и ломалось на кавычках/переводах
 *       строк в тексте исключения;</li>
 *   <li>у {@link JSONObject#put(String, Object)} {@code null} <b>удаляет</b> ключ, поэтому
 *       пустой текст ошибки давал ответ {@code {}} — теперь подставляется понятный текст.</li>
 * </ul>
 */
public final class JsonResponse {

    private JsonResponse() {
    }

    public static void ok(HttpServletResponse response, JSONObject body) throws IOException {
        send(response, HttpServletResponse.SC_OK, body);
    }

    public static void error(HttpServletResponse response, int status, String message) throws IOException {
        JSONObject body = new JSONObject();
        body.put("error", text(message));
        send(response, status, body);
    }

    /**
     * Ответ об ошибке; в текст попадают только тип и сообщение исключения —
     * без стека (стек пишется в лог сервера вызывающим сервлетом).
     */
    public static void error(HttpServletResponse response, int status, String prefix, Throwable cause)
            throws IOException {
        String detail = describe(cause);
        String message = prefix == null || prefix.isEmpty() ? detail : prefix + ": " + detail;
        error(response, status, message);
    }

    private static void send(HttpServletResponse response, int status, JSONObject body) throws IOException {
        // Если ответ уже начат, менять статус/заголовки нельзя — молча выходим,
        // иначе получаем IllegalStateException внутри обработчика ошибки.
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.getWriter().print(body.toString());
    }

    /** Тип и сообщение исключения в одну строку (сообщение может быть {@code null}). */
    public static String describe(Throwable cause) {
        if (cause == null) {
            return "неизвестная ошибка";
        }
        StringBuilder text = new StringBuilder(cause.getClass().getSimpleName());
        String message = cause.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            text.append(": ").append(message.trim());
        }
        return text.toString();
    }

    private static String text(String message) {
        if (message == null) {
            return "неизвестная ошибка";
        }
        String trimmed = message.trim();
        return trimmed.isEmpty() ? "неизвестная ошибка" : trimmed;
    }
}
