package io.contentpublisher.platform.domain;

public final class ArticleLimits {
    public static final int TITLE = 500;
    public static final int SUMMARY = 2000;
    public static final int MARKDOWN = 100_000;
    public static final int TAG = 50;
    public static final int KEYWORD = 100;
    public static final int TAGS = 15;
    public static final int KEYWORDS = 30;
    public static final int LANGUAGE = 20;

    private ArticleLimits() {
    }
}
