#ifndef CRUMA_BITMAP_SAMPLING_SH
#define CRUMA_BITMAP_SAMPLING_SH

#if BGFX_SHADER_LANGUAGE_HLSL > 300 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL
#define bitmapArrayBias(_sampler, _coord, _bias) \
    (_sampler).m_texture.SampleBias((_sampler).m_sampler, (_coord), (_bias))
#else
#define bitmapArrayBias(_sampler, _coord, _bias) texture((_sampler), (_coord), (_bias))
#endif

#endif
