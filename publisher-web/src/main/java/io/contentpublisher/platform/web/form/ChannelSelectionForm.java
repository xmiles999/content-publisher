package io.contentpublisher.platform.web.form;

import io.contentpublisher.platform.domain.ChannelType;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ChannelSelectionForm {
    private Set<ChannelType> enabled = new LinkedHashSet<>();
    private Map<ChannelType, Integer> versions = new EnumMap<>(ChannelType.class);
    public Set<ChannelType> getEnabled() { return enabled; }
    public void setEnabled(Set<ChannelType> enabled) { this.enabled = enabled; }
    public Map<ChannelType, Integer> getVersions() { return versions; }
    public void setVersions(Map<ChannelType, Integer> versions) { this.versions = versions; }
}
