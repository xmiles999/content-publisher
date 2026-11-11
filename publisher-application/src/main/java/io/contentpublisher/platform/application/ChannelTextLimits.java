package io.contentpublisher.platform.application;

import io.contentpublisher.platform.domain.ChannelType;
import java.util.regex.Pattern;

/** 当前渠道目录的默认限制；自托管实例和账号套餐仍须以平台实际规则为准。 */
public final class ChannelTextLimits {
    private static final Pattern WHITESPACE = Pattern.compile("\\p{IsWhite_Space}+");
    private static final Pattern URL = Pattern.compile("https?://[^\\p{IsWhite_Space}<>，。！？；：、）】》」』]+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING_PUNCTUATION = ".,!?;:)]}，。！？；：、）】》」』";
    private ChannelTextLimits() { }

    public static int titleLimit(ChannelType type) {
        return switch (type) {
            case XIAOHONGSHU -> 20;
            case WECHAT_OFFICIAL -> 64;
            case TOUTIAO -> 30;
            case BILIBILI_COLUMN -> 80;
            case LINKEDIN, JIANSHU, X, MASTODON -> 100;
            case REDDIT -> 300;
            default -> 500;
        };
    }

    public static int codePoints(String value) { return value.codePointCount(0, value.length()); }

    /** 工作区字数不包含 Unicode White_Space；不修改需要发布的原文。 */
    public static int textLength(String value) { return codePoints(withoutWhitespace(value)); }

    private static String withoutWhitespace(String value) { return WHITESPACE.matcher(value).replaceAll(""); }

    public static int bodyLength(ChannelType type, String value) {
        if (type != ChannelType.X && type != ChannelType.MASTODON) return textLength(value);
        var urls = URL.matcher(value);
        int count = 0, start = 0;
        while (urls.find()) {
            int end = urlEnd(value, urls.start(), urls.end());
            count += segmentLength(type, value.substring(start, urls.start())) + 23;
            start = end;
        }
        return count + segmentLength(type, value.substring(start));
    }

    private static int segmentLength(ChannelType type, String value) {
        return type == ChannelType.X ? weighted(value) : textLength(value);
    }

    private static int urlEnd(String value, int start, int end) {
        while (end > start && TRAILING_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) end--;
        return end;
    }

    static java.util.Set<String> httpUrls(String value) {
        return URL.matcher(value).results().map(m -> value.substring(m.start(), urlEnd(value, m.start(), m.end())))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static int weighted(String value) {
        // X 的单权重区间；组合 emoji 按码点保守累计，不低估长度。
        return withoutWhitespace(value).codePoints().map(cp -> cp <= 0x10ff || cp >= 0x2000 && cp <= 0x200d
                || cp >= 0x2010 && cp <= 0x201f || cp >= 0x2032 && cp <= 0x2037 ? 1 : 2).sum();
    }

    public static boolean fits(ChannelType type, String title, String body) {
        return textLength(title) <= titleLimit(type)
                && bodyLength(type, body) <= ChannelCatalog.definition(type).characterLimit();
    }
}
