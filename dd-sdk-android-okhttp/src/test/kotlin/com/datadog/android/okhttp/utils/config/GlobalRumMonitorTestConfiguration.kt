/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.okhttp.utils.config

import com.datadog.android.rum.GlobalRum
import com.datadog.android.rum.RumMonitor
import com.datadog.android.rum.internal.monitor.AdvancedNetworkRumMonitor
import com.datadog.android.v2.core.InternalSdkCore
import com.datadog.tools.unit.extensions.config.MockTestConfiguration
import com.nhaarman.mockitokotlin2.mock
import fr.xgouchet.elmyr.Forge

// TODO RUMM-2949 Share forgeries/test configurations between modules
internal class GlobalRumMonitorTestConfiguration(
    private val datadogSingletonTestConfiguration: DatadogSingletonTestConfiguration? = null
) : MockTestConfiguration<FakeRumMonitor>(FakeRumMonitor::class.java) {

    lateinit var mockSdkCore: InternalSdkCore

    override fun setUp(forge: Forge) {
        super.setUp(forge)
        mockSdkCore = datadogSingletonTestConfiguration?.mockInstance ?: mock()
        GlobalRum.registerIfAbsent(mockSdkCore, mockInstance)
    }

    override fun tearDown(forge: Forge) {
        GlobalRum::class.java.getDeclaredMethod("reset").apply {
            isAccessible = true
            invoke(null)
        }
        super.tearDown(forge)
    }
}

internal interface FakeRumMonitor : RumMonitor, AdvancedNetworkRumMonitor
