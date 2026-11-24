package com.rost.cruma.viewer.ui;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class EffectGuideActions {
    private static final long LIST_WAIT_NANOS = 60_000_000_000L;
    private static final String TOOL = "effects";
    private static final String NO_EFFECTS =
            "This view has no Effects tool. Open a world or Effects from the start menu.";

    private EffectGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        actions.put(
                "effects-open-panel",
                new NotedGuideAction(
                        "effects-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_EFFECTS), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
        var example = new PlayExample();
        actions.put(example.getId(), example);
    }

    private static @Nullable String blocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_EFFECTS);
        if (missing != null) {
            return missing;
        }
        var effects = host.getEffectsTool();
        return effects == null ? NO_EFFECTS : effects.getUnavailable();
    }

    private static final class PlayExample implements GuideAction {
        private long waitingUntil;
        private @Nullable String note;

        @Override
        public String getId() {
            return "effects-play-example";
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            return EffectGuideActions.blocked(host);
        }

        @Override
        public void run(GuideHost host) {
            var effects = host.getEffectsTool();
            if (effects == null) {
                return;
            }
            note = null;
            effects.open();
            waitingUntil = System.nanoTime() + LIST_WAIT_NANOS;
        }

        @Override
        public @Nullable String getStatus(GuideHost host) {
            var effects = host.getEffectsTool();
            if (waitingUntil == 0 || effects == null) {
                return note;
            }
            if (effects.isListing()) {
                if (System.nanoTime() < waitingUntil) {
                    return "Reading the client's effects…";
                }
                waitingUntil = 0;
                note = "The client's effects did not load. The Effects panel says why.";
                return note;
            }
            waitingUntil = 0;
            var name = effects.playExample();
            note = name == null
                    ? "The client has no LineageEffect package, so there is no effect to play."
                    : "Playing " + name + ". Its card in the panel names its package and counts its particles.";
            return note;
        }

        @Override
        public boolean isBusy() {
            return waitingUntil != 0;
        }

        @Override
        public void reset() {
            waitingUntil = 0;
            note = null;
        }
    }
}
