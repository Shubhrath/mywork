/*
 * Copyright (C) 2022 The Android Open Source Project
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

/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import android.telephony.TelephonyManager
import com.android.settingslib.AccessibilityContentDescriptions
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.Flags.statusBarStaticInoutIndicators
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.NEW_NETWORK_SLICE_UI
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileIconCustomizationMode
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

object MobileSignalStates {
    const val LEVEL_MASK = 0xff
    const val STATE_SHIFT = 16
    const val STATE_MASK = 0xff shl STATE_SHIFT // Equivalent to 0xff0000

    // Values based on your getSignalIcon logic and SignalDrawable.java
    // STATE_NONE is typically 0, representing normal operation for a given level.
    const val STATE_NONE = 0
    // STATE_EMPTY might represent a "no signal" bar (often level 0) or a specific "empty" icon.
    // Your Java code has: if (state == STATE_EMPTY) signalRes = R.drawable.ic_qs_5bar_signal_no_service;
    // This suggests STATE_EMPTY is for the "no_service" icon.
    const val STATE_EMPTY = 1 // Assuming 1, needs to match how you set this state.
    // STATE_CUT is from SignalDrawable, usually for "no internet" or "data disabled" exclamation.
    // Your Java code has: } else if (state == STATE_NONE || state == STATE_CUT) { switch (level) ... }
    // This groups STATE_CUT with STATE_NONE for level-based icons, but might also have a distinct meaning.
    const val STATE_CUT = 2

    // This constant seems to be used in your Java snippet to differentiate
    // between normal level icons (e.g. ic_qs_5bar_signal_0) and
    // "full" versions (e.g. ic_qs_5bar_signal_full_0).
    // We'll use this interpretation. If STATE_NONE implies something else,
    // this logic will need adjustment.
    // Effectively, your Java code uses `state == STATE_NONE` as a boolean flag for "full" icons.
}

/** Common interface for all of the location-based mobile icon view models. */
interface MobileIconViewModelCommon {
    val subscriptionId: Int
    /** True if this view should be visible at all. */
    val isVisible: StateFlow<Boolean>
    val icon: Flow<SignalIconModel>
    /** True if a separate exclamation icon should be shown. */
    val showSeparateExclamationIcon: StateFlow<Boolean>
    val contentDescription: Flow<ContentDescription?>
    val roaming: Flow<Boolean>
    /** The RAT icon (LTE, 3G, 5G, etc) to be displayed. Null if we shouldn't show anything */
    val networkTypeIcon: Flow<Icon.Resource?>
    /** The slice attribution. Drawn as a background layer */
    val networkTypeBackground: StateFlow<Icon.Resource?>
    val activityInVisible: Flow<Boolean>
    val activityOutVisible: Flow<Boolean>
    val activityContainerVisible: Flow<Boolean>
    val volteId: Flow<Int>
    val showSignalStrengthIcon: Flow<Boolean>
    val staticSignalIconRes: StateFlow<Int> // New property for static icon resource
}

/**
 * View model for the state of a single mobile icon. Each [MobileIconViewModel] will keep watch over
 * a single line of service via [MobileIconInteractor] and update the UI based on that
 * subscription's information.
 *
 * There will be exactly one [MobileIconViewModel] per filtered subscription offered from
 * [MobileIconsInteractor.filteredSubscriptions].
 *
 * For the sake of keeping log spam in check, every flow funding the [MobileIconViewModelCommon]
 * interface is implemented as a [StateFlow]. This ensures that each location-based mobile icon view
 * model gets the exact same information, as well as allows us to log that unified state only once
 * per icon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileIconViewModel(
    override val subscriptionId: Int,
    iconInteractor: MobileIconInteractor,
    airplaneModeInteractor: AirplaneModeInteractor,
    constants: ConnectivityConstants,
    flags: FeatureFlagsClassic,
    scope: CoroutineScope,
) : MobileIconViewModelCommon {
    private val cellProvider by lazy {
        CellularIconViewModel(
            subscriptionId,
            iconInteractor,
            airplaneModeInteractor,
            constants,
            flags,
            scope,
        )
    }

    private val satelliteProvider by lazy {
        CarrierBasedSatelliteViewModelImpl(
            subscriptionId,
            iconInteractor,
        )
    }

    /**
     * Similar to repository switching, this allows us to split up the logic of satellite/cellular
     * states, since they are different by nature
     */
    private val vmProvider: Flow<MobileIconViewModelCommon> =
        iconInteractor.isNonTerrestrial
            .mapLatest { nonTerrestrial ->
                if (nonTerrestrial) {
                    satelliteProvider
                } else {
                    cellProvider
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), cellProvider)

    override val isVisible: StateFlow<Boolean> =
        vmProvider
            .flatMapLatest { it.isVisible }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val icon: Flow<SignalIconModel> = vmProvider.flatMapLatest { it.icon }

    override val contentDescription: Flow<ContentDescription?> =
        vmProvider.flatMapLatest { it.contentDescription }

    override val roaming: Flow<Boolean> = vmProvider.flatMapLatest { it.roaming }

    override val networkTypeIcon: Flow<Icon.Resource?> =
        vmProvider.flatMapLatest { it.networkTypeIcon }

    override val networkTypeBackground: StateFlow<Icon.Resource?> =
        vmProvider
            .flatMapLatest { it.networkTypeBackground }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val activityInVisible: Flow<Boolean> =
        vmProvider.flatMapLatest { it.activityInVisible }

    override val activityOutVisible: Flow<Boolean> =
        vmProvider.flatMapLatest { it.activityOutVisible }

    override val activityContainerVisible: Flow<Boolean> =
        vmProvider.flatMapLatest { it.activityContainerVisible }

    override val volteId: Flow<Int> =
        vmProvider.flatMapLatest { it.volteId }

    override val showSignalStrengthIcon: Flow<Boolean> =
        vmProvider.flatMapLatest { it.showSignalStrengthIcon }

    override val showSeparateExclamationIcon: StateFlow<Boolean> =
        vmProvider
            .flatMapLatest { provider ->
                // Cast to CellularIconViewModel to access the specific flow,
                // or provide a default for other types like CarrierBasedSatelliteViewModelImpl.
                if (provider is CellularIconViewModel) {
                    provider.showSeparateExclamationIcon
                } else {
                    flowOf(false) // Satellites or other types won't have this separate exclamation.
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val staticSignalIconRes: StateFlow<Int> =
        vmProvider
            .flatMapLatest { provider ->
                if (provider is CellularIconViewModel) {
                    provider.staticSignalIconRes
                } else {
                    // Default for satellite or other types
                    flowOf(R.drawable.ic_qs_5bar_signal_0) // Or a more appropriate default like 0
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), R.drawable.ic_qs_5bar_signal_0) // Initial default
}

/** Representation of this network when it is non-terrestrial (e.g., satellite) */
private class CarrierBasedSatelliteViewModelImpl(
    override val subscriptionId: Int,
    interactor: MobileIconInteractor,
) : MobileIconViewModelCommon {
    override val isVisible: StateFlow<Boolean> = MutableStateFlow(true)
    override val icon: Flow<SignalIconModel> = interactor.signalLevelIcon

    override val contentDescription: Flow<ContentDescription> =
        MutableStateFlow(ContentDescription.Loaded(""))

    /** These fields are not used for satellite icons currently */
    override val roaming: Flow<Boolean> = flowOf(false)
    override val networkTypeIcon: Flow<Icon.Resource?> = flowOf(null)
    override val networkTypeBackground: StateFlow<Icon.Resource?> = MutableStateFlow(null)
    override val activityInVisible: Flow<Boolean> = flowOf(false)
    override val activityOutVisible: Flow<Boolean> = flowOf(false)
    override val activityContainerVisible: Flow<Boolean> = flowOf(false)
    override val volteId: Flow<Int> = flowOf(0)
    override val showSignalStrengthIcon: Flow<Boolean> = flowOf(false)
    override val showSeparateExclamationIcon: StateFlow<Boolean> = flowOf(false)
    override val staticSignalIconRes: StateFlow<Int> =
        flowOf(R.drawable.ic_qs_5bar_signal_0) // Default for satellite
}

/** Terrestrial (cellular) icon. */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
private class CellularIconViewModel(
    override val subscriptionId: Int,
    iconInteractor: MobileIconInteractor,
    airplaneModeInteractor: AirplaneModeInteractor,
    constants: ConnectivityConstants,
    flags: FeatureFlagsClassic,
    scope: CoroutineScope,
) : MobileIconViewModelCommon {

    val staticSignalIconRes: StateFlow<Int> =
        combine(
            iconInteractor.signalLevelIcon,
            iconInteractor.isInService, // For STATE_EMPTY logic
            iconInteractor.carrierNetworkChangeActive // For carrier change state
        ) { sigModel, isInService, carrierNetworkChange ->
            if (sigModel !is SignalIconModel.Cellular) {
                // Default or placeholder for non-cellular models if encountered
                R.drawable.ic_qs_5bar_signal_0 // Or some other default
            } else {
                // Logic adapted from getSignalIcon and discussion
                val level = sigModel.level
                val showExclamation = sigModel.showExclamationMark

                // Determine effective state based on model properties
                val effectiveState = when {
                    carrierNetworkChange -> MobileSignalStates.STATE_NONE // Or a specific carrier change icon/state
                    !isInService && showExclamation -> MobileSignalStates.STATE_EMPTY // No service takes precedence for exclamation
                    showExclamation -> MobileSignalStates.STATE_CUT // Exclamation, but in service (e.g. data off)
                    else -> MobileSignalStates.STATE_NONE // Normal operation
                }

                when (effectiveState) {
                    MobileSignalStates.STATE_EMPTY -> R.drawable.ic_qs_5bar_signal_no_service
                    MobileSignalStates.STATE_CUT -> { // Not "full" icons
                        when (level) {
                            0 -> R.drawable.ic_qs_5bar_signal_0
                            1 -> R.drawable.ic_qs_5bar_signal_1
                            2 -> R.drawable.ic_qs_5bar_signal_2
                            3 -> R.drawable.ic_qs_5bar_signal_3
                            4 -> R.drawable.ic_qs_5bar_signal_4
                            5 -> R.drawable.ic_qs_5bar_signal_5
                            else -> R.drawable.ic_qs_5bar_signal_0 // Default for unexpected levels
                        }
                    }
                    MobileSignalStates.STATE_NONE -> { // "full" icons for normal state
                        when (level) {
                            0 -> R.drawable.ic_qs_5bar_signal_full_0
                            1 -> R.drawable.ic_qs_5bar_signal_full_1
                            2 -> R.drawable.ic_qs_5bar_signal_full_2
                            3 -> R.drawable.ic_qs_5bar_signal_full_3
                            4 -> R.drawable.ic_qs_5bar_signal_full_4
                            5 -> R.drawable.ic_qs_5bar_signal_full_5
                            else -> R.drawable.ic_qs_5bar_signal_full_0 // Default for unexpected levels
                        }
                    }
                    else -> R.drawable.ic_qs_5bar_signal_0 // Fallback for any other state
                }
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(), R.drawable.ic_qs_5bar_signal_0) // Initial default

    // showSeparateExclamationIcon will use the class's existing icon property
    val showSeparateExclamationIcon: StateFlow<Boolean> =
        this.icon // Use the existing icon property from the class
            .map { signalIconModel ->
                (signalIconModel is SignalIconModel.Cellular && signalIconModel.showExclamationMark)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isVisible: StateFlow<Boolean> =
        if (!constants.hasDataCapabilities) {
                flowOf(false)
            } else {
                combine(
                    airplaneModeInteractor.isAirplaneMode,
                    iconInteractor.isAllowedDuringAirplaneMode,
                    iconInteractor.isForceHidden,
                    iconInteractor.voWifiAvailable,
                ) { isAirplaneMode, isAllowedDuringAirplaneMode, isForceHidden, voWifiAvailable ->
                    if (voWifiAvailable) {
                        true
                    } else if (isForceHidden) {
                        false
                    } else if (isAirplaneMode) {
                        isAllowedDuringAirplaneMode
                    } else {
                        true
                    }
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "visible",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val icon: Flow<SignalIconModel> = iconInteractor.signalLevelIcon

    override val contentDescription: Flow<ContentDescription?> =
        iconInteractor.signalLevelIcon
            .map {
                // We expect the signal icon to be cellular here since this is the cellular vm
                if (it !is SignalIconModel.Cellular) {
                    null
                } else {
                    val resId =
                        AccessibilityContentDescriptions.getDescriptionForLevel(
                            it.level,
                            it.numberOfLevels
                        )
                    if (resId != 0) {
                        ContentDescription.Resource(resId)
                    } else {
                        null
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private val showNetworkTypeIcon: Flow<Boolean> =
        combine(
                iconInteractor.isDataConnected,
                iconInteractor.isDataEnabled,
                iconInteractor.alwaysShowDataRatIcon,
                iconInteractor.mobileIsDefault,
                iconInteractor.carrierNetworkChangeActive,
            ) { dataConnected, dataEnabled, alwaysShow, mobileIsDefault, carrierNetworkChange ->
                alwaysShow ||
                    (!carrierNetworkChange && (dataEnabled && dataConnected && mobileIsDefault))
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "showNetworkTypeIcon",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val networkTypeIcon: Flow<Icon.Resource?> =
        combine(
                iconInteractor.networkTypeIconGroup,
                showNetworkTypeIcon,
                iconInteractor.networkTypeIconCustomization,
                iconInteractor.voWifiAvailable,
                iconInteractor.isInService,
            ) { networkTypeIconGroup, shouldShow, networkTypeIconCustomization, voWifiAvailable,
                isInService ->
                val desc =
                    if (networkTypeIconGroup.contentDescription != 0)
                        ContentDescription.Resource(networkTypeIconGroup.contentDescription)
                    else null
                val icon =
                    if (voWifiAvailable) {
                        Icon.Resource(TelephonyIcons.VOWIFI.dataType, desc)
                    } else {
                        if (networkTypeIconGroup.iconId != 0)
                            Icon.Resource(networkTypeIconGroup.iconId, desc)
                        else null
                    }
                return@combine when {
                    voWifiAvailable -> icon
                    networkTypeIconCustomization.isRatCustomization -> {
                        if (shouldShowNetworkTypeIcon(networkTypeIconCustomization)
                            && isInService) {
                            icon
                        } else {
                            null
                        }
                    }
                    !shouldShow -> null
                    else -> icon
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val networkTypeBackground =
        if (!flags.isEnabled(NEW_NETWORK_SLICE_UI)) {
                flowOf(null)
            } else {
                iconInteractor.showSliceAttribution.map {
                    if (it) {
                        Icon.Resource(R.drawable.mobile_network_type_background, null)
                    } else {
                        null
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val roaming: StateFlow<Boolean> =
        iconInteractor.isRoaming
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "roaming",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)


    override val volteId =
        combine (
                iconInteractor.imsInfo,
                iconInteractor.showVolteIcon,
                iconInteractor.isInService,
        ) { imsInfo, showVolteIcon, isInService ->
             if (!showVolteIcon) {
                return@combine 0
            }
            val voiceNetworkType = imsInfo.voiceNetworkType
            val netWorkType = imsInfo.originNetworkType
            if ((imsInfo.voiceCapable || imsInfo.videoCapable) && imsInfo.imsRegistered) {
                return@combine R.drawable.ic_volte
            } else if ((netWorkType == TelephonyManager.NETWORK_TYPE_LTE
                        || netWorkType == TelephonyManager.NETWORK_TYPE_LTE_CA)
                && isInService
                && voiceNetworkType  == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                return@combine R.drawable.ic_volte_no_voice
            } else {
                return@combine 0
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    override val showSignalStrengthIcon =
        combine(
            airplaneModeInteractor.isAirplaneMode,
            iconInteractor.isForceHidden,
        ) { isAirplaneMode, isForceHidden ->
            !isAirplaneMode && !isForceHidden
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val activity: Flow<DataActivityModel?> =
        if (!constants.shouldShowActivityConfig) {
            flowOf(null)
        } else {
            iconInteractor.activity
        }

    override val activityInVisible: Flow<Boolean> =
        activity
            .map { it?.hasActivityIn ?: false }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val activityOutVisible: Flow<Boolean> =
        activity
            .map { it?.hasActivityOut ?: false }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val activityContainerVisible: Flow<Boolean> =
        if (statusBarStaticInoutIndicators()) {
                flowOf(constants.shouldShowActivityConfig)
            } else {
                activity.map { it != null && (it.hasActivityIn || it.hasActivityOut) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private fun shouldShowNetworkTypeIcon(mode: MobileIconCustomizationMode): Boolean {
        return (mode.alwaysShowNetworkTypeIcon
            || mode.ddsRatIconEnhancementEnabled && mode.isDefaultDataSub
            || mode.nonDdsRatIconEnhancementEnabled
                && mode.mobileDataEnabled && (mode.dataRoamingEnabled || !mode.isRoaming))
    }
}
