/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics.drawable;

import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.util.MathUtils;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;

/**
 * Draws a Quantum Paper ripple.
 */
class Ripple {
    private static final TimeInterpolator INTERPOLATOR = new DecelerateInterpolator(2.0f);

    /** Starting radius for a ripple. */
    private static final int STARTING_RADIUS_DP = 40;

    /** Radius when finger is outside view bounds. */
    private static final int OUTSIDE_RADIUS_DP = 40;

    /** Margin when constraining outside touches (fraction of outer radius). */
    private static final float OUTSIDE_MARGIN = 0.8f;

    /** Resistance factor when constraining outside touches. */
    private static final float OUTSIDE_RESISTANCE = 0.7f;

    /** Duration for animating the trailing edge of the ripple. */
    private static final int EXIT_DURATION = 600;

    /** Duration for animating the leading edge of the ripple. */
    private static final int ENTER_DURATION = 400;

    /** Minimum elapsed time between start of enter and exit animations. */
    private static final int EXIT_MIN_DELAY = 200;

    /** Duration for animating between inside and outside touch. */
    private static final int OUTSIDE_DURATION = 300;

    /** Duration for animating pulses. */
    private static final int PULSE_DURATION = 400;

    /** Interval between pulses while inside and fully entered. */
    private static final int PULSE_INTERVAL = 400;

    /** Minimum alpha value during a pulse animation. */
    private static final int PULSE_MIN_ALPHA = 128;

    /** Delay before pulses start. */
    private static final int PULSE_DELAY = 500;

    private final Rect mBounds;
    private final Rect mPadding;
    private final int mMinRadius;
    private final int mOutsideRadius;

    /** Center x-coordinate. */
    private float mX;

    /** Center y-coordinate. */
    private float mY;

    /** Whether the center is within the parent bounds. */
    private boolean mInside;

    /** When the ripple started appearing. */
    private long mEnterTime = -1;

    /** When the ripple started vanishing. */
    private long mExitTime = -1;

    /** When the ripple last transitioned between inside and outside touch. */
    private long mOutsideTime = -1;

    /**
     * Creates a new ripple with the specified parent bounds, padding, initial
     * position, and screen density.
     */
    public Ripple(Rect bounds, Rect padding, float x, float y, float density) {
        mBounds = bounds;
        mPadding = padding;
        mInside = mBounds.contains((int) x, (int) y);

        mX = x;
        mY = y;

        mMinRadius = (int) (density * STARTING_RADIUS_DP + 0.5f);
        mOutsideRadius = (int) (density * OUTSIDE_RADIUS_DP + 0.5f);
    }

    /**
     * Updates the center coordinates.
     */
    public void move(float x, float y) {
        mX = x;
        mY = y;

        final boolean inside = mBounds.contains((int) x, (int) y);
        if (mInside != inside) {
            mOutsideTime = AnimationUtils.currentAnimationTimeMillis();
            mInside = inside;
        }
    }

    /**
     * Starts the enter animation.
     */
    public void enter() {
        mEnterTime = AnimationUtils.currentAnimationTimeMillis();
    }

    /**
     * Starts the exit animation. If {@link #enter()} was called recently, the
     * animation may be postponed.
     */
    public void exit() {
        final long minTime = mEnterTime + EXIT_MIN_DELAY;
        mExitTime = Math.max(minTime, AnimationUtils.currentAnimationTimeMillis());
    }

    /**
     * Returns whether this ripple is currently animating.
     */
    public boolean active() {
        final long currentTime = AnimationUtils.currentAnimationTimeMillis();
        return mEnterTime >= 0 && mEnterTime <= currentTime
                && (mExitTime < 0 || currentTime <= mExitTime + EXIT_DURATION);
    }

    /**
     * Constrains a value within a specified asymptotic margin outside a minimum
     * and maximum.
     */
    private static float looseConstrain(float value, float min, float max, float margin,
            float factor) {
        if (value < min) {
            return min - Math.min(margin, (float) Math.pow(min - value, factor));
        } else if (value > max) {
            return max + Math.min(margin, (float) Math.pow(value - max, factor));
        } else {
            return value;
        }
    }

    public void draw(Canvas c, Paint p) {
        final Rect bounds = mBounds;
        final Rect padding = mPadding;
        final float dX = Math.max(mX, bounds.right - mX);
        final float dY = Math.max(mY, bounds.bottom - mY);
        final int maxRadius = (int) Math.ceil(Math.sqrt(dX * dX + dY * dY));

        // Track three states:
        // - Enter: touch begins, affects outer radius
        // - Outside: touch moves outside bounds, affects maximum outer radius
        // - Exit: touch ends, affects inner radius
        final long currentTime = AnimationUtils.currentAnimationTimeMillis();
        final float enterState = mEnterTime < 0 ? 0 : INTERPOLATOR.getInterpolation(
                MathUtils.constrain((currentTime - mEnterTime) / (float) ENTER_DURATION, 0, 1));
        final float outsideState = mOutsideTime < 0 ? 1 : INTERPOLATOR.getInterpolation(
                MathUtils.constrain((currentTime - mOutsideTime) / (float) OUTSIDE_DURATION, 0, 1));
        final float exitState = mExitTime < 0 ? 0 : INTERPOLATOR.getInterpolation(
                MathUtils.constrain((currentTime - mExitTime) / (float) EXIT_DURATION, 0, 1));
        final float insideRadius = MathUtils.lerp(mMinRadius, maxRadius, enterState);
        final float outerRadius = MathUtils.lerp(mOutsideRadius, insideRadius,
                mInside ? outsideState : 1 - outsideState);

        // Apply resistance effect when outside bounds.
        final float x = looseConstrain(mX, bounds.left + padding.left, bounds.right - padding.right,
                outerRadius * OUTSIDE_MARGIN, OUTSIDE_RESISTANCE);
        final float y = looseConstrain(mY, bounds.top + padding.top, bounds.bottom - padding.bottom,
                outerRadius * OUTSIDE_MARGIN, OUTSIDE_RESISTANCE);

        // Compute maximum alpha, taking pulse into account when active.
        final long pulseTime = (currentTime - mEnterTime - ENTER_DURATION - PULSE_DELAY);
        final int maxAlpha;
        if (pulseTime < 0) {
            maxAlpha = 255;
        } else {
            final float pulseState = (pulseTime % (PULSE_INTERVAL + PULSE_DURATION))
                    / (float) PULSE_DURATION;
            if (pulseState >= 1) {
                maxAlpha = 255;
            } else {
                final float pulseAlpha;
                if (pulseState > 0.5) {
                    // Pulsing in to max alpha.
                    pulseAlpha = MathUtils.lerp(PULSE_MIN_ALPHA, 255, (pulseState - .5f) * 2);
                } else {
                    // Pulsing out to min alpha.
                    pulseAlpha = MathUtils.lerp(255, PULSE_MIN_ALPHA, pulseState * 2f);
                }

                if (exitState > 0) {
                    // Animating exit, interpolate pulse with exit state.
                    maxAlpha = (int) (MathUtils.lerp(255, pulseAlpha, exitState) + 0.5f);
                } else if (mInside) {
                    // No animation, no need to interpolate.
                    maxAlpha = (int) (pulseAlpha + 0.5f);
                } else {
                    // Animating inside, interpolate pulse with inside state.
                    maxAlpha = (int) (MathUtils.lerp(pulseAlpha, 255, outsideState) + 0.5f);
                }
            }
        }

        if (exitState <= 0) {
            // Exit state isn't showing, so we can simplify to a solid
            // circle.
            if (outerRadius > 0) {
                p.setAlpha(maxAlpha);
                p.setStyle(Style.FILL);
                c.drawCircle(x, y, outerRadius, p);
            }
        } else {
            // Both states are showing, so we need a circular stroke.
            final float innerRadius = MathUtils.lerp(0, outerRadius, exitState);
            final float strokeWidth = outerRadius - innerRadius;
            if (strokeWidth > 0) {
                final float strokeRadius = innerRadius + strokeWidth / 2f;
                final int alpha = (int) (MathUtils.lerp(maxAlpha, 0, exitState) + 0.5f);
                p.setAlpha(alpha);
                p.setStyle(Style.STROKE);
                p.setStrokeWidth(strokeWidth);
                c.drawCircle(x, y, strokeRadius, p);
            }
        }
    }
}
