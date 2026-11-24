package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152EffectiveInputs.Fact;
import com.rost.cruma.core.editing.P152EffectiveInputs.MapInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.ObjectReference;
import com.rost.cruma.core.editing.P152EffectiveInputs.Origin;
import com.rost.cruma.core.editing.P152EffectiveInputs.Provenance;
import com.rost.cruma.core.editing.P152ShadowMeshDrawInputs.Binding;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152ShadowMeshMaterials {
    private P152ShadowMeshMaterials() {}

    enum Source {
        NO_ACTOR,
        ACTOR_SKIN,
        MESH_SECTION,
        DEFAULT_MATERIAL
    }

    record Selection(Source source, String base, String material, boolean modified) {}

    record Root(int section, Source source, String path, Provenance provenance) {}

    record ActorMaterials(Binding drawInputs, Fact<List<ObjectReference>> skins, List<Root> roots) {
        ActorMaterials {
            roots = List.copyOf(roots);
        }
    }

    static Selection select(
            boolean actorPresent,
            int section,
            List<String> skins,
            String sectionMaterial,
            String defaultMaterial,
            int modifierFlags,
            int allowModifier,
            String modifier) {
        Objects.requireNonNull(skins);
        Objects.requireNonNull(sectionMaterial);
        Objects.requireNonNull(defaultMaterial);
        Objects.requireNonNull(modifier);
        if (section < 0) {
            throw new IllegalArgumentException("Negative mesh material section");
        }
        if (!actorPresent) {
            return new Selection(Source.NO_ACTOR, "", "", false);
        }
        String base = section < skins.size() ? Objects.requireNonNull(skins.get(section)) : "";
        var source = Source.ACTOR_SKIN;
        if (base.isEmpty()) {
            base = sectionMaterial;
            source = Source.MESH_SECTION;
        }
        if (base.isEmpty()) {
            base = defaultMaterial;
            source = Source.DEFAULT_MATERIAL;
        }
        boolean modified = !base.isEmpty() && (modifierFlags & 1) != 0 && allowModifier != 0 && !modifier.isEmpty();
        return new Selection(source, base, modified ? modifier : base, modified);
    }

    static List<ActorMaterials> capture(List<Binding> bindings, MapInputs inputs, BooleanSupplier cancelled)
            throws IOException {
        bindings = List.copyOf(bindings);
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(cancelled);
        inputs.verifySourcesUnchanged(cancelled);
        var fallback = inputs.getShadowDefaultMaterial();
        var result = new ArrayList<ActorMaterials>(bindings.size());
        for (var binding : bindings) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (!binding.placement().actor().packageSha256().equals(inputs.getSourceSha256())) {
                throw new IOException("Shadow material actor differs from the captured map");
            }
            var skins = inputs.getShadowMeshSkins(binding.placement().actor().objectPath());
            result.add(bind(binding, skins, fallback, cancelled));
        }
        inputs.verifySourcesUnchanged(cancelled);
        return List.copyOf(result);
    }

    static ActorMaterials bind(
            Binding binding, Fact<List<ObjectReference>> skins, Fact<String> fallback, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(fallback);
        Objects.requireNonNull(cancelled);
        skins = new Fact<>(List.copyOf(skins.value()), skins.provenance());
        var paths = skins.value().stream()
                .map(s -> s.isNull() ? "" : s.objectPath())
                .toList();
        var roots = new ArrayList<Root>(binding.mesh().sections().size());
        for (int section = 0; section < binding.mesh().sections().size(); section++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var material = binding.mesh().sections().get(section).material();
            if ((section >= paths.size() || paths.get(section).isEmpty()) && !material.referenceAuthored()) {
                throw new IOException("Mesh material slot has no authenticated default or authored reference");
            }
            var selected = select(
                    true, section, paths, material.reference() == 0 ? "" : material.path(), fallback.value(), 0, 1, "");
            if (selected.material().isEmpty()) {
                throw new IOException("Shadow material selection resolved to null");
            }
            var provenance = switch (selected.source()) {
                case ACTOR_SKIN -> skins.provenance();
                case DEFAULT_MATERIAL -> fallback.provenance();
                case MESH_SECTION ->
                    new Provenance(
                            Origin.INSTANCE,
                            "Engine.StaticMesh",
                            "Materials[" + section + "].Material",
                            binding.placement().mesh().packageSha256());
                case NO_ACTOR -> throw new AssertionError("Captured mesh has no actor");
            };
            roots.add(new Root(section, selected.source(), selected.material(), provenance));
        }
        return new ActorMaterials(binding, new Fact<>(List.copyOf(skins.value()), skins.provenance()), roots);
    }
}
