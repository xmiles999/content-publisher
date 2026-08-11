package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ChannelVerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_accounts")
class ChannelAccountEntity {
    @Id UUID id;
    @Column(name = "tenant_id", nullable = false, length = 100) String tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) ChannelType type;
    @Column(name = "display_name", nullable = false, length = 120) String displayName;
    @Column(name = "base_url", nullable = false, length = 2048) String baseUrl;
    @Column(name = "encrypted_credentials", nullable = false, columnDefinition = "text") String encryptedCredentials;
    @Column(name = "idempotency_key", nullable = false, length = 128) String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) String requestHash;
    @Column(name = "credential_fingerprint", nullable = false, length = 64) String credentialFingerprint;
    @Column(name = "account_version", nullable = false) int accountVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) ChannelAccountStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "verification_status", length = 20)
    ChannelVerificationStatus verificationStatus;
    @Column(name = "verification_message", length = 500) String verificationMessage;
    @Column(name = "last_verified_at") Instant lastVerifiedAt;
    @Column(name = "created_by", nullable = false, length = 200) String createdBy;
    @Column(name = "updated_by", nullable = false, length = 200) String updatedBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "deleted_by", length = 200) String deletedBy;
    @Column(name = "deleted_at") Instant deletedAt;

    protected ChannelAccountEntity() {}
}
