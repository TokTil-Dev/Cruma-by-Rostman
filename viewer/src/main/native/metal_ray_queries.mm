#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>

#include <dlfcn.h>
#include <mach-o/dyld.h>
#include <algorithm>
#include <atomic>
#include <bit>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <unordered_set>
#include <unordered_map>
#include <vector>

#define CRUMA_METAL_EXPORT extern "C" __attribute__((visibility("default")))

namespace {
constexpr uint32_t kAbiVersion = 18;
constexpr size_t kBlasBatchSize = 96;
constexpr uint32_t kVisibilitySlots = 4;

using BgfxEnqueueSharedEventFence = uint32_t (*)(
    void*, uint64_t, void*, uint64_t
);

static BgfxEnqueueSharedEventFence resolveBgfxSharedEventHook() {
    for (uint32_t index = 0; index < _dyld_image_count(); ++index) {
        const char* path = _dyld_get_image_name(index);
        if (path == nullptr || std::strstr(path, "/libbgfx.dylib") == nullptr) continue;
        void* image = dlopen(path, RTLD_LAZY | RTLD_NOLOAD);
        if (image == nullptr) continue;
        auto hook = reinterpret_cast<BgfxEnqueueSharedEventFence>(
            dlsym(image, "cruma_bgfx_metal_enqueue_shared_event_fence")
        );
        dlclose(image);
        if (hook != nullptr) return hook;
    }
    return nullptr;
}

static const char* kVisibilityShader = R"METAL(
#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace raytracing;

struct QueryParams {
    float4x4 inverseViewProjection;
    float4 rayBasis[4];
    float4 camera;
    float4 sun;
    float4 extent;
    float4 temporal;
};

struct PrimitiveData {
    float4 normal;
    const device uchar* alpha;
    float2 uv0;
    float2 uv1;
    float2 uv2;
    uint alphaWidth;
    uint alphaHeight;
    uint alphaFlags;
    float alphaCutoff;
};

struct InstanceData {
    float4 actorAndDistances;
    uint lodLevel;
    uint flags;
    const device float4* giAlbedo;
};

float addressCoordinate(float value, bool clampAxis)
{
    return clampAxis ? clamp(value, 0.0f, 1.0f) : fract(value);
}

uint addressTexel(int value, uint size, bool clampAxis)
{
    if (clampAxis) return uint(clamp(value, 0, int(size) - 1));
    int wrapped = value % int(size);
    return uint(wrapped < 0 ? wrapped + int(size) : wrapped);
}

float sampleAlpha(const device PrimitiveData* data, float2 uv)
{
    if (data->alpha == nullptr) return 1.0f;
    bool clampU = (data->alphaFlags & 1u) != 0u;
    bool clampV = (data->alphaFlags & 2u) != 0u;
    float2 addressed = float2(
        addressCoordinate(uv.x, clampU), addressCoordinate(uv.y, clampV)
    );
    float2 texel = addressed * float2(data->alphaWidth, data->alphaHeight) - 0.5f;
    int2 base = int2(floor(texel));
    float2 fraction = fract(texel);
    uint x0 = addressTexel(base.x, data->alphaWidth, clampU);
    uint y0 = addressTexel(base.y, data->alphaHeight, clampV);
    uint x1 = addressTexel(base.x + 1, data->alphaWidth, clampU);
    uint y1 = addressTexel(base.y + 1, data->alphaHeight, clampV);
    float a00 = float(data->alpha[y0 * data->alphaWidth + x0]) / 255.0f;
    float a10 = float(data->alpha[y0 * data->alphaWidth + x1]) / 255.0f;
    float a01 = float(data->alpha[y1 * data->alphaWidth + x0]) / 255.0f;
    float a11 = float(data->alpha[y1 * data->alphaWidth + x1]) / 255.0f;
    return mix(mix(a00, a10, fraction.x), mix(a01, a11, fraction.x), fraction.y);
}

bool lodPass(const device InstanceData* instance, float2 camera)
{
    if ((instance->flags & 1u) == 0u) return true;
    float distance1 = instance->actorAndDistances.z * 4.0f;
    float distance2 = instance->actorAndDistances.w * 4.0f;
    float distanceToActor = distance(camera, instance->actorAndDistances.xy);
    if (instance->lodLevel == 0u) return distanceToActor <= distance1;
    if (instance->lodLevel == 1u) {
        bool beforeTerminal = distance2 <= distance1 || distanceToActor <= distance2;
        return distanceToActor > distance1 && beforeTerminal;
    }
    return distanceToActor > distance2;
}

bool candidatePass(
    const device PrimitiveData* data,
    const device InstanceData* instances,
    uint userInstanceId,
    float2 camera,
    float2 barycentric)
{
    if (!lodPass(instances + userInstanceId, camera)) return false;
    float3 weights = float3(
        1.0f - barycentric.x - barycentric.y, barycentric.x, barycentric.y
    );
    float2 uv = data->uv0 * weights.x + data->uv1 * weights.y + data->uv2 * weights.z;
    return sampleAlpha(data, uv) >= data->alphaCutoff;
}

float signNotZero(float value)
{
    return value >= 0.0f ? 1.0f : -1.0f;
}

float encodeWorldNormal(float3 normal)
{
    float denominator = max(abs(normal.x) + abs(normal.y) + abs(normal.z), 0.000001f);
    float3 unitL1 = normal / denominator;
    float2 octahedral = unitL1.xy;
    if (unitL1.z < 0.0f) {
        octahedral = (1.0f - abs(octahedral.yx)) * float2(
            signNotZero(octahedral.x), signNotZero(octahedral.y)
        );
    }
    uint2 quantized = uint2(round(clamp(octahedral * 0.5f + 0.5f, 0.0f, 1.0f) * 31.0f));
    return float(1u + quantized.x + quantized.y * 32u);
}

kernel void crumaVisibility(
    uint2 pixel [[thread_position_in_grid]],
    instance_acceleration_structure scene [[buffer(0)]],
    constant QueryParams& params [[buffer(1)]],
    const device InstanceData* instances [[buffer(2)]],
    texture2d<float, access::write> output [[texture(0)]])
{
    uint width = uint(params.extent.x);
    uint height = uint(params.extent.y);
    if (pixel.x >= width || pixel.y >= height) return;

    float2 uv = (float2(pixel) + 0.5f) / float2(width, height);
    float2 ndc = float2(uv.x * 2.0f - 1.0f, 1.0f - uv.y * 2.0f);
    float3 viewDirection = float3(
        ndc.x / params.rayBasis[3].x,
        ndc.y / params.rayBasis[3].y,
        1.0f
    );
    float3 rayDirection = normalize(
        params.rayBasis[0].xyz * viewDirection.x
            + params.rayBasis[1].xyz * viewDirection.y
            + params.rayBasis[2].xyz
    );

    ray primary;
    primary.origin = params.camera.xyz;
    primary.direction = rayDirection;
    primary.min_distance = max(params.extent.z, 1.0f);
    primary.max_distance = params.camera.w;
    intersection_query<triangle_data, instancing> closest;
    intersection_params closestParams;
    closestParams.assume_geometry_type(geometry_type::triangle);
    closest.reset(primary, scene, closestParams);
    while (closest.next()) {
        if (closest.get_candidate_intersection_type() != intersection_type::triangle) continue;
        const device PrimitiveData* candidate =
            (const device PrimitiveData*)closest.get_candidate_primitive_data();
        if (candidatePass(
            candidate, instances, closest.get_candidate_user_instance_id(), params.camera.xy,
            closest.get_candidate_triangle_barycentric_coord()
        )) {
            closest.commit_triangle_intersection();
        }
    }
    if (closest.get_committed_intersection_type() == intersection_type::none) {
        output.write(float4(1.0f, 1.0f, 0.0f, 0.0f), pixel);
        return;
    }

    float hitDistance = closest.get_committed_distance();
    float3 hitPosition = primary.origin + primary.direction * hitDistance;

    const device PrimitiveData* primitive =
        (const device PrimitiveData*)closest.get_committed_primitive_data();
    float3 localNormal = primitive->normal.xyz;
    float3x3 worldToObject = float3x3(
        closest.get_committed_world_to_object_transform()[0],
        closest.get_committed_world_to_object_transform()[1],
        closest.get_committed_world_to_object_transform()[2]
    );
    float3 normal = normalize(transpose(worldToObject) * localNormal);
    if (dot(normal, primary.direction) > 0.0f) normal = -normal;
    float3 sunDirection = normalize(params.sun.xyz);
    float3 sunHelper = abs(sunDirection.z) < 0.92f ? float3(0.0f, 0.0f, 1.0f)
        : float3(0.0f, 1.0f, 0.0f);
    float3 sunTangent = normalize(cross(sunHelper, sunDirection));
    float3 sunBitangent = cross(sunDirection, sunTangent);
    float3 biasNormal = dot(normal, sunDirection) >= 0.0f ? normal : -normal;
    float sunVisible = 1.0f;
    constexpr uint sunSamples = 8u;
    constexpr float solarHalfAngle = 0.00465f;
    constexpr float goldenAngle = 2.39996322973f;
    constexpr float fullRotation = 6.28318530718f;
    sunVisible = 0.0f;
    for (uint sample = 0u; sample < sunSamples; ++sample) {
        float radius = sqrt((float(sample) + 0.5f) / float(sunSamples));
        float angle = float(sample) * goldenAngle + params.temporal.x * fullRotation;
        float2 disk = radius * float2(cos(angle), sin(angle));
        float3 direction = normalize(
            sunDirection + solarHalfAngle
                * (sunTangent * disk.x + sunBitangent * disk.y)
        );
        ray sunRay;
        sunRay.origin = hitPosition + biasNormal * 10.0f + direction * 4.0f;
        sunRay.direction = direction;
        sunRay.min_distance = 6.0f;
        sunRay.max_distance = params.sun.w;
        intersection_query<triangle_data, instancing> sunHit;
        intersection_params sunParams;
        sunParams.assume_geometry_type(geometry_type::triangle);
        sunParams.accept_any_intersection(true);
        sunHit.reset(sunRay, scene, sunParams);
        while (sunHit.next()) {
            if (sunHit.get_candidate_intersection_type() != intersection_type::triangle) continue;
            const device PrimitiveData* candidate =
                (const device PrimitiveData*)sunHit.get_candidate_primitive_data();
            if (candidatePass(
                candidate, instances, sunHit.get_candidate_user_instance_id(), params.camera.xy,
                sunHit.get_candidate_triangle_barycentric_coord()
            )) {
                sunHit.commit_triangle_intersection();
            }
        }
        sunVisible += sunHit.get_committed_intersection_type() == intersection_type::none
            ? 1.0f : 0.0f;
    }
    sunVisible /= float(sunSamples);
    output.write(float4(
        sunVisible, 1.0f,
        clamp(hitDistance / max(params.camera.w, 1.0f), 0.0f, 1.0f),
        encodeWorldNormal(normal)
    ), pixel);
}

struct GiParams {
    float4 origin, sun, energy, sky, camera, transport;
    float4 lights[48];
};
float3 giAxis(uint face) {
    if (face == 0) return float3(1,0,0);
    if (face == 1) return float3(-1,0,0);
    if (face == 2) return float3(0,1,0);
    if (face == 3) return float3(0,-1,0);
    if (face == 4) return float3(0,0,1);
    return float3(0,0,-1);
}
bool giCandidate(const device PrimitiveData* data, const device InstanceData* instances, uint id, float2 bary) {
    return candidatePass(data, instances, id, instances[id].actorAndDistances.xy, bary);
}
struct GiHit { bool hit; float distance; float3 normal, albedo, emission; };
GiHit giTrace(instance_acceleration_structure scene, const device InstanceData* instances, ray r) {
    intersection_query<triangle_data, instancing> hit;
    intersection_params options; options.assume_geometry_type(geometry_type::triangle);
    hit.reset(r,scene,options);
    while(hit.next()) {
        if(hit.get_candidate_intersection_type()!=intersection_type::triangle) continue;
        auto data=(const device PrimitiveData*)hit.get_candidate_primitive_data();
        if(giCandidate(data,instances,hit.get_candidate_user_instance_id(),hit.get_candidate_triangle_barycentric_coord()))
            hit.commit_triangle_intersection();
    }
    GiHit result; result.hit=hit.get_committed_intersection_type()!=intersection_type::none;
    result.distance=result.hit ? hit.get_committed_distance() : r.max_distance;
    result.normal=result.albedo=result.emission=float3(0);
    if(result.hit) {
        auto data=(const device PrimitiveData*)hit.get_committed_primitive_data();
        float3x3 inverse=float3x3(hit.get_committed_world_to_object_transform()[0],
            hit.get_committed_world_to_object_transform()[1],hit.get_committed_world_to_object_transform()[2]);
        result.normal=normalize(transpose(inverse)*data->normal.xyz);
        if(dot(result.normal,r.direction)>0) result.normal=-result.normal;
        const device float4* colors=instances[hit.get_committed_user_instance_id()].giAlbedo;
        if(colors!=nullptr) {
            float4 albedo=colors[hit.get_committed_primitive_id()*2];
            result.albedo=albedo.rgb*albedo.w;
            result.emission=colors[hit.get_committed_primitive_id()*2+1].rgb;
        }
    }
    return result;
}
bool giVisible(instance_acceleration_structure scene, const device InstanceData* instances,
               float3 origin, float3 direction, float distance) {
    ray r; r.origin=origin; r.direction=direction; r.min_distance=1; r.max_distance=distance;
    return !giTrace(scene,instances,r).hit;
}
float3 giEnergy(float3 color) {
    float peak=max(0.0f,max(color.x,max(color.y,color.z)));
    if(peak==0) return float3(0);
    float3 c=saturate(color/peak);
    return select(pow((c+0.055f)/1.055f,float3(2.4f)),c/12.92f,c<=0.04045f)*peak;
}
uint giHash(uint value) {
    value ^= value >> 16; value *= 0x7feb352du; value ^= value >> 15;
    value *= 0x846ca68bu; return value ^ (value >> 16);
}
float3 giDiffuseDirection(float3 normal, uint seed) {
    float u=(float(giHash(seed))+0.5f)/4294967296.0f;
    float v=(float(giHash(seed ^ 0x68bc21ebu))+0.5f)/4294967296.0f;
    float3 tangent=normalize(cross(abs(normal.z)<0.9f ? float3(0,0,1) : float3(0,1,0),normal));
    return tangent*(sqrt(u)*cos(6.283185307f*v)) + cross(normal,tangent)*(sqrt(u)*sin(6.283185307f*v))
        + normal*sqrt(max(0.0f,1-u));
}
kernel void crumaGi(uint localProbe [[thread_position_in_grid]],
    instance_acceleration_structure scene [[buffer(0)]], constant GiParams& params [[buffer(1)]],
    const device InstanceData* instances [[buffer(2)]], device float4* result [[buffer(3)]]) {
    uint probe=localProbe+uint(params.camera.z);
    if(probe>=864u) return;
    uint3 grid=uint3(probe%12u,(probe/12u)%12u,probe/144u);
    float3 origin=params.origin.xyz+float3(grid)*params.origin.w;
    uint seed=giHash(as_type<uint>(origin.x)^giHash(as_type<uint>(origin.y))^giHash(as_type<uint>(origin.z)));
    float3 sum[6]; float weight[6],first[6],second[6];
    for(uint f=0;f<6;++f) {sum[f]=float3(0);weight[f]=first[f]=second[f]=0;}
    constexpr uint count=96; uint nearHits=0;
    uint bounces=clamp(uint(params.transport.x),1u,3u);
    for(uint i=0;i<count;++i) {
        float z=1.0f-2.0f*(float(i)+0.5f)/float(count), angle=float(i)*2.399963229728653f;
        float radius=sqrt(max(0.0f,1-z*z));
        float3 direction=float3(cos(angle)*radius,sin(angle)*radius,z);
        ray r; r.origin=origin; r.direction=direction; r.min_distance=1; r.max_distance=params.sun.w;
        float distance=r.max_distance;
        float3 radiance=float3(0),throughput=float3(1);
        for(uint bounce=0;bounce<bounces;++bounce) {
            auto hit=giTrace(scene,instances,r);
            if(bounce==0) {distance=hit.distance;nearHits+=hit.hit && distance<8 ? 1u:0u;}
            if(!hit.hit) {radiance+=throughput*params.sky.rgb;break;}
            radiance+=throughput*hit.emission;
            if(max(hit.albedo.x,max(hit.albedo.y,hit.albedo.z))<0.00001f) break;
            float3 point=r.origin+r.direction*hit.distance+hit.normal*2;
            float cosine=max(dot(hit.normal,params.sun.xyz),0.0f);
            float3 direct=float3(0);
            if(cosine>0 && giVisible(scene,instances,point,params.sun.xyz,params.sun.w))
                direct+=params.energy.rgb*cosine;
            for(uint light=0;light<min(uint(params.transport.y),16u);++light) {
                float4 position=params.lights[light],color=params.lights[16+light],spot=params.lights[32+light];
                float3 toLight=position.xyz-point; float d=length(toLight);
                if(d>=position.w || d<1) continue;
                float3 lightDirection=toLight/d;
                float lambert=max(dot(hit.normal,lightDirection),0.0f);
                float radial=1-d/max(position.w,1.0f),cone=1;
                if(spot.w>=0) {cone=saturate((dot(-lightDirection,spot.xyz)-spot.w)/max(1-spot.w,0.0001f));cone*=cone;}
                if(lambert>0 && cone>0 && giVisible(scene,instances,point,lightDirection,max(1.0f,d-2)))
                    direct+=giEnergy(color.rgb)*color.w*radial*radial*cone*lambert;
            }
            radiance+=throughput*hit.albedo*direct;
            throughput*=hit.albedo;
            r.origin=point; r.direction=giDiffuseDirection(hit.normal,seed^(i*747796405u)^(bounce*2891336453u));
        }
        distance=min(distance/params.origin.w,8.0f);
        for(uint f=0;f<6;++f) {
            float cosine=max(dot(direction,giAxis(f)),0.0f);
            sum[f]+=radiance*cosine*(4.0f/float(count));
            float w=pow(cosine,16.0f);weight[f]+=w;first[f]+=distance*w;second[f]+=distance*distance*w;
        }
    }
    float valid=nearHits>count/4 ? 0.0f:1.0f;
    for(uint f=0;f<6;++f) {
        result[probe*12+f]=float4(sum[f],valid);
        result[probe*12+6+f]=float4(first[f]/max(weight[f],0.0001f),second[f]/max(weight[f],0.0001f),0,valid);
    }
}

)METAL";

enum class BuildState : uint32_t {
    Collecting = 0,
    BuildingBlas = 1,
    BuildingTlas = 2,
    Ready = 3,
    Failed = 4,
};

struct Geometry {
    uint64_t stableId = 0;
    id<MTLBuffer> vertices = nil;
    id<MTLBuffer> indices = nil;
    id<MTLBuffer> primitiveData = nil;
    id<MTLBuffer> giAlbedo = nil;
    id<MTLBuffer> alphaData = nil;
    id<MTLAccelerationStructure> blas = nil;
    uint32_t vertexCount = 0;
    uint32_t indexCount = 0;
    bool hasAlpha = false;
    simd_float3 localMin = simd_make_float3(FLT_MAX, FLT_MAX, FLT_MAX);
    simd_float3 localMax = simd_make_float3(-FLT_MAX, -FLT_MAX, -FLT_MAX);
};

struct alignas(16) PrimitiveDataCpu {
    simd_float4 normal;
    uint64_t alphaAddress;
    simd_float2 uv0;
    simd_float2 uv1;
    simd_float2 uv2;
    uint32_t alphaWidth;
    uint32_t alphaHeight;
    uint32_t alphaFlags;
    float alphaCutoff;
};
static_assert(sizeof(PrimitiveDataCpu) == 64, "Metal primitive-data ABI drift");

struct Placement {
    uint64_t ownerId = 0;
    uint64_t stableId = 0;
    uint64_t geometryId = 0;
    std::vector<float> matrices;
    int32_t lodLevel = -1;
    float lodDistance1 = 0.0f;
    float lodDistance2 = 0.0f;
};

struct alignas(16) InstanceDataCpu {
    simd_float4 actorAndDistances;
    uint32_t lodLevel;
    uint32_t flags;
    uint64_t giAlbedo;
};
static_assert(sizeof(InstanceDataCpu) == 32, "Metal instance-data ABI drift");

struct alignas(16) QueryParams {
    simd_float4x4 inverseViewProjection;
    simd_float4 rayBasis[4];
    simd_float4 camera;
    simd_float4 sun;
    simd_float4 extent;
    simd_float4 temporal;
};

struct RayQueryContext {
    id<MTLDevice> device = nil;
    id<MTLCommandQueue> queue = nil;
    std::unordered_map<uint64_t, Geometry> geometries;
    std::unordered_map<uint64_t, Placement> placements;
    std::vector<uint64_t> buildGeometryIds;
    std::vector<uint64_t> activeBlasBatch;
    std::vector<id<MTLResource>> blasTable;
    id<MTLAccelerationStructure> tlas = nil;
    id<MTLBuffer> instanceDescriptors = nil;
    id<MTLBuffer> instanceData = nil;
    std::vector<uint64_t> instanceGeometryIds;
    bool giBindingsDirty = true;
    id<MTLCommandBuffer> buildCommand = nil;
    std::vector<id<MTLCommandBuffer>> blasBuildCommands;
    id<MTLComputePipelineState> visibilityPipeline = nil;
    id<MTLComputePipelineState> giPipeline = nil;
    id<MTLBuffer> giParams = nil, giResult = nil;
    id<MTLCommandBuffer> giCommand = nil;
    id<MTLSharedEvent> visibilityEvent = nil;
    BgfxEnqueueSharedEventFence enqueueBgfxFence = nullptr;
    id<MTLTexture> visibilityTextures[kVisibilitySlots] = {};
    id<MTLSharedEvent> consumptionEvents[kVisibilitySlots] = {};
    std::vector<id<MTLTexture>> retiredTextures;
    id<MTLBuffer> queryParams[kVisibilitySlots] = {};
    simd_float4x4 queryViewProjections[kVisibilitySlots];
    id<MTLCommandBuffer> queryCommands[kVisibilitySlots] = {};
    uint64_t queryEventValues[kVisibilitySlots] = {};
    uint64_t consumptionWaitValues[kVisibilitySlots] = {};
    uint64_t querySerials[kVisibilitySlots] = {};
    uint64_t nextEventValue = 1;
    uint64_t nextConsumptionValue = 1;
    uint64_t nextQuerySerial = 1;
    uint32_t nextQuerySlot = 0;
    uint32_t pendingArmSlot = UINT32_MAX;
    uint32_t publishedSlot = UINT32_MAX;
    uint32_t textureWidth = 0;
    uint32_t textureHeight = 0;
    uint32_t queryWidths[kVisibilitySlots] = {};
    uint32_t queryHeights[kVisibilitySlots] = {};
    uint32_t publishedWidth = 0;
    uint32_t publishedHeight = 0;
    uint64_t textureGeneration = 0;
    uint64_t publishedSerial = 0;
    size_t buildCursor = 0;
    uint64_t sceneRevision = 1;
    uint64_t buildRevision = 0;
    uint32_t instanceCount = 0;
    simd_float2 focus = simd_make_float2(0.0f, 0.0f);
    float focusRadius = 0.0f;
    bool focusValid = false;
    bool queryPublishedForGpuWait[kVisibilitySlots] = {};
    BuildState state = BuildState::Collecting;
    std::string error;

    explicit RayQueryContext(id<MTLDevice> source) : device(source) {
        queue = [device newCommandQueue];
        if (queue == nil) fail("Metal command queue creation failed");
        if (@available(macOS 10.14, *)) {
            visibilityEvent = [device newSharedEvent];
            for (uint32_t slot = 0; slot < kVisibilitySlots; ++slot) {
                consumptionEvents[slot] = [device newSharedEvent];
            }
        }
        enqueueBgfxFence = resolveBgfxSharedEventHook();
        NSError* error = nil;
        auto library = [device newLibraryWithSource:
            [NSString stringWithUTF8String:kVisibilityShader] options:nil error:&error];
        auto function = [library newFunctionWithName:@"crumaVisibility"];
        visibilityPipeline = function == nil ? nil
            : [device newComputePipelineStateWithFunction:function error:&error];
        if (visibilityPipeline == nil) {
            NSString* description = error.localizedDescription;
            fail(description == nil ? "Metal visibility shader compilation failed"
                : std::string(description.UTF8String));
        }
    }

    ~RayQueryContext() {
        if (giCommand != nil && giCommand.status < MTLCommandBufferStatusCompleted)
            [giCommand waitUntilCompleted];
        for (id<MTLCommandBuffer> command : blasBuildCommands) {
            if (command != nil && command.status < MTLCommandBufferStatusCompleted) {
                [command waitUntilCompleted];
            }
        }
        if (buildCommand != nil
            && buildCommand.status < MTLCommandBufferStatusCompleted) {
            [buildCommand waitUntilCompleted];
        }
        for (id<MTLCommandBuffer> command : queryCommands) {
            if (command != nil && command.status < MTLCommandBufferStatusCompleted) {
                [command waitUntilCompleted];
            }
        }
    }

    void fail(const std::string& reason) {
        error = reason;
        state = BuildState::Failed;
    }

    void markDirty() {
        ++sceneRevision;
        if (state == BuildState::Ready) state = BuildState::Collecting;
    }
};

static id<MTLDevice> deviceFromOpaque(void* opaque) {
    return opaque == nullptr ? nil : (__bridge id<MTLDevice>)opaque;
}

static bool supportsRayQueries(id<MTLDevice> device) {
    if (device == nil) return false;
    if (@available(macOS 13.0, *)) {
        return device.supportsRaytracing;
    }
    return false;
}

static size_t copyUtf8(NSString* value, char* destination, size_t capacity) {
    if (value == nil) value = @"";
    const char* text = value.UTF8String;
    const size_t required = text == nullptr ? 1 : std::strlen(text) + 1;
    if (destination != nullptr && capacity > 0) {
        const size_t count = std::min(capacity - 1, required - 1);
        if (count > 0 && text != nullptr) std::memcpy(destination, text, count);
        destination[count] = '\0';
    }
    return required;
}

static size_t copyUtf8(const std::string& value, char* destination, size_t capacity) {
    const size_t required = value.size() + 1;
    if (destination != nullptr && capacity > 0) {
        const size_t count = std::min(capacity - 1, value.size());
        if (count > 0) std::memcpy(destination, value.data(), count);
        destination[count] = '\0';
    }
    return required;
}

static MTLAccelerationStructureTriangleGeometryDescriptor* triangleDescriptor(
    const Geometry& geometry
) {
    auto* descriptor = [MTLAccelerationStructureTriangleGeometryDescriptor descriptor];
    descriptor.vertexBuffer = geometry.vertices;
    descriptor.vertexBufferOffset = 0;
    descriptor.vertexFormat = MTLAttributeFormatFloat3;
    descriptor.vertexStride = sizeof(float) * 4;
    descriptor.indexBuffer = geometry.indices;
    descriptor.indexBufferOffset = 0;
    descriptor.indexType = MTLIndexTypeUInt32;
    descriptor.triangleCount = geometry.indexCount / 3;
    descriptor.opaque = geometry.hasAlpha ? NO : YES;
    if (@available(macOS 13.0, *)) {
        descriptor.primitiveDataBuffer = geometry.primitiveData;
        descriptor.primitiveDataBufferOffset = 0;
        descriptor.primitiveDataElementSize = sizeof(PrimitiveDataCpu);
        descriptor.primitiveDataStride = sizeof(PrimitiveDataCpu);
    }
    return descriptor;
}

static MTLPrimitiveAccelerationStructureDescriptor* blasDescriptor(const Geometry& geometry) {
    auto* descriptor = [MTLPrimitiveAccelerationStructureDescriptor descriptor];
    descriptor.geometryDescriptors = @[triangleDescriptor(geometry)];
    return descriptor;
}

static bool commandSucceeded(RayQueryContext& context) {
    if (context.state == BuildState::BuildingBlas && !context.blasBuildCommands.empty()) {
        bool completed = true;
        for (id<MTLCommandBuffer> command : context.blasBuildCommands) {
            const auto status = command.status;
            if (status < MTLCommandBufferStatusCompleted) {
                completed = false;
                continue;
            }
            if (status != MTLCommandBufferStatusCompleted) {
                NSString* description = command.error.localizedDescription;
                context.fail(description == nil
                    ? "Metal BLAS command failed"
                    : std::string(description.UTF8String));
                context.blasBuildCommands.clear();
                context.activeBlasBatch.clear();
                return false;
            }
        }
        if (!completed) return false;
        context.blasBuildCommands.clear();
        for (uint64_t geometryId : context.activeBlasBatch) {
            auto found = context.geometries.find(geometryId);
            if (found == context.geometries.end()) continue;
            found->second.vertices = nil;
            found->second.indices = nil;
            found->second.primitiveData = nil;
        }
        context.activeBlasBatch.clear();
        return true;
    }
    if (context.buildCommand == nil) return true;
    const auto status = context.buildCommand.status;
    if (status < MTLCommandBufferStatusCompleted) return false;
    if (status == MTLCommandBufferStatusCompleted) {
        context.buildCommand = nil;
        return true;
    }
    NSString* description = context.buildCommand.error.localizedDescription;
    context.fail(description == nil
        ? "Metal acceleration-structure command failed"
        : std::string(description.UTF8String));
    context.buildCommand = nil;
    context.activeBlasBatch.clear();
    return false;
}

static bool encodeBlasBatch(RayQueryContext& context) {
    context.activeBlasBatch.clear();
    while (context.buildCursor < context.buildGeometryIds.size()
        && context.activeBlasBatch.size() < kBlasBatchSize) {
        const uint64_t geometryId = context.buildGeometryIds[context.buildCursor++];
        auto found = context.geometries.find(geometryId);
        if (found == context.geometries.end() || found->second.blas != nil) continue;
        if (found->second.vertices == nil || found->second.indices == nil) {
            context.fail("Metal BLAS source buffers were released before build");
            return false;
        }
        context.activeBlasBatch.push_back(geometryId);
    }
    if (context.activeBlasBatch.empty()) return true;
    for (uint64_t geometryId : context.activeBlasBatch) {
        auto found = context.geometries.find(geometryId);
        if (found == context.geometries.end()) continue;
        auto descriptor = blasDescriptor(found->second);
        auto sizes = [context.device accelerationStructureSizesWithDescriptor:descriptor];
        found->second.blas = [context.device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
        if (found->second.blas == nil) {
            context.fail("Metal BLAS allocation failed");
            return false;
        }
    }
    context.blasBuildCommands.clear();
    context.blasBuildCommands.reserve(context.activeBlasBatch.size());
    for (uint64_t geometryId : context.activeBlasBatch) {
        auto found = context.geometries.find(geometryId);
        if (found == context.geometries.end()) continue;
        auto descriptor = blasDescriptor(found->second);
        auto sizes = [context.device accelerationStructureSizesWithDescriptor:descriptor];
        id<MTLBuffer> scratch = [context.device newBufferWithLength:std::max<NSUInteger>(
            sizes.buildScratchBufferSize, 256
        ) options:MTLResourceStorageModePrivate];
        auto command = [context.queue commandBuffer];
        auto encoder = [command accelerationStructureCommandEncoder];
        if (scratch == nil || command == nil || encoder == nil) {
            context.fail("Metal BLAS encoder allocation failed");
            return false;
        }
        [encoder buildAccelerationStructure:found->second.blas
            descriptor:descriptor
            scratchBuffer:scratch
            scratchBufferOffset:0];
        [encoder endEncoding];
        [command commit];
        context.blasBuildCommands.push_back(command);
    }
    context.state = BuildState::BuildingBlas;
    return true;
}

static MTLPackedFloat4x3 packedTransform(const float* matrix) {
    return MTLPackedFloat4x3(
        MTLPackedFloat3(matrix[0], matrix[1], matrix[2]),
        MTLPackedFloat3(matrix[4], matrix[5], matrix[6]),
        MTLPackedFloat3(matrix[8], matrix[9], matrix[10]),
        MTLPackedFloat3(matrix[12], matrix[13], matrix[14])
    );
}

static void transformedBounds(
    const Geometry& geometry,
    const float* matrix,
    simd_float3& minimum,
    simd_float3& maximum
) {
    const simd_float3 center = (geometry.localMin + geometry.localMax) * 0.5f;
    const simd_float3 extent = (geometry.localMax - geometry.localMin) * 0.5f;
    const simd_float3 transformedCenter = simd_make_float3(matrix[12], matrix[13], matrix[14])
        + simd_make_float3(matrix[0], matrix[1], matrix[2]) * center.x
        + simd_make_float3(matrix[4], matrix[5], matrix[6]) * center.y
        + simd_make_float3(matrix[8], matrix[9], matrix[10]) * center.z;
    const simd_float3 transformedExtent =
        simd_abs(simd_make_float3(matrix[0], matrix[1], matrix[2])) * extent.x
        + simd_abs(simd_make_float3(matrix[4], matrix[5], matrix[6])) * extent.y
        + simd_abs(simd_make_float3(matrix[8], matrix[9], matrix[10])) * extent.z;
    minimum = transformedCenter - transformedExtent;
    maximum = transformedCenter + transformedExtent;
}

static bool withinFocus(
    const RayQueryContext& context,
    const Geometry& geometry,
    const float* matrix
) {
    if (!context.focusValid) return false;
    simd_float3 minimum;
    simd_float3 maximum;
    transformedBounds(geometry, matrix, minimum, maximum);
    const simd_float2 closest = simd_make_float2(
        std::clamp(context.focus.x, minimum.x, maximum.x),
        std::clamp(context.focus.y, minimum.y, maximum.y)
    );
    const simd_float2 offset = closest - context.focus;
    return simd_dot(offset, offset) <= context.focusRadius * context.focusRadius;
}

static bool encodeTlas(RayQueryContext& context) {
    struct SelectedInstance {
        const Placement* placement;
        size_t matrixIndex;
    };
    std::vector<SelectedInstance> selectedInstances;
    std::unordered_set<uint64_t> selectedGeometryIds;
    for (const auto& entry : context.placements) {
        const auto geometry = context.geometries.find(entry.second.geometryId);
        if (geometry == context.geometries.end() || geometry->second.blas == nil) continue;
        const size_t count = entry.second.matrices.size() / 16;
        for (size_t instance = 0; instance < count; ++instance) {
            const float* matrix = entry.second.matrices.data() + instance * 16;
            if (!withinFocus(context, geometry->second, matrix)) continue;
            selectedInstances.push_back({&entry.second, instance});
            selectedGeometryIds.insert(entry.second.geometryId);
        }
    }
    if (selectedInstances.empty()) {
        context.tlas = nil;
        context.instanceDescriptors = nil;
        context.instanceData = nil;
        context.blasTable.clear();
        context.instanceCount = 0;
        context.state = BuildState::Collecting;
        return false;
    }
    if (selectedInstances.size() > UINT32_MAX) {
        context.fail("Metal local TLAS exceeds uint32 instance capacity");
        return false;
    }

    std::vector<uint64_t> selectedGeometryOrder(
        selectedGeometryIds.begin(), selectedGeometryIds.end()
    );
    std::sort(selectedGeometryOrder.begin(), selectedGeometryOrder.end());
    NSMutableArray<id<MTLAccelerationStructure>>* accelerationStructures =
        [NSMutableArray arrayWithCapacity:selectedGeometryOrder.size()];
    context.blasTable.clear();
    context.blasTable.reserve(selectedGeometryOrder.size());
    std::unordered_map<uint64_t, uint32_t> geometryIndices;
    for (uint64_t geometryId : selectedGeometryOrder) {
        auto found = context.geometries.find(geometryId);
        if (found == context.geometries.end() || found->second.blas == nil) continue;
        geometryIndices.emplace(geometryId, static_cast<uint32_t>(accelerationStructures.count));
        [accelerationStructures addObject:found->second.blas];
        context.blasTable.push_back(found->second.blas);
    }
    const size_t instanceCount = selectedInstances.size();
    const size_t descriptorBytes = static_cast<size_t>(instanceCount)
        * sizeof(MTLAccelerationStructureUserIDInstanceDescriptor);
    context.instanceDescriptors = [context.device newBufferWithLength:descriptorBytes
        options:MTLResourceStorageModeShared];
    context.instanceData = [context.device newBufferWithLength:
        static_cast<size_t>(instanceCount) * sizeof(InstanceDataCpu)
        options:MTLResourceStorageModeShared];
    if (context.instanceDescriptors == nil || context.instanceData == nil) {
        context.fail("Metal TLAS instance-buffer allocation failed");
        return false;
    }
    auto* descriptors = static_cast<MTLAccelerationStructureUserIDInstanceDescriptor*>(
        context.instanceDescriptors.contents
    );
    auto* instanceData = static_cast<InstanceDataCpu*>(context.instanceData.contents);
    context.instanceGeometryIds.resize(instanceCount);
    context.giBindingsDirty = true;
    for (size_t output = 0; output < selectedInstances.size(); ++output) {
        const auto selected = selectedInstances[output];
        const auto geometryIndex = geometryIndices.find(selected.placement->geometryId);
        if (geometryIndex == geometryIndices.end()) {
            context.fail("Metal local TLAS geometry table is inconsistent");
            return false;
        }
        const float* matrix = selected.placement->matrices.data() + selected.matrixIndex * 16;
        auto& descriptor = descriptors[output];
        descriptor.transformationMatrix = packedTransform(matrix);
        descriptor.options = MTLAccelerationStructureInstanceOptionDisableTriangleCulling;
        descriptor.mask = 0xff;
        descriptor.intersectionFunctionTableOffset = 0;
        descriptor.accelerationStructureIndex = geometryIndex->second;
        descriptor.userID = static_cast<uint32_t>(output);
        auto& metadata = instanceData[output];
        const bool hasLod = selected.placement->lodLevel >= 0
            && selected.placement->lodDistance1 > 0.0f;
        metadata.actorAndDistances = simd_make_float4(
            matrix[12], matrix[13],
            selected.placement->lodDistance1, selected.placement->lodDistance2
        );
        metadata.lodLevel = hasLod ? static_cast<uint32_t>(selected.placement->lodLevel) : 0u;
        metadata.flags = hasLod ? 1u : 0u;
        metadata.giAlbedo = 0;
        context.instanceGeometryIds[output] = selected.placement->geometryId;
    }
    auto tlasDescriptor = [MTLInstanceAccelerationStructureDescriptor descriptor];
    tlasDescriptor.instancedAccelerationStructures = accelerationStructures;
    tlasDescriptor.instanceCount = instanceCount;
    tlasDescriptor.instanceDescriptorBuffer = context.instanceDescriptors;
    tlasDescriptor.instanceDescriptorBufferOffset = 0;
    tlasDescriptor.instanceDescriptorStride = sizeof(MTLAccelerationStructureUserIDInstanceDescriptor);
    tlasDescriptor.instanceDescriptorType = MTLAccelerationStructureInstanceDescriptorTypeUserID;
    auto sizes = [context.device accelerationStructureSizesWithDescriptor:tlasDescriptor];
    context.tlas = [context.device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
    id<MTLBuffer> scratch = [context.device newBufferWithLength:std::max<NSUInteger>(
        sizes.buildScratchBufferSize, 256
    ) options:MTLResourceStorageModePrivate];
    auto command = [context.queue commandBuffer];
    auto encoder = [command accelerationStructureCommandEncoder];
    if (context.tlas == nil || scratch == nil || command == nil || encoder == nil) {
        context.fail("Metal TLAS encoder allocation failed");
        return false;
    }
    [encoder buildAccelerationStructure:context.tlas
        descriptor:tlasDescriptor
        scratchBuffer:scratch
        scratchBufferOffset:0];
    [encoder endEncoding];
    [command commit];
    context.buildCommand = command;
    context.instanceCount = static_cast<uint32_t>(instanceCount);
    context.state = BuildState::BuildingTlas;
    return true;
}

static bool queryInFlight(const RayQueryContext& context) {
    if (context.giCommand != nil && context.giCommand.status < MTLCommandBufferStatusCompleted) return true;
    for (id<MTLCommandBuffer> command : context.queryCommands) {
        if (command != nil) return true;
    }
    return false;
}

static void publishQuery(RayQueryContext& context, uint32_t slot) {
    const uint64_t serial = context.querySerials[slot];
    if (serial <= context.publishedSerial) return;
    context.publishedSlot = slot;
    context.publishedWidth = context.queryWidths[slot];
    context.publishedHeight = context.queryHeights[slot];
    context.publishedSerial = serial;
}

static bool pollQueries(RayQueryContext& context) {
    for (uint32_t slot = 0; slot < kVisibilitySlots; ++slot) {
        id<MTLCommandBuffer> command = context.queryCommands[slot];
        if (command == nil) continue;
        const auto status = command.status;
        if (status < MTLCommandBufferStatusCompleted) continue;
        if (status != MTLCommandBufferStatusCompleted) {
            NSString* description = command.error.localizedDescription;
            context.fail(description == nil ? "Metal ray-query dispatch failed"
                : std::string(description.UTF8String));
            context.queryCommands[slot] = nil;
            context.queryEventValues[slot] = 0;
            context.queryPublishedForGpuWait[slot] = false;
            if (context.pendingArmSlot == slot) context.pendingArmSlot = UINT32_MAX;
            return false;
        }
        if (!context.queryPublishedForGpuWait[slot]) {
            publishQuery(context, slot);
        }
        context.queryCommands[slot] = nil;
        context.queryEventValues[slot] = 0;
        context.queryPublishedForGpuWait[slot] = false;
        if (context.pendingArmSlot == slot) context.pendingArmSlot = UINT32_MAX;
    }
    return true;
}

static bool ensureVisibilityTextures(
    RayQueryContext& context, uint32_t width, uint32_t height
) {
    if (context.textureWidth >= width && context.textureHeight >= height) return true;
    if (queryInFlight(context)) return false;
    const uint32_t nextWidth = std::max(width, context.textureWidth);
    const uint32_t nextHeight = std::max(height, context.textureHeight);
    for (uint32_t slot = 0; slot < kVisibilitySlots; ++slot) {
        if (context.visibilityTextures[slot] != nil) {
            context.retiredTextures.push_back(context.visibilityTextures[slot]);
        }
    }
    auto descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
        width:nextWidth height:nextHeight mipmapped:NO];
    descriptor.storageMode = MTLStorageModePrivate;
    descriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    for (uint32_t slot = 0; slot < kVisibilitySlots; ++slot) {
        context.visibilityTextures[slot] = [context.device newTextureWithDescriptor:descriptor];
        context.queryParams[slot] = [context.device newBufferWithLength:sizeof(QueryParams)
            options:MTLResourceStorageModeShared];
        if (context.visibilityTextures[slot] == nil || context.queryParams[slot] == nil) {
            context.fail("Metal visibility texture allocation failed");
            return false;
        }
    }
    context.textureWidth = nextWidth;
    context.textureHeight = nextHeight;
    context.publishedSlot = UINT32_MAX;
    context.publishedWidth = 0;
    context.publishedHeight = 0;
    ++context.textureGeneration;
    return true;
}
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_abi() {
    return kAbiVersion;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_supported(void* opaqueDevice) {
    @autoreleasepool {
        return supportsRayQueries(deviceFromOpaque(opaqueDevice)) ? 1u : 0u;
    }
}

CRUMA_METAL_EXPORT size_t cruma_metal_ray_query_device_name(
    void* opaqueDevice, char* destination, size_t capacity
) {
    @autoreleasepool {
        return copyUtf8(deviceFromOpaque(opaqueDevice).name, destination, capacity);
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_system_supported() {
    @autoreleasepool {
        auto device = MTLCreateSystemDefaultDevice();
        if (!supportsRayQueries(device)) return 0u;
        RayQueryContext context(device);
        return context.state == BuildState::Failed ? 0u : 1u;
    }
}

CRUMA_METAL_EXPORT size_t cruma_metal_ray_query_system_device_name(
    char* destination, size_t capacity
) {
    @autoreleasepool {
        return copyUtf8(MTLCreateSystemDefaultDevice().name, destination, capacity);
    }
}

CRUMA_METAL_EXPORT void* cruma_metal_ray_query_create(void* opaqueDevice) {
    @autoreleasepool {
        auto device = deviceFromOpaque(opaqueDevice);
        if (!supportsRayQueries(device)) return nullptr;
        auto* context = new RayQueryContext(device);
        return context;
    }
}

CRUMA_METAL_EXPORT void cruma_metal_ray_query_destroy(void* opaqueContext) {
    @autoreleasepool {
        delete static_cast<RayQueryContext*>(opaqueContext);
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_register_geometry(
    void* opaqueContext,
    uint64_t stableId,
    const void* vertexBytes,
    uint32_t vertexCount,
    uint32_t vertexStride,
    uint32_t vertexUvOffset,
    const uint32_t* indexBytes,
    uint32_t indexCount,
    const uint32_t* ranges,
    uint32_t rangeCount,
    const uint8_t* alphaBytes,
    uint32_t alphaByteCount
) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || stableId == 0 || vertexBytes == nullptr || vertexCount == 0
            || vertexStride < sizeof(float) * 3 || vertexUvOffset > vertexStride
            || sizeof(float) * 2 > vertexStride - vertexUvOffset
            || indexBytes == nullptr || indexCount < 3
            || ranges == nullptr || rangeCount == 0 || context->state == BuildState::Failed) {
            return 0;
        }
        if ((alphaBytes == nullptr) != (alphaByteCount == 0)) return 0;
        if (context->geometries.contains(stableId)) return 1;
        uint64_t selectedIndexCount = 0;
        for (uint32_t range = 0; range < rangeCount; ++range) {
            const uint32_t* descriptor = ranges + range * 8;
            const uint32_t offset = descriptor[0];
            const uint32_t count = descriptor[1];
            if (count % 3 != 0 || offset > indexCount || count > indexCount - offset) return 0;
            const uint32_t alphaOffset = descriptor[2];
            if (alphaOffset != UINT32_MAX) {
                const uint32_t width = descriptor[3];
                const uint32_t height = descriptor[4];
                const uint64_t bytes = static_cast<uint64_t>(width) * height;
                if (width == 0 || height == 0 || alphaOffset > alphaByteCount
                    || bytes > alphaByteCount - alphaOffset) return 0;
            }
            selectedIndexCount += count;
        }
        if (selectedIndexCount < 3 || selectedIndexCount > UINT32_MAX) return 0;
        Geometry geometry;
        geometry.stableId = stableId;
        geometry.vertexCount = vertexCount;
        geometry.indexCount = static_cast<uint32_t>(selectedIndexCount);
        geometry.hasAlpha = alphaByteCount != 0;
        geometry.vertices = [context->device newBufferWithLength:
            static_cast<size_t>(vertexCount) * sizeof(float) * 4
            options:MTLResourceStorageModeShared];
        geometry.indices = [context->device newBufferWithLength:
            static_cast<size_t>(geometry.indexCount) * sizeof(uint32_t)
            options:MTLResourceStorageModeShared];
        geometry.primitiveData = [context->device newBufferWithLength:
            static_cast<size_t>(geometry.indexCount / 3) * sizeof(PrimitiveDataCpu)
            options:MTLResourceStorageModeShared];
        if (alphaByteCount != 0) {
            geometry.alphaData = [context->device newBufferWithLength:alphaByteCount
                options:MTLResourceStorageModeShared];
        }
        if (geometry.vertices == nil || geometry.indices == nil || geometry.primitiveData == nil
            || alphaByteCount != 0 && geometry.alphaData == nil) {
            context->fail("Metal ray geometry-buffer allocation failed");
            return 0;
        }
        if (alphaByteCount != 0) {
            std::memcpy(geometry.alphaData.contents, alphaBytes, alphaByteCount);
        }
        const auto* source = static_cast<const uint8_t*>(vertexBytes);
        auto* positions = static_cast<float*>(geometry.vertices.contents);
        for (uint32_t vertex = 0; vertex < vertexCount; ++vertex) {
            std::memcpy(positions + vertex * 4, source + static_cast<size_t>(vertex) * vertexStride, 12);
            positions[vertex * 4 + 3] = 1.0f;
        }
        auto* destinationIndices = static_cast<uint32_t*>(geometry.indices.contents);
        auto* primitiveData = static_cast<PrimitiveDataCpu*>(geometry.primitiveData.contents);
        size_t outputIndex = 0;
        size_t outputTriangle = 0;
        const uint64_t alphaBaseAddress = alphaByteCount == 0 ? 0 : geometry.alphaData.gpuAddress;
        for (uint32_t range = 0; range < rangeCount; ++range) {
            const uint32_t* descriptor = ranges + range * 8;
            const uint32_t offset = descriptor[0];
            const uint32_t count = descriptor[1];
            const uint32_t alphaOffset = descriptor[2];
            const bool masked = alphaOffset != UINT32_MAX;
            for (uint32_t index = 0; index < count; index += 3) {
                simd_float3 trianglePositions[3];
                simd_float2 triangleUvs[3];
                for (uint32_t corner = 0; corner < 3; ++corner) {
                    const uint32_t vertex = indexBytes[offset + index + corner];
                    if (vertex >= vertexCount) {
                        context->fail("Metal ray geometry contains an out-of-range vertex index");
                        return 0;
                    }
                    destinationIndices[outputIndex++] = vertex;
                    trianglePositions[corner] = simd_make_float3(
                        positions[vertex * 4], positions[vertex * 4 + 1], positions[vertex * 4 + 2]
                    );
                    std::memcpy(
                        &triangleUvs[corner],
                        source + static_cast<size_t>(vertex) * vertexStride + vertexUvOffset,
                        sizeof(simd_float2)
                    );
                    geometry.localMin = simd_min(geometry.localMin, trianglePositions[corner]);
                    geometry.localMax = simd_max(geometry.localMax, trianglePositions[corner]);
                }
                simd_float3 normal = simd_cross(
                    trianglePositions[1] - trianglePositions[0],
                    trianglePositions[2] - trianglePositions[0]
                );
                const float lengthSquared = simd_length_squared(normal);
                normal = lengthSquared > 1.0e-12f
                    ? normal * (1.0f / std::sqrt(lengthSquared))
                    : simd_make_float3(0.0f, 0.0f, 1.0f);
                PrimitiveDataCpu& data = primitiveData[outputTriangle++];
                data.normal = simd_make_float4(normal.x, normal.y, normal.z, 0.0f);
                data.alphaAddress = masked ? alphaBaseAddress + alphaOffset : 0;
                data.uv0 = triangleUvs[0];
                data.uv1 = triangleUvs[1];
                data.uv2 = triangleUvs[2];
                data.alphaWidth = descriptor[3];
                data.alphaHeight = descriptor[4];
                data.alphaFlags = descriptor[5];
                data.alphaCutoff = std::bit_cast<float>(descriptor[6]);
            }
        }
        context->geometries.emplace(stableId, std::move(geometry));
        context->markDirty();
        return 1;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_register_instances(
    void* opaqueContext,
    uint64_t ownerId,
    uint64_t stableId,
    uint64_t geometryId,
    const float* matrices,
    uint32_t instanceCount,
    int32_t lodLevel,
    float lodDistance1,
    float lodDistance2
) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || ownerId == 0 || stableId == 0 || geometryId == 0
            || matrices == nullptr || instanceCount == 0 || context->state == BuildState::Failed) {
            return 0;
        }
        Placement placement;
        placement.ownerId = ownerId;
        placement.stableId = stableId;
        placement.geometryId = geometryId;
        placement.matrices.assign(matrices, matrices + static_cast<size_t>(instanceCount) * 16);
        placement.lodLevel = lodLevel;
        placement.lodDistance1 = lodDistance1;
        placement.lodDistance2 = lodDistance2;
        context->placements.insert_or_assign(stableId, std::move(placement));
        context->markDirty();
        return 1;
    }
}

CRUMA_METAL_EXPORT void cruma_metal_ray_query_remove_owner(
    void* opaqueContext, uint64_t ownerId
) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || ownerId == 0) return;
        bool removed = false;
        for (auto iterator = context->placements.begin(); iterator != context->placements.end();) {
            if (iterator->second.ownerId == ownerId) {
                iterator = context->placements.erase(iterator);
                removed = true;
            } else {
                ++iterator;
            }
        }
        if (removed) context->markDirty();
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_remove_geometry(
    void* opaqueContext, uint64_t geometryId
) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || geometryId == 0) return 0;
        for (const auto& entry : context->placements) {
            if (entry.second.geometryId == geometryId) return 0;
        }
        if (!context->geometries.contains(geometryId)) return 1;
        if (context->giCommand != nil && context->giCommand.status < MTLCommandBufferStatusCompleted)
            return 0;
        for (id<MTLCommandBuffer> command : context->blasBuildCommands) {
            if (command != nil && command.status < MTLCommandBufferStatusCompleted)
                return 0;
        }
        if (context->buildCommand != nil && context->buildCommand.status < MTLCommandBufferStatusCompleted)
            return 0;
        for (id<MTLCommandBuffer> command : context->queryCommands) {
            if (command != nil && command.status < MTLCommandBufferStatusCompleted)
                return 0;
        }
        context->geometries.erase(geometryId);
        context->giBindingsDirty = true;
        context->markDirty();
        return 1;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_set_focus(
    void* opaqueContext, float x, float y, float radius
) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || context->state == BuildState::Failed
            || !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(radius)
            || radius < 1.0f) {
            return 0;
        }
        const simd_float2 next = simd_make_float2(x, y);
        const float movementThreshold = std::max(radius * 0.25f, 1'000.0f);
        const simd_float2 offset = next - context->focus;
        const bool radiusChanged = !context->focusValid
            || std::abs(radius - context->focusRadius) > std::max(radius, context->focusRadius) * 0.05f;
        if (radiusChanged || simd_dot(offset, offset) > movementThreshold * movementThreshold) {
            context->focus = next;
            context->focusRadius = radius;
            context->focusValid = true;
            context->markDirty();
        }
        return 1;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_poll_build(void* opaqueContext) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr) return static_cast<uint32_t>(BuildState::Failed);
        if (context->state == BuildState::Failed) return static_cast<uint32_t>(context->state);
        if (context->state == BuildState::BuildingBlas) {
            if (!commandSucceeded(*context)) return static_cast<uint32_t>(context->state);
            if (context->state == BuildState::Failed) return static_cast<uint32_t>(context->state);
            if (context->buildCursor < context->buildGeometryIds.size()) {
                encodeBlasBatch(*context);
                if (!context->blasBuildCommands.empty() || context->state == BuildState::Failed) {
                    return static_cast<uint32_t>(context->state);
                }
            }
            encodeTlas(*context);
            return static_cast<uint32_t>(context->state);
        }
        if (context->state == BuildState::BuildingTlas) {
            if (!commandSucceeded(*context)) return static_cast<uint32_t>(context->state);
            if (context->state == BuildState::Failed) return static_cast<uint32_t>(context->state);
            if (context->buildRevision == context->sceneRevision) {
                context->state = BuildState::Ready;
                return static_cast<uint32_t>(context->state);
            }
            context->state = BuildState::Collecting;
        }
        if (context->state == BuildState::Collecting) {
            context->buildGeometryIds.clear();
            context->buildGeometryIds.reserve(context->geometries.size());
            for (const auto& entry : context->geometries) {
                if (entry.second.indexCount >= 3) context->buildGeometryIds.push_back(entry.first);
            }
            std::sort(context->buildGeometryIds.begin(), context->buildGeometryIds.end());
            context->buildCursor = 0;
            context->buildRevision = context->sceneRevision;
            context->tlas = nil;
            context->instanceDescriptors = nil;
            context->instanceData = nil;
            if (context->buildGeometryIds.empty()) return static_cast<uint32_t>(context->state);
            encodeBlasBatch(*context);
            if (context->blasBuildCommands.empty() && context->state != BuildState::Failed) {
                encodeTlas(*context);
            }
        }
        return static_cast<uint32_t>(context->state);
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_instance_count(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->instanceCount;
}

CRUMA_METAL_EXPORT size_t cruma_metal_ray_query_last_error(
    void* opaqueContext, char* destination, size_t capacity
) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return copyUtf8(context == nullptr ? std::string("Metal ray-query context is null")
        : context->error, destination, capacity);
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_dispatch(
    void* opaqueContext,
    const float* inverseViewProjection,
    const float* rayBasis,
    const float* camera,
    const float* sun,
    uint32_t renderWidth,
    uint32_t renderHeight
) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || inverseViewProjection == nullptr || rayBasis == nullptr
            || camera == nullptr || sun == nullptr
            || renderWidth == 0 || renderHeight == 0 || context->state == BuildState::Failed) {
            return 0;
        }
        if (!pollQueries(*context) || context->state != BuildState::Ready) return 0;
        const uint32_t width = std::max(1u, (renderWidth + 1u) / 2u);
        const uint32_t height = std::max(1u, (renderHeight + 1u) / 2u);
        if (!ensureVisibilityTextures(*context, width, height)) return 0;
        const uint32_t slot = context->nextQuerySlot;
        if (context->queryCommands[slot] != nil) return 0;
        context->nextQuerySlot = (slot + 1u) % kVisibilitySlots;
        auto* params = static_cast<QueryParams*>(context->queryParams[slot].contents);
        std::memcpy(&params->inverseViewProjection, inverseViewProjection, sizeof(float) * 16);
        std::memcpy(params->rayBasis, rayBasis, sizeof(float) * 16);
        context->queryViewProjections[slot] = simd_inverse(params->inverseViewProjection);
        params->camera = simd_make_float4(camera[0], camera[1], camera[2], camera[3]);
        params->sun = simd_make_float4(sun[0], sun[1], sun[2], sun[3]);
        params->extent = simd_make_float4(
            static_cast<float>(width), static_cast<float>(height), camera[4], 0.0f
        );
        constexpr double goldenConjugate = 0.6180339887498948482;
        const double phase = std::fmod(
            static_cast<double>(context->nextQuerySerial) * goldenConjugate, 1.0
        );
        params->temporal = simd_make_float4(static_cast<float>(phase), 0.0f, 0.0f, 0.0f);
        auto command = [context->queue commandBuffer];
        if (command == nil) {
            context->fail("Metal visibility compute encoder allocation failed");
            return 0;
        }
        if (context->consumptionWaitValues[slot] != 0) {
            [command encodeWaitForEvent:context->consumptionEvents[slot]
                value:context->consumptionWaitValues[slot]];
        }
        auto encoder = [command computeCommandEncoder];
        if (encoder == nil) {
            context->fail("Metal visibility compute encoder allocation failed");
            return 0;
        }
        [encoder setComputePipelineState:context->visibilityPipeline];
        [encoder setAccelerationStructure:context->tlas atBufferIndex:0];
        [encoder setBuffer:context->queryParams[slot] offset:0 atIndex:1];
        [encoder setBuffer:context->instanceData offset:0 atIndex:2];
        [encoder setTexture:context->visibilityTextures[slot] atIndex:0];
        if (!context->blasTable.empty()) {
            [encoder useResources:context->blasTable.data()
                count:context->blasTable.size()
                usage:MTLResourceUsageRead];
        }
        for (const auto& entry : context->geometries) {
            if (entry.second.alphaData != nil) {
                [encoder useResource:entry.second.alphaData usage:MTLResourceUsageRead];
            }
        }
        const NSUInteger executionWidth = context->visibilityPipeline.threadExecutionWidth;
        const NSUInteger threadsPerGroup = std::min<NSUInteger>(
            context->visibilityPipeline.maxTotalThreadsPerThreadgroup,
            std::max<NSUInteger>(executionWidth, 64)
        );
        const MTLSize group = MTLSizeMake(executionWidth,
            std::max<NSUInteger>(1, threadsPerGroup / executionWidth), 1);
        [encoder dispatchThreads:MTLSizeMake(width, height, 1) threadsPerThreadgroup:group];
        [encoder endEncoding];
        const uint64_t eventValue = context->nextEventValue++;
        if (context->visibilityEvent != nil) {
            [command encodeSignalEvent:context->visibilityEvent value:eventValue];
            id<MTLSharedEvent> event = context->visibilityEvent;
            [command addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                if (completed.status != MTLCommandBufferStatusCompleted
                    && event.signaledValue < eventValue) {
                    event.signaledValue = eventValue;
                }
            }];
        }
        [command commit];
        context->queryWidths[slot] = width;
        context->queryHeights[slot] = height;
        context->queryCommands[slot] = command;
        context->queryEventValues[slot] = eventValue;
        context->querySerials[slot] = context->nextQuerySerial++;
        context->queryPublishedForGpuWait[slot] = false;
        context->pendingArmSlot = slot;
        return 1;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_poll_visibility(void* opaqueContext) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || context->state == BuildState::Failed) return 0;
        if (!pollQueries(*context) || context->state == BuildState::Failed) return 0;
        return context->publishedSlot == UINT32_MAX ? 0u : 1u;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_arm_bgfx_wait(void* opaqueContext) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || context->state == BuildState::Failed) return 0;
        const uint32_t slot = context->pendingArmSlot;
        if (slot == UINT32_MAX || context->queryCommands[slot] == nil) {
            return context->publishedSlot == UINT32_MAX ? 0u : 1u;
        }
        const uint64_t serial = context->querySerials[slot];
        if (context->queryPublishedForGpuWait[slot]) return 1;
        if (context->visibilityEvent == nil || context->consumptionEvents[slot] == nil
            || context->enqueueBgfxFence == nullptr || context->queryEventValues[slot] == 0) {
            return 0;
        }
        const uint64_t consumptionValue = context->nextConsumptionValue++;
        if (context->enqueueBgfxFence(
                (__bridge void*)context->visibilityEvent, context->queryEventValues[slot],
                (__bridge void*)context->consumptionEvents[slot], consumptionValue
            ) == 0) {
            return 0;
        }
        context->consumptionWaitValues[slot] = consumptionValue;
        publishQuery(*context, slot);
        context->queryPublishedForGpuWait[slot] = true;
        if (context->queryCommands[slot].status >= MTLCommandBufferStatusCompleted) {
            if (!pollQueries(*context) || context->state == BuildState::Failed) return 0;
        }
        return 1;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_bgfx_wait_available(
    void* opaqueContext
) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    if (context == nullptr || context->visibilityEvent == nil
        || context->enqueueBgfxFence == nullptr) return 0;
    for (id<MTLSharedEvent> event : context->consumptionEvents) {
        if (event == nil) return 0;
    }
    return 1;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_bgfx_wait_hook_loaded() {
    return resolveBgfxSharedEventHook() == nullptr ? 0u : 1u;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_wait_visibility(void* opaqueContext) {
    @autoreleasepool {
        auto* context = static_cast<RayQueryContext*>(opaqueContext);
        if (context == nullptr || context->state == BuildState::Failed) return 0;
        const uint32_t slot = context->pendingArmSlot;
        if (slot != UINT32_MAX && context->queryCommands[slot] != nil
            && context->queryCommands[slot].status < MTLCommandBufferStatusCompleted) {
            [context->queryCommands[slot] waitUntilCompleted];
        }
        if (!pollQueries(*context) || context->state == BuildState::Failed) return 0;
        return context->publishedSlot == UINT32_MAX ? 0u : 1u;
    }
}

CRUMA_METAL_EXPORT uint64_t cruma_metal_ray_query_texture_generation(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->textureGeneration;
}

CRUMA_METAL_EXPORT uint64_t cruma_metal_ray_query_published_serial(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->publishedSerial;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_published_slot(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? UINT32_MAX : context->publishedSlot;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_texture_width(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->textureWidth;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_texture_height(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->textureHeight;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_published_width(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->publishedWidth;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_published_height(void* opaqueContext) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    return context == nullptr ? 0 : context->publishedHeight;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_ray_query_published_reprojection(
    void* opaqueContext, float* viewProjection, float* camera
) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    if (context == nullptr || context->publishedSlot == UINT32_MAX
        || viewProjection == nullptr || camera == nullptr) {
        return 0;
    }
    const uint32_t slot = context->publishedSlot;
    std::memcpy(viewProjection, &context->queryViewProjections[slot], sizeof(float) * 16);
    auto* params = static_cast<QueryParams*>(context->queryParams[slot].contents);
    std::memcpy(camera, &params->camera, sizeof(float) * 4);
    return 1;
}

CRUMA_METAL_EXPORT void* cruma_metal_ray_query_texture(
    void* opaqueContext, uint32_t slot
) {
    auto* context = static_cast<RayQueryContext*>(opaqueContext);
    if (context == nullptr || slot >= kVisibilitySlots) return nullptr;
    return (__bridge void*)context->visibilityTextures[slot];
}


CRUMA_METAL_EXPORT uint32_t cruma_metal_gi_materials(void* opaque, uint64_t id,
    const float* rgba, uint32_t triangles) {
    auto* ctx=static_cast<RayQueryContext*>(opaque);
    if(ctx==nullptr || rgba==nullptr || ctx->giCommand!=nil) return 0;
    auto found=ctx->geometries.find(id);
    if(found==ctx->geometries.end() || triangles!=found->second.indexCount/3) return 0;
    if(found->second.giAlbedo!=nil) return 1;
    for(size_t i=0;i<size_t(triangles)*8;++i)
        if(!std::isfinite(rgba[i]) || rgba[i]<0 || (i%8<4 && rgba[i]>1)) return 0;
    found->second.giAlbedo=[ctx->device newBufferWithBytes:rgba length:size_t(triangles)*32
        options:MTLResourceStorageModeShared];
    ctx->giBindingsDirty=true;
    return found->second.giAlbedo!=nil ? 1 : 0;
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_gi_dispatch(void* opaque, const float* params) {
    @autoreleasepool {
        auto* ctx=static_cast<RayQueryContext*>(opaque);
        if(ctx==nullptr || params==nullptr || ctx->state!=BuildState::Ready || ctx->giCommand!=nil) return 0;
        for(uint32_t i=0;i<216;++i) if(!std::isfinite(params[i])) return 0;
        if(params[3]<=0 || params[7]<=0 || params[18]<0 || params[18]>=864
            || params[19]<0 || params[19]>864 || params[18]+params[19]>864
            || params[20]<1 || params[20]>3 || params[21]<0 || params[21]>16) return 0;
        if(ctx->giPipeline==nil) {
            NSError* error=nil;
            auto library=[ctx->device newLibraryWithSource:[NSString stringWithUTF8String:kVisibilityShader]
                options:nil error:&error];
            auto function=[library newFunctionWithName:@"crumaGi"];
            ctx->giPipeline=function==nil ? nil : [ctx->device newComputePipelineStateWithFunction:function error:&error];
            if(ctx->giPipeline==nil) { ctx->fail(error==nil ? "GI pipeline failed" : std::string(error.localizedDescription.UTF8String)); return 0; }
            ctx->giParams=[ctx->device newBufferWithLength:864 options:MTLResourceStorageModeShared];
            ctx->giResult=[ctx->device newBufferWithLength:864*12*16 options:MTLResourceStorageModeShared];
            if(ctx->giParams==nil || ctx->giResult==nil) {ctx->fail("GI buffer allocation failed");return 0;}
        }
        if(ctx->giBindingsDirty) {
            auto* metadata=static_cast<InstanceDataCpu*>(ctx->instanceData.contents);
            for(size_t i=0;i<ctx->instanceGeometryIds.size();++i) {
                auto found=ctx->geometries.find(ctx->instanceGeometryIds[i]);
                metadata[i].giAlbedo=found==ctx->geometries.end() || found->second.giAlbedo==nil
                    ? 0 : found->second.giAlbedo.gpuAddress;
            }
            ctx->giBindingsDirty=false;
        }
        std::memcpy(ctx->giParams.contents,params,864);
        auto command=[ctx->queue commandBuffer];
        auto encoder=[command computeCommandEncoder];
        if(command==nil || encoder==nil) {ctx->fail("GI encoder allocation failed");return 0;}
        [encoder setComputePipelineState:ctx->giPipeline];
        [encoder setAccelerationStructure:ctx->tlas atBufferIndex:0];
        [encoder setBuffer:ctx->giParams offset:0 atIndex:1];
        [encoder setBuffer:ctx->instanceData offset:0 atIndex:2];
        [encoder setBuffer:ctx->giResult offset:0 atIndex:3];
        if(!ctx->blasTable.empty()) [encoder useResources:ctx->blasTable.data() count:ctx->blasTable.size() usage:MTLResourceUsageRead];
        for(const auto& entry:ctx->geometries) if(entry.second.alphaData!=nil)
            [encoder useResource:entry.second.alphaData usage:MTLResourceUsageRead];
        for(const auto& entry:ctx->geometries) if(entry.second.giAlbedo!=nil)
            [encoder useResource:entry.second.giAlbedo usage:MTLResourceUsageRead];
        [encoder dispatchThreads:MTLSizeMake(params[19]>0 ? uint32_t(params[19]) : 864u,1,1)
            threadsPerThreadgroup:MTLSizeMake(ctx->giPipeline.threadExecutionWidth,1,1)];
        [encoder endEncoding]; [command commit]; ctx->giCommand=command;
        return 1;
    }
}

CRUMA_METAL_EXPORT uint32_t cruma_metal_gi_read(void* opaque, float* output, uint32_t floats, double* milliseconds) {
    auto* ctx=static_cast<RayQueryContext*>(opaque);
    if(ctx==nullptr || output==nullptr || milliseconds==nullptr || floats!=864*12*4 || ctx->giCommand==nil) return 0;
    if(ctx->giCommand.status<MTLCommandBufferStatusCompleted) return 0;
    if(ctx->giCommand.status!=MTLCommandBufferStatusCompleted) {
        ctx->fail("Metal GI dispatch failed"); ctx->giCommand=nil; return 0;
    }
    *milliseconds=(ctx->giCommand.GPUEndTime-ctx->giCommand.GPUStartTime)*1000.0;
    std::memcpy(output,ctx->giResult.contents,static_cast<size_t>(floats)*4);
    ctx->giCommand=nil; return 1;
}
