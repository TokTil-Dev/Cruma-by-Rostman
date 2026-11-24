package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.EAlphaOperation;
import com.rost.cruma.core.unreal.UMaterial.EColorOperation;
import com.rost.cruma.core.unreal.UMaterial.OutputBlending;
import com.rost.cruma.core.unreal.UMaterial.UBitMapMaterial;
import com.rost.cruma.core.unreal.UMaterial.UColorModifier;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UConstantColor;
import com.rost.cruma.core.unreal.UMaterial.UFadeColor;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexCoordSource;
import com.rost.cruma.core.unreal.UMaterial.UTexEnvMap;
import com.rost.cruma.core.unreal.UMaterial.UTexOscillator;
import com.rost.cruma.core.unreal.UMaterial.UTexOscillatorTriggered;
import com.rost.cruma.core.unreal.UMaterial.UTexPanner;
import com.rost.cruma.core.unreal.UMaterial.UTexPannerTriggered;
import com.rost.cruma.core.unreal.UMaterial.UTexRotator;
import com.rost.cruma.core.unreal.UMaterial.UTexScaler;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UMaterial.UVertexColor;
import com.rost.cruma.core.unreal.UObjectRef;
import com.rost.cruma.core.unreal.primitives.UColorFade;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.AuthoredMaterialFactors.Step;
import com.rost.cruma.core.world.AuthoredMaterialPassProgram.ShaderMaps;
import com.rost.cruma.core.world.AuthoredMaterialPasses.Builder;
import com.rost.cruma.core.world.AuthoredMaterialProgram.Compilation;
import com.rost.cruma.core.world.AuthoredMaterialProgram.CoordinateSource;
import com.rost.cruma.core.world.AuthoredMaterialProgram.Stage;
import com.rost.cruma.core.world.TextureCoordinates.Angles;
import com.rost.cruma.core.world.TextureCoordinates.Axis;
import com.rost.cruma.core.world.TextureCoordinates.Jitter;
import com.rost.cruma.core.world.TextureCoordinates.Modifier;
import com.rost.cruma.core.world.TextureCoordinates.Oscillator;
import com.rost.cruma.core.world.TextureCoordinates.Panner;
import com.rost.cruma.core.world.TextureCoordinates.Rotator;
import com.rost.cruma.core.world.TextureCoordinates.Scaler;
import com.rost.cruma.core.world.TextureCoordinates.Source;
import com.rost.cruma.core.world.TextureCoordinates.Trigger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class AuthoredMaterialCompiler {
    private final Function<UTexture, TextureSequence> load;
    private final Function<UMaterial, String> identify;
    private final IdentityHashMap<UTexture, TextureSequence> bitmaps = new IdentityHashMap<>();
    private final IdentityHashMap<UMaterial, Modifier> modifiers = new IdentityHashMap<>();
    private final IdentityHashMap<Modifier, String> coordinateIdentities = new IdentityHashMap<>();
    private final IdentityHashMap<UMaterial, AuthoredMaterialProgram> programs = new IdentityHashMap<>();
    private final IdentityHashMap<UMaterial, HashMap<PassKey, AuthoredMaterialPassProgram>> passPrograms =
            new IdentityHashMap<>();
    private final IdentityHashMap<UMaterial, Compilation> compilations = new IdentityHashMap<>();

    AuthoredMaterialCompiler(Function<UTexture, TextureSequence> load) {
        this(load, UMaterial::getSourceIdentity);
    }

    AuthoredMaterialCompiler(Function<UTexture, TextureSequence> load, Function<UMaterial, String> identify) {
        this.load = load;
        this.identify = identify;
    }

    enum Client {
        P152,
        P162
    }

    static int materialSize(@Nullable UMaterial material, boolean horizontal) {
        try {
            return materialSize(material, horizontal, new IdentityHashMap<>());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Nullable
    AuthoredMaterialProgram compile(@Nullable UMaterial material) {
        if (material == null) {
            return null;
        }
        if (!programs.containsKey(material)) {
            programs.put(material, compileUncached(material));
        }
        return programs.get(material);
    }

    @Nullable
    AuthoredMaterialProgram compileBspWater(@Nullable UMaterial material) {
        return compile(material);
    }

    @Nullable
    AuthoredMaterialPassProgram compilePasses(@Nullable UMaterial material, int capacity, Client client) {
        return compilePasses(material, capacity, client, false);
    }

    @Nullable
    AuthoredMaterialPassProgram compilePasses(
            @Nullable UMaterial material, int capacity, Client client, boolean reflections) {
        if (material == null) {
            return null;
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("invalid material pass capacity");
        }
        Objects.requireNonNull(client);
        var key = new PassKey(capacity, client, reflections);
        var entries = passPrograms.computeIfAbsent(material, _ -> new HashMap<>());
        if (!entries.containsKey(key)) {
            entries.put(key, compilePassesUncached(material, key));
        }
        return entries.get(key);
    }

    void clear() {
        programs.clear();
        passPrograms.clear();
        compilations.clear();
        modifiers.clear();
        coordinateIdentities.clear();
        bitmaps.clear();
    }

    private record PassKey(int capacity, Client client, boolean reflections) {}

    private @Nullable AuthoredMaterialPassProgram compilePassesUncached(UMaterial material, PassKey key) {
        try {
            var visited = new IdentityHashMap<UMaterial, Boolean>();
            var compilationMaterial = material;
            while (compilationMaterial instanceof UFinalBlend || compilationMaterial instanceof UColorModifier) {
                if (visited.put(compilationMaterial, Boolean.TRUE) != null) {
                    throw new UnsupportedGraph();
                }
                compilationMaterial =
                        ((UModifier) compilationMaterial).getMaterial().get();
            }
            visited.clear();
            var outer = new ArrayList<Modifier>();
            Integer modifierColor = null;
            var coordinateRoot = false;
            var internalColor = false;
            while (material instanceof UModifier modifier) {
                if (visited.put(material, Boolean.TRUE) != null) {
                    throw new UnsupportedGraph();
                }
                if (material instanceof UColorModifier color) {
                    modifierColor = UColorFade.argb(color.getColor());
                    if (coordinateRoot) {
                        internalColor = true;
                    }
                } else if (!(material instanceof UFinalBlend)) {
                    coordinateRoot = true;
                    var coordinate = coordinateModifier(modifier);
                    if (coordinate != null) {
                        outer.add(coordinate);
                    }
                }
                material = modifier.getMaterial().get();
            }
            var shader = material instanceof UShader;
            if (shader) {
                var source = (UShader) material;
                if (source.getSelfIllumination().peekFullName() != null
                        || source.getDetail().peekFullName() != null) {
                    return null;
                }
                if (source.getSpecular().get() != null && source.getOutputBlending() != OutputBlending.OB_Normal) {
                    return null;
                }
            } else if (!(material instanceof UTexture || material instanceof UCombiner)) {
                return null;
            }
            if (internalColor && !shader) {
                return null;
            }
            var plan = new Plan(64);
            plan.coldCoordinates.addAll(outer);
            var diffuse = shader ? ((UShader) material).getDiffuse().get() : material;
            var opacity = shader ? materialOf(((UShader) material).getOpacity()) : null;
            compileColor(diffuse, plan, 0, !shader);
            prependCoordinates(plan.stages, 0, outer);
            var builder = new Builder(key.capacity());
            if (shader) {
                var framebuffer = AuthoredFramebuffer.resolve(material);
                if (framebuffer == null) {
                    return null;
                }
                builder.setFramebuffer(
                        blendFactor(framebuffer.color().source()),
                        blendFactor(framebuffer.color().destination()),
                        framebuffer.blending());
            }
            var compiledFactors = new HashMap<Integer, AuthoredMaterialPassProgram.Factor>();
            var factorAssignment = 0;
            for (var i = 0; i < plan.stages.size(); i++) {
                while (factorAssignment < plan.factorAssignments.size()
                        && plan.factorAssignments.get(factorAssignment).stage() == i) {
                    compiledFactors.put(
                            builder.getCurrentPass(),
                            plan.factorAssignments.get(factorAssignment++).factor());
                }
                builder.addColorStage(plan.stages.get(i), plan.factorGates.get(i));
            }
            builder.addLighting(shader, opacity != null);
            if (opacity != null && opacity != diffuse) {
                var bitmap = resolveBitmap(opacity);
                if (bitmap == null) {
                    return null;
                }
                plan.coldCoordinates.addAll(bitmap.coordinates().modifiers());
                var opacityStages = new ArrayList<Stage>();
                opacityStages.add(createStage(bitmap, 3, 1, 1, 2, 2, 1));
                prependCoordinates(opacityStages, 0, outer);
                builder.addColorStage(opacityStages.getFirst(), 0);
            }
            if (shader && ((UShader) material).getSpecular().get() != null) {
                var source = (UShader) material;
                if (builder.getCurrentPass() != 0 || internalColor) {
                    return null;
                }
                var mask = source.getSpecularMask().get();
                var maskBitmap = mask == null ? null : resolveBitmap(mask);
                var specularBitmap =
                        opacity == null ? resolveBitmap(source.getSpecular().get()) : null;
                if (specularBitmap != null) {
                    var constantMask = mask instanceof UConstantColor;
                    if (mask != null && mask != diffuse && maskBitmap == null && !constantMask) {
                        return null;
                    }
                    var separateMask = maskBitmap != null && mask != diffuse;
                    if (builder.getRemainingStages() <= (separateMask ? 3 : 2)) {
                        return null;
                    }
                    var stages = new ArrayList<Stage>();
                    if (separateMask) {
                        stages.add(createStage(maskBitmap, 3, 1, 1, 2, 2, 1));
                        plan.coldCoordinates.addAll(maskBitmap.coordinates().modifiers());
                        prependCoordinates(stages, 0, outer);
                        builder.addColorStage(stages.removeFirst(), 0);
                    }
                    builder.captureSpecularMask(constantMask);
                    stages.add(createStage(
                            specularBitmap,
                            constantMask ? 25 : mask == null ? 7 : 18,
                            constantMask ? 3 : 1,
                            2,
                            2,
                            1,
                            1));
                    plan.coldCoordinates.addAll(specularBitmap.coordinates().modifiers());
                    prependCoordinates(stages, 0, outer);
                    builder.addColorStage(stages.getFirst(), 0);
                    if (constantMask) {
                        var alpha = Byte.toUnsignedInt(
                                ((UConstantColor) mask).getColor().a());
                        compiledFactors.put(0, new AuthoredMaterialPassProgram.Factor(alpha * 0x01010101, null));
                    }
                } else {
                    if (mask != null && maskBitmap == null) {
                        return null;
                    }
                    var specular = new Plan(64);
                    compileColor(source.getSpecular().get(), specular, 0);
                    if (maskBitmap != null) {
                        specular.coldCoordinates.addAll(maskBitmap.coordinates().modifiers());
                        specular.addColorStage(createStage(maskBitmap, 3, 1, 1, 2, 2, 1), 0);
                    }
                    if (specular.stages.size() >= key.capacity()) {
                        return null;
                    }
                    prependCoordinates(specular.stages, 0, outer);
                    plan.coldCoordinates.addAll(specular.coldCoordinates);
                    if (specular.cachedAnimation != null) {
                        plan.cachedAnimation = specular.cachedAnimation;
                    }
                    builder.startSpecularPass(mask != null, source.isTwoSided());
                    var specularFactor = 0;
                    for (var i = 0; i < specular.stages.size(); i++) {
                        while (specularFactor < specular.factorAssignments.size()
                                && specular.factorAssignments
                                                .get(specularFactor)
                                                .stage()
                                        == i) {
                            compiledFactors.put(
                                    builder.getCurrentPass(),
                                    specular.factorAssignments
                                            .get(specularFactor++)
                                            .factor());
                        }
                        builder.addColorStage(specular.stages.get(i), specular.factorGates.get(i));
                    }
                    if (opacity == null && maskBitmap != null) {
                        builder.captureSpecularMask(false);
                    }
                }
            }
            if (shader && opacity == null && ((UShader) material).getOutputBlending() == OutputBlending.OB_Normal) {
                builder.addOpaqueAlpha();
            }
            if (internalColor) {
                builder.addInternalColor();
                compiledFactors.put(0, new AuthoredMaterialPassProgram.Factor(modifierColor, null));
            }
            var passes = builder.build();
            if (!key.reflections() && passes.hasReflectionCoordinates()) {
                return null;
            }
            var factors = new ArrayList<AuthoredMaterialPassProgram.Factor>();
            for (var i = 0; i < passes.passes().size(); i++) {
                factors.add(compiledFactors.getOrDefault(i, new AuthoredMaterialPassProgram.Factor(0, null)));
            }
            var paths = new ArrayList<TextureCoordinates>();
            for (var sample : passes.samples()) {
                paths.add(sample.coordinates());
            }
            var binding = compileBinding(compilationMaterial, modifierColor, plan, paths, key.client());
            return new AuthoredMaterialPassProgram(
                    passes,
                    binding.factor(),
                    plan.cachedAnimation,
                    modifierColor != null,
                    modifierColor == null ? 0xffffffff : modifierColor,
                    binding.coordinates(),
                    binding.compilation(),
                    binding.sources(),
                    factors,
                    binding.factorVisitor(),
                    shader ? ((UShader) material).getSpecularPower() : 15f,
                    shader ? ((UShader) material).getSpecularScale() : 1f,
                    shader ? shaderMaps((UShader) material) : ShaderMaps.EMPTY);
        } catch (UnsupportedGraph failure) {
            return null;
        }
    }

    private ShaderMaps shaderMaps(UShader shader) {
        var normal = shader.getNormalMap().get();
        if (normal instanceof UModifier modifier) {
            normal = modifier.getMaterial().get();
        }
        var specular = shader.getSpecularMap().get();
        return new ShaderMaps(
                normal instanceof UTexture texture ? bitmaps.computeIfAbsent(texture, load) : null,
                specular instanceof UTexture texture ? bitmaps.computeIfAbsent(texture, load) : null,
                shader.getBumpOffsetScaleFactor(),
                shader.getBumpOffsetBiasFactor());
    }

    private @Nullable AuthoredMaterialProgram compileUncached(UMaterial material) {
        try {
            var visited = new IdentityHashMap<UMaterial, Boolean>();
            Integer modifierColor = null;
            for (var current = material;
                    current instanceof UModifier modifier;
                    current = modifier.getMaterial().get()) {
                if (visited.put(current, Boolean.TRUE) != null) {
                    throw new UnsupportedGraph();
                }
                if (current instanceof UColorModifier color) {
                    modifierColor = UColorFade.argb(color.getColor());
                }
            }
            visited.clear();
            var compilationMaterial = material;
            while (compilationMaterial instanceof UFinalBlend || compilationMaterial instanceof UColorModifier) {
                compilationMaterial =
                        ((UModifier) compilationMaterial).getMaterial().get();
            }
            var outerCoordinates = new ArrayList<Modifier>();
            var coordinateRoot = false;
            var internalColorModifier = false;
            while (material instanceof UModifier modifier) {
                if (visited.put(material, Boolean.TRUE) != null) {
                    throw new UnsupportedGraph();
                }
                if (coordinateRoot && material instanceof UColorModifier) {
                    internalColorModifier = true;
                }
                if (!(material instanceof UFinalBlend || material instanceof UColorModifier)) {
                    coordinateRoot = true;
                    var coordinate = coordinateModifier(modifier);
                    if (coordinate != null) {
                        outerCoordinates.add(coordinate);
                    }
                }
                material = modifier.getMaterial().get();
            }
            var plan = new Plan();
            plan.coldCoordinates.addAll(outerCoordinates);
            var stages = plan.stages;
            if (material instanceof UShader shader) {
                if (shader.getSpecular().peekFullName() != null
                        || shader.getSelfIllumination().peekFullName() != null
                        || shader.getDetail().peekFullName() != null) {
                    return null;
                }
                var diffuse = shader.getDiffuse().get();
                compileColor(diffuse, plan, 0);
                var opacity = materialOf(shader.getOpacity());
                if (opacity != null && opacity != diffuse) {
                    var bitmap = resolveBitmap(opacity);
                    if (bitmap == null) {
                        return null;
                    }
                    plan.coldCoordinates.addAll(bitmap.coordinates().modifiers());
                    stages.add(createStage(bitmap, 3, 1, 1, 2, 2, 1));
                }
                if (opacity == null && shader.getOutputBlending() == OutputBlending.OB_Normal) {
                    append(stages, createStage(null, 2, 1, 1, 2, 21, 1));
                }
            } else {
                compileColor(material, plan, 0, true);
            }
            prependCoordinates(stages, 0, outerCoordinates);
            for (var stage : stages) {
                if (stage.coordinates().source() != Source.MESH_UV) {
                    return null;
                }
            }
            if (modifierColor != null) {
                if (internalColorModifier) {
                    append(stages, createStage(null, 4, 1, 3, 4, 1, 3));
                }
                append(stages, createStage(null, 4, 1, 3, 4, 1, 3));
            }
            if (stages.size() > AuthoredMaterialProgram.MAX_STAGES) {
                return null;
            }
            var paths = new ArrayList<TextureCoordinates>();
            for (var stage : stages) {
                paths.add(stage.coordinates());
            }
            var binding = compileBinding(compilationMaterial, modifierColor, plan, paths, Client.P152);
            return new AuthoredMaterialProgram(
                    stages,
                    binding.factor(),
                    plan.cachedAnimation,
                    modifierColor != null,
                    modifierColor == null ? 0xffffffff : modifierColor,
                    binding.coordinates(),
                    binding.compilation(),
                    binding.sources(),
                    binding.factorVisitor());
        } catch (UnsupportedGraph failure) {
            return null;
        }
    }

    private Binding compileBinding(
            UMaterial compilationMaterial,
            @Nullable Integer modifierColor,
            Plan plan,
            List<TextureCoordinates> paths,
            Client client) {
        var coordinates = new ArrayList<Modifier>();
        var factorSteps = new ArrayList<Step>();
        var factor = 0;
        if (compilationMaterial instanceof UShader shader) {
            factor = factorCoordinates(shader.getDiffuse().get(), coordinates, factorSteps, modifierColor, factor, 0);
            factor = factorCoordinates(
                    materialOf(shader.getOpacity()), coordinates, factorSteps, modifierColor, factor, 0);
            factor = factorCoordinates(shader.getSpecular().get(), coordinates, factorSteps, modifierColor, factor, 0);
            factor = factorCoordinates(
                    materialOf(shader.getSelfIllumination()), coordinates, factorSteps, modifierColor, factor, 0);
            factor = factorCoordinates(
                    materialOf(shader.getDetail()), coordinates, factorSteps, modifierColor, factor, 0);
        } else if (compilationMaterial instanceof UConstantColor constant) {
            factor = UColorFade.argb(constant.getColor());
        } else if (client == Client.P162
                && compilationMaterial instanceof UCombiner combiner
                && combiner.getMaterial1().get() instanceof UBitMapMaterial
                && combiner.getMaterial2().get() instanceof UBitMapMaterial) {
            factor = factorCoordinates(combiner, coordinates, factorSteps, modifierColor, factor, 0);
        }
        var compilation = compilations.computeIfAbsent(
                compilationMaterial, source -> new Compilation(plan.coldCoordinates, identify.apply(source)));
        var used = new IdentityHashMap<Modifier, Boolean>();
        for (var modifier : plan.coldCoordinates) {
            used.put(modifier, Boolean.TRUE);
        }
        for (var modifier : coordinates) {
            used.put(modifier, Boolean.TRUE);
        }
        for (var path : paths) {
            for (var modifier : path.modifiers()) {
                used.put(modifier, Boolean.TRUE);
            }
        }
        var sources = new ArrayList<CoordinateSource>();
        for (var modifier : used.keySet()) {
            var identity = coordinateIdentities.get(modifier);
            if (identity != null) {
                sources.add(new CoordinateSource(modifier, identity));
            }
        }
        var dynamic = false;
        for (var step : factorSteps) {
            if (step.animation() != null) {
                dynamic = true;
            }
        }
        return new Binding(
                factor, coordinates, compilation, sources, dynamic ? new AuthoredMaterialFactors(factorSteps) : null);
    }

    private record Binding(
            int factor,
            List<Modifier> coordinates,
            Compilation compilation,
            List<CoordinateSource> sources,
            @Nullable AuthoredMaterialFactors factorVisitor) {}

    private void compileColor(@Nullable UMaterial node, Plan plan, int depth) {
        compileColor(node, plan, depth, false);
    }

    private void compileColor(@Nullable UMaterial node, Plan plan, int depth, boolean framebufferWrappers) {
        compileColor(node, plan, depth, framebufferWrappers, false);
    }

    private void compileColor(
            @Nullable UMaterial node, Plan plan, int depth, boolean framebufferWrappers, boolean invertResult) {
        var stages = plan.stages;
        if (depth > plan.limit || stages.size() >= plan.limit) {
            throw new UnsupportedGraph();
        }
        var input = resolveBitmap(node, framebufferWrappers);
        if (input != null) {
            plan.coldCoordinates.addAll(input.coordinates().modifiers());
            plan.addColorStage(createStage(input, 2, 2, 1, 2, invertResult ? 18 : 2, 1), 0);
            return;
        }
        if (node instanceof UModifier) {
            var coordinates = new ArrayList<Modifier>();
            while (node instanceof UModifier modifier) {
                var coordinate = coordinateModifier(modifier);
                if (coordinate != null) {
                    coordinates.add(coordinate);
                }
                node = modifier.getMaterial().get();
            }
            var firstStage = stages.size();
            plan.coldCoordinates.addAll(coordinates);
            compileColor(node, plan, depth, framebufferWrappers, invertResult);
            prependCoordinates(stages, firstStage, coordinates);
            return;
        }
        if (node instanceof UConstantColor || node instanceof UFadeColor) {
            plan.addFactor(node, true);
            plan.addColorStage(createStage(null, 2, 3, 1, 2, 3, 1), 0);
            return;
        }
        if (node instanceof UVertexColor) {
            plan.addColorStage(createStage(null, 2, 0, 1, 3, 1, 1), 0);
            return;
        }
        if (!(node instanceof UCombiner combiner)) {
            throw new UnsupportedGraph();
        }
        var first = combiner.getMaterial1().get();
        var second = combiner.getMaterial2().get();
        var mask = combiner.getMask().get();
        if (first == null || second == null) {
            throw new UnsupportedGraph();
        }
        var firstBitmap = resolveBitmap(first);
        var secondBitmap = resolveBitmap(second);
        if (firstBitmap == null && secondBitmap == null) {
            throw new UnsupportedGraph();
        }
        var swapped = secondBitmap == null || firstBitmap != null && combiner.isInvertMask() && mask == second;
        var current = swapped ? second : first;
        var sampledMaterial = swapped ? first : second;
        compileColor(current, plan, depth + 1, false, mask == current && combiner.isInvertMask());
        var sample = swapped ? firstBitmap : secondBitmap;
        var m1 = swapped ? 2 : 1;
        var m2 = swapped ? 1 : 2;
        var maskAlpha = 1;
        var blendOperation = 16;
        if (mask == sampledMaterial) {
            if (combiner.isInvertMask()) {
                plan.coldCoordinates.addAll(sample.coordinates().modifiers());
                plan.addColorStage(createStage(sample, 2, 1, 1, 2, 18, 1), 0);
                maskAlpha = invertResult ? 17 : 1;
            } else {
                maskAlpha = invertResult ? 18 : 2;
                blendOperation = 13;
            }
        } else if (mask != null && mask != current) {
            var maskBitmap = resolveBitmap(mask);
            if (maskBitmap != null) {
                plan.coldCoordinates.addAll(maskBitmap.coordinates().modifiers());
                plan.addColorStage(createStage(maskBitmap, 3, 1, 1, 2, combiner.isInvertMask() ? 18 : 2, 1), 0);
                maskAlpha = invertResult ? 17 : 1;
            } else if (mask instanceof UConstantColor || mask instanceof UFadeColor) {
                plan.addFactor(mask, false);
                maskAlpha = 3;
                blendOperation = 14;
            } else if (mask instanceof UVertexColor) {
                maskAlpha = 0;
                blendOperation = 12;
            } else {
                throw new UnsupportedGraph();
            }
        }
        var operation = switch (combiner.getCombineOperation()) {
            case CO_Use_Color_From_Material1 -> swapped ? 2 : 3;
            case CO_Use_Color_From_Material2 -> swapped ? 3 : 2;
            case CO_Multiply -> combiner.isModulate4X() ? 6 : combiner.isModulate2X() ? 5 : 4;
            case CO_Add -> 7;
            case CO_Subtract -> 10;
            case CO_AlphaBlend_With_Mask -> blendOperation;
            case CO_Add_With_Mask_Modulation -> 18;
            default -> throw new UnsupportedGraph();
        };
        if (combiner.getAlphaOperation() != EAlphaOperation.AO_Use_Mask
                && mask != null
                && mask != first
                && mask != second) {
            throw new UnsupportedGraph();
        }
        var alphaOp = switch (combiner.getAlphaOperation()) {
            case AO_Use_Mask -> 2;
            case AO_Multiply -> 4;
            case AO_Add -> 7;
            case AO_Use_Alpha_From_Material1 -> swapped ? 2 : 3;
            case AO_Use_Alpha_From_Material2 -> swapped ? 3 : 2;
        };
        var alpha1 = switch (combiner.getAlphaOperation()) {
            case AO_Use_Mask -> maskAlpha;
            default -> 2;
        };
        var reverseArguments = operation == 10 || operation >= 12 && operation <= 16 || operation == 18;
        plan.coldCoordinates.addAll(sample.coordinates().modifiers());
        plan.addColorStage(
                createStage(
                        sample, operation, reverseArguments ? m2 : 2, reverseArguments ? m1 : 1, alphaOp, alpha1, 1),
                combiner.getCombineOperation() == EColorOperation.CO_Multiply ? 1 : 0);
        if (invertResult && combiner.getAlphaOperation() != EAlphaOperation.AO_Use_Mask) {
            plan.addColorStage(createStage(null, 2, 1, 1, 2, 17, 1), 0);
        }
    }

    private int factorCoordinates(
            @Nullable UMaterial material,
            List<Modifier> result,
            List<Step> steps,
            @Nullable Integer modifierColor,
            int factor,
            int depth) {
        if (material == null) {
            return factor;
        }
        if (depth > AuthoredMaterialProgram.MAX_STAGES) {
            throw new UnsupportedGraph();
        }
        var outer = new ArrayList<Modifier>();
        while (material instanceof UModifier modifier) {
            if (modifier instanceof UColorModifier color) {
                modifierColor = UColorFade.argb(color.getColor());
            }
            if (!(modifier instanceof UColorModifier
                    || modifier instanceof UFinalBlend
                    || modifier instanceof UTexEnvMap)) {
                var coordinate = coordinateModifier(modifier);
                if (coordinate != null) {
                    outer.add(coordinate);
                }
            }
            material = modifier.getMaterial().get();
        }
        if (material instanceof UConstantColor || material instanceof UFadeColor) {
            result.addAll(outer);
            var value = constantFactor(material, steps);
            if (factor == 0) {
                factor = value;
            }
        } else if (material instanceof UCombiner combiner) {
            result.addAll(outer);
            var first = combiner.getMaterial1().get();
            var second = combiner.getMaterial2().get();
            var firstBitmap = resolveBitmap(first);
            var secondBitmap = resolveBitmap(second);
            var swapped = secondBitmap == null
                    || firstBitmap != null
                            && combiner.isInvertMask()
                            && combiner.getMask().get() == second;
            var current = swapped ? second : first;
            factor = factorCoordinates(current, result, steps, modifierColor, factor, depth + 1);
            var mask = materialOf(combiner.getMask());
            if (mask != null && mask != current) {
                var value = constantFactor(mask, steps);
                if (factor == 0) {
                    factor = value;
                }
            }
            var sampled = swapped ? firstBitmap : secondBitmap;
            if (sampled == null) {
                throw new UnsupportedGraph();
            }
            result.addAll(sampled.coordinates().modifiers());
            if (combiner.getCombineOperation() == EColorOperation.CO_Add && modifierColor != null) {
                factor = modifierColor;
                steps.add(new Step(modifierColor, null, true));
            }
        }
        return factor;
    }

    private Stage createStage(
            @Nullable Bitmap input, int colorOp, int color1, int color2, int alphaOp, int alpha1, int alpha2) {
        return new Stage(
                input == null ? null : bitmaps.computeIfAbsent(input.texture(), load),
                input == null ? TextureCoordinates.IDENTITY : input.coordinates(),
                colorOp,
                color1,
                color2,
                alphaOp,
                alpha1,
                alpha2);
    }

    private @Nullable Bitmap resolveBitmap(@Nullable UMaterial node) {
        return resolveBitmap(node, false);
    }

    private @Nullable Bitmap resolveBitmap(@Nullable UMaterial node, boolean framebufferWrappers) {
        var coordinates = new ArrayList<Modifier>();
        var visited = new IdentityHashMap<UMaterial, Boolean>();
        Source source = null;
        var otherSources = false;
        while (node instanceof UModifier modifier) {
            if (visited.size() >= 64 || visited.put(node, Boolean.TRUE) != null) {
                throw new UnsupportedGraph();
            }
            if (framebufferWrappers && (node instanceof UFinalBlend || node instanceof UColorModifier)) {
                node = modifier.getMaterial().get();
                continue;
            }
            if (modifier instanceof UTexEnvMap environment) {
                if (source == null) {
                    source = reflectionSource(environment);
                }
            } else {
                otherSources |= modifier.getTexCoordSource() != 15;
                var coordinate = coordinateModifier(modifier);
                if (coordinate != null) {
                    coordinates.add(coordinate);
                }
            }
            node = modifier.getMaterial().get();
        }
        if (source == null) {
            return node instanceof UTexture texture
                    ? new Bitmap(texture, new TextureCoordinates(coordinates, Source.MESH_UV))
                    : null;
        }
        if (!(node instanceof UTexture texture) || otherSources) {
            throw new UnsupportedGraph();
        }
        return new Bitmap(texture, reflectionCoordinates(coordinates, source));
    }

    private @Nullable Modifier coordinateModifier(UModifier source) {
        if (!(source instanceof UTexOscillator
                || source instanceof UTexRotator
                || source instanceof UTexScaler
                || source instanceof UTexPanner
                || source instanceof UTexCoordSource)) {
            throw new UnsupportedGraph();
        }
        if ((source.getTexCoordSource() != 0 && source.getTexCoordSource() != 15)
                || source.getTexCoordCount() != 0
                || source.isTexCoordProjected()) {
            throw new UnsupportedGraph();
        }
        if (source instanceof UTexCoordSource channel) {
            if (channel.getSourceChannel() != 0) {
                throw new UnsupportedGraph();
            }
            return null;
        }
        var coordinate = modifiers.computeIfAbsent(source, m -> {
            if (m instanceof UTexPanner) {
                return toModifier(m, 1, 1);
            }
            var width = materialSize(m, true, new IdentityHashMap<>());
            var height = materialSize(m, false, new IdentityHashMap<>());
            if (width <= 0 || height <= 0) {
                throw new UnsupportedGraph();
            }
            return toModifier(m, width, height);
        });
        coordinateIdentities.put(coordinate, identify.apply(source));
        return coordinate;
    }

    private Modifier toModifier(UMaterial source, int width, int height) {
        return switch (source) {
            case UTexOscillator osc -> {
                var trigger = osc instanceof UTexOscillatorTriggered t
                        ? new Trigger(
                                t.getRetriggerAction(),
                                t.getStopAfterPeriod(),
                                t.getTriggeredTime(),
                                t.isReverse(),
                                t.isTriggered())
                        : null;
                yield new Oscillator(
                        new Axis(
                                osc.getUOscillationType(),
                                osc.getUOscillationRate(),
                                osc.getUOscillationPhase(),
                                osc.getUOscillationAmplitude()),
                        new Axis(
                                osc.getVOscillationType(),
                                osc.getVOscillationRate(),
                                osc.getVOscillationPhase(),
                                osc.getVOscillationAmplitude()),
                        osc.getUOffset() / width,
                        osc.getVOffset() / height,
                        new Jitter(osc.getLastSu(), osc.getLastSv(), osc.getCurrentUJitter(), osc.getCurrentVJitter()),
                        trigger);
            }
            case UTexRotator r ->
                new Rotator(
                        r.getTexRotationType(),
                        toAngles(r.getRotation()),
                        toAngles(r.getOscillationRate()),
                        toAngles(r.getOscillationAmplitude()),
                        toAngles(r.getOscillationPhase()),
                        r.getUOffset() / width,
                        r.getVOffset() / height);
            case UTexScaler s ->
                new Scaler(s.getUScale(), s.getVScale(), s.getUOffset() / width, s.getVOffset() / height);
            case UTexPanner p ->
                new Panner(
                        p.getPanRate(),
                        toAngles(p.getPanDirection()),
                        p instanceof UTexPannerTriggered t
                                ? new Trigger(
                                        t.getRetriggerAction(),
                                        t.getStopAfterPeriod(),
                                        t.getTriggeredTime(),
                                        t.isReverse(),
                                        t.isTriggered())
                                : null);
            default -> throw new UnsupportedGraph();
        };
    }

    private static int blendFactor(AuthoredFramebuffer.Factor factor) {
        return switch (factor) {
            case ZERO -> 1;
            case ONE -> 2;
            case SOURCE_COLOR -> 3;
            case INVERSE_SOURCE_COLOR -> 4;
            case SOURCE_ALPHA -> 5;
            case INVERSE_SOURCE_ALPHA -> 6;
            case DESTINATION_COLOR -> 9;
        };
    }

    private static Source reflectionSource(UTexEnvMap environment) {
        if (environment.isTexCoordProjected()) {
            throw new UnsupportedGraph();
        }
        return switch (environment.getEnvMapTypeId()) {
            case 0 -> Source.WORLD_REFLECTION;
            case 1 -> Source.CAMERA_REFLECTION;
            case 2 -> Source.SPHERE_MAP;
            default -> throw new UnsupportedGraph();
        };
    }

    private static TextureCoordinates reflectionCoordinates(List<Modifier> modifiers, Source source) {
        if (source != Source.WORLD_REFLECTION) {
            return new TextureCoordinates(modifiers, source);
        }
        for (var modifier : modifiers) {
            if (!isAlwaysIdentity(modifier)) {
                throw new UnsupportedGraph();
            }
        }
        return new TextureCoordinates(List.of(), source);
    }

    private static boolean isAlwaysIdentity(Modifier modifier) {
        return switch (modifier) {
            case Scaler scaler ->
                scaler.u() == 1f && scaler.v() == 1f && scaler.offsetU() == 0f && scaler.offsetV() == 0f;
            case Rotator rotator -> {
                var still = rotator.rotation().pitch() == 0
                        && rotator.rotation().yaw() == 0
                        && rotator.rotation().roll() == 0;
                var amplitude = rotator.amplitude().pitch() != 0
                        || rotator.amplitude().yaw() != 0
                        || rotator.amplitude().roll() != 0;
                yield still && (rotator.type() != 2 || !amplitude);
            }
            case Panner panner -> panner.rate() == 0f;
            case Oscillator _ -> false;
        };
    }

    private static int constantFactor(@Nullable UMaterial material, List<Step> steps) {
        while (material instanceof UModifier modifier) {
            material = modifier.getMaterial().get();
        }
        if (material instanceof UConstantColor constant) {
            var value = UColorFade.argb(constant.getColor());
            steps.add(new Step(value, null, false));
            return value;
        }
        if (material instanceof UFadeColor fade) {
            var curve = fade.getCurve();
            steps.add(new Step(curve.color1(), curve.isAnimated() ? curve : null, false));
            return curve.color1();
        }
        return 0;
    }

    private static void append(List<Stage> stages, Stage stage) {
        if (stages.size() >= AuthoredMaterialProgram.MAX_STAGES) {
            throw new UnsupportedGraph();
        }
        stages.add(stage);
    }

    private static void prependCoordinates(List<Stage> stages, int firstStage, List<Modifier> outer) {
        if (outer.isEmpty()) {
            return;
        }
        for (var i = firstStage; i < stages.size(); i++) {
            var stage = stages.get(i);
            if (stage.bitmap() == null) {
                continue;
            }
            var coordinates = new ArrayList<>(outer);
            coordinates.addAll(stage.coordinates().modifiers());
            var source = stage.coordinates().source();
            stages.set(
                    i,
                    new Stage(
                            stage.bitmap(),
                            source == Source.MESH_UV
                                    ? new TextureCoordinates(coordinates)
                                    : reflectionCoordinates(coordinates, source),
                            stage.colorOperation(),
                            stage.colorArgument1(),
                            stage.colorArgument2(),
                            stage.alphaOperation(),
                            stage.alphaArgument1(),
                            stage.alphaArgument2()));
        }
    }

    private static final class Plan {
        private final List<Stage> stages = new ArrayList<>();
        private final List<Integer> factorGates = new ArrayList<>();
        private final List<Modifier> coldCoordinates = new ArrayList<>();
        private final List<FactorAssignment> factorAssignments = new ArrayList<>();
        private final int limit;
        private int factor;
        private @Nullable UColorFade animatedFactor;
        private @Nullable UColorFade cachedAnimation;

        private Plan() {
            this(AuthoredMaterialProgram.MAX_STAGES);
        }

        private Plan(int limit) {
            this.limit = limit;
        }

        private void addColorStage(Stage stage, int factorGate) {
            if (stages.size() >= limit) {
                throw new UnsupportedGraph();
            }
            stages.add(stage);
            factorGates.add(factorGate);
        }

        private void addFactor(UMaterial node, boolean colorInput) {
            if (node instanceof UConstantColor constant) {
                factorAssignments.add(new FactorAssignment(
                        stages.size(),
                        new AuthoredMaterialPassProgram.Factor(UColorFade.argb(constant.getColor()), null)));
                if (animatedFactor != null) {
                    throw new UnsupportedGraph();
                }
                setFactor(UColorFade.argb(constant.getColor()));
            } else if (node instanceof UFadeColor fade) {
                var curve = fade.getCurve();
                if (!curve.isSupported()) {
                    throw new UnsupportedGraph();
                }
                factorAssignments.add(new FactorAssignment(
                        stages.size(),
                        new AuthoredMaterialPassProgram.Factor(curve.color1(), curve.isAnimated() ? curve : null)));
                if (!curve.isAnimated()) {
                    if (animatedFactor != null) {
                        throw new UnsupportedGraph();
                    }
                    setFactor(curve.color1());
                } else {
                    if (factor != 0 || animatedFactor != null && !animatedFactor.equals(curve)) {
                        throw new UnsupportedGraph();
                    }
                    animatedFactor = curve;
                    if (colorInput) {
                        cachedAnimation = curve;
                    }
                }
            } else {
                throw new UnsupportedGraph();
            }
        }

        private void setFactor(int value) {
            if (factor != 0 && factor != value) {
                throw new UnsupportedGraph();
            }
            factor = value;
        }
    }

    private record FactorAssignment(int stage, AuthoredMaterialPassProgram.Factor factor) {}

    private static int materialSize(
            @Nullable UMaterial material, boolean horizontal, IdentityHashMap<UMaterial, Boolean> path) {
        if (material == null) {
            return 0;
        }
        if (path.size() >= 64 || path.put(material, Boolean.TRUE) != null) {
            throw new UnsupportedGraph();
        }
        try {
            return switch (material) {
                case UTexture texture -> horizontal ? texture.getUSize() : texture.getVSize();
                case UModifier modifier -> materialSize(modifier.getMaterial().get(), horizontal, path);
                case UShader shader ->
                    materialSize(
                            shader.getDiffuse().get() != null
                                    ? shader.getDiffuse().get()
                                    : materialOf(shader.getSelfIllumination()),
                            horizontal,
                            path);
                case UCombiner combiner ->
                    Math.max(
                            materialSize(combiner.getMaterial1().get(), horizontal, path),
                            materialSize(combiner.getMaterial2().get(), horizontal, path));
                default -> 0;
            };
        } finally {
            path.remove(material);
        }
    }

    private static Angles toAngles(URotator rotator) {
        return new Angles(rotator.pitch(), rotator.yaw(), rotator.roll());
    }

    private static @Nullable UMaterial materialOf(UObjectRef<?, ?> reference) {
        var value = reference.get();
        return value instanceof UMaterial material ? material : null;
    }

    private record Bitmap(UTexture texture, TextureCoordinates coordinates) {}

    private static final class UnsupportedGraph extends RuntimeException {}
}
