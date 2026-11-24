package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.config.KeptLayers;
import glm_.glm;
import glm_.mat4x4.Mat4;
import glm_.quat.Quat;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.FloatBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

public class Camera {
    public static final float MAX_PITCH_DEGREES = 89f;

    public static final float MIN_ORTHO_ZOOM = .01f;

    public static final float MAX_ORTHO_ZOOM = 10f;

    public static final float MIN_ZOOM_PERCENT = 10f;

    public static final float MAX_ZOOM_PERCENT = 10_000f;

    public static final float DEFAULT_ORTHO_FAR = 1000000f;

    private static final float ORTHO_FRAME_MARGIN = 1.25f;
    private static final float MIN_FRAMED_UNITS = 32f;
    private static final Vec3 UP_DIR = new Vec3(0f, 0f, 1f);
    private static final Vec3 RIGHT_DIR = new Vec3(1, 0, 0);
    private static final Vec3 FORWARD_DIR = new Vec3(0, 0, -1);
    private static final Vec3 FORWARD_DIR_ORTHO = new Vec3(0, -1, 0);
    private static final float MAP_SIZE = 32768f;
    private static final float ORTHO_PAN_SHARE = .5f;

    private static final float OFF_SCREEN_LABEL_LIMIT = 2.5f;
    private static final float NEAR_RAMP_START = 30000f;
    private static final float NEAR_RAMP_SLOPE = 0.05f;
    private static final float CLEARANCE_NEAR_SHARE = 0.5f;
    private static final float CLEARANCE_NEAR_FAR_SHARE = 0.05f;
    private static final float SKY_NEAR = 10.0f;
    private static final float REFLECTION_NEAR = 10.0f;

    private final Context context;
    private float fov;
    private ScreenLens lens = ScreenLens.WORLD;
    private float near;
    private final Vec3 position;
    private final Mat4 viewMatrix = new Mat4();
    private final FloatBuffer viewMatrixBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projectionMatrixBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer skyProjectionMatrixBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer reflectionProjectionMatrixBuffer = BufferUtils.createFloatBuffer(16);
    private Quat orientation;
    private Quat perspectiveOrientation;
    private CameraView view = CameraView.PERSPECTIVE;
    private float savedDepth;
    private boolean authoredDirectionValid;
    private long authoredYawBits;
    private long authoredPitchBits;
    private long authoredRollBits;
    private boolean ortho = false;
    private float zoomFactor = 1f;

    private int zNear = 0;
    private float clearance;
    private float far = 0f;
    private float orthoFar = DEFAULT_ORTHO_FAR;
    private @Nullable ShaderHeightCut shaderHeightCut;
    private long stateRevision;
    private long projectionRevision = Long.MIN_VALUE;
    private int projectionWidth = Integer.MIN_VALUE;
    private int projectionHeight = Integer.MIN_VALUE;
    private Mat4 cachedProjectionMatrix;
    private long frustumRevision = Long.MIN_VALUE;
    private int frustumWidth = Integer.MIN_VALUE;
    private int frustumHeight = Integer.MIN_VALUE;
    private Frustum cachedFrustum;
    private long rayRevision = Long.MIN_VALUE;
    private int rayWidth = Integer.MIN_VALUE;
    private int rayHeight = Integer.MIN_VALUE;
    private final float[] inverseViewProjection = new float[16];

    private final float[] rayBasis = new float[16];

    private final float[] rayProjectionScratch = new float[16];
    private final float[] cursorRayScratch = new float[3];

    public Camera(Context context, float fov, float near, Vec3 position) {
        this.context = context;
        this.fov = fov;
        this.near = near;
        this.position = position;
        this.orientation = orthoOrientation(CameraView.TOP);
    }

    public static float zoomPercent(float zoomFactor) {
        return 100f / Math.clamp(zoomFactor, MIN_ORTHO_ZOOM, MAX_ORTHO_ZOOM);
    }

    public static float zoomFactorOfPercent(float percent) {
        return Math.clamp(
                100f / Math.clamp(percent, MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT), MIN_ORTHO_ZOOM, MAX_ORTHO_ZOOM);
    }

    public void translate(Vec3 direction) {
        position.plusAssign(direction);
        updateViewMatrix();
    }

    public void setPosition(float x, float y, float z) {
        if (isInteractiveOrtho()) {
            var axis = view.getDepthAxis();
            var depth = component(axis, x, y, z);
            if (Float.floatToIntBits(depth) != Float.floatToIntBits(component(axis, position))) {
                savedDepth = depth;
                var eye = depth + view.getEyeOffset();
                switch (axis) {
                    case 0 -> x = eye;
                    case 1 -> y = eye;
                    default -> z = eye;
                }
            }
        }
        if (Float.floatToIntBits(position.getX()) == Float.floatToIntBits(x)
                && Float.floatToIntBits(position.getY()) == Float.floatToIntBits(y)
                && Float.floatToIntBits(position.getZ()) == Float.floatToIntBits(z)) {
            return;
        }
        this.position.setX(x);
        this.position.setY(y);
        this.position.setZ(z);
        updateViewMatrix();
    }

    public void focus(Bounds3f bounds) {
        focus(bounds, 0, 0, 1, 1);
    }

    public void focus(Bounds3f bounds, float centerNdcX, float centerNdcY, float widthFraction, float heightFraction) {
        if (!Float.isFinite(centerNdcX)
                || !Float.isFinite(centerNdcY)
                || !(widthFraction > 0 && widthFraction <= 1)
                || !(heightFraction > 0 && heightFraction <= 1)) {
            throw new IllegalArgumentException("invalid focus viewport");
        }
        var dx = bounds.maxX() - bounds.minX();
        var dy = bounds.maxY() - bounds.minY();
        var dz = bounds.maxZ() - bounds.minZ();
        var radius = Math.max(16f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * .5f);
        var aspect =
                Math.max(.1f, (float) Math.max(1, context.framebuffer.width) / Math.max(1, context.framebuffer.height));
        if (ortho) {
            setPosition(bounds.centerX(), bounds.centerY(), isInteractiveOrtho() ? bounds.centerZ() : position.getZ());
            if (isInteractiveOrtho()) {
                var across = Math.max(MIN_FRAMED_UNITS, extent(view.getRightAxis(), dx, dy, dz))
                        * ORTHO_FRAME_MARGIN
                        / (Math.max(1f, aspect) * widthFraction);
                var along = Math.max(MIN_FRAMED_UNITS, extent(view.getUpAxis(), dx, dy, dz))
                        * ORTHO_FRAME_MARGIN
                        / (Math.max(1f, 1f / aspect) * heightFraction);
                zoomFactor = Math.clamp(Math.max(across, along) / MAP_SIZE, MIN_ORTHO_ZOOM, MAX_ORTHO_ZOOM);
            } else {
                var available =
                        Math.min(Math.max(1f, aspect) * widthFraction, Math.max(1f, 1f / aspect) * heightFraction);
                zoomFactor = Math.clamp(radius * 2.5f / (MAP_SIZE * available), MIN_ORTHO_ZOOM, MAX_ORTHO_ZOOM);
            }
            stateRevision++;
            var center = getCursorRayOrigin(0, 0);
            var target = getCursorRayOrigin(centerNdcX, centerNdcY);
            var focus = getWorldFocusPosition();
            setPosition(
                    focus.getX() - (target.getX() - center.getX()),
                    focus.getY() - (target.getY() - center.getY()),
                    focus.getZ());
            return;
        }
        updateInverseViewProjection();
        var horizontal = Math.hypot(rayBasis[12], Math.abs(centerNdcX) + widthFraction) / widthFraction;
        var vertical = Math.hypot(rayBasis[13], Math.abs(centerNdcY) + heightFraction) / heightFraction;
        var distance = Math.max(getAltitudeNear() + radius, (float) (radius * Math.max(horizontal, vertical) * 1.15));
        var ray = getCursorRayDirection(centerNdcX, centerNdcY);
        var forward = getCursorRayDirection(0, 0);
        var rayDistance = distance / Math.max(.01f, ray.dot(forward));
        setPosition(
                bounds.centerX() - ray.getX() * rayDistance,
                bounds.centerY() - ray.getY() * rayDistance,
                bounds.centerZ() - ray.getZ() * rayDistance);
    }

    public void rotate(Quat rotation) {
        this.orientation = rotation.times(orientation).normalize(orientation);
        authoredDirectionValid = false;
        updateViewMatrix();
    }

    public void setDirection(double yawDegrees, double pitchDegrees) {
        setDirection(yawDegrees, pitchDegrees, 0);
    }

    public void setDirection(double yawDegrees, double pitchDegrees, double rollDegrees) {
        var clampedPitch = Math.max(-MAX_PITCH_DEGREES, Math.min(MAX_PITCH_DEGREES, pitchDegrees));
        var yawBits = Double.doubleToLongBits(yawDegrees);
        var pitchBits = Double.doubleToLongBits(clampedPitch);
        var rollBits = Double.doubleToLongBits(rollDegrees);
        if (authoredDirectionValid
                && authoredYawBits == yawBits
                && authoredPitchBits == pitchBits
                && authoredRollBits == rollBits) {
            return;
        }
        var yaw = Math.toRadians(yawDegrees);
        var pitch = Math.toRadians(clampedPitch);
        var horizontal = Math.cos(pitch);
        var direction = new Vec3(
                (float) (Math.cos(yaw) * horizontal), (float) (Math.sin(yaw) * horizontal), (float) Math.sin(pitch));
        this.orientation =
                glm.INSTANCE.quatLookAtLH(direction, rollDegrees == 0 ? UP_DIR : rolledUp(direction, rollDegrees));
        authoredDirectionValid = true;
        authoredYawBits = yawBits;
        authoredPitchBits = pitchBits;
        authoredRollBits = rollBits;
        updateViewMatrix();
    }

    public void setOrthoProjection(boolean ortho) {
        if (this.ortho == ortho) {
            return;
        }
        if (ortho) {
            perspectiveOrientation = orientation;
            orientation = orthoOrientation(view.isOrtho() ? view : CameraView.TOP);
        } else {
            if (perspectiveOrientation != null) {
                orientation = perspectiveOrientation;
                perspectiveOrientation = null;
            }
            if (view.isOrtho()) {
                setComponent(position, view.getDepthAxis(), savedDepth);
                view = CameraView.PERSPECTIVE;
            }
        }
        this.ortho = ortho;
        updateViewMatrix();
    }

    public void toggleInteractiveOrthoProjection() {
        var top = ortho && (view == CameraView.TOP || !view.isOrtho());
        setView(top ? CameraView.PERSPECTIVE : CameraView.TOP);
    }

    public void setView(CameraView next) {
        if (next == null) {
            throw new IllegalArgumentException("camera view required");
        }
        if (next == view && (next.isOrtho() || !ortho)) {
            return;
        }
        if (ortho) {
            setOrthoProjection(false);
        }
        if (!next.isOrtho()) {
            return;
        }
        savedDepth = component(next.getDepthAxis(), position);
        view = next;
        setOrthoProjection(true);
        setComponent(position, next.getDepthAxis(), savedDepth + next.getEyeOffset());
        updateViewMatrix();
    }

    public CameraView getView() {
        return view;
    }

    public boolean isInteractiveOrtho() {
        return ortho && view.isOrtho();
    }

    public void updateOrthoZoomFactor(float zoomFactorDelta) {
        setOrthoZoomFactor(zoomFactor * zoomFactorDelta);
    }

    public void setOrthoZoomFactor(float zoom) {
        if (Float.isNaN(zoom)) {
            return;
        }
        var next = Math.clamp(zoom, MIN_ORTHO_ZOOM, MAX_ORTHO_ZOOM);
        if (Float.floatToIntBits(next) != Float.floatToIntBits(zoomFactor)) {
            zoomFactor = next;
            stateRevision++;
        }
    }

    public void zoomOrthoAt(float factor, float ndcX, float ndcY) {
        if (!ortho) {
            return;
        }
        var before = getCursorRayOrigin(ndcX, ndcY);
        var previous = zoomFactor;
        setOrthoZoomFactor(zoomFactor * factor);
        if (Float.floatToIntBits(previous) == Float.floatToIntBits(zoomFactor)) {
            return;
        }
        var shift = before.minus(getCursorRayOrigin(ndcX, ndcY));
        if (view.isOrtho()) {
            setComponent(shift, view.getDepthAxis(), 0f);
        }
        translate(shift);
    }

    public float getOrthoVisibleWidth() {
        var width = Math.max(1, context.framebuffer.width);
        var height = Math.max(1, context.framebuffer.height);
        return MAP_SIZE * zoomFactor * Math.max(1f, (float) width / height);
    }

    public float getOrthoVisibleSize() {
        return MAP_SIZE * zoomFactor;
    }

    public float getOrthoPanSpeed() {
        return getOrthoVisibleWidth() * ORTHO_PAN_SHARE;
    }

    public void setZNear(int zNear) {
        var clamped = Math.max(0, Math.min(999999, zNear));
        if (this.zNear != clamped) {
            this.zNear = clamped;
            stateRevision++;
        }
    }

    public void setOrthoFar(float orthoFar) {
        var clamped = Math.max(zNear + 1f, orthoFar);
        if (Float.floatToIntBits(this.orthoFar) != Float.floatToIntBits(clamped)) {
            this.orthoFar = clamped;
            stateRevision++;
        }
    }

    public void cutHeights(@Nullable Integer top, @Nullable Integer bottom) {
        cutHeights(top, bottom, KeptLayers.NONE);
    }

    public void cutHeights(@Nullable Integer top, @Nullable Integer bottom, KeptLayers kept) {
        cutHeights(top, bottom, kept, null);
    }

    public void cutHeights(
            @Nullable Integer top, @Nullable Integer bottom, KeptLayers kept, @Nullable CutCircle circle) {
        if ((kept.hasAny() || circle != null) && (top != null || bottom != null)) {
            setOrthoFar(DEFAULT_ORTHO_FAR);
            if (shaderHeightCut == null || !shaderHeightCut.matches(top, bottom, kept, circle)) {
                shaderHeightCut = new ShaderHeightCut(top, bottom, kept, circle);
            }
            return;
        }
        shaderHeightCut = null;
        var eye = position.getZ();
        if (top != null) {
            setZNear(Math.max(1, Math.round(eye - top)));
        }
        setOrthoFar(bottom == null ? DEFAULT_ORTHO_FAR : eye - bottom);
    }

    public void cutDepths(@Nullable Integer near, @Nullable Integer far) {
        shaderHeightCut = null;
        if (!isInteractiveOrtho()) {
            setOrthoFar(DEFAULT_ORTHO_FAR);
            return;
        }
        if (near != null) {
            setZNear(Math.max(1, Math.round(depthFromEye(near))));
        }
        setOrthoFar(far == null ? DEFAULT_ORTHO_FAR : Math.min(DEFAULT_ORTHO_FAR, depthFromEye(far)));
    }

    public @Nullable ShaderHeightCut getShaderHeightCut() {
        return shaderHeightCut;
    }

    public void setFar(float far) {
        if (Float.floatToIntBits(this.far) != Float.floatToIntBits(far)) {
            this.far = far;
            stateRevision++;
        }
    }

    public Vec3 getPosition() {
        return position;
    }

    public record Rotation(float w, float x, float y, float z) {
        public Rotation {
            var length = w * w + x * x + y * y + z * z;
            if (!Float.isFinite(length) || Math.abs(length - 1) > 0.01f) {
                throw new IllegalArgumentException("Invalid camera rotation");
            }
        }

        private Quat quaternion() {
            return new Quat(w, x, y, z);
        }

        private static Rotation of(Quat value) {
            return value == null ? null : new Rotation(value.w, value.x, value.y, value.z);
        }
    }

    public record Snapshot(
            float x,
            float y,
            float z,
            Rotation orientation,
            Rotation perspectiveOrientation,
            boolean ortho,
            boolean interactiveOrtho,
            float fov,
            float near,
            float far,
            int orthoNear,
            float orthoFar,
            float zoomFactor,
            CameraView view) {
        public Snapshot {
            if (view == null) {
                view = interactiveOrtho ? CameraView.TOP : CameraView.PERSPECTIVE;
            }
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(z)
                    || orientation == null
                    || !Float.isFinite(fov)
                    || fov <= 0
                    || fov >= 180
                    || !Float.isFinite(near)
                    || near <= 0
                    || !Float.isFinite(far)
                    || !Float.isFinite(orthoFar)
                    || orthoFar <= orthoNear
                    || orthoNear < 0
                    || !Float.isFinite(zoomFactor)
                    || zoomFactor < MIN_ORTHO_ZOOM
                    || zoomFactor > MAX_ORTHO_ZOOM
                    || interactiveOrtho && (!ortho || perspectiveOrientation == null)
                    || interactiveOrtho != view.isOrtho()) {
                throw new IllegalArgumentException("Invalid camera snapshot");
            }
        }
    }

    public Snapshot snapshot() {
        var focus = getWorldFocusPosition();
        return new Snapshot(
                focus.getX(),
                focus.getY(),
                focus.getZ(),
                Rotation.of(orientation),
                Rotation.of(perspectiveOrientation),
                ortho,
                isInteractiveOrtho(),
                fov,
                near,
                far,
                zNear,
                orthoFar,
                zoomFactor,
                view);
    }

    public void restoreSnapshot(Snapshot snapshot) {
        position.setX(snapshot.x());
        position.setY(snapshot.y());
        position.setZ(snapshot.z());
        view = snapshot.view();
        if (view.isOrtho()) {
            savedDepth = component(view.getDepthAxis(), position);
            setComponent(position, view.getDepthAxis(), savedDepth + view.getEyeOffset());
        }
        orientation = snapshot.orientation().quaternion();
        perspectiveOrientation = snapshot.perspectiveOrientation() == null
                ? null
                : snapshot.perspectiveOrientation().quaternion();
        ortho = snapshot.ortho();
        fov = snapshot.fov();
        near = snapshot.near();
        far = snapshot.far();
        zNear = snapshot.orthoNear();
        orthoFar = snapshot.orthoFar();
        zoomFactor = snapshot.zoomFactor();
        authoredDirectionValid = false;
        updateViewMatrix();
    }

    public void copyPoseFrom(Camera source) {
        position.setX(source.position.getX());
        position.setY(source.position.getY());
        position.setZ(source.position.getZ());
        orientation = new Quat(source.orientation);
        perspectiveOrientation = source.perspectiveOrientation == null ? null : new Quat(source.perspectiveOrientation);
        view = source.view;
        savedDepth = source.savedDepth;
        ortho = source.ortho;
        zoomFactor = source.zoomFactor;
        authoredDirectionValid = false;
        updateViewMatrix();
    }

    public Vec3 getWorldFocusPosition() {
        if (!isInteractiveOrtho()) {
            return position;
        }
        var focus = new Vec3(position);
        setComponent(focus, view.getDepthAxis(), savedDepth);
        return focus;
    }

    public Vec3 getForwardDir() {
        return ortho ? orientation.times(FORWARD_DIR_ORTHO) : orientation.times(FORWARD_DIR);
    }

    public Vec3 getPerspectiveLookDir() {
        var rotation = ortho && perspectiveOrientation != null ? perspectiveOrientation : orientation;
        return rotation.times(new Vec3(0, 0, 1));
    }

    public Vec3 getRightDir() {
        return orientation.times(RIGHT_DIR);
    }

    public double getYawDegrees() {
        var look = getPerspectiveLookDir();
        return Math.toDegrees(Math.atan2(look.getY(), look.getX()));
    }

    public double getPitchDegrees() {
        var look = getPerspectiveLookDir();
        return Math.toDegrees(Math.asin(Math.clamp(look.getZ(), -1f, 1f)));
    }

    public double getRollDegrees() {
        var rotation = ortho && perspectiveOrientation != null ? perspectiveOrientation : orientation;
        var look = rotation.times(new Vec3(0, 0, 1));
        var top = rotation.times(new Vec3(0, 1, 0));
        var level = rolledUp(look, 0);
        var right = new Vec3(
                level.getY() * look.getZ() - level.getZ() * look.getY(),
                level.getZ() * look.getX() - level.getX() * look.getZ(),
                level.getX() * look.getY() - level.getY() * look.getX());
        var along = top.getX() * level.getX() + top.getY() * level.getY() + top.getZ() * level.getZ();
        var across = top.getX() * right.getX() + top.getY() * right.getY() + top.getZ() * right.getZ();
        return Math.toDegrees(Math.atan2(across, along));
    }

    public Vec3 getUpDir() {
        return UP_DIR;
    }

    public float getFov() {
        return fov;
    }

    public ScreenLens getLens() {
        return lens;
    }

    public void setLens(ScreenLens lens) {
        if (lens == null) {
            throw new IllegalArgumentException("camera lens required");
        }
        if (!lens.equals(this.lens)) {
            this.lens = lens;
            stateRevision++;
        }
    }

    public void setFov(float fov) {
        if (!Float.isFinite(fov) || fov <= 0 || fov >= 180) {
            throw new IllegalArgumentException("A field of view is between 0 and 180 degrees: " + fov);
        }
        if (Float.floatToIntBits(this.fov) != Float.floatToIntBits(fov)) {
            this.fov = fov;
            stateRevision++;
        }
    }

    public float getOrthoZoomFactor() {
        return zoomFactor;
    }

    public float getNear() {
        return near;
    }

    public float getOrthoFar() {
        return orthoFar;
    }

    public float getProjectionNear() {
        if (ortho) {
            return zNear;
        }
        return Math.max(getAltitudeNear(), getClearanceNear());
    }

    public void setClearance(float clearance) {
        var next = clearance > 0f ? clearance : 0f;
        if (Float.floatToIntBits(next) != Float.floatToIntBits(this.clearance)) {
            this.clearance = next;
            if (!ortho) {
                stateRevision++;
            }
        }
    }

    public float getReflectionNear() {
        return ortho ? zNear : REFLECTION_NEAR;
    }

    public FloatBuffer getViewMatrix() {
        return viewMatrixBuffer;
    }

    public FloatBuffer getProjectionMatrix() {
        return createProjectionMatrix().to(projectionMatrixBuffer);
    }

    public FloatBuffer getSkyProjectionMatrix() {
        return createPerspectiveMatrix(SKY_NEAR).to(skyProjectionMatrixBuffer);
    }

    public FloatBuffer getReflectionProjectionMatrix() {
        return (ortho ? createProjectionMatrix() : createPerspectiveMatrix(REFLECTION_NEAR))
                .to(reflectionProjectionMatrixBuffer);
    }

    public Frustum getFrustum() {
        var width = context.framebuffer.width;
        var height = context.framebuffer.height;
        if (cachedFrustum == null
                || frustumRevision != stateRevision
                || frustumWidth != width
                || frustumHeight != height) {
            cachedFrustum = new Frustum(createProjectionMatrix(), viewMatrix);
            frustumRevision = stateRevision;
            frustumWidth = width;
            frustumHeight = height;
        }
        return cachedFrustum;
    }

    public @Nullable Vec2 projectToScreen(Vec3 world, int screenWidth, int screenHeight) {
        var clip = createProjectionMatrix().times(viewMatrix).times(new Vec4(world, 1f));
        var w = clip.getW();
        if (w <= 0f) {
            return null;
        }
        var ndcX = clip.getX() / w;
        var ndcY = clip.getY() / w;
        if (ndcX < -OFF_SCREEN_LABEL_LIMIT
                || ndcX > OFF_SCREEN_LABEL_LIMIT
                || ndcY < -OFF_SCREEN_LABEL_LIMIT
                || ndcY > OFF_SCREEN_LABEL_LIMIT) {
            return null;
        }
        ndcX = Math.max(-1f, Math.min(1f, ndcX));
        ndcY = Math.max(-1f, Math.min(1f, ndcY));
        var screenX = (ndcX * 0.5f + 0.5f) * screenWidth;
        var screenY = (1f - (ndcY * 0.5f + 0.5f)) * screenHeight;
        return new Vec2(screenX, screenY);
    }

    public @Nullable Vec2 projectToScreenRaw(Vec3 world, int screenWidth, int screenHeight) {
        var clip = createProjectionMatrix().times(viewMatrix).times(new Vec4(world, 1f));
        var w = clip.getW();
        if (w <= 0f) {
            return null;
        }
        var ndcX = clip.getX() / w;
        var ndcY = clip.getY() / w;
        return new Vec2((ndcX * 0.5f + 0.5f) * screenWidth, (1f - (ndcY * 0.5f + 0.5f)) * screenHeight);
    }

    public void copyViewProjection(float[] target) {
        if (target == null || target.length < 16) {
            throw new IllegalArgumentException("view-projection target requires sixteen floats");
        }
        createProjectionMatrix().times(viewMatrix).to(target, 0);
    }

    public @Nullable Vec2 projectToNdc(Vec3 world) {
        var clip = createProjectionMatrix().times(viewMatrix).times(new Vec4(world, 1f));
        var w = clip.getW();
        if (w <= 0f) {
            return null;
        }
        return new Vec2(clip.getX() / w, clip.getY() / w);
    }

    public Vec3 getCursorRayDirection(float ndcX, float ndcY) {
        writeCursorRayDirection(ndcX, ndcY, cursorRayScratch);
        return new Vec3(cursorRayScratch[0], cursorRayScratch[1], cursorRayScratch[2]);
    }

    public Vec3 getCursorRayOrigin(float ndcX, float ndcY) {
        if (!ortho) {
            return new Vec3(position);
        }
        updateInverseViewProjection();
        var viewX = ndcX / rayBasis[12];
        var viewY = ndcY / rayBasis[13];
        return new Vec3(
                position.getX() + rayBasis[0] * viewX + rayBasis[4] * viewY,
                position.getY() + rayBasis[1] * viewX + rayBasis[5] * viewY,
                position.getZ() + rayBasis[2] * viewX + rayBasis[6] * viewY);
    }

    public void writeCursorRayDirection(float ndcX, float ndcY, float[] target) {
        if (target == null || target.length < 3) {
            throw new IllegalArgumentException("cursor ray target must contain at least three floats");
        }
        updateInverseViewProjection();
        if (ortho) {
            var dx = rayBasis[8];
            var dy = rayBasis[9];
            var dz = rayBasis[10];
            var len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            target[0] = dx / len;
            target[1] = dy / len;
            target[2] = dz / len;
            return;
        }
        var viewX = ndcX / rayBasis[12];
        var viewY = ndcY / rayBasis[13];
        var dx = rayBasis[0] * viewX + rayBasis[4] * viewY + rayBasis[8];
        var dy = rayBasis[1] * viewX + rayBasis[5] * viewY + rayBasis[9];
        var dz = rayBasis[2] * viewX + rayBasis[6] * viewY + rayBasis[10];
        var len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            var fallback = getForwardDir().negate();
            target[0] = fallback.getX();
            target[1] = fallback.getY();
            target[2] = fallback.getZ();
            return;
        }
        target[0] = dx / len;
        target[1] = dy / len;
        target[2] = dz / len;
    }

    public void copyInverseViewProjection(float[] target) {
        if (target == null || target.length < 16) {
            throw new IllegalArgumentException("inverse view-projection target requires sixteen floats");
        }
        updateInverseViewProjection();
        System.arraycopy(inverseViewProjection, 0, target, 0, 16);
    }

    public void copyRayBasis(float[] target) {
        if (target == null || target.length < 16) {
            throw new IllegalArgumentException("ray basis target requires sixteen floats");
        }
        updateInverseViewProjection();
        System.arraycopy(rayBasis, 0, target, 0, 16);
    }

    public void copyInverseView(float[] target) {
        if (target == null || target.length < 16) {
            throw new IllegalArgumentException("inverse view target requires sixteen floats");
        }
        orientation.toMat4().to(target, 0);
    }

    public boolean isOrtho() {
        return this.ortho;
    }

    private float getAltitudeNear() {
        var altitude = Math.abs(position.getZ());
        return altitude > NEAR_RAMP_START ? near + (altitude - NEAR_RAMP_START) * NEAR_RAMP_SLOPE : near;
    }

    private float getClearanceNear() {
        if (!(clearance > 0f)) {
            return 0f;
        }
        var width = (float) Math.max(1, context.framebuffer.width);
        var height = (float) Math.max(1, context.framebuffer.height);
        var xScale = lens.computeXScale(fov, width, height);
        var yScale = lens.computeYScale(fov, width, height);
        var cornerCosine = 1.0 / Math.sqrt(1.0 + 1.0 / ((double) xScale * xScale) + 1.0 / ((double) yScale * yScale));
        var asked = (float) (clearance * cornerCosine * CLEARANCE_NEAR_SHARE);
        return far > near ? Math.min(asked, far * CLEARANCE_NEAR_FAR_SHARE) : asked;
    }

    private void updateInverseViewProjection() {
        var width = context.framebuffer.width;
        var height = context.framebuffer.height;
        if (rayRevision == stateRevision && rayWidth == width && rayHeight == height) {
            return;
        }
        var projection = createProjectionMatrix();
        projection.times(viewMatrix).inverse().to(inverseViewProjection, 0);
        orientation.toMat4().to(rayBasis, 0);
        projection.to(rayProjectionScratch, 0);
        rayBasis[12] = rayProjectionScratch[0];
        rayBasis[13] = rayProjectionScratch[5];
        rayBasis[14] = 0f;
        rayBasis[15] = 0f;
        rayRevision = stateRevision;
        rayWidth = width;
        rayHeight = height;
    }

    private void updateViewMatrix() {
        orientation
                .conjugate()
                .toMat4()
                .translate(position.negate(), viewMatrix)
                .to(viewMatrixBuffer);
        stateRevision++;
    }

    private float depthFromEye(int coordinate) {
        return (coordinate - component(view.getDepthAxis(), position)) * view.getDepthDirection();
    }

    private Mat4 createProjectionMatrix() {
        var width = context.framebuffer.width;
        var height = context.framebuffer.height;
        if (cachedProjectionMatrix == null
                || projectionRevision != stateRevision
                || projectionWidth != width
                || projectionHeight != height) {
            cachedProjectionMatrix = buildProjectionMatrix();
            projectionRevision = stateRevision;
            projectionWidth = width;
            projectionHeight = height;
        }
        return cachedProjectionMatrix;
    }

    private Mat4 buildProjectionMatrix() {
        if (ortho) {
            var mapSize = MAP_SIZE * zoomFactor;
            var halfWidth = mapSize / 2f;
            var halfHeight = mapSize / 2f;
            var framebufferWidth = Math.max(1, context.framebuffer.width);
            var framebufferHeight = Math.max(1, context.framebuffer.height);
            var aspect = (float) framebufferWidth / framebufferHeight;
            if (aspect >= 1f) {
                halfWidth *= aspect;
            } else {
                halfHeight /= aspect;
            }
            var left = -halfWidth;
            var right = halfWidth;
            var bottom = -halfHeight;
            var top = halfHeight;
            return glm.INSTANCE.ortho(left, right, bottom, top, zNear, orthoFar);
        }
        return createPerspectiveMatrix(getProjectionNear());
    }

    private Mat4 createPerspectiveMatrix(float perspectiveNear) {
        var width = (float) Math.max(1, context.framebuffer.width);
        var height = (float) Math.max(1, context.framebuffer.height);
        var projection = new Mat4(0f);

        projection.set(0, 0, lens.computeXScale(fov, width, height));
        projection.set(1, 1, lens.computeYScale(fov, width, height));
        var depth = far > perspectiveNear ? far / (far - perspectiveNear) : 1f;
        projection.set(2, 2, depth);
        projection.set(2, 3, 1f);
        projection.set(3, 2, -(depth * perspectiveNear));
        return projection;
    }

    private static float extent(int axis, float dx, float dy, float dz) {
        return axis == 0 ? dx : axis == 1 ? dy : dz;
    }

    private static Vec3 rolledUp(Vec3 look, double rollDegrees) {
        var along = look.getZ();
        var upX = -look.getX() * along;
        var upY = -look.getY() * along;
        var upZ = 1 - look.getZ() * along;
        var length = (float) Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        upX /= length;
        upY /= length;
        upZ /= length;
        var rightX = upY * look.getZ() - upZ * look.getY();
        var rightY = upZ * look.getX() - upX * look.getZ();
        var rightZ = upX * look.getY() - upY * look.getX();
        var roll = Math.toRadians(rollDegrees);
        var cos = (float) Math.cos(roll);
        var sin = (float) Math.sin(roll);
        return new Vec3(upX * cos + rightX * sin, upY * cos + rightY * sin, upZ * cos + rightZ * sin);
    }

    private static Quat orthoOrientation(CameraView view) {
        return glm.INSTANCE.quatLookAtLH(view.getLook(), view.getUp());
    }

    private static float component(int axis, float x, float y, float z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static float component(int axis, Vec3 vector) {
        return component(axis, vector.getX(), vector.getY(), vector.getZ());
    }

    private static void setComponent(Vec3 vector, int axis, float value) {
        switch (axis) {
            case 0 -> vector.setX(value);
            case 1 -> vector.setY(value);
            default -> vector.setZ(value);
        }
    }
}
