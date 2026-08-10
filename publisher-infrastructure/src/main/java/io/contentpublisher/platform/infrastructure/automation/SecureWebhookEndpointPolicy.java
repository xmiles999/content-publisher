package io.contentpublisher.platform.infrastructure.automation;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.port.WebhookEndpointPolicy;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public class SecureWebhookEndpointPolicy implements WebhookEndpointPolicy {
    @Override
    public URI validate(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw rejected();
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isNonPublic(address)) throw rejected();
            }
            return uri;
        } catch (ApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApplicationException("WEBHOOK_URL_REJECTED", "通知 Webhook 地址无效或域名无法解析", exception);
        }
    }

    private boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private ApplicationException rejected() {
        return new ApplicationException("WEBHOOK_URL_REJECTED", "通知 Webhook 必须解析到公网 HTTPS 地址");
    }
}
