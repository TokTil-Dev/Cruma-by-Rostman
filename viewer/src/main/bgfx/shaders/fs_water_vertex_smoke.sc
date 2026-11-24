$input v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_texcoord5, v_texcoord6, v_texcoord7, v_color0, v_tangent, v_waterEye, v_waterLight, v_softDepth, v_actorAmbientData, v_nativeDiffuse, v_nativeSunColor, v_nativeSunNormal, v_nativeSunDirection

#include <bgfx_shader.sh>
#include "native_static_mesh_sun.sh"
uniform vec4 u_sceneFrame[18];

void main()
{
    gl_FragData[0] = v_waterEye;
    gl_FragData[1] = vec4(v_waterLight, v_nativeSunColor.a);
    gl_FragData[2] = nativeStaticMeshSunDiffuse(v_nativeDiffuse, v_nativeSunColor.rgb,
        v_nativeSunNormal, v_nativeSunDirection);
}
