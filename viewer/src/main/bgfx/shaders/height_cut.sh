bool heightCutRejects(vec4 cut, vec4 area, vec3 worldPosition)
{
    vec2 offset = worldPosition.xy - area.xy;
    return ((cut.z > 0.5 && worldPosition.z > cut.x) || (cut.w > 0.5 && worldPosition.z < cut.y))
        && (area.z <= 0.0 || dot(offset, offset) < area.z * area.z);
}
