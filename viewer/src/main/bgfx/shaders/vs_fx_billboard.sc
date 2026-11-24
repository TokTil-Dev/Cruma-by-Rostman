$input a_position, a_texcoord0, i_data0, i_data1, i_data2, i_data3, i_data4
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_color0

#include <bgfx_compute.sh>
#include "soft_particle.sh"

uniform vec4 u_fxParams[10];
BUFFER_RO(u_fxActorTransforms, vec4, 12);

vec3 rotateByQuaternion(vec3 source, vec4 rotation)
{
    vec3 doubledCross = 2.0 * cross(rotation.xyz, source);
    return source + rotation.w * doubledCross + cross(rotation.xyz, doubledCross);
}

void main()
{
    float transformCode = i_data4.w;
    int transformIndex = int(abs(transformCode) - 0.5);
    vec4 actorRotation = u_fxActorTransforms[transformIndex * 3];
    vec3 actorPosition = u_fxActorTransforms[transformIndex * 3 + 1].xyz;
    bool relativeCoordinates = transformCode > 0.0;
    vec3 center = relativeCoordinates
        ? rotateByQuaternion(i_data0.xyz, actorRotation) + actorPosition
        : i_data0.xyz + actorPosition;
    vec2 size = vec2(i_data0.w, i_data1.x);
    float angle = i_data1.y;
    float orientMode = i_data1.z;

    vec3 rightAxis = u_fxParams[0].xyz;
    vec3 upAxis = u_fxParams[1].xyz;
    vec3 direction = relativeCoordinates && orientMode > 0.5 && orientMode < 1.5
        ? rotateByQuaternion(i_data4.xyz, actorRotation)
        : i_data4.xyz;
    if (orientMode > 0.5 && orientMode < 1.5 && dot(direction, direction) > 0.00000001)
    {
        vec3 velocity = normalize(direction);
        vec3 viewDirection = normalize(center - u_fxParams[2].xyz);
        vec3 candidate = cross(velocity, viewDirection);
        float candidateLength = length(candidate);
        rightAxis = candidateLength > 0.0001 ? candidate / candidateLength : rightAxis;
        upAxis = velocity;
    }
    else if (orientMode > 3.5 && dot(direction, direction) > 0.00000001)
    {
        vec4 inverseRotation = vec4(-actorRotation.xyz, actorRotation.w);
        vec3 normal = normalize(relativeCoordinates ? rotateByQuaternion(direction, inverseRotation) : direction);
        vec3 nonParallel = abs(normal.x) > 0.57
            ? vec3(0.0, 1.0, 0.0)
            : (abs(normal.y) > 0.57 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0));
        vec3 planeUp = cross(normal, nonParallel);
        float rise = length(planeUp);
        upAxis = planeUp / max(rise, 0.000001);
        rightAxis = normalize(cross(upAxis, normal));
        if (relativeCoordinates)
        {
            upAxis = rotateByQuaternion(upAxis, actorRotation);
            rightAxis = rotateByQuaternion(rightAxis, actorRotation);
        }
        size = vec2(size.y < 0.0 ? size.x : -size.x, size.y * rise);
    }

    vec2 offset = a_position.xy * size;
    float cosine = cos(angle);
    float sine = sin(angle);
    offset = vec2(offset.x * cosine - offset.y * sine, offset.x * sine + offset.y * cosine);
    vec3 world = center + offset.x * rightAxis + offset.y * upAxis;
    gl_Position = mul(u_viewProj, vec4(world, 1.0));
    float viewDepth = mul(u_view, vec4(world, 1.0)).z;
    v_texcoord0 = vec2(
        i_data3.x + a_texcoord0.x * i_data3.z,
        i_data3.y + a_texcoord0.y * i_data3.w
    );
    float blendCode = i_data1.w;
    float nextCell = floor(blendCode);
    float cellsV = max(floor(1.0 / max(i_data3.w, 0.0001) + 0.5), 1.0);
    float nextColumn = floor(nextCell / cellsV);
    float nextRow = nextCell - nextColumn * cellsV;
    v_texcoord4 = vec4(
        (nextColumn + a_texcoord0.x) * i_data3.z,
        (nextRow + a_texcoord0.y) * i_data3.w,
        blendCode - nextCell,
        0.0
    );
    v_texcoord1 = vec4(a_position.xy + vec2(0.5, 0.5), 0.0, 0.0);
    v_texcoord2 = vec4(softParticleDepthUv(gl_Position), viewDepth, abs(viewDepth));
    v_texcoord3 = vec4(world, 1.0);
    v_color0 = i_data2;
}
