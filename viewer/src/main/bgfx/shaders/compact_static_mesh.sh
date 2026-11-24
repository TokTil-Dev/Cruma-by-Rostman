#define COMPACT_STATIC_MESH_MARKER 2.0

bool compactStaticMeshLayout(vec4 color1)
{
    return color1.w > COMPACT_STATIC_MESH_MARKER - 0.5;
}
