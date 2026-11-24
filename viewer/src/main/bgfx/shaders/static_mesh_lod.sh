float staticMeshLodCoverage(vec2 camera, vec2 actorOrigin, vec4 lod, float forceBase)
{
    float level = lod.z;
    if (level < -0.5)
    {
        return 1.0;
    }
    if (forceBase > 1.5)
    {
        return 1.0;
    }
    if (forceBase > 0.5)
    {
        return level < 0.5 ? 1.0 : 0.0;
    }
    if (lod.x <= 0.0)
    {
        return 1.0;
    }
    float ratio = forceBase < 0.0 ? -forceBase : 1.0;
    float distance1 = lod.x * (mod(floor(lod.w / 4.0), 2.0) > 0.5 ? ratio : 1.0);
    float distance2 = lod.y * (mod(floor(lod.w / 8.0), 2.0) > 0.5 ? ratio : 1.0);
    float distanceToActor = length(camera - actorOrigin);
    if (level < 0.5)
    {
        return distanceToActor <= distance1 ? 1.0 : 0.0;
    }
    if (level < 1.5)
    {
        bool beforeTerminal = distance2 <= distance1 || distanceToActor <= distance2;
        return distanceToActor > distance1 && beforeTerminal ? 1.0 : 0.0;
    }
    return distanceToActor > distance2 ? 1.0 : 0.0;
}
