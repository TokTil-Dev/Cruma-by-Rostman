package com.rost.cruma.viewer.agent.edit;

import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ExportSettings;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ClientWriteTools implements ToolGroup {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

    static void requireWrite(AgentHub hub, String tool, String action) throws ToolProblem {
        var access = hub.getAccess();
        if (!access.allows(Access.WRITE)) {
            throw new ToolProblem(tool
                    + " "
                    + action
                    + " changes a client and needs Write access, and Cruma gives "
                    + access.getLabel()
                    + ". Ask the person to raise it in Settings, AI agent.");
        }
    }

    static Path newFolder(ToolArgs args, AgentHub hub, String name) throws ToolProblem {
        var exports = ExportSettings.in(new ConfigLoader(ConfigLoader.parseOptions(
                        new String[] {"-c", hub.getConfigDirectory().toString()})))
                .getFolder()
                .toAbsolutePath()
                .normalize();
        var asked = args.getText("folder");
        if (asked == null) {
            return exports.resolve(name + " " + STAMP.format(LocalDateTime.now()));
        }
        var folder = fullPath(asked, "folder");
        if (!folder.startsWith(exports) && !hub.getAccess().allows(Access.WRITE)) {
            throw new ToolProblem("Writing outside the export folder " + exports
                    + " needs Write access, and Cruma gives " + hub.getAccess().getLabel()
                    + ". Leave folder out, or ask the person to raise it in Settings, AI agent.");
        }
        return folder;
    }

    static Path fullPath(String text, String name) throws ToolProblem {
        try {
            var path = Path.of(text);
            if (!path.isAbsolute()) {
                throw new ToolProblem(name + " must be a full path.");
            }
            return path.normalize();
        } catch (InvalidPathException wrong) {
            throw new ToolProblem(name + " is not a path: " + wrong.getMessage());
        }
    }

    static Path requiredFolder(ToolArgs args, String why) throws ToolProblem {
        var text = args.getText("folder");
        if (text == null) {
            throw new ToolProblem("folder is missing: " + why);
        }
        return fullPath(text, "folder");
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    @Override
    public void register(Tools tools) {
        tools.hub(
                ToolSpec.named(
                                "scene_install",
                                "Put a scene into a client",
                                "Puts your scene over a cut scene of the client, or a lobby copy back into its map:"
                                        + " check writes nothing, write makes the file in a folder, install and restore"
                                        + " change the client. film_read shows the scene.")
                        .choice(
                                "action",
                                "check when left out. install, restore: Needs Write.",
                                SceneInstall.ACTIONS.toArray(String[]::new))
                        .text("scene", "Your scene, from scene_list.")
                        .text("client", "The client it reads, by name or folder. The scene's own when left out.")
                        .text("replaces", "The cut scene it goes over. A copy goes over its own when left out.")
                        .text(
                                "folder",
                                "write: a new folder, a dated one in the export folder when left out. Outside the"
                                        + " export folder: Needs Write. install, restore: the folder write made.")
                        .text("into", "install: the client folder that takes the file, best a copy.")
                        .writes()
                        .destructive()
                        .build(),
                SceneInstall::call);
        tools.hub(
                ToolSpec.named(
                                "tiles_move",
                                "Copy, move or swap tiles",
                                "Copies, moves or swaps whole map tiles of a client, also from another client, with"
                                    + " the server's geodata and spawns. plan writes nothing, run and undo change the"
                                    + " client.")
                        .choice(
                                "action",
                                "plan when left out. export writes into a folder, and no client changes. run, undo:"
                                        + " Needs Write.",
                                TileMoves.ACTIONS.toArray(String[]::new))
                        .choice("kind", "copy when left out.", "copy", "move", "swap")
                        .text("from", "The source tile, such as 22_22 or 21_26_Classic.")
                        .text("to", "The target tile.")
                        .text("client", "The client that changes, by name or folder. run and undo need it.")
                        .text("from_client", "Another client to copy the tile from. It does not change.")
                        .flag("replace", "Write over the target's map.")
                        .flag("backup", "Keep a backup for undo. true when left out.")
                        .choice(
                                "geodata",
                                "The tile's geodata region goes with it, copied or built from the map.",
                                "copy",
                                "build")
                        .text("geodata_folder", "The server's geodata folder. The client's settings when left out.")
                        .flag("pathnodes", "run: rebuild the geodata folder's pathnode graph after it.")
                        .flag("spawns", "The zones and makers of npcpos.txt inside the tile go with it.")
                        .text("scripts", "The server's folder with npcpos.txt. The client's settings when left out.")
                        .text(
                                "folder",
                                "export: a new folder, a dated one in the export folder when left out. Outside the"
                                        + " export folder: Needs Write. undo: a folder from backups.")
                        .writes()
                        .destructive()
                        .build(),
                TileMoves::call);
    }
}
