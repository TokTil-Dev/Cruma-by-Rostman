package com.rost.cruma.viewer.guide;

import java.util.ArrayList;
import java.util.List;

public final class GuideHistory {
    public static final String HOME = "";

    private final List<String> pages = new ArrayList<>(List.of(HOME));
    private int at;

    public String getCurrent() {
        return pages.get(at);
    }

    public void open(String page) {
        if (page.equals(getCurrent())) {
            return;
        }
        pages.subList(at + 1, pages.size()).clear();
        pages.add(page);
        at++;
    }

    public boolean canGoBack() {
        return at > 0;
    }

    public boolean canGoForward() {
        return at < pages.size() - 1;
    }

    public void back() {
        if (canGoBack()) {
            at--;
        }
    }

    public void forward() {
        if (canGoForward()) {
            at++;
        }
    }
}
