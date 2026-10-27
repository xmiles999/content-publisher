package io.contentpublisher.platform.web.form;

import jakarta.validation.constraints.Min;

public class ReadershipForm {
    @Min(value = 0, message = "统计版本无效")
    private int version;
    @Min(value = 0, message = "阅读次数不能为负数")
    private Long views;
    @Min(value = 0, message = "去重人数不能为负数")
    private Long uniqueReaders;
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }
    public Long getUniqueReaders() { return uniqueReaders; }
    public void setUniqueReaders(Long uniqueReaders) { this.uniqueReaders = uniqueReaders; }
}
