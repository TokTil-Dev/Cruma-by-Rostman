uniform vec4 u_authoredMaterial[35];
SAMPLER2DARRAY(s_authored1, 1);
SAMPLER2DARRAY(s_authored2, 2);
SAMPLER2DARRAY(s_authored3, 4);
SAMPLER2DARRAY(s_authored4, 5);
SAMPLER2DARRAY(s_authored5, 7);
SAMPLER2DARRAY(s_authored6, 12);
SAMPLER2DARRAY(s_authored7, 13);

vec4 authoredArgument(vec4 current, vec4 bitmap, vec4 vertex, float argument)
{
    int flags = int(argument);
    int source = flags & 15;
    vec4 value = source == 0 ? vertex : source == 1 ? current : source == 3 ? u_authoredMaterial[33]
        : source == 5 ? vec4_splat(0.0) : bitmap;
    if ((flags & 32) != 0) value = vec4_splat(value.a);
    if ((flags & 16) != 0) value = vec4_splat(1.0) - value;
    return value;
}

bool authoredSamplesBitmap(vec4 operations, vec4 alphaArgs)
{
    return (int(operations.y) & 15) == 2 || (int(operations.z) & 15) == 2
        || (int(alphaArgs.x) & 15) == 2 || (int(alphaArgs.y) & 15) == 2;
}

vec4 authoredStage(vec4 current, vec4 bitmap, vec4 vertex, vec4 operations, vec4 alphaArgs)
{
    vec4 a = authoredArgument(current, bitmap, vertex, operations.y);
    vec4 b = authoredArgument(current, bitmap, vertex, operations.z);
    vec3 color = a.rgb;
    float op = operations.x;
    if (op == 3.0) color = b.rgb;
    else if (op >= 4.0 && op <= 6.0) color = a.rgb * b.rgb * exp2(op - 4.0);
    else if (op == 7.0) color = a.rgb + b.rgb;
    else if (op == 10.0) color = a.rgb - b.rgb;
    else if (op == 12.0) color = mix(b.rgb, a.rgb, vertex.a);
    else if (op == 13.0) color = mix(b.rgb, a.rgb, bitmap.a);
    else if (op == 14.0) color = mix(b.rgb, a.rgb, u_authoredMaterial[33].a);
    else if (op == 16.0) color = mix(b.rgb, a.rgb, current.a);
    else if (op == 18.0) color = a.rgb + a.a * b.rgb;
    float aa = authoredArgument(current, bitmap, vertex, alphaArgs.x).a;
    float ab = authoredArgument(current, bitmap, vertex, alphaArgs.y).a;
    float alpha = aa;
    if (operations.w == 3.0) alpha = ab;
    else if (operations.w == 4.0) alpha = aa * ab;
    else if (operations.w == 7.0) alpha = aa + ab;
    return vec4(color, alpha);
}

vec3 authoredUv(vec2 uv, int index)
{
    int base = 1 + index * 4;
    vec3 inputUv = vec3(uv, 1.0);
    return vec3(dot(inputUv, u_authoredMaterial[base + 2].xyz),
        dot(inputUv, u_authoredMaterial[base + 3].xyz), u_authoredMaterial[base + 1].z);
}

#define AUTHORED_STAGE(_index, _sampler) \
    if (u_authoredMaterial[0].x > float(_index)) \
    { \
        vec4 operations = u_authoredMaterial[1 + (_index) * 4]; \
        vec4 alphaArgs = u_authoredMaterial[2 + (_index) * 4]; \
        vec4 bitmap = vec4_splat(1.0); \
        if (authoredSamplesBitmap(operations, alphaArgs)) \
            bitmap = bitmapArrayBias(_sampler, authoredUv(uv, _index), mipBias); \
        if (u_authoredMaterial[0].z > 0.5 && u_authoredMaterial[0].x < float(_index) + 1.5) \
            current.a *= u_authoredMaterial[34].a; \
        else \
            current = authoredStage(current, bitmap, vertex, operations, alphaArgs); \
    }

vec4 authoredMaterial(vec2 uv, float mipBias, vec4 vertex)
{
    vec4 current = vertex;
    AUTHORED_STAGE(0, s_sceneDiffuse)
    AUTHORED_STAGE(1, s_authored1)
    AUTHORED_STAGE(2, s_authored2)
    AUTHORED_STAGE(3, s_authored3)
    AUTHORED_STAGE(4, s_authored4)
    AUTHORED_STAGE(5, s_authored5)
    AUTHORED_STAGE(6, s_authored6)
    AUTHORED_STAGE(7, s_authored7)
    return current;
}
