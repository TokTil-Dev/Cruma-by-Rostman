package com.rost.cruma.viewer.launch;

public final class StartKeys {
    private StartKeys() {}

    public enum Enter {
        NONE,
        OPEN,
        TAKE_RESULT
    }

    public static Enter onEnter(
            boolean popupOpen,
            boolean guideFocused,
            boolean fieldActive,
            boolean searchActive,
            boolean navVisible,
            String query) {
        if (popupOpen || guideFocused || (fieldActive && !searchActive)) {
            return Enter.NONE;
        }
        if (fieldActive) {
            return query.isBlank() ? Enter.OPEN : Enter.TAKE_RESULT;
        }
        return navVisible ? Enter.NONE : Enter.OPEN;
    }
}
