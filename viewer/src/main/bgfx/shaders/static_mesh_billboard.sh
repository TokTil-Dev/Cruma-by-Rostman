bool staticMeshUsesBillboard(vec4 metadata)
{
    return mod(floor(metadata.w / 2.0), 2.0) > 0.5;
}

vec2 staticMeshBillboardTilt(vec4 metadata)
{
    return metadata.z < -0.5 ? metadata.xy : vec2(0.0, 0.0);
}

vec2 staticMeshBillboardTowardView(mat4 view)
{
    return -mul(vec4(0.0, 0.0, 1.0, 0.0), view).xy;
}

vec4 staticMeshBillboardWorldPosition(
    vec3 localPosition,
    vec4 sourceColumn0,
    vec4 sourceColumn1,
    vec4 sourceColumn2,
    vec4 pivot,
    vec2 toEye,
    vec2 tilt
)
{
    vec3 scaled = localPosition * vec3(
        length(sourceColumn0.xyz), length(sourceColumn1.xyz), length(sourceColumn2.xyz)
    );
    float sinePitch = sin(tilt.x);
    float cosinePitch = cos(tilt.x);
    float sineRoll = sin(tilt.y);
    float cosineRoll = cos(tilt.y);
    vec3 turned = vec3(cosinePitch, 0.0, sinePitch) * scaled.x
        + vec3(sineRoll * sinePitch, cosineRoll, -sineRoll * cosinePitch) * scaled.y
        + vec3(-cosineRoll * sinePitch, sineRoll, cosineRoll * cosinePitch) * scaled.z;
    float yaw = (dot(toEye, toEye) > 0.0 ? atan2(toEye.y, toEye.x) : 0.0) + 1.5707963;
    float sineYaw = sin(yaw);
    float cosineYaw = cos(yaw);
    return vec4(
        pivot.x + turned.x * cosineYaw - turned.y * sineYaw,
        pivot.y + turned.x * sineYaw + turned.y * cosineYaw,
        pivot.z + turned.z,
        1.0
    );
}
