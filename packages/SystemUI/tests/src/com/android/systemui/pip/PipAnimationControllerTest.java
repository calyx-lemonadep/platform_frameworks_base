/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.pip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.IWindowContainer;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests against {@link PipAnimationController} to ensure that it sends the right callbacks
 * depending on the various interactions.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class PipAnimationControllerTest extends SysuiTestCase {

    private PipAnimationController mPipAnimationController;

    @Mock
    private IWindowContainer mWindowContainer;

    @Mock
    private PipAnimationController.PipAnimationCallback mPipAnimationCallback;

    @Before
    public void setUp() throws Exception {
        mPipAnimationController = new PipAnimationController(mContext);
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void getAnimator_withAlpha_returnFloatAnimator() {
        final PipAnimationController.PipTransitionAnimator animator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        new Rect(), 0f, 1f);

        assertEquals("Expect ANIM_TYPE_ALPHA animation",
                animator.getAnimationType(), PipAnimationController.ANIM_TYPE_ALPHA);
    }

    @Test
    public void getAnimator_withBounds_returnBoundsAnimator() {
        final PipAnimationController.PipTransitionAnimator animator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        new Rect(), new Rect());

        assertEquals("Expect ANIM_TYPE_BOUNDS animation",
                animator.getAnimationType(), PipAnimationController.ANIM_TYPE_BOUNDS);
    }

    @Test
    public void getAnimator_whenSameTypeRunning_updateExistingAnimator() {
        final Rect startValue = new Rect(0, 0, 100, 100);
        final Rect endValue1 = new Rect(100, 100, 200, 200);
        final Rect endValue2 = new Rect(200, 200, 300, 300);
        final PipAnimationController.PipTransitionAnimator oldAnimator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        startValue, endValue1);
        oldAnimator.setSurfaceControlTransactionFactory(DummySurfaceControlTx::new);
        oldAnimator.start();

        final PipAnimationController.PipTransitionAnimator newAnimator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        startValue, endValue2);

        assertEquals("getAnimator with same type returns same animator",
                oldAnimator, newAnimator);
        assertEquals("getAnimator with same type updates end value",
                endValue2, newAnimator.getEndValue());
    }

    @Test
    public void getAnimator_scheduleFinishPip() {
        PipAnimationController.PipTransitionAnimator animator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        new Rect(), 0f, 1f);
        assertTrue("scheduleFinishPip is true", animator.shouldScheduleFinishPip());

        animator = mPipAnimationController
                .getAnimator(mWindowContainer, false /* scheduleFinishPip */,
                        new Rect(), 0f, 1f);
        assertFalse("scheduleFinishPip is false", animator.shouldScheduleFinishPip());
    }

    @Test
    public void pipTransitionAnimator_updateEndValue() {
        final Rect startValue = new Rect(0, 0, 100, 100);
        final Rect endValue1 = new Rect(100, 100, 200, 200);
        final Rect endValue2 = new Rect(200, 200, 300, 300);
        final PipAnimationController.PipTransitionAnimator animator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        startValue, endValue1);

        animator.updateEndValue(endValue2);

        assertEquals("updateEndValue updates end value", animator.getEndValue(), endValue2);
    }

    @Test
    public void pipTransitionAnimator_setPipAnimationCallback() {
        final Rect startValue = new Rect(0, 0, 100, 100);
        final Rect endValue = new Rect(100, 100, 200, 200);
        final PipAnimationController.PipTransitionAnimator animator = mPipAnimationController
                .getAnimator(mWindowContainer, true /* scheduleFinishPip */,
                        startValue, endValue);
        animator.setSurfaceControlTransactionFactory(DummySurfaceControlTx::new);

        animator.setPipAnimationCallback(mPipAnimationCallback);

        // onAnimationStart triggers onPipAnimationStart
        animator.onAnimationStart(animator);
        verify(mPipAnimationCallback).onPipAnimationStart(mWindowContainer, animator);

        // onAnimationCancel triggers onPipAnimationCancel
        animator.onAnimationCancel(animator);
        verify(mPipAnimationCallback).onPipAnimationCancel(mWindowContainer, animator);

        // onAnimationEnd triggers onPipAnimationEnd
        animator.onAnimationEnd(animator);
        verify(mPipAnimationCallback).onPipAnimationEnd(eq(mWindowContainer),
                any(SurfaceControl.Transaction.class), eq(animator));
    }

    /**
     * A dummy {@link SurfaceControl.Transaction} class.
     * This is created as {@link Mock} does not support method chaining.
     */
    private static class DummySurfaceControlTx extends SurfaceControl.Transaction {
        @Override
        public SurfaceControl.Transaction setAlpha(SurfaceControl leash, float alpha) {
            return this;
        }

        @Override
        public SurfaceControl.Transaction setPosition(SurfaceControl leash, float x, float y) {
            return this;
        }

        @Override
        public SurfaceControl.Transaction setWindowCrop(SurfaceControl leash, int w, int h) {
            return this;
        }

        @Override
        public void apply() {}
    }
}