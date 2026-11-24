float nativeShaderPower(float value, float exponent)
{
    float magnitude = abs(value);
    float logarithm = magnitude != 0.0 ? log2(magnitude) : -3.402823466e38;
    return exp2(logarithm * exponent);
}
