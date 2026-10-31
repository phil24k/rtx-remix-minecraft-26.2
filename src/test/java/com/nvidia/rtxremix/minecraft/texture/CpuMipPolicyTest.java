/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuMipPolicyTest {
    @Test
    void automaticallyEnablesTheWorkaroundForWindowsAndLinuxRadeonVendors() {
        assertTrue(CpuMipPolicy.enabled(null, "ATI Technologies Inc."));
        assertTrue(CpuMipPolicy.enabled(null, "AMD"));
        assertTrue(CpuMipPolicy.enabled(null, "Advanced Micro Devices, Inc."));
    }

    @Test
    void keepsOtherVendorsOnTheRuntimeMipPath() {
        assertFalse(CpuMipPolicy.enabled(null, "NVIDIA Corporation"));
        assertFalse(CpuMipPolicy.enabled(null, "Intel"));
        assertFalse(CpuMipPolicy.enabled(null, null));
    }

    @Test
    void explicitOverrideWinsOverVendorDetection() {
        assertTrue(CpuMipPolicy.enabled("true", "NVIDIA Corporation"));
        assertFalse(CpuMipPolicy.enabled("false", "AMD"));
    }
}
