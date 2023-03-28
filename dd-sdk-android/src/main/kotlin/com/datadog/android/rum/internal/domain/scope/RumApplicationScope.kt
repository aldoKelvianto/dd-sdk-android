/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.rum.internal.domain.scope

import androidx.annotation.WorkerThread
import com.datadog.android.core.internal.net.FirstPartyHostHeaderTypeResolver
import com.datadog.android.rum.RumSessionListener
import com.datadog.android.rum.internal.RumFeature
import com.datadog.android.rum.internal.domain.RumContext
import com.datadog.android.rum.internal.vitals.VitalMonitor
import com.datadog.android.v2.api.SdkCore
import com.datadog.android.v2.core.internal.ContextProvider
import com.datadog.android.v2.core.internal.storage.DataWriter

@Suppress("LongParameterList")
internal class RumApplicationScope(
    applicationId: String,
    private val sdkCore: SdkCore,
    internal val samplingRate: Float,
    internal val backgroundTrackingEnabled: Boolean,
    internal val trackFrustrations: Boolean,
    private val firstPartyHostHeaderTypeResolver: FirstPartyHostHeaderTypeResolver,
    private val cpuVitalMonitor: VitalMonitor,
    private val memoryVitalMonitor: VitalMonitor,
    private val frameRateVitalMonitor: VitalMonitor,
    private val sessionListener: RumSessionListener?,
    private val contextProvider: ContextProvider
) : RumScope {

    private val rumContext = RumContext(applicationId = applicationId)
    internal val childScopes: MutableList<RumScope> = mutableListOf(
        RumSessionScope(
            this,
            sdkCore,
            samplingRate,
            backgroundTrackingEnabled,
            trackFrustrations,
            firstPartyHostHeaderTypeResolver,
            cpuVitalMonitor,
            memoryVitalMonitor,
            frameRateVitalMonitor,
            sessionListener,
            contextProvider,
            false
        )
    )

    val activeSession: RumScope?
        get() {
            return childScopes.find { it.isActive() }
        }
    private var lastActiveViewScope: RumViewScope? = null

    // region RumScope

    @WorkerThread
    override fun handleEvent(
        event: RumRawEvent,
        writer: DataWriter<Any>
    ): RumScope {
        val isInteraction = (event is RumRawEvent.StartView) || (event is RumRawEvent.StartAction)
        if (activeSession == null && isInteraction) {
            startNewSession(event, writer)
        } else if (event is RumRawEvent.StopSession) {
            // Grab the last active view before the session shuts down
            lastActiveViewScope = (activeSession as? RumSessionScope)?.lastActiveViewScope
            sdkCore.updateFeatureContext(RumFeature.RUM_FEATURE_NAME) {
                it.putAll(getRumContext().toMap())
            }
        }

        delegateToChildren(event, writer)

        return this
    }

    override fun isActive(): Boolean {
        return true
    }

    override fun getRumContext(): RumContext {
        return rumContext
    }

    // endregion

    @WorkerThread
    private fun delegateToChildren(
        event: RumRawEvent,
        writer: DataWriter<Any>
    ) {
        val iterator = childScopes.iterator()
        @Suppress("UnsafeThirdPartyFunctionCall") // next/remove can't fail: we checked hasNext
        while (iterator.hasNext()) {
            val result = iterator.next().handleEvent(event, writer)
            if (result == null) {
                iterator.remove()
            }
        }
    }

    @WorkerThread
    private fun startNewSession(event: RumRawEvent, writer: DataWriter<Any>) {
        val newSession = RumSessionScope(
            this,
            sdkCore,
            samplingRate,
            backgroundTrackingEnabled,
            trackFrustrations,
            firstPartyHostHeaderTypeResolver,
            cpuVitalMonitor,
            memoryVitalMonitor,
            frameRateVitalMonitor,
            sessionListener,
            contextProvider,
            true
        )
        childScopes.add(newSession)
        if (event !is RumRawEvent.StartView) {
            lastActiveViewScope?.let {
                if (it.keyRef.get() != null) {
                    // Restart the last active view
                    val startViewEvent = RumRawEvent.StartView(
                        key = it.keyRef.get()!!,
                        name = it.name,
                        attributes = it.attributes
                    )
                    newSession.handleEvent(startViewEvent, writer)
                }
            }
        }
        lastActiveViewScope = null
    }
}
