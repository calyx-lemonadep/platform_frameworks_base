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

package com.android.server.om;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.om.OverlayInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerServiceImplRebootTests extends OverlayManagerServiceImplTestsBase {

    private static final String OVERLAY = "com.dummy.overlay";
    private static final String TARGET = "com.dummy.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";

    @Test
    public void testUpdateOverlaysForUser() {
        final OverlayManagerServiceImpl impl = getImpl();
        installTargetPackage(TARGET, USER);
        installTargetPackage("some.other.target", USER);
        installOverlayPackage(OVERLAY, TARGET, USER);

        // do nothing, expect no change
        final List<String> a = impl.updateOverlaysForUser(USER);
        assertEquals(1, a.size());
        assertTrue(a.contains(TARGET));

        // upgrade overlay, keep target
        beginUpgradeOverlayPackage(OVERLAY, USER);
        endUpgradeOverlayPackage(OVERLAY, TARGET, USER);

        final List<String> b = impl.updateOverlaysForUser(USER);
        assertEquals(1, b.size());
        assertTrue(b.contains(TARGET));

        // do nothing, expect no change
        final List<String> c = impl.updateOverlaysForUser(USER);
        assertEquals(1, c.size());
        assertTrue(c.contains(TARGET));

        // upgrade overlay, switch to new target
        addOverlayPackage(OVERLAY, "some.other.target", USER, true, false, 0);
        final List<String> d = impl.updateOverlaysForUser(USER);
        assertEquals(2, d.size());
        assertTrue(d.containsAll(Arrays.asList(TARGET, "some.other.target")));

        // do nothing, expect no change
        final List<String> f = impl.updateOverlaysForUser(USER);
        assertEquals(1, f.size());
        assertTrue(f.contains("some.other.target"));
    }

    @Test
    public void testImmutableEnabledChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        installTargetPackage(TARGET, USER);

        addOverlayPackage(OVERLAY, TARGET, USER, false, false, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertFalse(o1.isEnabled());
        assertFalse(o1.isMutable);

        addOverlayPackage(OVERLAY, TARGET, USER, false, true, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o2);
        assertTrue(o2.isEnabled());
        assertFalse(o2.isMutable);

        addOverlayPackage(OVERLAY, TARGET, USER, false, false, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertFalse(o3.isMutable);
    }

    @Test
    public void testMutableEnabledChangeHasNoEffect() {
        final OverlayManagerServiceImpl impl = getImpl();
        installTargetPackage(TARGET, USER);

        addOverlayPackage(OVERLAY, TARGET, USER, true, false, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertFalse(o1.isEnabled());
        assertTrue(o1.isMutable);

        addOverlayPackage(OVERLAY, TARGET, USER, true, true, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o2);
        assertFalse(o2.isEnabled());
        assertTrue(o2.isMutable);

        addOverlayPackage(OVERLAY, TARGET, USER, true, false, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertTrue(o3.isMutable);
    }

    @Test
    public void testMutabilityChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        installTargetPackage(TARGET, USER);

        addOverlayPackage(OVERLAY, TARGET, USER, false, true, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertTrue(o1.isEnabled());
        assertFalse(o1.isMutable);

        addOverlayPackage(OVERLAY, TARGET, USER, true, false, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o2);
        assertFalse(o2.isEnabled());
        assertTrue(o2.isMutable);

        addOverlayPackage(OVERLAY, TARGET, USER, false, false, 0);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertFalse(o3.isMutable);
    }

    @Test
    public void testPriorityChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        installTargetPackage(TARGET, USER);

        addOverlayPackage(OVERLAY, TARGET, USER, false, true, 0);
        addOverlayPackage(OVERLAY2, TARGET, USER, false, true, 1);
        impl.updateOverlaysForUser(USER);

        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY2, USER);
        assertNotNull(o1);
        assertNotNull(o2);
        assertEquals(0, o1.priority);
        assertEquals(1, o2.priority);

        addOverlayPackage(OVERLAY, TARGET, USER, false, true, 1);
        addOverlayPackage(OVERLAY2, TARGET, USER, false, true, 0);
        impl.updateOverlaysForUser(USER);

        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        final OverlayInfo o4 = impl.getOverlayInfo(OVERLAY2, USER);
        assertNotNull(o3);
        assertNotNull(o4);
        assertEquals(1, o3.priority);
        assertEquals(0, o4.priority);
    }
}