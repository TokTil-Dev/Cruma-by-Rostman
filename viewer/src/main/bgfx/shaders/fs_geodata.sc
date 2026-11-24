$input v_color0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

SAMPLER2D(s_geodataDepth, 0);
SAMPLER2D(s_geodataModelDepth, 1);

uniform vec4 u_geodataView;
uniform vec4 u_geodataHeight;
uniform vec4 u_geodataProjection;
uniform vec4 u_geodataStyle;
uniform vec4 u_geodataFocus;
uniform vec4 u_geodataHover;
uniform vec4 u_geodataSelect;

float geodataViewDistance(float depth)
{
    float denominator = depth * u_geodataProjection.y - u_geodataProjection.x;
    denominator = abs(denominator) > 0.000001 ? denominator : 0.000001;
    return abs((u_geodataProjection.z - depth * u_geodataProjection.w) / denominator);
}

float geodataLine(float distance, float width)
{
    return 1.0 - smoothstep(width * 0.5 - 0.5, width * 0.5 + 0.5, distance);
}

float geodataBand(float distance, float width, float pixel)
{
    return 1.0 - smoothstep(width - pixel * 0.5, width + pixel * 0.5, distance);
}

vec4 geodataBits(float mask)
{
    return mod(floor(vec4_splat(mask) / vec4(1.0, 2.0, 4.0, 8.0) + 0.001), 2.0);
}

float geodataMatches(vec4 target, vec2 cellMin, float floorZ)
{
    vec2 offset = abs(cellMin - target.xy);
    return target.w * step(offset.x + offset.y, 0.5) * step(abs(floorZ - target.z), 0.5);
}

void main()
{
    vec3 world = v_texcoord2.xyz;
    float mask = v_texcoord1.x;
    float kind = v_texcoord1.y;
    float fraction = v_texcoord1.z;
    float floorZ = v_texcoord1.w;
    float lineWidth = u_geodataView.z;

    vec2 cell = world.xy / 16.0;
    vec2 cellX = dFdx(cell);
    vec2 cellY = dFdy(cell);
    vec2 cellPerPixel = max(abs(cellX) + abs(cellY), vec2_splat(0.00001));
    float pixel = max(cellPerPixel.x, cellPerPixel.y);
    float fractionPerPixel = max(fwidth(fraction), 0.00001);

    vec2 inside = fract(cell);
    vec2 border = min(inside, vec2_splat(1.0) - inside) / cellPerPixel;
    vec3 base = v_color0.rgb;
    vec3 wall = vec3(1.0, 0.34, 0.28);
    vec3 color = base;
    float alpha = 0.0;

    if (kind < 0.5)
    {
        float grid = geodataLine(min(border.x, border.y), lineWidth) * smoothstep(3.0, 9.0, 1.0 / pixel);
        if (u_geodataStyle.z > 0.0)
        {
            float distance = length(world.xy - u_geodataFocus.xy);
            grid *= 1.0 - smoothstep(u_geodataStyle.z * 0.75, u_geodataStyle.z, distance);
        }
        grid *= u_geodataStyle.y;

        float edge = 0.0;
        float obstacle = 0.0;
        if (mask > 0.5)
        {
            float bandWidth = max(0.12, (lineWidth + 1.5) * pixel);
            vec4 bits = geodataBits(mask);
            edge = max(
                max(bits.x * geodataBand(1.0 - inside.x, bandWidth, pixel),
                    bits.y * geodataBand(inside.x, bandWidth, pixel)),
                max(bits.z * geodataBand(1.0 - inside.y, bandWidth, pixel),
                    bits.w * geodataBand(inside.y, bandWidth, pixel)));
            if (mask > 14.5)
            {
                obstacle = 1.0;
                float hatchCoordinate = (cell.x + cell.y) * 3.0;
                float hatchPerPixel = max(3.0 * (abs(cellX.x + cellX.y) + abs(cellY.x + cellY.y)), 0.00001);
                float stripe = min(fract(hatchCoordinate), 1.0 - fract(hatchCoordinate)) / hatchPerPixel;
                float hatch = geodataLine(stripe, max(1.0, lineWidth)) * smoothstep(2.0, 6.0, 1.0 / pixel);
                edge = max(edge, hatch * 0.75);
            }
        }

        alpha = u_geodataStyle.x * mix(1.0, 1.6, obstacle);
        color = mix(color, mix(base, vec3_splat(1.0), 0.35), grid);
        alpha = mix(alpha, 0.6, grid);
        color = mix(color, wall, edge);
        alpha = mix(alpha, 0.95, edge);

        if (u_geodataHover.w > 0.5 || u_geodataSelect.w > 0.5)
        {
            vec2 cellMin = floor(cell) * 16.0;
            float outline = geodataLine(min(border.x, border.y), lineWidth + 2.0);
            float hovered = geodataMatches(u_geodataHover, cellMin, floorZ);
            color = mix(color, vec3_splat(1.0), hovered * max(outline, 0.35));
            alpha = mix(alpha, 1.0, hovered * max(outline, 0.4));
            float selected = geodataMatches(u_geodataSelect, cellMin, floorZ);
            vec3 accent = vec3(0.36, 0.86, 0.95);
            color = mix(color, accent, selected * max(outline, 0.45));
            alpha = mix(alpha, 1.0, selected * max(outline, 0.5));
        }
    }
    else if (kind < 1.5)
    {
        color = base * 0.6;
        alpha = u_geodataStyle.w * clamp(16.0 / max(mask, 1.0), 0.2, 1.0);
    }
    else
    {
        float top = geodataLine((1.0 - fraction) / fractionPerPixel, max(1.0, lineWidth));
        color = mix(base, base * 0.6 + vec3_splat(0.4), top * 0.5);
        alpha = max(0.75 * (1.0 - fraction) * (1.0 - fraction * 0.35), top * 0.7);
    }

    alpha *= v_color0.a * u_geodataView.w;
    if (alpha < 0.004)
    {
        discard;
    }

    float hidden = 0.0;
    if (u_geodataHeight.w > 0.0)
    {
        float sceneDepth = texelFetch(s_geodataDepth, ivec2(gl_FragCoord.xy), 0).x;
        if (sceneDepth < 0.999999)
        {
            float own = geodataViewDistance(gl_FragCoord.z);
            float scene = geodataViewDistance(sceneDepth);
            float tolerance = u_geodataHeight.w * (1.0 + own / 4096.0);
            hidden = smoothstep(tolerance * 0.5, tolerance, own - scene);
        }
        if (u_geodataHeight.x > 0.5)
        {
            float modelDepth = texelFetch(s_geodataModelDepth, ivec2(gl_FragCoord.xy), 0).x;
            if (modelDepth < 0.999999)
            {
                float own = geodataViewDistance(gl_FragCoord.z);
                float model = geodataViewDistance(modelDepth);
                float tolerance = u_geodataHeight.y * (1.0 + own / 4096.0);
                hidden = max(hidden, smoothstep(tolerance * 0.5, tolerance, own - model));
            }
        }
    }
    alpha *= mix(1.0, u_geodataHeight.z, hidden);
    if (alpha < 0.004)
    {
        discard;
    }
    gl_FragColor = vec4(color * mix(1.0, 0.8, hidden), clamp(alpha, 0.0, 1.0));
}
