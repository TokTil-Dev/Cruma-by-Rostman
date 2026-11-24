package com.rost.cruma.core.world;

import com.rost.cruma.core.world.AuthoredMaterialProgram.Compilation;
import com.rost.cruma.core.world.AuthoredMaterialProgram.CoordinateSource;
import com.rost.cruma.core.world.TextureCoordinates.Matrix;
import com.rost.cruma.core.world.TextureCoordinates.Modifier;
import com.rost.cruma.core.world.TextureCoordinates.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class AuthoredMaterialRuntime {
    private final IdentityHashMap<Object, Float> compiled = new IdentityHashMap<>();
    private final IdentityHashMap<Object, List<Integer>> passFactors = new IdentityHashMap<>();
    private final HashMap<String, Object> sourceIdentities = new HashMap<>();
    private final DoubleSupplier random;
    private State coordinates;

    public AuthoredMaterialRuntime() {
        this(new NativeRandom());
    }

    public AuthoredMaterialRuntime(DoubleSupplier random) {
        this.random = Objects.requireNonNull(random);
        coordinates = new State(random, false);
    }

    public void begin(AuthoredMaterialProgram program, float seconds) {
        begin(program.coordinateSources(), program.factorCoordinates(), program.compilation(), seconds);
    }

    public void begin(AuthoredMaterialPassProgram program, float seconds) {
        begin(program.coordinateSources(), program.factorCoordinates(), program.compilation(), seconds);
    }

    public Binding bind(AuthoredMaterialPassProgram program, float seconds) {
        begin(program, seconds);
        var key = compilationKey(program.compilation());
        var cached = passFactors.computeIfAbsent(key, _ -> {
            var values = new ArrayList<Integer>();
            for (var factor : program.compiledFactors()) {
                values.add(factor.at(compiled.get(key)));
            }
            return List.copyOf(values);
        });
        var factors = new ArrayList<Integer>();
        factors.add(program.textureFactor(seconds));
        for (var draw = 1; draw < program.passes().drawCount(); draw++) {
            factors.add(program.animatedFactor() == null ? cached.get(draw) : factors.getFirst());
        }
        var matrices = new ArrayList<Matrix>();
        for (var sample : program.boundSamples()) {
            matrices.add(getMatrix(sample.coordinates(), seconds));
        }
        return new Binding(factors, matrices);
    }

    public record Binding(List<Integer> textureFactors, List<Matrix> matrices) {
        public Binding {
            textureFactors = List.copyOf(textureFactors);
            matrices = List.copyOf(matrices);
        }
    }

    public Matrix getMatrix(TextureCoordinates path, float seconds) {
        return path.matrix(seconds, coordinates);
    }

    public void clear() {
        compiled.clear();
        passFactors.clear();
        sourceIdentities.clear();
        coordinates = new State(random, false);
    }

    private void begin(
            List<CoordinateSource> sources, List<Modifier> factorCoordinates, Compilation compilation, float seconds) {
        for (var source : sources) {
            coordinates.bindIdentity(source.modifier(), sourceIdentity(source.sourceIdentity()));
        }
        for (var modifier : factorCoordinates) {
            modifier.matrix(seconds, coordinates);
        }
        var key = compilationKey(compilation);
        if (compiled.putIfAbsent(key, seconds) == null) {
            for (var modifier : compilation.coordinates()) {
                modifier.matrix(seconds, coordinates);
            }
        }
    }

    private Object compilationKey(Compilation compilation) {
        var identity = compilation.sourceIdentity();
        return identity == null ? compilation : sourceIdentity(identity);
    }

    private Object sourceIdentity(String identity) {
        return sourceIdentities.computeIfAbsent(identity, source -> new Object());
    }
}
