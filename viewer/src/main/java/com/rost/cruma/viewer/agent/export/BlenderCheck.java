package com.rost.cruma.viewer.agent.export;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.export.LocationExport;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class BlenderCheck {
    private static final String SCRIPT = "blender-check.py";
    private static final String RESOURCE = "/com/rost/cruma/viewer/agent/export/" + SCRIPT;
    private static final String ANSWER_VARIABLE = "CRUMA_CHECK_FILE";
    private static final String SCRIPT_FAILED = "3";
    private static final String OPENED = "Cruma: opened ";
    private static final long MOST_NANOS = TimeUnit.MINUTES.toNanos(20);
    private static final int LOG_LINES = 12;
    private static final int MOST_PARTS = 24;
    private static final double FACES_BLENDER_MAY_DROP = .01;
    private static final Pattern LOCATION =
            Pattern.compile("^Objects: (\\d+), meshes: \\d+, triangles: (\\d+), textures: (\\d+)$", Pattern.MULTILINE);
    private static final Pattern MODEL =
            Pattern.compile("^Bones: (\\d+), triangles: (\\d+), textures: \\d+$", Pattern.MULTILINE);
    private static final Pattern ANIMATIONS = Pattern.compile("^Animations \\((\\d+)\\)", Pattern.MULTILINE);

    private BlenderCheck() {}

    static List<String> command(Path blender, Path folder, Path check) {
        return List.of(
                blender.toString(),
                "--background",
                "--factory-startup",
                "--python-exit-code",
                SCRIPT_FAILED,
                "--python",
                folder.resolve(LocationExport.BLENDER_SCRIPT).toString(),
                "--python",
                check.toString(),
                "--",
                folder.toString());
    }

    static JSONObject run(Path blender, Path folder, Job job) throws ToolProblem, InterruptedException {
        Path work = null;
        try {
            work = Files.createTempDirectory("cruma-blender-check-");
            var check = work.resolve(SCRIPT);
            try (var script = BlenderCheck.class.getResourceAsStream(RESOURCE)) {
                if (script == null) {
                    throw new ToolProblem(SCRIPT + " is missing from Cruma.");
                }
                Files.write(check, script.readAllBytes());
            }
            var answer = work.resolve("scene.json");
            var log = work.resolve("blender.log");
            var builder = new ProcessBuilder(command(blender, folder, check))
                    .directory(folder.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put(ANSWER_VARIABLE, answer.toString());
            var process = builder.start();
            job.setProgress("Blender reads the export with no window");
            job.onCancel(process::destroy);
            var ending = new Thread(process::destroy, "cruma-blender-check-end");
            Runtime.getRuntime().addShutdownHook(ending);
            try {
                await(process, job);
            } finally {
                Runtime.getRuntime().removeShutdownHook(ending);
            }
            var lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            var opened = lines.stream().filter(line -> line.startsWith(OPENED)).findFirst();
            if (process.exitValue() != 0 || opened.isEmpty() || !Files.isRegularFile(answer)) {
                throw new ToolProblem("Blender could not open the export, code " + process.exitValue() + ":\n"
                        + String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size())));
            }
            var scene = JSON.parseObject(Files.readString(answer, StandardCharsets.UTF_8));
            scene.put("opened", opened.get().substring(OPENED.length()));
            var info = folder.resolve(LocationExport.INFO);
            compare(scene, Files.isRegularFile(info) ? Files.readString(info, StandardCharsets.UTF_8) : "");
            return scene;
        } catch (IOException failed) {
            throw new ToolProblem("Blender did not start: " + failed.getMessage());
        } finally {
            delete(work);
        }
    }

    static void compare(JSONObject scene, String info) {
        var differences = new ArrayList<String>();
        var notes = new ArrayList<String>();
        var location = LOCATION.matcher(info);
        var model = MODEL.matcher(info);
        if (location.find()) {
            compareLocation(
                    scene,
                    Long.parseLong(location.group(1)),
                    Long.parseLong(location.group(2)),
                    Long.parseLong(location.group(3)),
                    differences,
                    notes);
        } else if (model.find()) {
            var animations = ANIMATIONS.matcher(info);
            compareModel(
                    scene,
                    Long.parseLong(model.group(1)),
                    Long.parseLong(model.group(2)),
                    animations.find() ? Long.parseLong(animations.group(1)) : -1,
                    differences,
                    notes);
        } else {
            return;
        }
        compareNpcLight(scene, differences);
        scene.put("matches", differences.isEmpty());
        scene.put("differences", differences);
        if (!notes.isEmpty()) {
            scene.put("notes", notes);
        }
    }

    private static void compareLocation(
            JSONObject scene,
            long objects,
            long triangles,
            long textures,
            List<String> differences,
            List<String> notes) {
        differ("Meshes in the .glb", scene.getLongValue("file_meshes"), objects, differences);
        differ("Textures in the .glb", scene.getLongValue("file_images"), textures, differences);
        var inFile = 0L;
        var parts = scene.getJSONArray("parts");
        for (var index = 0; index < parts.size(); index++) {
            var part = parts.getJSONObject(index);
            var name = part.getString("part");
            inFile += part.getLongValue("triangles_in_file");
            var wanted = part.containsKey("effect_actors")
                    ? part.getLongValue("effect_actors")
                    : part.getLongValue("in_file");
            differ(name + " in Blender", part.getLongValue("in_blender"), wanted, differences);
            compareTriangles(
                    name,
                    part.getLongValue("triangles_in_blender"),
                    part.getLongValue("triangles_in_file"),
                    differences,
                    notes);
        }
        differ("Triangles in the .glb", inFile, triangles, differences);
        if (parts.size() > MOST_PARTS) {
            scene.put("more_parts", parts.size() - MOST_PARTS);
            scene.put("parts", new JSONArray(parts.subList(0, MOST_PARTS)));
        }
    }

    private static void compareModel(
            JSONObject scene,
            long bones,
            long triangles,
            long animations,
            List<String> differences,
            List<String> notes) {
        var armatures = scene.getJSONArray("armature_list");
        if (armatures.isEmpty()) {
            differences.add("Blender has no armature.");
            return;
        }
        var armature = armatures.getJSONObject(0);
        differ("Bones in Blender", armature.getLongValue("bones"), bones, differences);
        compareTriangles("The model", armature.getLongValue("skinned_triangles"), triangles, differences, notes);
        if (animations >= 0) {
            differ("Actions in Blender", scene.getLongValue("actions"), animations, differences);
        }
    }

    private static void compareNpcLight(JSONObject scene, List<String> differences) {
        var materials = scene.getLongValue("npc_materials");
        var lit = scene.getLongValue("npc_materials_in_viewer_light");
        if (lit < materials) {
            differences.add((materials - lit) + " of " + materials
                    + " materials of NPCs keep Blender's light, and the script gives them the viewer's light of NPCs.");
        }
    }

    private static void compareTriangles(
            String what, long found, long wanted, List<String> differences, List<String> notes) {
        var lost = wanted - found;
        if (lost > 0 && lost <= wanted * FACES_BLENDER_MAY_DROP) {
            notes.add(what + " has " + wanted + " triangles and Blender shows " + found
                    + ". Its importer keeps one face of those that share all corners.");
        } else {
            differ(what + " triangles in Blender", found, wanted, differences);
        }
    }

    private static void differ(String what, long found, long wanted, List<String> differences) {
        if (found != wanted) {
            differences.add(what + ": " + found + ", and the export says " + wanted + ".");
        }
    }

    private static void await(Process process, Job job) throws ToolProblem, InterruptedException {
        var stopped = "Stopped. The export stays as it was.";
        var deadline = System.nanoTime() + MOST_NANOS;
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (job.isCancelled()) {
                process.destroy();
                throw new ToolProblem(stopped);
            }
            if (System.nanoTime() > deadline) {
                process.destroy();
                throw new ToolProblem(
                        "Blender did not finish in " + TimeUnit.NANOSECONDS.toMinutes(MOST_NANOS) + " minutes.");
            }
        }
        if (job.isCancelled()) {
            throw new ToolProblem(stopped);
        }
    }

    private static void delete(@Nullable Path folder) {
        if (folder == null) {
            return;
        }
        try (var walk = Files.walk(folder)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}
