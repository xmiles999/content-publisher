package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.application.ChannelCatalog;
import io.contentpublisher.platform.application.PublicationRecord;

public record PublicationMatrixCell(
        ChannelCatalog.ChannelDefinition channel,
        PublicationRecord latest,
        PublicationRecord published) {
}
