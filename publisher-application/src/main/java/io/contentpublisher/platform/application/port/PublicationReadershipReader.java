package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.ChannelAccount;

public interface PublicationReadershipReader {
    long readViews(ChannelAccount account, String externalId);
}
