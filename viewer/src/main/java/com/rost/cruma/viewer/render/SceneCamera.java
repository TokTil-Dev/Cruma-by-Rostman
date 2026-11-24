package com.rost.cruma.viewer.render;

import org.jetbrains.annotations.Nullable;

public final class SceneCamera {
    private static final float BEHIND = 220;
    private static final float DISTANCE_AT_ONCE = 1_000_000;
    private static final int ANGLE_AT_ONCE = 0x320000;
    private static final int HALF_TURN = 0x8000;
    private static final int TURN = 0x10000;
    private static final float NEAR = 40;
    private static final float NEAR_SPEED = 100;
    private static final float CLOSING = 50f / 20f;
    private static final int NEAR_ANGLE = 2000;
    private static final float NEAR_ANGLE_SPEED = 5000;

    private boolean active;
    private boolean flying;
    private @Nullable Target target;
    private @Nullable Target bone;
    private float distance;
    private float distanceGoal;
    private float distanceRate;
    private int yaw;
    private int yawGoal;
    private int yawRate;
    private int pitch;
    private int pitchGoal;
    private int pitchRate;
    private int relYaw;
    private int relYawGoal;
    private int relYawRate;
    private int relPitch;
    private int relPitchGoal;
    private int relPitchRate;
    private int relRoll;
    private int relRollGoal;
    private int relRollRate;
    private float hold;
    private float x;
    private float y;
    private float z;
    private int viewYaw;
    private int viewPitch;
    private int viewRoll;
    private float fov;

    public void reset() {
        active = false;
        flying = false;
        target = null;
        bone = null;
        fov = 0;
    }

    public void set(
            Target target,
            float distance,
            int yaw,
            int pitch,
            float time,
            float duration,
            int relYaw,
            int relPitch,
            int relRoll,
            boolean relative,
            boolean immediate,
            float fov) {
        var turned = relative ? yaw + target.getYaw() : yaw;
        if (fov != 0) {
            this.fov = fov;
        }
        if (!active || this.target != target) {
            this.distance = distance;
            this.yaw = turned;
            this.pitch = pitch;
            this.relYaw = 0;
            this.relPitch = 0;
            this.relRoll = 0;
            relYawRate = rate(relYaw, time);
            relPitchRate = rate(relPitch, time);
            relRollRate = rate(relRoll, time);
            if (immediate) {
                var direction = direction(pitch, turned);
                var away = distance + BEHIND;
                x = target.x() - direction[0] * away;
                y = target.y() - direction[1] * away;
                z = target.z() - direction[2] * away;
                viewPitch = pitch;
                viewYaw = turned;
                viewRoll = 0;
            }
        } else {
            distanceRate = time == 0 ? DISTANCE_AT_ONCE : Math.abs((this.distance - distance) / time);
            yawRate = rate(turned - this.yaw, time);
            pitchRate = rate(pitch - this.pitch, time);
            relYawRate = rate(relYaw - this.relYaw, time);
            relPitchRate = rate(relPitch - this.relPitch, time);
            relRollRate = rate(relRoll - this.relRoll, time);
            flying = false;
        }
        active = true;
        if (this.target != target) {
            this.target = target;
            flying = true;
        }
        distanceGoal = distance;
        yawGoal = turned;
        pitchGoal = pitch;
        hold = duration;
        relYawGoal = relYaw;
        relPitchGoal = relPitch;
        relRollGoal = relRoll;
    }

    public void standAt(float x, float y, float z, double yawDegrees, double pitchDegrees, double rollDegrees) {
        this.x = x;
        this.y = y;
        this.z = z;
        viewYaw = (int) Math.round(yawDegrees * TURN / 360);
        viewPitch = (int) Math.round(pitchDegrees * TURN / 360);
        viewRoll = (int) Math.round(rollDegrees * TURN / 360);
    }

    public void aroundBone(Target body, Target bone) {
        target = body;
        this.bone = bone;
    }

    public void aroundTarget() {
        bone = null;
    }

    public void update(float seconds) {
        var looked = bone == null ? target : bone;
        if (!active || looked == null) {
            return;
        }
        if (flying) {
            fly(looked, seconds);
            if (!flying) {
                return;
            }
        } else {
            follow(looked, seconds);
        }
        viewYaw = wrap(viewYaw + relYaw);
        viewPitch = wrap(viewPitch + relPitch);
        viewRoll = wrap(viewRoll + relRoll);
        hold -= seconds;
        if (hold <= 0) {
            release();
        }
    }

    public boolean isActive() {
        return active;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    public double getYawDegrees() {
        return viewYaw * 360.0 / TURN;
    }

    public double getPitchDegrees() {
        return viewPitch * 360.0 / TURN;
    }

    public double getRollDegrees() {
        return viewRoll * 360.0 / TURN;
    }

    public float getFov() {
        return fov;
    }

    public interface Target {
        float x();

        float y();

        float z();

        int getYaw();
    }

    private void release() {
        active = false;
        flying = false;
        target = null;
        fov = 0;
    }

    private void follow(Target looked, float seconds) {
        if (distanceGoal != distance) {
            var left = Math.abs(distanceGoal - distance);
            var step = Math.min(left, distanceRate * seconds);
            distance += distance <= distanceGoal ? step : -step;
        }
        yaw += step(yawGoal - yaw, yawRate, seconds);
        pitch += step(pitchGoal - pitch, pitchRate, seconds);
        var direction = direction(pitch, yaw);
        var away = distance + BEHIND;
        x = looked.x() - direction[0] * away;
        y = looked.y() - direction[1] * away;
        z = looked.z() - direction[2] * away;
        var dx = looked.x() - x;
        var dy = looked.y() - y;
        var dz = looked.z() - z;
        viewYaw = yawOf(dx, dy);
        viewPitch = pitchOf(dx, dy, dz);
        viewRoll = 0;
        relYaw += step(relYawGoal - relYaw, relYawRate, seconds);
        relPitch += step(relPitchGoal - relPitch, relPitchRate, seconds);
        relRoll += step(relRollGoal - relRoll, relRollRate, seconds);
    }

    private void fly(Target looked, float seconds) {
        var direction = direction(pitchGoal, yawGoal);
        var away = distanceGoal + BEHIND;
        var goalX = looked.x() - direction[0] * away;
        var goalY = looked.y() - direction[1] * away;
        var goalZ = looked.z() - direction[2] * away;
        var dx = goalX - x;
        var dy = goalY - y;
        var dz = goalZ - z;
        var left = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        var lookX = looked.x() - x;
        var lookY = looked.y() - y;
        var lookZ = looked.z() - z;
        var lookYaw = yawOf(lookX, lookY);
        var lookPitch = pitchOf(lookX, lookY, lookZ);
        if (left > 0) {
            var step = left > NEAR ? left * CLOSING * seconds : seconds * NEAR_SPEED;
            if (step < left) {
                x += dx / left * step;
                y += dy / left * step;
                z += dz / left * step;
            } else {
                x = goalX;
                y = goalY;
                z = goalZ;
            }
        }
        viewYaw = wrap(viewYaw + closing(lookYaw - viewYaw, seconds));
        viewPitch = wrap(viewPitch + closing(lookPitch - viewPitch, seconds));
        relYaw += closing(relYawGoal - relYaw, seconds);
        relPitch += closing(relPitchGoal - relPitch, seconds);
        relRoll += closing(relRollGoal - relRoll, seconds);
        if (left == 0 && wrap(lookYaw - viewYaw) == 0 && wrap(lookPitch - viewPitch) == 0) {
            flying = false;
            viewYaw = wrap(viewYaw + relYaw);
            viewPitch = wrap(viewPitch + relPitch);
            viewRoll = wrap(viewRoll + relRoll);
        }
    }

    private static int rate(int angle, float time) {
        return time == 0 ? ANGLE_AT_ONCE : (int) (Math.abs(wrap(angle)) / time);
    }

    private static int step(int angle, int rate, float seconds) {
        var left = wrap(angle);
        if (left == 0) {
            return 0;
        }
        var step = (int) ((left < 0 ? -rate : rate) * seconds);
        if (left > 0 && left < step || left < 0 && step < left) {
            return left;
        }
        return step;
    }

    private static int closing(int angle, float seconds) {
        var left = wrap(angle);
        if (left == 0) {
            return 0;
        }
        float speed;
        if (left >= -NEAR_ANGLE && left < 0) {
            speed = -NEAR_ANGLE_SPEED;
        } else if (left > 0 && left <= NEAR_ANGLE) {
            speed = NEAR_ANGLE_SPEED;
        } else {
            speed = left * CLOSING;
        }
        var step = (int) (speed * seconds);
        if (left > 0 && left < step || left < 0 && step < left) {
            return left;
        }
        return step;
    }

    private static int wrap(int angle) {
        var wrapped = angle;
        while (wrapped > HALF_TURN) {
            wrapped -= TURN;
        }
        while (wrapped < -HALF_TURN + 1) {
            wrapped += TURN;
        }
        return wrapped;
    }

    private static float[] direction(int pitch, int yaw) {
        var p = pitch * 2 * Math.PI / TURN;
        var w = yaw * 2 * Math.PI / TURN;
        return new float[] {
            (float) (Math.cos(p) * Math.cos(w)), (float) (Math.cos(p) * Math.sin(w)), (float) Math.sin(p)
        };
    }

    private static int yawOf(float x, float y) {
        return (int) Math.round(Math.atan2(y, x) * TURN / (2 * Math.PI));
    }

    private static int pitchOf(float x, float y, float z) {
        return (int) Math.round(Math.atan2(z, Math.sqrt(x * x + y * y)) * TURN / (2 * Math.PI));
    }
}
