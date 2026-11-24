import collections
import json
import os
import struct
import sys

import bpy

LISTED = 12
TUFTS = "Tufts"
PAWN = "cruma_pawn"
PAWN_LIGHT = "cruma_pawn_light"


def export_folder():
    return sys.argv[sys.argv.index("--") + 1]


def main_scene():
    return max(bpy.data.scenes, key=lambda scene: len(scene.objects))


def read_glb(folder):
    names = sorted(name for name in os.listdir(folder) if name.lower().endswith(".glb"))
    if not names:
        return {}
    with open(os.path.join(folder, names[0]), "rb") as file:
        file.seek(12)
        size, _ = struct.unpack("<II", file.read(8))
        return json.loads(file.read(size))


def file_triangles(document, node):
    if "mesh" not in node:
        return 0
    accessors = document["accessors"]
    primitives = document["meshes"][node["mesh"]]["primitives"]
    triangles = sum(accessors[primitive["indices"]]["count"] // 3 for primitive in primitives if "indices" in primitive)
    copies = node.get("extensions", {}).get("EXT_mesh_gpu_instancing")
    return triangles * (accessors[copies["attributes"]["TRANSLATION"]]["count"] if copies else 1)


class Triangles:

    def __init__(self):
        self.known = {}

    def of(self, mesh):
        if mesh not in self.known:
            mesh.calc_loop_triangles()
            self.known[mesh] = len(mesh.loop_triangles)
        return self.known[mesh]


def tuft_of(obj):
    modifier = obj.modifiers.get(TUFTS)
    if modifier is None or modifier.node_group is None:
        return None
    return next((node.inputs["Object"].default_value for node in modifier.node_group.nodes if node.type == "OBJECT_INFO"), None)


def drawn_triangles(obj, triangles):
    tuft = tuft_of(obj)
    if tuft is not None:
        return len(obj.data.vertices) * triangles.of(tuft.data)
    return triangles.of(obj.data)


def part_of(document, group, triangles):
    nodes = document["nodes"]
    children = [nodes[index] for index in group.get("children", [])]
    collection = bpy.data.collections.get(group.get("name", ""))
    objects = list(collection.objects)
    tufts = [tuft_of(obj) for obj in objects if obj.type == "MESH"]
    objects = [obj for obj in objects if obj not in tufts]
    kinds = collections.Counter(obj.type for obj in objects)
    actors = set()
    for child in children:
        extras = child.get("extras", {})
        if "cruma_effect" in extras:
            actors.add(extras.get("cruma_effect_actor", child.get("name")))
    part = {
        "part": group.get("name", ""),
        "in_file": len(children),
        "in_blender": kinds["MESH"] + kinds["LIGHT"] + kinds["SPEAKER"],
        "triangles_in_file": sum(file_triangles(document, child) for child in children),
        "triangles_in_blender": sum(drawn_triangles(obj, triangles) for obj in objects if obj.type == "MESH"),
    }
    if actors:
        part["effect_actors"] = len(actors)
    return part


def parts_of(document, triangles):
    nodes = document.get("nodes", [])
    parts = []
    for scene in document.get("scenes", []):
        for root in scene.get("nodes", []):
            for index in nodes[root].get("children", []):
                group = nodes[index]
                if "mesh" not in group and bpy.data.collections.get(group.get("name", "")) is not None:
                    parts.append(part_of(document, group, triangles))
    return parts


def armature_of(obj, triangles):
    meshes = [child for child in obj.children_recursive if child.type == "MESH"
              and any(modifier.type == "ARMATURE" for modifier in child.modifiers)]
    animation = obj.animation_data
    action = animation.action if animation is not None else None
    return {
        "name": obj.name,
        "bones": len(obj.data.bones),
        "plays": action.name if action is not None else "",
        "skinned_meshes": len(meshes),
        "skinned_triangles": sum(triangles.of(mesh.data) for mesh in meshes),
    }


def npc_materials(objects):
    materials = {slot.material for obj in objects if obj.type == "MESH" and PAWN in obj.keys()
                 for slot in obj.material_slots if slot.material is not None}
    return len(materials), sum(1 for material in materials if PAWN_LIGHT in material.keys())


def read():
    scene = main_scene()
    document = read_glb(export_folder())
    objects = list(scene.objects)
    armatures = [obj for obj in objects if obj.type == "ARMATURE"]
    triangles = Triangles()
    nodes = document.get("nodes", [])
    npcs, lit = npc_materials(objects)
    return {
        "blender": bpy.app.version_string,
        "scene": scene.name,
        "objects": len(objects),
        "kinds": dict(collections.Counter(obj.type for obj in objects)),
        "file_meshes": sum(1 for node in nodes if "mesh" in node),
        "file_images": len(document.get("images", [])),
        "parts": parts_of(document, triangles),
        "tufts": sum(len(obj.data.vertices) for obj in objects if obj.type == "MESH" and tuft_of(obj) is not None),
        "images": len(bpy.data.images),
        "materials": len(bpy.data.materials),
        "actions": len(bpy.data.actions),
        "armatures": len(armatures),
        "armature_list": [armature_of(obj, triangles) for obj in armatures[:LISTED]],
        "npc_materials": npcs,
        "npc_materials_in_viewer_light": lit,
    }


with open(os.environ["CRUMA_CHECK_FILE"], "w", encoding="utf-8") as answer:
    json.dump(read(), answer)
