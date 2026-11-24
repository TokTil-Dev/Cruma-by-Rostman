#include "authored_reflection.sh"

uniform vec4 u_authoredPasses[155];

vec4 authoredPassArgument(vec4 current, vec4 bitmap, vec4 diffuse, int flags)
{
    int source = flags & 15;
    vec4 value = source == 0 ? diffuse : source == 1 ? current : source == 2 ? bitmap
        : source == 3 ? u_authoredPasses[1] : vec4_splat(0.0);
    if ((flags & 32) != 0) value = vec4_splat(value.a);
    if ((flags & 16) != 0) value = vec4_splat(1.0) - value;
    return value;
}

vec4 authoredPassOperation(vec4 current, vec4 bitmap, vec4 diffuse, vec4 op, vec4 args, float mFactor)
{
    vec4 a = authoredPassArgument(current, bitmap, diffuse, int(op.z));
    vec4 b = authoredPassArgument(current, bitmap, diffuse, int(op.w));
    vec3 color = a.rgb;
    if (op.x == 3.0) color = b.rgb;
    else if (op.x == 4.0) color = a.rgb * b.rgb * ((int(args.w) & 1) == 0 ? mFactor : 1.0);
    else if (op.x == 5.0) color = a.rgb * b.rgb * 2.0;
    else if (op.x == 6.0) color = a.rgb * b.rgb * 4.0;
    else if (op.x == 7.0) color = a.rgb + b.rgb;
    else if (op.x == 10.0) color = a.rgb - b.rgb;
    else if (op.x == 12.0) color = mix(b.rgb, a.rgb, diffuse.a);
    else if (op.x == 13.0) color = mix(b.rgb, a.rgb, bitmap.a);
    else if (op.x == 14.0) color = mix(b.rgb, a.rgb, u_authoredPasses[1].a);
    else if (op.x == 16.0) color = mix(b.rgb, a.rgb, current.a);
    else if (op.x == 18.0) color = a.rgb + a.a * b.rgb;
    else if (op.x == 25.0 || op.x == 26.0)
    {
        vec3 c = authoredPassArgument(current, bitmap, diffuse, (int(args.w) >> 3) & 63).rgb;
        color = op.x == 25.0 ? c + a.rgb * b.rgb : c * a.rgb + (vec3_splat(1.0) - c) * b.rgb;
    }
    float aa = authoredPassArgument(current, bitmap, diffuse, int(args.x)).a;
    float ab = authoredPassArgument(current, bitmap, diffuse, int(args.y)).a;
    float alpha = aa;
    if (op.y == 3.0) alpha = ab;
    else if (op.y == 4.0) alpha = aa * ab;
    else if (op.y == 7.0) alpha = aa + ab;
    return vec4(color, alpha);
}

vec3 authoredPassUv(vec2 uv, vec2 worldReflection, vec3 cameraReflection, vec2 sphereMap, int sampleIndex)
{
    int at = 3 + sampleIndex * 2;
    int source = (int(u_authoredPasses[14].w) >> (sampleIndex * 2)) & 3;
    vec3 p = vec3(uv, 1.0);
    if (source == 2) p = cameraReflection;
    else if (source == 3) p = vec3(sphereMap, 1.0);
    vec2 result = authoredReflectionCoordinates(p, u_authoredPasses[at].xyz, u_authoredPasses[at + 1].xyz);
    if (source == 1) result = worldReflection;
    return vec3(result, u_authoredPasses[at].w);
}

#define AUTHORED_PASS_SAMPLE(_index, _sampler) \
    texels[_index] = vec4_splat(0.0); \
    if (u_authoredPasses[0].w > float(_index)) \
        texels[_index] = bitmapArrayBias(_sampler, \
            authoredPassUv(uv, worldReflection, cameraReflection, sphereMap, _index), mipBias);

vec4 authoredPassMaterial(vec2 uv, vec2 worldReflection, vec3 viewPosition, vec3 viewNormal, float mipBias,
    vec4 diffuse, float mFactor, vec4 cameraPoint, out float specularMask)
{
    specularMask = 0.0;
    vec3 cameraReflection = vec3_splat(0.0);
    vec2 sphereMap = vec2_splat(0.0);
    if (u_authoredPasses[14].w > 0.5)
    {
        cameraReflection = cameraPoint.w > 0.5 ? cameraPoint.xyz : authoredReflection(viewPosition, viewNormal);
        sphereMap = authoredSphereMap(viewNormal);
    }
    vec4 texels[8];
    AUTHORED_PASS_SAMPLE(0, s_sceneDiffuse)
    AUTHORED_PASS_SAMPLE(1, s_authored1)
    AUTHORED_PASS_SAMPLE(2, s_authored2)
    AUTHORED_PASS_SAMPLE(3, s_authored3)
    AUTHORED_PASS_SAMPLE(4, s_authored4)
    AUTHORED_PASS_SAMPLE(5, s_authored5)
    AUTHORED_PASS_SAMPLE(6, s_authored6)
    AUTHORED_PASS_SAMPLE(7, s_authored7)
    vec4 accumulated = diffuse;
    bool haveResult = false;
    for (int passIndex = 0; passIndex < 8; passIndex++)
    {
        if (float(passIndex) >= u_authoredPasses[0].x) break;
        if (u_authoredPasses[0].z > 0.5 && float(passIndex) != u_authoredPasses[0].y) continue;
        vec4 passInfo = u_authoredPasses[19 + passIndex];
        if (passInfo.x == passInfo.y) continue;
        vec4 current = diffuse;
        for (int instruction = 0; instruction < 64; instruction++)
        {
            if (float(instruction) < passInfo.x) continue;
            if (float(instruction) >= passInfo.y) break;
            vec4 op = u_authoredPasses[27 + instruction * 2];
            vec4 args = u_authoredPasses[28 + instruction * 2];
            vec4 bitmap = vec4_splat(0.0);
            if (args.z >= 0.0) bitmap = texels[int(args.z)];
            current = authoredPassOperation(current, bitmap, diffuse, op, args, mFactor);
            if ((int(args.w) & 2) != 0) specularMask = current.a;
            else if ((int(args.w) & 4) != 0) specularMask = u_authoredPasses[1].a;
        }
        if (!haveResult || u_authoredPasses[0].z > 0.5 || (passInfo.z == 2.0 && passInfo.w == 1.0)) accumulated = current;
        else if (passInfo.w == 2.0) accumulated = current * (passInfo.z == 2.0 ? 1.0 : current.a) + accumulated;
        else accumulated = current * current.a + accumulated * (1.0 - current.a);
        haveResult = true;
    }
    return accumulated;
}

vec4 authoredPassMaterial(vec2 uv, vec2 worldReflection, vec3 viewPosition, vec3 viewNormal, float mipBias,
    vec4 diffuse, float mFactor, out float specularMask)
{
    return authoredPassMaterial(uv, worldReflection, viewPosition, viewNormal, mipBias, diffuse, mFactor,
        vec4_splat(0.0), specularMask);
}

vec4 authoredPassMaterial(vec2 uv, vec2 worldReflection, vec3 viewPosition, vec3 viewNormal, float mipBias,
    vec4 diffuse, float mFactor)
{
    float specularMask;
    return authoredPassMaterial(uv, worldReflection, viewPosition, viewNormal, mipBias, diffuse, mFactor,
        vec4_splat(0.0), specularMask);
}
