$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_postSource, 0);
uniform vec4 u_postParams[4];

vec3 sampleSource(vec2 uv)
{
    vec2 halfTexel = u_postParams[0].xy * 0.5;
    return texture2D(
        s_postSource,
        clamp(uv, halfTexel, u_postParams[0].zw - halfTexel)
    ).rgb;
}

void main()
{
    vec2 inverseExtent = u_postParams[0].xy;
    vec2 uv = v_texcoord0 * u_postParams[0].zw;
    vec3 center = sampleSource(uv);
    vec3 northWest = sampleSource(uv + vec2(-1.0, -1.0) * inverseExtent);
    vec3 northEast = sampleSource(uv + vec2( 1.0, -1.0) * inverseExtent);
    vec3 southWest = sampleSource(uv + vec2(-1.0,  1.0) * inverseExtent);
    vec3 southEast = sampleSource(uv + vec2( 1.0,  1.0) * inverseExtent);
    vec3 luma = vec3(0.299, 0.587, 0.114);
    float lumaCenter = dot(center, luma);
    float lumaMin = min(lumaCenter, min(min(dot(northWest, luma), dot(northEast, luma)), min(dot(southWest, luma), dot(southEast, luma))));
    float lumaMax = max(lumaCenter, max(max(dot(northWest, luma), dot(northEast, luma)), max(dot(southWest, luma), dot(southEast, luma))));
    vec2 direction;
    direction.x = -((dot(northWest + northEast, luma)) - (dot(southWest + southEast, luma)));
    direction.y =  ((dot(northWest + southWest, luma)) - (dot(northEast + southEast, luma)));
    float reduce = max((dot(northWest + northEast + southWest + southEast, luma) * 0.25) * 0.125, 0.0078125);
    float reciprocal = 1.0 / (min(abs(direction.x), abs(direction.y)) + reduce);
    direction = clamp(direction * reciprocal, vec2(-8.0, -8.0), vec2(8.0, 8.0)) * inverseExtent;
    vec3 resultA = 0.5 * (
        sampleSource(uv + direction * (1.0 / 3.0 - 0.5))
        + sampleSource(uv + direction * (2.0 / 3.0 - 0.5))
    );
    vec3 resultB = resultA * 0.5 + 0.25 * (
        sampleSource(uv + direction * -0.5)
        + sampleSource(uv + direction * 0.5)
    );
    float lumaB = dot(resultB, luma);
    gl_FragColor = vec4(lumaB < lumaMin || lumaB > lumaMax ? resultA : resultB, 1.0);
}
