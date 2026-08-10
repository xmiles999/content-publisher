package io.contentpublisher.platform.web.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

@Controller
public class PortalErrorController implements org.springframework.boot.web.servlet.error.ErrorController {
    @RequestMapping("/error")
    public Object error(HttpServletRequest request, HttpServletResponse response) {
        int status = status(request);
        String path = value(request, RequestDispatcher.ERROR_REQUEST_URI);
        String code = switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "PAGE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            default -> "INTERNAL_SERVER_ERROR";
        };
        String message = switch (status) {
            case 400 -> "请求参数无效，请检查后重试。";
            case 401 -> "请先登录后再继续操作。";
            case 403 -> "当前账号没有访问此资源的权限。";
            case 404 -> "请求的页面不存在，可能已移动或被删除。";
            case 405 -> "当前页面不支持该请求方式。";
            default -> "服务暂时无法完成请求，请稍后重试；如持续发生，请联系管理员查看日志。";
        };
        response.setStatus(status);
        if (path.startsWith("/api/")) {
            return ResponseEntity.status(status).body(Map.of(
                    "status", status,
                    "code", code,
                    "message", message));
        }
        ModelAndView view = new ModelAndView("portal-error");
        view.setStatus(org.springframework.http.HttpStatusCode.valueOf(status));
        view.addObject("status", status);
        view.addObject("code", code);
        view.addObject("message", message);
        view.addObject("requestPath", path);
        return view;
    }

    private int status(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer status && status >= 400 && status <= 599) return status;
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed >= 400 && parsed <= 599 ? parsed : 500;
        } catch (NumberFormatException ignored) {
            return 500;
        }
    }

    private String value(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        return value == null ? "" : String.valueOf(value);
    }
}
