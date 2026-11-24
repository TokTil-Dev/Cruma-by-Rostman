$input a_position, a_texcoord0, i_data0, i_data1, i_data2, i_data3
$output v_texcoord0

#include <bgfx_shader.sh>
#include "static_mesh_billboard.sh"
#include "tex_rotator.sh"

uniform vec4 u_shadowMaterial[4];

vec2 shadowUv(vec2 uv)
{
    float mode = u_shadowMaterial[1].x;
    float time = u_shadowMaterial[1].y;
    if (mode == 1.0)
    {
        uv += u_shadowMaterial[1].zw * time;
    }
    else if (mode == 2.0)
    {
        uv = texRotatorYawUv(uv, u_shadowMaterial[1].zw, u_shadowMaterial[2].x * time);
    }
    else if (mode == 3.0)
    {
        uv.x += u_shadowMaterial[1].w
            * sin(6.2831853 * u_shadowMaterial[1].z * time);
        uv.y += u_shadowMaterial[2].y
            * sin(6.2831853 * u_shadowMaterial[2].x * time);
    }
    else if (mode == 4.0)
    {
        uv = uv / u_shadowMaterial[1].zw + u_shadowMaterial[2].xy;
    }
    else if (mode == 5.0)
    {
        uv = texRotatorYawUv(
            uv, vec2(u_shadowMaterial[1].w, u_shadowMaterial[2].y),
            u_shadowMaterial[2].x * sin(6.2831853 * u_shadowMaterial[1].z * time)
        );
    }
    return uv;
}

void main()
{
    vec4 metadata = vec4(i_data0.w, i_data1.w, i_data2.w, i_data3.w);
    mat4 model = mtxFromCols(
        vec4(i_data0.xyz, 0.0), vec4(i_data1.xyz, 0.0),
        vec4(i_data2.xyz, 0.0), vec4(i_data3.xyz, 1.0)
    );
    vec4 world = mul(model, vec4(a_position, 1.0));
    if (staticMeshUsesBillboard(metadata))
    {
        world = staticMeshBillboardWorldPosition(
            a_position, i_data0, i_data1, i_data2, i_data3,
            staticMeshBillboardTowardView(u_view), staticMeshBillboardTilt(metadata)
        );
    }
    gl_Position = mul(u_viewProj, world);
    v_texcoord0 = shadowUv(a_texcoord0);
}
