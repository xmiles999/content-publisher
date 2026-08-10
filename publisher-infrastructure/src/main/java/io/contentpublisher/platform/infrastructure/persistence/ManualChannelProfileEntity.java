package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.domain.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "manual_channel_profiles")
class ManualChannelProfileEntity {
    @Id UUID id;
    @Column(name = "tenant_id", nullable = false, length = 100) String tenantId;
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 40) ChannelType channelType;
    @Column(nullable = false) boolean enabled;
    @Column(name = "account_alias", length = 120) String accountAlias;
    @Column(name = "default_tags_json", nullable = false, columnDefinition = "text") String defaultTagsJson;
    @Column(name = "default_section", length = 200) String defaultSection;
    @Column(length = 1000) String notes;
    @Column(name = "sort_order", nullable = false) int sortOrder;
    @Column(name = "login_confirmed_at") Instant loginConfirmedAt;
    @Column(name = "profile_version", nullable = false) int profileVersion;
    @Column(name = "created_by", nullable = false, length = 200) String createdBy;
    @Column(name = "updated_by", nullable = false, length = 200) String updatedBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected ManualChannelProfileEntity() {
    }
}
