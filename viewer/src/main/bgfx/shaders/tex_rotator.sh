vec2 texRotatorUv(vec2 uv, vec2 pivot, vec3 angles)
{
    vec3 sine = sin(angles);
    vec3 cosine = cos(angles);
    vec2 centered = uv - pivot;
    return vec2(
        centered.x * (cosine.y * cosine.x)
            + centered.y * (sine.x * sine.z * cosine.y - cosine.z * sine.y)
            - (cosine.z * sine.x * cosine.y + sine.y * sine.z),
        centered.x * (cosine.x * sine.y)
            + centered.y * (sine.x * sine.z * sine.y + cosine.y * cosine.z)
            + (cosine.y * sine.z - cosine.z * sine.x * sine.y)
    ) + pivot;
}

vec2 texRotatorYawUv(vec2 uv, vec2 pivot, float yaw)
{
    float cosine = cos(yaw);
    float sine = sin(yaw);
    vec2 centered = uv - pivot;
    return vec2(
        centered.x * cosine - centered.y * sine,
        centered.x * sine + centered.y * cosine
    ) + pivot;
}

vec3 texRotatorRow(vec3 v, vec3 angles)
{
    vec3 sine = sin(angles);
    vec3 cosine = cos(angles);
    return vec3(
        v.x * (cosine.y * cosine.x)
            + v.y * (sine.x * sine.z * cosine.y - cosine.z * sine.y)
            - v.z * (cosine.z * sine.x * cosine.y + sine.y * sine.z),
        v.x * (cosine.x * sine.y)
            + v.y * (sine.x * sine.z * sine.y + cosine.y * cosine.z)
            + v.z * (cosine.y * sine.z - cosine.z * sine.x * sine.y),
        v.x * sine.x - v.y * (cosine.x * sine.z) + v.z * (cosine.x * cosine.z)
    );
}

vec2 animateEnvironmentUv(vec3 p, float mode, float time, vec4 parameters, float roll)
{
    if (mode == 1.0)
    {
        return p.xy + p.z * parameters.xy * time;
    }
    else if (mode == 2.0)
    {
        vec3 turned = texRotatorRow(vec3(p.xy - p.z * parameters.xy, p.z),
            vec3(parameters.w, parameters.z, roll) * time);
        return turned.xy + turned.z * parameters.xy;
    }
    else if (mode == 3.0)
    {
        return p.xy + p.z * parameters.yw * sin(6.2831853 * parameters.xz * time);
    }
    else if (mode == 4.0)
    {
        return p.xy / parameters.xy + p.z * parameters.zw;
    }
    else if (mode == 5.0)
    {
        vec2 pivot = vec2(parameters.y, parameters.w);
        vec3 turned = texRotatorRow(vec3(p.xy - p.z * pivot, p.z),
            vec3(0.0, parameters.z * sin(6.2831853 * parameters.x * time), 0.0));
        return turned.xy + turned.z * pivot;
    }
    else if (mode > 5.5)
    {
        vec3 angles = vec3(parameters.w, parameters.z, roll) * (mode < 6.5 ? time : 1.0);
        return texRotatorRow(p, angles).xy / parameters.xy;
    }
    return p.xy;
}
