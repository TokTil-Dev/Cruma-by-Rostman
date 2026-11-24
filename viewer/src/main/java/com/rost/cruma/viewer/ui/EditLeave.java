package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.editing.EditorUi;
import java.util.List;

final class EditLeave {
    static final String TITLE = "Unsaved changes";

    enum Reason {
        VIEWER,
        MENU,
        WINDOW,
        RELOAD
    }

    enum Choice {
        SAVE,
        KEEP,
        DISCARD
    }

    record Outcome(boolean stay, boolean dropDraft, Reason reason) {}

    private EditLeave() {}

    static List<Choice> choices(Reason reason) {
        return reason == Reason.RELOAD ? List.of(Choice.SAVE, Choice.DISCARD) : List.of(Choice.values());
    }

    static String line(int changes, Reason reason) {
        var count = changes <= 0
                ? "Some changes are not saved."
                : EditorUi.plural(changes, "change is", "changes are") + " not saved.";
        var lost = changes == 1
                ? " Save it first, or it is lost with the reload."
                : " Save them first, or they are lost with the reload.";
        return reason == Reason.RELOAD ? count + lost : count;
    }

    static String label(Choice choice, Reason reason) {
        var reload = reason == Reason.RELOAD;
        return switch (choice) {
            case SAVE -> "Save…";
            case KEEP -> "Leave and keep the draft";
            case DISCARD -> reload ? "Discard and reload" : "Discard";
        };
    }

    static String tip(Choice choice, Reason reason) {
        var reload = reason == Reason.RELOAD;
        return switch (choice) {
            case SAVE -> "Stay in Edit and open the save review.";
            case KEEP ->
                "Leave Edit. The recovery draft keeps the changes, and the next time you open Edit it offers to"
                        + " recover them.";
            case DISCARD ->
                "Drop every change that is not saved and its recovery draft, then "
                        + (reload ? "load the maps again." : "leave.") + " Saved folders stay.";
        };
    }

    static Outcome outcome(Choice choice, Reason reason) {
        return switch (choice) {
            case SAVE -> new Outcome(true, false, reason);
            case KEEP -> new Outcome(false, false, reason);
            case DISCARD -> new Outcome(false, true, reason);
        };
    }
}
