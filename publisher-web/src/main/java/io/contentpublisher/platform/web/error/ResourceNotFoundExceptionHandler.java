package io.contentpublisher.platform.web.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class ResourceNotFoundExceptionHandler {
    private final PortalErrorController portalErrorController;

    public ResourceNotFoundExceptionHandler(PortalErrorController portalErrorController) {
        this.portalErrorController = portalErrorController;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    Object resourceNotFound(HttpServletRequest request, HttpServletResponse response) {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpServletResponse.SC_NOT_FOUND);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        return portalErrorController.error(request, response);
    }
}
