package io.contentpublisher.platform.web.form;

import io.contentpublisher.platform.domain.ChannelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ManualChannelProfileForm {
    @NotNull
    private ChannelType channelType;

    @Min(0)
    private int expectedVersion;

    private boolean enabled;

    @Size(max = 120, message = "账号别名不能超过 120 个字符")
    private String accountAlias;

    @Size(max = 1220, message = "默认标签内容过长")
    private String defaultTags;

    @Size(max = 200, message = "默认栏目不能超过 200 个字符")
    private String defaultSection;

    @Size(max = 1000, message = "发布备注不能超过 1000 个字符")
    private String notes;

    @Min(value = 0, message = "排序不能小于 0")
    @Max(value = 1000, message = "排序不能大于 1000")
    private int sortOrder;

    public ChannelType getChannelType() { return channelType; }
    public void setChannelType(ChannelType channelType) { this.channelType = channelType; }
    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAccountAlias() { return accountAlias; }
    public void setAccountAlias(String accountAlias) { this.accountAlias = accountAlias; }
    public String getDefaultTags() { return defaultTags; }
    public void setDefaultTags(String defaultTags) { this.defaultTags = defaultTags; }
    public String getDefaultSection() { return defaultSection; }
    public void setDefaultSection(String defaultSection) { this.defaultSection = defaultSection; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
