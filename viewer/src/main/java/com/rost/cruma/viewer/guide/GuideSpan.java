package com.rost.cruma.viewer.guide;

public sealed interface GuideSpan {
    static String plain(Iterable<? extends GuideSpan> spans) {
        var text = new StringBuilder();
        for (var span : spans) {
            text.append(span.text());
        }
        return text.toString();
    }

    String text();

    record Text(String text) implements GuideSpan {}

    record Name(String text) implements GuideSpan {}

    record Code(String text) implements GuideSpan {}

    record Key(String text) implements GuideSpan {}

    record Link(String text, String target) implements GuideSpan {}
}
