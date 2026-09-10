package io.contentpublisher.platform.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "article_assets")
class ArticleAssetEntity {
    @Id UUID id;
    @Column(name = "tenant_id", nullable = false, length = 100) String tenantId;
    @Column(name = "article_id", nullable = false) UUID articleId;
    @Column(name = "original_filename", nullable = false, length = 120) String originalFilename;
    @Column(name = "content_type", nullable = false, length = 40) String contentType;
    @Column(name = "byte_size", nullable = false) long byteSize;
    @Column(name = "created_by", nullable = false, length = 200) String createdBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
}
