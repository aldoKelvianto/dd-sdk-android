/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay

import com.datadog.android.Datadog
import com.datadog.android.sessionreplay.internal.SessionReplayFeature
import com.datadog.android.v2.api.FeatureSdkCore
import com.datadog.android.v2.api.SdkCore

/**
 * An entry point to Datadog Session Replay feature.
 */
object SessionReplay {

    /**
     * Enables a SessionReplay feature based on the configuration provided.
     *
     * @param sessionReplayConfiguration Configuration to use for the feature.
     * @param sdkCore SDK instance to register feature in. If not provided, default SDK instance
     * will be used.
     */
    @JvmOverloads
    @JvmStatic
    fun enable(
        sessionReplayConfiguration: SessionReplayConfiguration,
        sdkCore: SdkCore = Datadog.getInstance()
    ) {
        val sessionReplayFeature = SessionReplayFeature(
            customEndpointUrl = sessionReplayConfiguration.customEndpointUrl,
            privacy = sessionReplayConfiguration.privacy,
            customMappers = sessionReplayConfiguration.customMappers,
            customOptionSelectorDetectors = sessionReplayConfiguration.customOptionSelectorDetectors
        )

        (sdkCore as FeatureSdkCore).registerFeature(sessionReplayFeature)
    }
}
