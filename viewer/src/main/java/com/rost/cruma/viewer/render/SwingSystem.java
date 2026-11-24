package com.rost.cruma.viewer.render;

import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.ActorMotion;
import com.rost.cruma.core.world.L2Movement;
import com.rost.cruma.core.world.L2Swing;
import com.rost.cruma.core.world.L2Swing.Kind;
import com.rost.cruma.core.world.L2Swing.State;
import com.rost.cruma.core.world.LightCorona;
import com.rost.cruma.core.world.MotionCarrier;
import com.rost.cruma.core.world.MotionLink;
import com.rost.cruma.core.world.MovementCarrier;
import com.rost.cruma.core.world.PhysicsRotation;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.CarriedActor;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

public final class SwingSystem implements CoronaSystem.Carriers {
    private static final URotator NO_TURN = new URotator(0, 0, 0);
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, PhysicsRotation.State> turns = new HashMap<>();
    private final List<PhysicsRotation.State> turnList = new ArrayList<>();
    private final Map<String, List<String>> pathsByMap = new HashMap<>();
    private final Map<String, List<String>> turnPathsByMap = new HashMap<>();
    private final Map<String, List<CarriedEmitter>> emittersByMap = new HashMap<>();
    private final Map<String, List<TurnedEmitter>> turnedEmittersByMap = new HashMap<>();
    private final Map<String, String> lightCarriers = new HashMap<>();
    private final Map<String, List<String>> lightsByMap = new HashMap<>();
    private final Map<String, MotionLink.Placement> placements = new HashMap<>();
    private final Map<String, List<String>> placementPathsByMap = new HashMap<>();
    private final Map<String, MotionLink> baselines = new HashMap<>();
    private final Map<String, L2Swing> savedSwings = new HashMap<>();
    private final Map<String, PhysicsRotation> savedTurns = new HashMap<>();
    private final Set<String> savedMeshes = new HashSet<>();
    private final Map<String, List<String>> savedPathsByMap = new HashMap<>();
    private final Set<String> gone = new HashSet<>();
    private final Set<String> edited = new HashSet<>();
    private final Map<String, FollowerPlace> followerPlaces = new HashMap<>();
    private final Map<String, String> copiedCarriers = new LinkedHashMap<>();
    private final Map<String, String> copiedLights = new LinkedHashMap<>();
    private Map<String, SavedLocation> savedLocations = Map.of();
    private long savedLocationsLayout = -1;
    private long layout;
    private long motion;
    private final Map<String, MovementSlot> movements = new HashMap<>();
    private final Map<String, L2Movement> savedMovements = new HashMap<>();
    private final Map<String, List<String>> movementPathsByMap = new HashMap<>();
    private final Mat4 movementScratch = new Mat4();

    private record SavedLocation(String map, List<CarriedEmitter> list, Vec3 location) {}

    private static final class CarriedEmitter {
        private final ParticleEmitter emitter;
        private final String carrier;
        private final Vec3 savedStart;
        private final URotator savedTurn;
        private final Vec3 offset;
        private final Vec3 location;
        private Vec3 start;
        private URotator turn;
        private long revision = -1L;
        private boolean attached;

        private CarriedEmitter(ParticleEmitter emitter, String carrier, L2Swing.Follower follower) {
            this.emitter = emitter;
            this.carrier = carrier;
            var place = emitter.getActorPos();
            savedStart = new Vec3(place.getX(), place.getY(), place.getZ());
            offset = follower.location().minus(savedStart);
            location = new Vec3(follower.location());
            var turn = follower.rotation();
            savedTurn = turn;
            start = savedStart;
            this.turn = turn;
        }
    }

    private record FollowerPlace(Vec3 location, URotator rotation) {}

    private static final class TurnedEmitter {
        private final ParticleEmitter emitter;
        private final PhysicsRotation.State turn;
        private long revision;

        private TurnedEmitter(ParticleEmitter emitter, PhysicsRotation.State turn) {
            this.emitter = emitter;
            this.turn = turn;
            revision = turn.getRevision();
        }
    }

    public void add(String map, List<L2Swing> swings, List<PhysicsRotation> turning, List<ParticleEmitter> emitters) {
        emittersByMap.remove(map);
        turnedEmittersByMap.remove(map);
        if (swings.isEmpty() && turning.isEmpty()) {
            return;
        }
        var saved = savedPathsByMap.computeIfAbsent(map, ignored -> new ArrayList<>());
        var turningActors = new HashMap<String, PhysicsRotation.State>();
        for (var turn : turning) {
            var state = existingTurnOf(map, turn);
            var placed = followerPlaces.get(turn.actorPath());
            if (placed != null) {
                placeTurn(state, placed);
                edited.add(turn.actorPath());
            }
            turningActors.put(turn.actorPath(), state);
            savedTurns.put(turn.actorPath(), turn);
            savedMeshes.add(turn.actorPath());
            saved.add(turn.actorPath());
        }
        var carriedEmitters = new HashMap<String, L2Swing.Follower>();
        var carrierOfEmitter = new HashMap<String, String>();
        var lights = lightsByMap.computeIfAbsent(map, ignored -> new ArrayList<>());
        for (var swing : swings) {
            existingStateOf(map, swing);
            savedSwings.put(swing.actorPath(), swing);
            savedMeshes.add(swing.actorPath());
            saved.add(swing.actorPath());
            for (var follower : swing.followers()) {
                if (follower.kind() == Kind.EMITTER && !turningActors.containsKey(follower.actorPath())) {
                    carriedEmitters.put(follower.actorPath(), follower);
                    carrierOfEmitter.put(follower.actorPath(), swing.actorPath());
                } else if (follower.kind() == Kind.LIGHT) {
                    lightCarriers.put(follower.actorPath(), swing.actorPath());
                    lights.add(follower.actorPath());
                } else if (follower.kind() == Kind.MESH || follower.kind() == Kind.SWING) {
                    savedMeshes.add(follower.actorPath());
                    saved.add(follower.actorPath());
                }
            }
        }
        var carried = new ArrayList<CarriedEmitter>();
        var turned = new ArrayList<TurnedEmitter>();
        for (var emitter : emitters) {
            var turn = turningActors.get(emitter.getRenderActorName());
            if (turn != null) {
                var turnedEmitter = new TurnedEmitter(emitter, turn);
                if (followerPlaces.containsKey(emitter.getRenderActorName())) {
                    turnedEmitter.revision = -1L;
                }
                turned.add(turnedEmitter);
                continue;
            }
            var follower = carriedEmitters.get(emitter.getRenderActorName());
            if (follower != null) {
                var carriedEmitter = new CarriedEmitter(emitter, carrierOfEmitter.get(follower.actorPath()), follower);
                var placed = followerPlaces.get(follower.actorPath());
                if (placed != null) {
                    moveStart(carriedEmitter, placed.location().minus(carriedEmitter.offset), placed.rotation());
                }
                carried.add(carriedEmitter);
            }
        }
        if (!carried.isEmpty()) {
            emittersByMap.put(map, carried);
        }
        if (!turned.isEmpty()) {
            turnedEmittersByMap.put(map, turned);
        }
        layout++;
        moveCarried();
        placeCarried();
        placeTurnedEmitters();
    }

    public void remove(String map) {
        removeMovements(map);
        turnedEmittersByMap.remove(map);
        emittersByMap.remove(map);
        var turnPaths = turnPathsByMap.remove(map);
        if (turnPaths != null) {
            var removed = new HashSet<PhysicsRotation.State>();
            for (var path : turnPaths) {
                var state = turns.remove(path);
                if (state != null) {
                    removed.add(state);
                }
            }
            turnList.removeIf(removed::contains);
        }
        var placed = placementPathsByMap.remove(map);
        if (placed != null) {
            for (var path : placed) {
                placements.remove(path);
                baselines.remove(path);
                gone.remove(path);
                edited.remove(path);
            }
        }
        var lights = lightsByMap.remove(map);
        if (lights != null) {
            lights.forEach(lightCarriers::remove);
        }
        var saved = savedPathsByMap.remove(map);
        if (saved != null) {
            for (var path : saved) {
                savedSwings.remove(path);
                savedTurns.remove(path);
                savedMeshes.remove(path);
            }
        }
        var paths = pathsByMap.remove(map);
        if (paths != null) {
            for (var path : paths) {
                states.remove(path);
                gone.remove(path);
                edited.remove(path);
            }
        }
        layout++;
    }

    public int getCount() {
        return states.size();
    }

    public int getTurnCount() {
        return turnList.size();
    }

    public ActorMotion createMotion(String map, MotionLink link) {
        var path = link.getActorPath();
        var placement = placementOf(map, path);
        baselines.putIfAbsent(path, link);
        if (savedMeshes.add(path)) {
            savedPathsByMap.computeIfAbsent(map, ignored -> new ArrayList<>()).add(path);
        }
        var swing = link.swing();
        var swingState = swing == null ? null : stateOf(map, swing, false);
        var turnState = link.turn() == null ? null : turnOf(map, link.turn(), false);
        linkMovement(map, placement, link);
        placement.set(link, swingState, turnState);
        motion++;
        return placement.createMotion();
    }

    public ActorMotion createEditedMotion(String map, MotionLink link) {
        var path = link.getActorPath();
        var placement = placementOf(map, path);
        baselines.putIfAbsent(path, link);
        var back = gone.remove(path);
        var swing = link.swing();
        var swingState = swing == null ? null : stateOf(map, swing, back && link.follower() == null);
        var turnState = link.turn() == null ? null : turnOf(map, link.turn(), back);
        linkMovement(map, placement, link);
        placement.set(link, swingState, turnState);
        edited.add(path);
        motion++;
        layout++;
        moveCarried();
        placeCarried();
        placeTurnedEmitters();
        return placement.createMotion();
    }

    public @Nullable ActorMotion continueMotion(String actorPath) {
        var placement = gone.contains(actorPath) ? null : placements.get(actorPath);
        return placement == null ? null : placement.createMotion();
    }

    public boolean isMoving(String actorPath) {
        return (placements.containsKey(actorPath) || movements.containsKey(actorPath)) && !gone.contains(actorPath);
    }

    public @Nullable MotionLink getLink(String actorPath) {
        var placement = gone.contains(actorPath) ? null : placements.get(actorPath);
        return placement == null ? null : placement.getLink();
    }

    public @Nullable String describe(String actorPath, List<CarriedActor> carried) {
        var link = getLink(actorPath);
        if (link == null) {
            return describeMovement(actorPath, carried);
        }
        var text = new ArrayList<String>();
        var swing = link.swing();
        if (swing != null && link.follower() == null) {
            text.add("Swings by itself in " + swingAxes(swing) + ".");
        }
        var carrier = carrierOf(link);
        if (carrier != null && !gone.contains(carrier)) {
            text.add("Carried by " + shortName(carrier) + ".");
        }
        if (!carried.isEmpty()) {
            text.add(describeCarried(carried));
        }
        var turn = link.turn();
        if (turn != null) {
            text.add("Turns by itself: " + turnRate(turn.rate()) + ".");
        }
        text.add(EditingSession.SAVED_PLACE_LINE);
        return String.join(" ", text);
    }

    public Set<String> getCarriedMeshes(Collection<String> carriers) {
        var result = new LinkedHashSet<String>();
        if (carriers.isEmpty()) {
            return result;
        }
        placements.forEach((path, placement) -> {
            if (gone.contains(path)) {
                return;
            }
            var now = carrierOf(placement.getLink());
            var baseline = baselines.get(path);
            var before = baseline == null ? null : carrierOf(baseline);
            if (now != null && carriers.contains(now) || before != null && carriers.contains(before)) {
                result.add(path);
            }
        });
        return result;
    }

    public Map<String, Kind> getCarried(String carrier) {
        var result = new LinkedHashMap<String, Kind>();
        if (gone.contains(carrier) || !states.containsKey(carrier) && !movements.containsKey(carrier)) {
            return result;
        }
        var meshes = new TreeMap<String, Kind>();
        for (var entry : placements.entrySet()) {
            var link = entry.getValue().getLink();
            if (!gone.contains(entry.getKey()) && carrier.equals(carrierOf(link))) {
                var follower = link.follower();
                meshes.put(
                        entry.getKey(),
                        follower != null ? follower.kind() : link.swing() != null ? Kind.SWING : Kind.MESH);
            }
        }
        var saved = savedSwings.get(carrier);
        if (saved != null) {
            for (var follower : saved.followers()) {
                var kind = follower.kind();
                if (kind == Kind.EMITTER || kind == Kind.LIGHT) {
                    result.put(follower.actorPath(), kind);
                } else {
                    var mesh = meshes.remove(follower.actorPath());
                    if (mesh != null) {
                        result.put(follower.actorPath(), mesh);
                    }
                }
            }
        }
        result.putAll(meshes);
        addCopies(carrier, result);
        return result;
    }

    public boolean place(String actorPath, Vec3 location, URotator rotation, Vec3 scale, Vec3 prePivot) {
        var placement = placements.get(actorPath);
        if (placement == null) {
            return false;
        }
        var link = placement.getLink();
        L2Swing.Saved saved = null;
        if (link.swing() != null && link.follower() == null) {
            var baseline = baselines.get(actorPath);
            var origin = baseline == null ? null : baseline.swing();
            saved = origin != null && origin.rotation().equals(rotation) ? origin.saved() : null;
        }
        var placed = link.withPlacement(location, rotation, scale, prePivot, saved);
        var swingState = placement.getSwingState();
        if (placed.swing() != null && placed.follower() == null && swingState != null) {
            swingState.rebase(placed.swing());
        }
        var turnState = placement.getTurnState();
        if (placed.turn() != null && turnState != null) {
            turnState.rebase(placed.turn());
        }
        placement.set(placed, swingState, turnState);
        edited.add(actorPath);
        motion++;
        moveCarried();
        placeCarried();
        placeTurnedEmitters();
        return true;
    }

    public void addCopiedEffect(
            String copy, List<ParticleEmitter> twins, List<ParticleEmitter> sources, Vec3 location, URotator rotation) {
        addCopiedEffect(copy, twins, sources, location, rotation, null);
    }

    public void addCopiedEffect(
            String copy,
            List<ParticleEmitter> twins,
            List<ParticleEmitter> sources,
            Vec3 location,
            URotator rotation,
            @Nullable String newCarrier) {
        forgetCopiedEffect(copy);
        String carrier = null;
        for (var index = 0; index < twins.size(); index++) {
            var source = sources.get(index);
            for (var carried : emittersByMap.values()) {
                CarriedEmitter template = null;
                for (var emitter : carried) {
                    if (emitter.emitter == source) {
                        template = emitter;
                    }
                }
                if (template != null) {
                    var twin = twins.get(index);
                    twin.moveActor(location.minus(template.offset));
                    var follower = new L2Swing.Follower(copy, Kind.EMITTER, new Vec3(location), rotation);
                    carrier = newCarrier != null ? newCarrier : template.carrier;
                    carried.add(new CarriedEmitter(twin, carrier, follower));
                    break;
                }
            }
        }
        if (carrier == null) {
            return;
        }
        copiedCarriers.put(copy, carrier);
        layout++;
        placeCarried();
    }

    public @Nullable Vec3 getSavedLocation(String actorPath) {
        var turn = turns.get(actorPath);
        if (turn != null) {
            return new Vec3(turn.getTurn().location());
        }
        var saved = savedLocations.get(actorPath);
        if (savedLocationsLayout != layout || saved != null && emittersByMap.get(saved.map()) != saved.list()) {
            indexSavedLocations();
            saved = savedLocations.get(actorPath);
        }
        return saved == null ? null : new Vec3(saved.location());
    }

    public void setCopiedLights(Map<String, String> carriers) {
        if (copiedLights.equals(carriers)) {
            return;
        }
        copiedLights.keySet().forEach(lightCarriers::remove);
        copiedLights.clear();
        copiedLights.putAll(carriers);
        lightCarriers.putAll(carriers);
        layout++;
    }

    public void forgetCopiedEffect(String copy) {
        if (copiedCarriers.remove(copy) == null) {
            return;
        }
        for (var carried : emittersByMap.values()) {
            carried.removeIf(emitter -> copy.equals(emitter.emitter.getRenderActorName()));
        }
        layout++;
    }

    public @Nullable String describeEffect(String actorPath) {
        var text = new ArrayList<String>();
        var turn = turns.get(actorPath);
        String carrier =
                turn == null ? copiedCarriers.get(actorPath) : turn.getTurn().carrier();
        if (carrier == null) {
            for (var carried : emittersByMap.values()) {
                for (var emitter : carried) {
                    if (actorPath.equals(emitter.emitter.getRenderActorName())) {
                        carrier = emitter.carrier;
                    }
                }
            }
        }
        if (carrier != null && states.containsKey(carrier) && !gone.contains(carrier)) {
            text.add("Carried by " + shortName(carrier) + ".");
        }
        if (turn != null) {
            text.add("Turns by itself: " + turnRate(turn.getTurn().rate()) + ".");
        }
        if (text.isEmpty()) {
            return null;
        }
        text.add(EditingSession.SAVED_PLACE_LINE);
        return String.join(" ", text);
    }

    public boolean placesEffect(String actorPath) {
        if (turns.containsKey(actorPath)) {
            return true;
        }
        for (var carried : emittersByMap.values()) {
            for (var emitter : carried) {
                if (actorPath.equals(emitter.emitter.getRenderActorName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void placeFollower(String actorPath, Vec3 location, URotator rotation) {
        var placed = new FollowerPlace(new Vec3(location), rotation);
        followerPlaces.put(actorPath, placed);
        for (var carried : emittersByMap.values()) {
            for (var emitter : carried) {
                if (actorPath.equals(emitter.emitter.getRenderActorName())) {
                    moveStart(emitter, placed.location().minus(emitter.offset), rotation);
                }
            }
        }
        var turn = turns.get(actorPath);
        if (turn != null) {
            placeTurn(turn, placed);
            edited.add(actorPath);
        }
        motion++;
        moveCarried();
        placeCarried();
        placeTurnedEmitters();
    }

    public void forget(String actorPath) {
        restMovement(actorPath);
        if (!placements.containsKey(actorPath) && !states.containsKey(actorPath) && !turns.containsKey(actorPath)) {
            return;
        }
        if (savedMeshes.contains(actorPath)) {
            gone.add(actorPath);
            edited.add(actorPath);
            var state = states.get(actorPath);
            if (state != null) {
                state.rest();
            }
        } else {
            drop(actorPath);
        }
        motion++;
        layout++;
        moveCarried();
        placeCarried();
        placeTurnedEmitters();
    }

    public void restoreEdited() {
        restoreMovements();
        if (edited.isEmpty() && gone.isEmpty() && followerPlaces.isEmpty()) {
            return;
        }
        if (!followerPlaces.isEmpty()) {
            for (var carried : emittersByMap.values()) {
                for (var emitter : carried) {
                    if (followerPlaces.containsKey(emitter.emitter.getRenderActorName())) {
                        moveStart(emitter, emitter.savedStart, emitter.savedTurn);
                    }
                }
            }
            for (var turned : turnedEmittersByMap.values()) {
                for (var emitter : turned) {
                    if (followerPlaces.containsKey(emitter.emitter.getRenderActorName())) {
                        emitter.revision = -1L;
                    }
                }
            }
            followerPlaces.clear();
        }
        var changed = new LinkedHashSet<>(edited);
        changed.addAll(gone);
        gone.clear();
        edited.clear();
        for (var path : changed) {
            if (!savedMeshes.contains(path)) {
                drop(path);
                continue;
            }
            var swing = savedSwings.get(path);
            var state = states.get(path);
            if (swing != null && state != null) {
                state.restart(swing);
            }
            var turn = savedTurns.get(path);
            var turnState = turns.get(path);
            if (turn != null && turnState != null) {
                turnState.restart(turn);
            }
        }
        for (var path : changed) {
            var placement = placements.get(path);
            var baseline = baselines.get(path);
            var swing = baseline == null ? null : baseline.swing();
            var swingState = swing == null ? null : states.get(swing.actorPath());
            var turnState = baseline == null || baseline.turn() == null ? null : turns.get(path);
            if (placement != null
                    && baseline != null
                    && (swing == null || swingState != null)
                    && (baseline.turn() == null || turnState != null)) {
                placement.set(baseline, swingState, turnState);
            }
        }
        motion++;
        layout++;
        moveCarried();
        placeCarried();
        placeTurnedEmitters();
    }

    public void advance(float seconds) {
        if (!(seconds > 0f) || states.isEmpty() && turnList.isEmpty() && movements.isEmpty()) {
            return;
        }
        var changed = false;
        for (var state : states.values()) {
            changed |= state.advance(seconds);
        }
        advanceMovements(seconds);
        changed |= moveCarried();
        for (var index = 0; index < turnList.size(); index++) {
            turnList.get(index).advance(seconds);
        }
        placeTurnedEmitters();
        if (changed) {
            motion++;
            placeCarried();
        }
    }

    @Override
    public boolean carries(String actorPath) {
        return lightCarriers.containsKey(actorPath);
    }

    @Override
    public LightCorona place(LightCorona corona) {
        var carrier = lightCarriers.get(corona.actorPath());
        var state = carrier == null ? null : states.get(carrier);
        if (state == null) {
            return corona;
        }
        return corona.carriedTo(state.follow(corona.location()), state.follow(corona.rotation()));
    }

    @Override
    public long getLayout() {
        return layout;
    }

    @Override
    public long getMotion() {
        return motion;
    }

    public ActorMotion createMovementMotion(String map, String actorPath, L2Movement movement) {
        var slot = movementSlotOf(map, actorPath, movement);
        layout++;
        return new MovementView(slot);
    }

    public ActorMotion createEditedMovementMotion(String map, String actorPath, L2Movement movement) {
        var slot = movementSlotOf(map, actorPath, movement);
        if (slot.resting || !slot.state.getSource().equals(movement)) {
            slot.state = movement.newState();
        }
        slot.resting = false;
        gone.remove(actorPath);
        edited.add(actorPath);
        motion++;
        layout++;
        return new MovementView(slot);
    }

    public @Nullable L2Movement getMovementSource(String actorPath) {
        var slot = movements.get(actorPath);
        return slot == null ? null : slot.state.getSource();
    }

    public @Nullable L2Movement getSavedMovement(String actorPath) {
        return savedMovements.get(actorPath);
    }

    public boolean placeMovement(String actorPath, L2Movement movement) {
        var slot = movements.get(actorPath);
        if (slot == null) {
            return false;
        }
        slot.state = movement.newState();
        edited.add(actorPath);
        motion++;
        return true;
    }

    private boolean moveCarried() {
        var changed = false;
        for (var state : states.values()) {
            var carrier = state.getSwing().carrier();
            var carrierState = carrier == null ? null : states.get(carrier);
            if (carrierState != null) {
                var before = state.getRevision();
                state.carry(carrierState.follow(state.getSwing().location()));
                changed |= state.getRevision() != before;
            }
        }
        for (var index = 0; index < turnList.size(); index++) {
            var turn = turnList.get(index);
            var carrier = turn.getTurn().carrier();
            var carrierState = carrier == null ? null : states.get(carrier);
            if (carrierState != null) {
                turn.carry(carrierState.follow(turn.getTurn().location()), carrierState.follow(NO_TURN));
            }
        }
        return changed;
    }

    private void placeTurnedEmitters() {
        if (turnedEmittersByMap.isEmpty()) {
            return;
        }
        for (var turned : turnedEmittersByMap.values()) {
            for (var index = 0; index < turned.size(); index++) {
                var emitter = turned.get(index);
                var revision = emitter.turn.getRevision();
                if (revision != emitter.revision) {
                    emitter.revision = revision;
                    emitter.emitter.carryActor(emitter.turn.getLocation(), emitter.turn.getRotation());
                }
            }
        }
    }

    private void placeCarried() {
        for (var carried : emittersByMap.values()) {
            for (var emitter : carried) {
                var carrier = states.get(emitter.carrier);
                if (carrier == null) {
                    if (emitter.attached) {
                        emitter.attached = false;
                        emitter.revision = -1L;
                        emitter.emitter.carryActor(emitter.start, emitter.turn);
                    }
                    continue;
                }
                var revision = carrier.getRevision();
                if (!emitter.attached || revision != emitter.revision) {
                    emitter.attached = true;
                    emitter.revision = revision;
                    emitter.emitter.carryActor(carrier.follow(emitter.start), carrier.follow(emitter.turn));
                }
            }
        }
    }

    private void moveStart(CarriedEmitter emitter, Vec3 start, URotator turn) {
        emitter.start = start;
        emitter.turn = turn;
        emitter.revision = -1L;
        if (states.get(emitter.carrier) == null) {
            emitter.attached = false;
            emitter.emitter.carryActor(start, turn);
        }
    }

    private State stateOf(String map, L2Swing swing, boolean restart) {
        var state = states.get(swing.actorPath());
        if (state == null) {
            return existingStateOf(map, swing);
        }
        if (restart) {
            state.restart(swing);
            edited.add(swing.actorPath());
        } else if (!state.getSwing().equals(swing)) {
            state.rebase(swing);
            edited.add(swing.actorPath());
        }
        return state;
    }

    private State existingStateOf(String map, L2Swing swing) {
        var state = states.get(swing.actorPath());
        if (state == null) {
            state = swing.newState();
            states.put(swing.actorPath(), state);
            pathsByMap.computeIfAbsent(map, ignored -> new ArrayList<>()).add(swing.actorPath());
        }
        return state;
    }

    private PhysicsRotation.State turnOf(String map, PhysicsRotation turn, boolean restart) {
        var state = turns.get(turn.actorPath());
        if (state == null) {
            return existingTurnOf(map, turn);
        }
        if (restart) {
            state.restart(turn);
            edited.add(turn.actorPath());
        } else if (!state.getTurn().equals(turn)) {
            state.rebase(turn);
            edited.add(turn.actorPath());
        }
        return state;
    }

    private PhysicsRotation.State existingTurnOf(String map, PhysicsRotation turn) {
        var state = turns.get(turn.actorPath());
        if (state == null) {
            state = turn.newState();
            turns.put(turn.actorPath(), state);
            turnList.add(state);
            turnPathsByMap.computeIfAbsent(map, ignored -> new ArrayList<>()).add(turn.actorPath());
        }
        return state;
    }

    private MotionLink.Placement placementOf(String map, String path) {
        var placement = placements.get(path);
        if (placement == null) {
            placement = new MotionLink.Placement();
            placements.put(path, placement);
            placementPathsByMap
                    .computeIfAbsent(map, ignored -> new ArrayList<>())
                    .add(path);
        }
        return placement;
    }

    private void addCopies(String carrier, Map<String, Kind> result) {
        copiedCarriers.forEach((copy, copied) -> {
            if (copied.equals(carrier)) {
                result.put(copy, Kind.EMITTER);
            }
        });
        copiedLights.forEach((copy, copied) -> {
            if (copied.equals(carrier)) {
                result.put(copy, Kind.LIGHT);
            }
        });
    }

    private void indexSavedLocations() {
        var index = new HashMap<String, SavedLocation>();
        emittersByMap.forEach((map, carried) -> {
            for (var emitter : carried) {
                var name = emitter.emitter.getRenderActorName();
                if (name != null) {
                    index.putIfAbsent(name, new SavedLocation(map, carried, emitter.location));
                }
            }
        });
        savedLocations = index;
        savedLocationsLayout = layout;
    }

    private void drop(String path) {
        placements.remove(path);
        baselines.remove(path);
        placementPathsByMap.values().forEach(paths -> paths.remove(path));
        if (states.remove(path) != null) {
            pathsByMap.values().forEach(paths -> paths.remove(path));
        }
        var turn = turns.remove(path);
        if (turn != null) {
            turnList.remove(turn);
            turnPathsByMap.values().forEach(paths -> paths.remove(path));
        }
        gone.remove(path);
        edited.remove(path);
    }

    private MovementSlot movementSlotOf(String map, String actorPath, L2Movement movement) {
        var slot = movements.get(actorPath);
        if (slot == null) {
            slot = new MovementSlot(movement.newState());
            movements.put(actorPath, slot);
            movementPathsByMap
                    .computeIfAbsent(map, ignored -> new ArrayList<>())
                    .add(actorPath);
        }
        savedMovements.putIfAbsent(actorPath, movement);
        return slot;
    }

    private void linkMovement(String map, MotionLink.Placement placement, MotionLink link) {
        MovementCarrier carrier = link.movement();
        if (carrier != null) {
            placement.setMovementState(movementSlotOf(map, carrier.actorPath(), carrier.movement()));
        }
    }

    private void advanceMovements(float seconds) {
        for (var slot : movements.values()) {
            if (!slot.resting) {
                slot.state.advance(seconds, movementScratch);
            }
        }
    }

    private void restMovement(String actorPath) {
        var slot = movements.get(actorPath);
        if (slot != null) {
            slot.resting = true;
            gone.add(actorPath);
            edited.add(actorPath);
            motion++;
            layout++;
        }
    }

    private void restoreMovements() {
        movements.forEach((path, slot) -> {
            var saved = savedMovements.get(path);
            if (slot.resting || saved != null && slot.state.getSource() != saved) {
                slot.resting = false;
                if (saved != null) {
                    slot.state = saved.newState();
                }
                motion++;
                layout++;
            }
        });
    }

    private void removeMovements(String map) {
        var paths = movementPathsByMap.remove(map);
        if (paths != null) {
            for (var path : paths) {
                movements.remove(path);
                savedMovements.remove(path);
            }
        }
    }

    private @Nullable String describeMovement(String actorPath, List<CarriedActor> carried) {
        var slot = movements.get(actorPath);
        if (slot == null || slot.resting || carried.isEmpty()) {
            return null;
        }
        return EditingSession.MOVEMENT_LINE + " " + describeCarried(carried) + " " + EditingSession.SAVED_PLACE_LINE;
    }

    private static final class MovementSlot implements MotionCarrier {
        private L2Movement.State state;
        private boolean resting;

        private MovementSlot(L2Movement.State state) {
            this.state = state;
        }

        @Override
        public Vec3 follow(Vec3 start) {
            return resting ? start : state.follow(start);
        }

        @Override
        public URotator follow(URotator start) {
            return resting ? start : state.follow(start);
        }
    }

    private static final class MovementView implements ActorMotion {
        private final MovementSlot slot;

        private MovementView(MovementSlot slot) {
            this.slot = slot;
        }

        @Override
        public Mat4 advance(float seconds, Mat4 output) {
            return slot.state.writeModelMatrix(output);
        }

        @Override
        public URotator getRotation() {
            return slot.state.getRotation();
        }

        @Override
        public float getLocationX() {
            return slot.state.getLocationX();
        }

        @Override
        public float getLocationY() {
            return slot.state.getLocationY();
        }

        @Override
        public Mat4 writeSavedMatrix(Mat4 output) {
            return slot.state.writeSavedMatrix(output);
        }
    }

    private static void placeTurn(PhysicsRotation.State state, FollowerPlace placed) {
        state.rebase(state.getTurn().withPlacement(placed.location(), placed.rotation()));
    }

    private static String describeCarried(List<CarriedActor> carried) {
        var count = carried.size();
        var actors = count == 1 ? "1 actor" : count + " actors";
        var kept = new LinkedHashMap<String, List<String>>();
        for (var actor : carried) {
            if (actor.kept() != null) {
                kept.computeIfAbsent(actor.kept(), ignored -> new ArrayList<>()).add(shortName(actor.path()));
            }
        }
        if (kept.isEmpty()) {
            return "Carries " + actors + (count == 1 ? " that moves with it." : " that move with it.");
        }
        var text = new StringBuilder("Carries ").append(actors).append('.');
        var moving = count - kept.values().stream().mapToInt(List::size).sum();
        if (moving > 0) {
            text.append(' ').append(moving).append(moving == 1 ? " of them moves with it." : " of them move with it.");
        }
        kept.forEach((reason, names) -> text.append(' ')
                .append(joinNames(names))
                .append(names.size() == 1 ? " keeps its saved place: " : " keep their saved places: ")
                .append(reason));
        return text.toString();
    }

    private static String joinNames(List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private static @Nullable String carrierOf(MotionLink link) {
        if (link.movement() != null) {
            return link.movement().actorPath();
        }
        var swing = link.swing();
        if (link.follower() != null && swing != null) {
            return swing.actorPath();
        }
        if (swing != null) {
            return swing.carrier();
        }
        var turn = link.turn();
        return turn == null ? null : turn.carrier();
    }

    private static String swingAxes(L2Swing swing) {
        var axes = new ArrayList<String>();
        if (swing.movesAxis(0)) {
            axes.add("Pitch");
        }
        if (swing.movesAxis(2)) {
            axes.add("Yaw");
        }
        if (swing.movesAxis(1)) {
            axes.add("Roll");
        }
        if (axes.size() < 2) {
            return axes.isEmpty() ? "no axis" : axes.getFirst();
        }
        return String.join(", ", axes.subList(0, axes.size() - 1)) + " and " + axes.getLast();
    }

    private static String turnRate(URotator rate) {
        var parts = new ArrayList<String>();
        if ((rate.pitch() & 0xffff) != 0) {
            parts.add("Pitch " + rate.pitch());
        }
        if ((rate.yaw() & 0xffff) != 0) {
            parts.add("Yaw " + rate.yaw());
        }
        if ((rate.roll() & 0xffff) != 0) {
            parts.add("Roll " + rate.roll());
        }
        return String.join(", ", parts) + " a second";
    }

    private static String shortName(String actorPath) {
        return actorPath.substring(actorPath.lastIndexOf('.') + 1);
    }
}
