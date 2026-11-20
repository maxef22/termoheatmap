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
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Корневые локации географического дерева.
 *
 * <pre>GET /tree/roots  →  {"roots":[{"name":"hotel","displayName":"Отель","path":"#hotel"}]}</pre>
 *
 * <p>Путь строится из reference-имени, а не из {@code Location.toString()}: так он не зависит
 * от внутреннего формата строкового представления локации. Чтение идёт под соединением
 * оператора — оператор видит только свои локации.</p>
 */
public class TreeRootsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final JSONArray roots = new JSONArray();
        try {
            SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);
            connection.runReadAction(new ReadAction() {
                @Override
                public void execute(SystemAccess access) throws Exception {
                    Tree geographic = access.getTree(SystemTree.Geographic);
                    for (Location child : geographic.getRoot().getChildren()) {
                        String referenceName = child.getReferenceName();
                        if (referenceName == null
                                || referenceName.trim().isEmpty()
                                || "#".equals(referenceName.trim())) {
                            continue;
                        }
                        String displayName = child.getDisplayName();
                        if (displayName == null || displayName.trim().isEmpty()) {
                            displayName = referenceName;
                        }
                        JSONObject root = new JSONObject();
                        root.put("name", referenceName);
                        root.put("displayName", displayName);
                        root.put("path", "#" + referenceName);
                        roots.put(root);
                    }
                }
            });
        } catch (InvalidConnectionRequestException e) {
            JsonResponse.error(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Нет сессии оператора WebCTRL — откройте аддон из интерфейса WebCTRL");
            return;
        } catch (Exception e) {
            log("Не удалось прочитать корневые локации", e);
            JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Ошибка чтения дерева локаций", e);
            return;
        }

        JSONObject body = new JSONObject();
        body.put("roots", roots);
        JsonResponse.ok(response, body);
    }
}
