#include <bgfx_shader.sh>

uniform vec4 u_waterFormulaProbe[6];
uniform vec4 u_waterSunProbe;
uniform vec4 u_sceneFrame[18];
#include "scene_vertex_diffuse.sh"

void main()
{
    v_actorAmbientData = vec3(u_waterFormulaProbe[5].x > 0.5 ? u_waterFormulaProbe[5].y : 1.0,
        u_waterFormulaProbe[5].z, u_waterFormulaProbe[5].w);
    gl_Position = vec4(a_position, 1.0);
    gl_Position.z += u_waterFormulaProbe[1].w;
    v_texcoord0 = vec2(0.05, 0.25) + u_waterFormulaProbe[1].yz
        + a_position.xy * u_waterFormulaProbe[4].xy;
    v_texcoord1 = vec4(0.25, 0.25, 0.0, 0.0);
    v_texcoord2 = vec4(u_waterFormulaProbe[2].xyz, -1.0);
    v_texcoord3 = vec4(u_waterFormulaProbe[0].zw, -1.0, 1.0);
    v_texcoord4 = vec4(0.0, 0.0, 1.0, 0.0);
    v_texcoord5 = u_waterFormulaProbe[3];
    v_texcoord6 = vec4(u_waterFormulaProbe[0].xy * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
    v_texcoord7 = vec4(0.0, 0.0, u_waterFormulaProbe[2].w, 1.0);
    v_color0 = vec4(1.0, 1.0, 1.0, 1.0);
    if (u_waterFormulaProbe[4].z > 0.5)
        v_color0 = vec4(0.25, 0.5, 0.75, u_waterFormulaProbe[4].w);
    v_waterEye = vec4(0.0, 0.0, 0.0, 0.0);
    v_waterLight = vec3(0.0, 0.0, 0.0);
    v_softDepth = vec3(0.0, 0.0, 0.0);
    if (u_waterFormulaProbe[1].x > 0.5)
    {
        v_waterEye = vec4(v_texcoord3.xyz, 1.0);
        v_waterLight = vec3(0.0, 1.0, 0.0);
    }
    if (u_waterFormulaProbe[3].z == -2.0)
        v_waterEye = vec4(normalize(vec3(0.8, 0.3, -0.1)), 2.0);
    v_nativeDiffuse = v_color0;
    v_nativeSunColor = u_waterSunProbe;
    v_nativeSunNormal = vec3(0.0, 0.0, 1.0);
    v_nativeSunDirection = vec3(0.0, 0.0, 1.0);
#if defined(WATER_FORMULA_MAPPED)
    v_nativeLightmap0 = vec4(0.0, 0.0, 0.0, v_texcoord0.x);
    v_nativeLightmap1 = vec4(0.0, 0.0, 0.0, v_texcoord0.y);
    v_nativeLightmap2 = vec3_splat(0.0);
#endif
    if (v_actorAmbientData.y > 0.5)
        v_nativeDiffuse = sceneVertexDiffuse(v_actorAmbientData, vec4(v_texcoord2.rgb, max(-u_waterFormulaProbe[1].x, 0.0)),
            v_color0, v_texcoord7.z > 0.5, v_texcoord5.w > 4.5 && v_texcoord5.w < 5.5, u_sceneFrame[9].rgb, u_sceneFrame[13].rgb,
            u_sceneFrame[8].rgb, u_sceneFrame[15].w, 0.0);
    v_tangent = vec4(1.0, 0.0, 0.0, 1.0);
}
