$input a_position, a_color0, a_texcoord1, a_texcoord2
$output v_color0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

uniform vec4 u_geodataLineView;

void main()
{
    vec4 clip = mul(u_viewProj, vec4(a_position, 1.0));
    float halfWidth = a_texcoord2.x * 0.5;
    vec2 offset;
    if (a_texcoord2.z > 0.5)
    {
        offset = vec2(a_texcoord1.w, a_texcoord2.w) * halfWidth;
    }
    else
    {
        vec4 other = mul(u_viewProj, vec4(a_texcoord1.xyz, 1.0));
        vec2 here = clip.xy / max(clip.w, 0.0001) / u_geodataLineView.xy;
        vec2 there = other.xy / max(other.w, 0.0001) / u_geodataLineView.xy;
        vec2 direction = there - here;
        float span = sqrt(dot(direction, direction));
        direction = span > 0.00001 ? direction / span : vec2(1.0, 0.0);
        float flip = a_texcoord2.w > 0.5 ? -1.0 : 1.0;
        offset = vec2(-direction.y, direction.x) * a_texcoord1.w * flip * halfWidth;
    }
    gl_Position = clip + vec4(offset * u_geodataLineView.xy * clip.w, 0.0, 0.0);
    v_color0 = a_color0;
    v_texcoord1 = vec4(a_texcoord2.y, a_texcoord1.w, a_texcoord2.x, a_texcoord2.z);
    v_texcoord2 = vec4(a_texcoord1.w, a_texcoord2.w, 0.0, 0.0);
}
