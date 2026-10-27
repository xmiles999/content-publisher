package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelType;

public interface PublicationReadershipReader {
    long readViews(ChannelAccount account, String externalId);
    long readPublicViews(ChannelType channel, String externalUrl);
}
