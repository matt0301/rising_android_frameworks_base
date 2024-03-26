/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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

package android.util;

import com.android.server.LocalServices;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManagerInternal;
import android.util.Log;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.time.Instant;
import java.util.Set;
import java.util.Map;

/** @hide */
public class RisingBoostFramework {

    private static final String TAG = "RisingBoostFramework";
    public final static boolean DEBUG = false;

    /**
    * For reference, see
    * hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
    **/
    private int HINT_RENDER = PowerManagerInternal.MODE_EXPENSIVE_RENDERING;
    private int HINT_INTERACTION = PowerManagerInternal.BOOST_INTERACTION;
    private int HINT_LAUNCH = PowerManagerInternal.MODE_LAUNCH;
    private int HINT_LOW_POWER = PowerManagerInternal.MODE_LOW_POWER;
    private int HINT_SUSTAINED = PowerManagerInternal.MODE_SUSTAINED_PERFORMANCE;
    private int HINT_FIXED_PERF = PowerManagerInternal.MODE_FIXED_PERFORMANCE;
    private final static int HINT_GAME = 15;
    private final static int HINT_GAME_LOADING = 16;

    private static RisingBoostFramework instance;

    private PowerManagerInternal mPowerManagerInternal;
    private EnumMap<WorkloadType, HintInfo> workloadHints;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Instant mPreviousTimeout = null;
    private boolean mBoostActive = false;
    private int mLastBoostDuration = 0;

    private ArrayMap<String, Boolean> gamePackageMap = new ArrayMap<>();

    // Boost/Mode decay durations
    private final static int POWER_BOOST_TIMEOUT_MS = 15 * 1000;
    private final static int LONG_DURATION_MS = 300 * 1000;
    private final static int WORKLOAD_GAME_DURATION_MS = 300 * 1000;
    private final static int WORKLOAD_LOW_POWER_MS = 300 * 1000;
    
    private HintType BOOST = HintType.BOOST;
    private HintType MODE = HintType.MODE;

    /** @hide */
    private RisingBoostFramework() {
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        initHintInfos();
    }

    /** @hide */
    public static synchronized RisingBoostFramework getInstance() {
        if (instance == null) {
            instance = new RisingBoostFramework();
        }
        return instance;
    }

    private void initHintInfos() {
        workloadHints = new EnumMap<>(WorkloadType.class);
        workloadHints.put(WorkloadType.ANIMATION, new HintInfo(HINT_INTERACTION, BOOST, 0));
        workloadHints.put(WorkloadType.SCROLLING, new HintInfo(HINT_INTERACTION, BOOST, 0));
        workloadHints.put(WorkloadType.VENDOR_HINT_KILL, new HintInfo(HINT_INTERACTION, BOOST, 0));
        workloadHints.put(WorkloadType.TAP_EVENT, new HintInfo(HINT_INTERACTION, BOOST, 0));
        workloadHints.put(WorkloadType.VENDOR_HINT_ROTATION_LATENCY_BOOST, new HintInfo(HINT_INTERACTION, BOOST, 0));
        workloadHints.put(WorkloadType.LOADING, new HintInfo(HINT_INTERACTION, BOOST, POWER_BOOST_TIMEOUT_MS));
        workloadHints.put(WorkloadType.VENDOR_HINT_PACKAGE_INSTALL_BOOST, new HintInfo(HINT_INTERACTION, BOOST, POWER_BOOST_TIMEOUT_MS));
        workloadHints.put(WorkloadType.LAUNCH, new HintInfo(HINT_LAUNCH, MODE, POWER_BOOST_TIMEOUT_MS));
        workloadHints.put(WorkloadType.GAME, new HintInfo(HINT_GAME, MODE, WORKLOAD_GAME_DURATION_MS));
        workloadHints.put(WorkloadType.HEAVY_GAME, new HintInfo(HINT_RENDER, MODE, WORKLOAD_GAME_DURATION_MS));
        workloadHints.put(WorkloadType.LOW_POWER, new HintInfo(HINT_LOW_POWER, MODE, WORKLOAD_LOW_POWER_MS));
        workloadHints.put(WorkloadType.SUSTAINED_PERFORMANCE, new HintInfo(HINT_SUSTAINED, MODE, LONG_DURATION_MS));
        workloadHints.put(WorkloadType.FIXED_PERFORMANCE, new HintInfo(HINT_FIXED_PERF, MODE, LONG_DURATION_MS));
        

    }

    public enum HintType {
        BOOST,
        MODE
    }

    /**
     * Enum for different types of workloads.
     * @hide
     */
    public enum WorkloadType {
        ANIMATION,
        GAME,
        HEAVY_GAME,
        LAUNCH,
        LOADING,
        SCROLLING,
        TAP_EVENT,
        LOW_POWER,
        SUSTAINED_PERFORMANCE,
        FIXED_PERFORMANCE,
        VENDOR_HINT_KILL,
        VENDOR_HINT_PACKAGE_INSTALL_BOOST,
        VENDOR_HINT_ROTATION_LATENCY_BOOST
    }

    /**
     * Enum for hint informations.
     * @hide
     */
    private static class HintInfo {
        final int hint;
        final HintType hintType;
        final int duration;
        HintInfo(int hint, HintType hintType, int duration) {
            this.hint = hint;
            this.hintType = hintType;
            this.duration = duration;
        }
    }

    /**
     * Apply boost or mode based on the specified workload type.
     *
     * @param workloadType The type of workload to apply.
     * @hide
     */
    public void perfBoost(WorkloadType workloadType) {
        HintInfo hintInfo = workloadHints.get(workloadType);
        if (hintInfo != null) {
            perfBoost(workloadType, hintInfo.duration);
        }
    }

    /**
     * Apply boost or mode based on the specified workload type with the given duration.
     *
     * @param workloadType The type of workload to apply.
     * @param duration     The duration for which the boost or mode should be applied.
     * @hide
     */
    public void perfBoost(WorkloadType workloadType, int duration) {
        if (mPowerManagerInternal == null) {
            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        }
        if (mPowerManagerInternal == null || mBoostActive) return;
        HintInfo hintInfo = workloadHints.get(workloadType);
        if (hintInfo == null) return;
        final boolean boostAllowed = mPreviousTimeout == null || Instant.now().isAfter(
                mPreviousTimeout.plusMillis(mLastBoostDuration));
        if (boostAllowed) {
            mPreviousTimeout = Instant.now();
            if (hintInfo.hintType == BOOST) {
                mPowerManagerInternal.setPowerBoost(hintInfo.hint, duration);
            } else if (hintInfo.hintType == MODE) {
                mPowerManagerInternal.setPowerMode(hintInfo.hint, true);
            }
            mLastBoostDuration = duration;
            mBoostActive = true;
            handler.postDelayed(() -> {
                if (hintInfo.hintType == MODE) {
                    mPowerManagerInternal.setPowerMode(hintInfo.hint, false);
                }
                mBoostActive = false;
            }, duration);
        } else {
            if (DEBUG) Log.d(TAG, "Previous boost still active, skipping new boost.");
        }
    }

    /**
     * Add a package to the game package list.
     *
     * @param packageName The name of the package to add.
     * @hide
     */
    public void addPackageToGameList(String packageName) {
        gamePackageMap.put(packageName, true);
    }

    /**
     * Check a package if its on the game package list.
     *
     * @param packageName The name of the package to check.
     * @hide
     */
    public boolean isPackageOnGameList(String packageName) {
        return gamePackageMap.containsKey(packageName);
    }
}
