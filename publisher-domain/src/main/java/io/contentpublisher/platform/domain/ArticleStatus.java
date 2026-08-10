package io.contentpublisher.platform.domain;

public enum ArticleStatus {
    DRAFT,
    READY,
    APPROVED,
    PUBLISHED,
    REJECTED;

    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }

    public boolean isPublishable() {
        return this == READY || this == APPROVED || this == PUBLISHED;
    }

    public boolean isConfirmedBaseline() {
        return isPublishable();
    }
}
