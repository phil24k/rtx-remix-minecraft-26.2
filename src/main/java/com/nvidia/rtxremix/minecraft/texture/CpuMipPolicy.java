/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Locale;

/** Selects the Radeon mip workaround without changing GeForce's texture path. */
final class CpuMipPolicy {
    private CpuMipPolicy() {}

    static boolean enabled(String override, String glVendor) {
        if (override != null) return Boolean.parseBoolean(override);
        if (glVendor == null) return false;
        String vendor = glVendor.toLowerCase(Locale.ROOT);
        return vendor.contains("amd") || vendor.contains("ati technologies")
                || vendor.contains("advanced micro devices");
    }
}
