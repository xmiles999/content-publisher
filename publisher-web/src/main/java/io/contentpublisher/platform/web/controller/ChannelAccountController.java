package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.web.dto.ChannelAccountResponse;
import io.contentpublisher.platform.web.dto.CreateChannelAccountRequest;
import io.contentpublisher.platform.web.dto.UpdateChannelAccountStatusRequest;
import io.contentpublisher.platform.web.dto.UpdateChannelAccountRequest;
import io.contentpublisher.platform.web.dto.RotateChannelCredentialsRequest;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channel-accounts")
public class ChannelAccountController {
    private final PublishingApplicationService publishing;
    private final RequestActorProvider actors;

    public ChannelAccountController(PublishingApplicationService publishing, RequestActorProvider actors) {
        this.publishing = publishing;
        this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<ChannelAccountResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateChannelAccountRequest request) {
        var saved = publishing.createAccount(actors.currentActor(), request.type(), request.displayName(),
                request.baseUrl(), request.credentials(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/channel-accounts/" + saved.id()))
                .body(ChannelAccountResponse.from(saved));
    }

    @GetMapping
    public List<ChannelAccountResponse> list() {
        return publishing.listAccounts(actors.currentActor()).stream().map(ChannelAccountResponse::from).toList();
    }

    @GetMapping("/{accountId}")
    public ChannelAccountResponse get(@PathVariable UUID accountId) {
        return ChannelAccountResponse.from(publishing.getAccount(actors.currentActor(), accountId));
    }

    @PatchMapping("/{accountId}/status")
    public ChannelAccountResponse updateStatus(@PathVariable UUID accountId,
                                               @Valid @RequestBody UpdateChannelAccountStatusRequest request) {
        return ChannelAccountResponse.from(publishing.updateAccountStatus(actors.currentActor(), accountId,
                request.expectedVersion(), request.status()));
    }

    @PutMapping("/{accountId}")
    public ChannelAccountResponse update(@PathVariable UUID accountId,
                                         @Valid @RequestBody UpdateChannelAccountRequest request) {
        return ChannelAccountResponse.from(publishing.updateAccountProfile(actors.currentActor(), accountId,
                request.expectedVersion(), request.displayName(), request.baseUrl()));
    }

    @PutMapping("/{accountId}/credentials")
    public ChannelAccountResponse rotateCredentials(@PathVariable UUID accountId,
                                                    @Valid @RequestBody RotateChannelCredentialsRequest request) {
        return ChannelAccountResponse.from(publishing.rotateCredentials(actors.currentActor(), accountId,
                request.expectedVersion(), request.credentials()));
    }

    @PostMapping("/{accountId}/verify")
    public ChannelAccountResponse verify(@PathVariable UUID accountId) {
        return ChannelAccountResponse.from(publishing.verifyConnection(actors.currentActor(), accountId));
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID accountId, @RequestParam int expectedVersion) {
        publishing.removeAccount(actors.currentActor(), accountId, expectedVersion);
    }
}
