package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.*;
import io.contentpublisher.platform.web.form.ReadershipForm;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/publishing/articles/{articleId}/readership/{method}/{publicationId}")
public class PortalReadershipController {
    private final ReadershipApplicationService readership;
    private final RequestActorProvider actors;
    public PortalReadershipController(ReadershipApplicationService readership, RequestActorProvider actors) {
        this.readership = readership;
        this.actors = actors;
    }

    @GetMapping
    public String edit(@PathVariable UUID articleId, @PathVariable PublicationMethod method,
                       @PathVariable UUID publicationId, Model model) {
        PublicationRecord record = load(articleId, method, publicationId, model);
        var snapshot = readership.snapshot(actors.currentActor(), record);
        var form = new ReadershipForm();
        form.setVersion(snapshot.version());
        form.setViews(snapshot.views());
        form.setUniqueReaders(snapshot.uniqueReaders());
        model.addAttribute("readershipForm", form);
        return "publication-readership";
    }

    @PostMapping
    public String save(@PathVariable UUID articleId, @PathVariable PublicationMethod method,
                       @PathVariable UUID publicationId, @Valid @ModelAttribute ReadershipForm readershipForm,
                       BindingResult binding, Model model, RedirectAttributes redirect) {
        load(articleId, method, publicationId, model);
        if (!binding.hasErrors()) {
            try {
                readership.saveManual(actors.currentActor(), articleId, method, publicationId,
                        readershipForm.getVersion(), readershipForm.getViews(), readershipForm.getUniqueReaders());
                redirect.addFlashAttribute("successMessage", "阅读统计已保存");
                return "redirect:" + path(articleId, method, publicationId);
            } catch (ApplicationException failure) { model.addAttribute("errorMessage", failure.getMessage()); }
        }
        return "publication-readership";
    }

    @PostMapping("/sync")
    public String sync(@PathVariable UUID articleId, @PathVariable PublicationMethod method,
                       @PathVariable UUID publicationId, @RequestParam int version, RedirectAttributes redirect) {
        // Validate the tenant and article before handling a recoverable provider failure.
        readership.publication(actors.currentActor(), articleId, method, publicationId);
        try {
            readership.sync(actors.currentActor(), articleId, method, publicationId, version);
            redirect.addFlashAttribute("successMessage", "已同步官方浏览次数；接口不提供去重人数");
        } catch (ApplicationException failure) { redirect.addFlashAttribute("errorMessage", failure.getMessage()); }
        return "redirect:" + path(articleId, method, publicationId);
    }

    private PublicationRecord load(UUID articleId, PublicationMethod method, UUID publicationId, Model model) {
        var record = readership.publication(actors.currentActor(), articleId, method, publicationId);
        model.addAttribute("publication", record);
        model.addAttribute("readership", readership.snapshot(actors.currentActor(), record));
        model.addAttribute("supportsSync", ReadershipApplicationService.supportsSync(record));
        model.addAttribute("channelName", ChannelCatalog.definition(record.channelType()).displayName());
        model.addAttribute("readershipPath", path(articleId, method, publicationId));
        return record;
    }

    private String path(UUID articleId, PublicationMethod method, UUID publicationId) {
        return "/publishing/articles/" + articleId + "/readership/" + method.name() + "/" + publicationId;
    }
}
