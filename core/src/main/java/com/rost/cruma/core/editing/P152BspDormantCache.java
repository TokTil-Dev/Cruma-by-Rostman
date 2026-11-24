package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152BspReceiverModel.DormantFacts;
import com.rost.cruma.core.editing.P152BspWorldModel.Selection;
import com.rost.cruma.core.editing.P152EffectiveInputs.BspSources;
import com.rost.cruma.core.editing.P152EffectiveInputs.DormantRenderer;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.MapInputs;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152BspDormantCache {
    static final String PROFILE = "p152-ship-position-fresh-dormant-saved-masks-v1";
    static final String SOURCE_SHA256 = "024adb9bab8ce51d7ab88b217584f8721e22ebdc76d54a2a9fa3c0f08e29ef5a";

    private P152BspDormantCache() {}

    record DanglingLinkCounts(int placementAtlasLinks, int sectionAtlasLinks) {}

    static final class Receipt {
        private final BspSources sources;
        private final MapInputs map;
        private final DormantRenderer renderer;
        private final String packageName, bodySha256;
        private final int levelReference, modelReference;
        private final DormantFacts facts;
        private final List<Input> inputs;

        private Receipt(
                BspSources sources,
                MapInputs map,
                DormantRenderer renderer,
                PackageBytes source,
                Selection selection,
                DormantFacts facts) {
            this.sources = sources;
            this.map = map;
            this.renderer = renderer;
            this.packageName = source.packageName;
            this.bodySha256 = P152ActorDomain.hash(source.body);
            this.levelReference = selection.getLevelReference();
            this.modelReference = selection.getModelReference();
            this.facts = facts;
            var all = new ArrayList<>(sources.getReceipt());
            all.add(renderer.getInput());
            inputs = List.copyOf(all);
        }

        String getProfile() {
            return PROFILE;
        }

        String getSourceSha256() {
            return map.getSourceSha256();
        }

        int getLevelReference() {
            return levelReference;
        }

        int getModelReference() {
            return modelReference;
        }

        int getPlacementsCount() {
            return facts.placementAtlasLinks().size();
        }

        DanglingLinkCounts getDanglingLinkCounts() {
            return new DanglingLinkCounts(getPlacementsCount(), (int) facts.sectionAtlasLinks().stream()
                    .filter(index -> index >= 0)
                    .count());
        }

        DormantFacts getFacts() {
            return facts;
        }

        List<Input> getInputs() {
            return inputs;
        }

        void requireSource(PackageBytes source) throws IOException {
            if (!packageName.equals(source.packageName) || !bodySha256.equals(P152ActorDomain.hash(source.body))) {
                throw new IOException("Dormant BSP receipt belongs to a different frozen source");
            }
        }

        void verifySourcesUnchanged() throws IOException {
            sources.verifySourcesUnchanged();
            map.verifyMapUnchanged();
            renderer.verifySourcesUnchanged();
        }
    }

    static Receipt capture(BspSources sources, MapInputs inputs, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        sources.requireMap(inputs);
        inputs.verifyMapUnchanged();
        var source = inputs.getPackageBytes();
        if (!SOURCE_SHA256.equals(inputs.getSourceSha256())
                || !source.packageName.equals("Ship_position")
                || source.fileVersion != 132
                || source.licenseVersion != 40) {
            throw new IOException("Dormant BSP profile requires the exact audited Ship_position source");
        }
        var selected = P152BspWorldModel.select(source, cancelled);
        if (selected.getLevelReference() != 5
                || selected.getModelReference() != 26
                || !source.getObjectPath(selected.getModelReference()).equals("Ship_position.Model16")) {
            throw new IOException("Dormant BSP native Level.Model binding differs from the audited source");
        }
        var facts = P152BspReceiverModel.inspectDormantStructure(source, selected.getModelReference(), cancelled);
        facts.requireLevelReference(selected.getLevelReference());
        if (facts.nodes() != 6
                || facts.surfaces() != 6
                || facts.zones() != 2
                || facts.leaves() != 1
                || facts.placementAtlasLinks().size() != 6) {
            throw new IOException("Dormant BSP structure differs from the audited source");
        }
        selected.requireSource(source);
        P152FreshHashQuery.checkCancelled(cancelled);
        var renderer = sources.captureDormantRenderer();
        var receipt = new Receipt(sources, inputs, renderer, source, selected, facts);
        receipt.requireSource(source);
        receipt.verifySourcesUnchanged();
        P152FreshHashQuery.checkCancelled(cancelled);
        return receipt;
    }
}
