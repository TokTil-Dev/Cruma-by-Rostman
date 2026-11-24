package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.USphere;
import glm_.vec3.Vec3;
import java.util.Objects;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public final class NativeLightCollector {
    public static final int LIMIT = 256;

    private final Source[] selected = new Source[LIMIT];
    private int count;

    public record Source(
            Object identity, Vec3 position, float radius, int effect, boolean specialLit, int emitterKind) {
        public Source {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(position);
        }
    }

    public record Receiver(
            USphere sphere,
            int drawType,
            boolean unlit,
            boolean specialLit,
            boolean useDynamicLights,
            boolean staticActor,
            boolean lightChanged,
            boolean hasStaticMesh,
            boolean hasStaticMeshInstance,
            boolean mover,
            boolean moverAllowsStaticLights,
            boolean sunAffect) {}

    public interface Inputs {
        int getLeafCount();

        int getStaticSource(int leaf, int offset);

        @Nullable
        Source getStaticRenderData(int source);

        @Nullable
        Source getRuntimeRenderData(int leaf, int offset);
    }

    public int collect(Receiver receiver, Inputs inputs) {
        for (var i = 0; i < count; i++) {
            selected[i] = null;
        }
        count = 0;
        if (receiver.unlit()) {
            return 0;
        }
        var skipStatic = receiver.drawType() == 8
                && ((receiver.staticActor()
                                && !receiver.lightChanged()
                                && receiver.hasStaticMesh()
                                && receiver.hasStaticMeshInstance())
                        || (receiver.mover() && !receiver.moverAllowsStaticLights()));
        for (var leaf = 0; leaf < inputs.getLeafCount(); leaf++) {
            if (!skipStatic) {
                for (var offset = 0; count < LIMIT; offset++) {
                    var source = inputs.getStaticSource(leaf, offset);
                    if (source == NativeLightScene.NO_SOURCE) {
                        break;
                    }
                    if (source == NativeLightScene.UNAVAILABLE_SOURCE) {
                        continue;
                    }
                    var data = inputs.getStaticRenderData(source);
                    if (data != null) {
                        accept(receiver, data);
                    }
                }
            }
            if (receiver.useDynamicLights()) {
                for (var offset = 0; count < LIMIT; offset++) {
                    var data = inputs.getRuntimeRenderData(leaf, offset);
                    if (data == null) {
                        break;
                    }
                    accept(receiver, data);
                }
            }
            if (count == LIMIT) {
                break;
            }
        }
        return count;
    }

    public int collect(
            NativeLightScene scene,
            NativeLightReceiver receiver,
            IntFunction<@Nullable Source> staticGetter,
            Source @Nullable [][] runtimeLeaves) {
        var flags = receiver.getFlags();
        var inputs = new Inputs() {
            public int getLeafCount() {
                return receiver.getLeafCount();
            }

            public int getStaticSource(int leaf, int offset) {
                var start = scene.getLeaf(receiver.getLeaf(leaf)).permeating();
                return start < 0 || offset >= scene.getStaticReferenceCount() - start
                        ? NativeLightScene.NO_SOURCE
                        : scene.getStaticSource(start + offset);
            }

            public @Nullable Source getStaticRenderData(int source) {
                return staticGetter.apply(source);
            }

            public @Nullable Source getRuntimeRenderData(int leaf, int offset) {
                if (runtimeLeaves == null) {
                    return null;
                }
                var lights = runtimeLeaves[receiver.getLeaf(leaf)];
                return lights == null || offset >= lights.length ? null : lights[offset];
            }
        };
        return collect(
                new Receiver(
                        receiver.getWorldSphere(),
                        flags.drawType(),
                        flags.unlit(),
                        flags.specialLit(),
                        flags.useDynamicLights(),
                        flags.staticActor(),
                        flags.lightChanged(),
                        true,
                        flags.hasStaticMeshInstance(),
                        false,
                        false,
                        flags.sunAffect()),
                inputs);
    }

    public int size() {
        return count;
    }

    public Source getSource(int index) {
        return selected[Objects.checkIndex(index, count)];
    }

    private void accept(Receiver receiver, Source source) {
        if (receiver.specialLit() != source.specialLit()) {
            return;
        }
        var center = receiver.sphere().location();
        var dx = source.position().getX() - center.getX();
        var dy = source.position().getY() - center.getY();
        var dz = source.position().getZ() - center.getZ();
        var distanceSquared = (dx * dx + dy * dy) + dz * dz;
        var reach = source.radius() + receiver.sphere().radius();
        if (source.emitterKind() >= 0) {
            if (reach * reach < distanceSquared) {
                return;
            }
            if (source.emitterKind() == 0 && receiver.drawType() != 2) {
                return;
            }
            if (source.emitterKind() == 1 && receiver.drawType() == 2) {
                return;
            }
        } else if (source.effect() != 19 && reach * reach < distanceSquared) {
            return;
        }
        if (receiver.drawType() == 2 && source.effect() == 19 && !receiver.sunAffect()) {
            return;
        }
        for (var i = 0; i < count; i++) {
            if (selected[i].identity() == source.identity()) {
                return;
            }
        }
        selected[count++] = source;
    }
}
