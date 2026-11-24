float fsrLuma(vec3 color)
{
    return color.b * 0.5 + color.r * 0.5 + color.g;
}

void fsrEasuSet(
    inout vec2 direction,
    inout float edgeLength,
    float weight,
    float lumaA,
    float lumaB,
    float lumaC,
    float lumaD,
    float lumaE)
{
    float dc = lumaD - lumaC;
    float cb = lumaC - lumaB;
    float lengthX = max(abs(dc), abs(cb));
    float directionX = lumaD - lumaB;
    direction.x += directionX * weight;
    lengthX = clamp(abs(directionX) / max(lengthX, 1.0e-8), 0.0, 1.0);
    edgeLength += lengthX * lengthX * weight;

    float ec = lumaE - lumaC;
    float ca = lumaC - lumaA;
    float lengthY = max(abs(ec), abs(ca));
    float directionY = lumaE - lumaA;
    direction.y += directionY * weight;
    lengthY = clamp(abs(directionY) / max(lengthY, 1.0e-8), 0.0, 1.0);
    edgeLength += lengthY * lengthY * weight;
}

void fsrEasuTap(
    inout vec3 accumulatedColor,
    inout float accumulatedWeight,
    vec2 offset,
    vec2 direction,
    vec2 anisotropicLength,
    float lobe,
    float clippingPoint,
    vec3 color)
{
    vec2 rotated;
    rotated.x = offset.x * direction.x + offset.y * direction.y;
    rotated.y = offset.x * -direction.y + offset.y * direction.x;
    rotated *= anisotropicLength;
    float distanceSquared = min(dot(rotated, rotated), clippingPoint);
    float windowBase = (2.0 / 5.0) * distanceSquared - 1.0;
    float windowLobe = lobe * distanceSquared - 1.0;
    windowBase *= windowBase;
    windowLobe *= windowLobe;
    windowBase = (25.0 / 16.0) * windowBase - ((25.0 / 16.0) - 1.0);
    float weight = windowBase * windowLobe;
    accumulatedColor += color * weight;
    accumulatedWeight += weight;
}

vec3 fsrEasu(vec2 outputPixel, vec4 con0)
{
    vec2 sourcePosition = outputPixel * con0.xy + con0.zw;
    vec2 sourceFloor = floor(sourcePosition);
    vec2 fractionalPosition = sourcePosition - sourceFloor;

    vec3 b = fsrEasuSample(sourceFloor + vec2( 0.0, -1.0));
    vec3 c = fsrEasuSample(sourceFloor + vec2( 1.0, -1.0));
    vec3 e = fsrEasuSample(sourceFloor + vec2(-1.0,  0.0));
    vec3 f = fsrEasuSample(sourceFloor + vec2( 0.0,  0.0));
    vec3 g = fsrEasuSample(sourceFloor + vec2( 1.0,  0.0));
    vec3 h = fsrEasuSample(sourceFloor + vec2( 2.0,  0.0));
    vec3 i = fsrEasuSample(sourceFloor + vec2(-1.0,  1.0));
    vec3 j = fsrEasuSample(sourceFloor + vec2( 0.0,  1.0));
    vec3 k = fsrEasuSample(sourceFloor + vec2( 1.0,  1.0));
    vec3 l = fsrEasuSample(sourceFloor + vec2( 2.0,  1.0));
    vec3 n = fsrEasuSample(sourceFloor + vec2( 0.0,  2.0));
    vec3 o = fsrEasuSample(sourceFloor + vec2( 1.0,  2.0));

    float bL = fsrLuma(b);
    float cL = fsrLuma(c);
    float eL = fsrLuma(e);
    float fL = fsrLuma(f);
    float gL = fsrLuma(g);
    float hL = fsrLuma(h);
    float iL = fsrLuma(i);
    float jL = fsrLuma(j);
    float kL = fsrLuma(k);
    float lL = fsrLuma(l);
    float nL = fsrLuma(n);
    float oL = fsrLuma(o);

    float oneMinusX = 1.0 - fractionalPosition.x;
    float oneMinusY = 1.0 - fractionalPosition.y;
    vec2 direction = vec2_splat(0.0);
    float edgeLength = 0.0;
    fsrEasuSet(direction, edgeLength, oneMinusX * oneMinusY, bL, eL, fL, gL, jL);
    fsrEasuSet(direction, edgeLength, fractionalPosition.x * oneMinusY, cL, fL, gL, hL, kL);
    fsrEasuSet(direction, edgeLength, oneMinusX * fractionalPosition.y, fL, iL, jL, kL, nL);
    fsrEasuSet(direction, edgeLength, fractionalPosition.x * fractionalPosition.y, gL, jL, kL, lL, oL);

    float directionSquared = dot(direction, direction);
    if (directionSquared < (1.0 / 32768.0)) {
        direction = vec2(1.0, 0.0);
    } else {
        direction *= inversesqrt(directionSquared);
    }
    edgeLength = edgeLength * 0.5;
    edgeLength *= edgeLength;
    float stretch = dot(direction, direction) / max(abs(direction.x), abs(direction.y));
    vec2 anisotropicLength = vec2(
        1.0 + (stretch - 1.0) * edgeLength,
        1.0 - 0.5 * edgeLength
    );
    float lobe = 0.5 + ((0.25 - 0.04) - 0.5) * edgeLength;
    float clippingPoint = 1.0 / lobe;

    vec3 minimum4 = min(min(f, g), min(j, k));
    vec3 maximum4 = max(max(f, g), max(j, k));
    vec3 accumulatedColor = vec3_splat(0.0);
    float accumulatedWeight = 0.0;
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 0.0, -1.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, b);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 1.0, -1.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, c);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2(-1.0,  1.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, i);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 0.0,  1.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, j);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 0.0,  0.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, f);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2(-1.0,  0.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, e);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 1.0,  1.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, k);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 2.0,  1.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, l);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 2.0,  0.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, h);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 1.0,  0.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, g);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 1.0,  2.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, o);
    fsrEasuTap(accumulatedColor, accumulatedWeight, vec2( 0.0,  2.0) - fractionalPosition, direction, anisotropicLength, lobe, clippingPoint, n);
    return clamp(accumulatedColor / accumulatedWeight, minimum4, maximum4);
}
