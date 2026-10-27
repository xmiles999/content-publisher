package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ContentGenerator;
import io.contentpublisher.platform.domain.AdaptedContent;
import io.contentpublisher.platform.domain.ChannelType;

public final class ChannelCopyOptimizationService {
    private final ContentGenerator generator;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(2);

    public ChannelCopyOptimizationService(ContentGenerator generator) { this.generator = generator; }

    public AdaptedContent optimize(String tenantId, AdaptedContent source) {
        if (source.title() == null || source.title().isBlank() || source.title().length() > 500
                || source.body() == null || source.body().isBlank() || source.body().length() > 100_000) {
            throw new ApplicationException("CHANNEL_COPY_INVALID", "平台标题或正文无效，请检查内容长度");
        }
        ChannelType type = source.channelType();
        if (ChannelTextLimits.fits(type, source.title(), source.body())) return source;
        if (generator == null) throw new ApplicationException("AI_DISABLED", "请配置 AI 服务，或手动精简平台文案");
        // 一次调用；不循环重试，也不把仍超限的结果硬截断。
        if (!slots.tryAcquire()) throw new ApplicationException("CHANNEL_COPY_BUSY", "已有文案正在优化，请稍后重试；当前输入已保留");
        ContentGenerator.ChannelCopy generated;
        try {
            generated = generator.optimizeChannelCopy(tenantId, type, source.format(), source.title(), source.body(),
                    ChannelTextLimits.titleLimit(type), ChannelCatalog.definition(type).characterLimit());
        } catch (ApplicationException exception) {
            if (java.util.Set.of("AI_REQUEST_FAILED", "AI_REQUEST_INTERRUPTED").contains(exception.code())) {
                throw new ApplicationException("CHANNEL_COPY_OPTIMIZATION_FAILED", "AI 优化未完成，请手动精简或稍后重试；当前输入已保留", exception);
            }
            throw exception;
        } finally {
            slots.release();
        }
        var result = generated == null ? null : new ContentGenerator.ChannelCopy(
                ChannelTextLimits.codePoints(source.title()) <= ChannelTextLimits.titleLimit(type) ? source.title() : generated.title(),
                ChannelTextLimits.bodyLength(type, source.body()) <= ChannelCatalog.definition(type).characterLimit()
                        ? source.body() : generated.body());
        if (result == null || result.title() == null || result.body() == null
                || result.title().isBlank() || result.body().isBlank()
                || !ChannelTextLimits.fits(type, result.title(), result.body())) {
            throw new ApplicationException("CHANNEL_COPY_LIMIT_EXCEEDED", "AI 结果仍超过平台限制或内容为空，请手动精简或重新优化；当前输入已保留");
        }
        // 不允许模型捏造新链接；原有链接可按篇幅取舍。
        var sourceUrls = ChannelTextLimits.httpUrls(source.title() + "\n" + source.body());
        if (!sourceUrls.containsAll(ChannelTextLimits.httpUrls(result.title() + "\n" + result.body()))) {
            throw new ApplicationException("CHANNEL_COPY_INVALID", "AI 结果包含原文没有的链接，未应用；请核对后重试");
        }
        return new AdaptedContent(type, source.format(), result.title(), result.body(),
                source.tags(), source.characterLimit());
    }
}
