vec3 packHfViewDepth(float value)
{
    float depth = clamp(value, 0.0, 65535.99);
    float integerDepth = floor(depth);
    float high = floor(integerDepth / 256.0);
    float low = integerDepth - high * 256.0;
    return vec3(high / 255.0, low / 255.0, depth - integerDepth);
}

float unpackHfViewDepth(vec3 packed)
{
    return packed.r * 255.0 * 256.0 + packed.g * 255.0 + packed.b;
}
