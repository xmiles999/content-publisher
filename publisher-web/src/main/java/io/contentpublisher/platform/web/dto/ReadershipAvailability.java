package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.application.PublicationMethod;
import io.contentpublisher.platform.application.PublicationRecord;
import io.contentpublisher.platform.application.ReadershipApplicationService;
import io.contentpublisher.platform.domain.ChannelType;

/** Describes implemented synchronization coverage without claiming live platform connectivity. */
public record ReadershipAvailability(boolean syncSupported, String label, String explanation) {
    public static ReadershipAvailability from(PublicationRecord record) {
        if (record.channelType() == ChannelType.JUEJIN) {
            if (ReadershipApplicationService.supportsSync(record)) {
                return new ReadershipAvailability(true, "支持链接读取",
                        "可通过已保存的掘金文章链接读取公开阅读次数，无需配置账号或填写数字；平台未提供去重人数。");
            }
            return new ReadershipAvailability(false, "需要有效的掘金文章链接",
                    "请在文章发布页保存 https://juejin.cn/post/文章编号 格式的链接，再同步阅读量；无需手动填写数字。");
        }
        if (ReadershipApplicationService.supportsSync(record)) {
            return new ReadershipAvailability(true, "支持接口同步",
                    "可使用原发布账号获取官方浏览次数；接口未提供去重阅读人数，无需自行填写。");
        }
        if (record.method() == PublicationMethod.MANUAL
                && (record.channelType() == ChannelType.DEV || record.channelType() == ChannelType.DISCOURSE)) {
            return new ReadershipAvailability(false, "此记录暂不支持自动同步",
                    "这条人工发布记录未关联 API 发布账号。该渠道目前仅支持 API 发布记录的统计同步。");
        }
        if (record.channelType() == ChannelType.CSDN) {
            return new ReadershipAvailability(false, "暂未接入自动统计",
                    "CSDN 公开页面目前会拦截读取，尚未接入可靠来源。无需手动填写；可打开平台文章查看，或按需补录。");
        }
        return new ReadershipAvailability(false, "暂未接入自动统计",
                "当前尚未接入该渠道的阅读数据。无需手动填写；可打开平台文章查看已有数据，或按需补录。");
    }
}
