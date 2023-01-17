/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.internal

import com.datadog.android.sessionreplay.RecordCallback
import com.datadog.android.v2.api.SdkCore

internal class SessionReplayRecordCallback(private val sdkCore: SdkCore) : RecordCallback {

    override fun onRecordForViewSent(viewId: String) {
        sdkCore.updateFeatureContext(SessionReplayFeature.SESSION_REPLAY_FEATURE_NAME) {
            it[viewId] = true
        }
    }
}
