package io.contentpublisher.platform.web.error;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.web.controller.ContentCreationPortalController;
import io.contentpublisher.platform.web.controller.ContentLibraryPortalController;
import io.contentpublisher.platform.web.controller.JobPortalController;
import io.contentpublisher.platform.web.controller.AutomationPortalController;
import io.contentpublisher.platform.web.controller.PortalAiSettingsController;
import io.contentpublisher.platform.web.controller.PortalPublishingController;
import io.contentpublisher.platform.web.controller.RecycleBinPortalController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = {
        ContentCreationPortalController.class,
        ContentLibraryPortalController.class,
        AutomationPortalController.class,
        JobPortalController.class,
        RecycleBinPortalController.class,
        PortalPublishingController.class,
        PortalAiSettingsController.class
})
public class PortalExceptionHandler {
    @ExceptionHandler(ApplicationException.class)
    ModelAndView applicationError(ApplicationException exception) {
        return error(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ModelAndView invalidArgument(IllegalArgumentException exception) {
        return error("INVALID_ARGUMENT", exception.getMessage());
    }

    private ModelAndView error(String code, String message) {
        ModelAndView view = new ModelAndView("portal-error");
        view.setStatus(org.springframework.http.HttpStatus.BAD_REQUEST);
        view.addObject("status", 400);
        view.addObject("code", code);
        view.addObject("message", message);
        return view;
    }
}
