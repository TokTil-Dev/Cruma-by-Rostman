$input v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_texcoord5, v_texcoord6, v_texcoord7, v_color0, v_tangent, v_waterEye, v_waterLight, v_softDepth, v_actorAmbientData, v_nativeDiffuse, v_nativeSunColor, v_nativeSunNormal, v_nativeSunDirection

#include "hover.sh"

void main()
{
    gl_FragColor = hoverColor(v_texcoord3.xyz, gl_FragCoord);
}
