package io.contentpublisher.platform.web.form;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BatchPublishArticleForm {
    @NotEmpty(message = "请至少选择一个 API 发布账号")
    @Size(max = 20, message = "单次最多发布到 20 个账号")
    private List<UUID> channelAccountIds = new ArrayList<>();

    @Size(max = 2048, message = "原文链接不能超过 2048 个字符")
    private String canonicalUrl;

    @Size(max = 40, message = "计划发布时间格式无效")
    private String scheduledAt;

    @Size(max = 64, message = "带时区的计划发布时间格式无效")
    private String scheduledAtOffset;

    @Size(max = 80, message = "时区名称过长")
    private String timeZone;

    @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}")
    private String idempotencyKey;

    public List<UUID> getChannelAccountIds() { return channelAccountIds; }
    public void setChannelAccountIds(List<UUID> channelAccountIds) { this.channelAccountIds = channelAccountIds; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }
    public String getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(String scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getScheduledAtOffset() { return scheduledAtOffset; }
    public void setScheduledAtOffset(String scheduledAtOffset) { this.scheduledAtOffset = scheduledAtOffset; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
