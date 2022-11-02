/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.rum.internal.domain.scope

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.annotation.WorkerThread
import androidx.fragment.app.Fragment
import com.datadog.android.core.internal.net.FirstPartyHostDetector
import com.datadog.android.core.internal.system.BuildSdkVersionProvider
import com.datadog.android.core.internal.system.DefaultBuildSdkVersionProvider
import com.datadog.android.core.internal.utils.devLogger
import com.datadog.android.core.internal.utils.loggableStackTrace
import com.datadog.android.core.internal.utils.resolveViewUrl
import com.datadog.android.core.internal.utils.sdkLogger
import com.datadog.android.log.internal.utils.debugWithTelemetry
import com.datadog.android.rum.GlobalRum
import com.datadog.android.rum.RumActionType
import com.datadog.android.rum.RumAttributes
import com.datadog.android.rum.RumPerformanceMetric
import com.datadog.android.rum.internal.FeaturesContextResolver
import com.datadog.android.rum.internal.RumFeature
import com.datadog.android.rum.internal.domain.RumContext
import com.datadog.android.rum.internal.domain.Time
import com.datadog.android.rum.internal.domain.event.RumEventSourceProvider
import com.datadog.android.rum.internal.vitals.VitalInfo
import com.datadog.android.rum.internal.vitals.VitalListener
import com.datadog.android.rum.internal.vitals.VitalMonitor
import com.datadog.android.rum.model.ActionEvent
import com.datadog.android.rum.model.ErrorEvent
import com.datadog.android.rum.model.LongTaskEvent
import com.datadog.android.rum.model.ViewEvent
import com.datadog.android.v2.api.SdkCore
import com.datadog.android.v2.core.internal.ContextProvider
import com.datadog.android.v2.core.internal.storage.DataWriter
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@Suppress("LargeClass", "LongParameterList")
internal open class RumViewScope(
    private val parentScope: RumScope,
    private val sdkCore: SdkCore,
    key: Any,
    internal val name: String,
    eventTime: Time,
    initialAttributes: Map<String, Any?>,
    internal val firstPartyHostDetector: FirstPartyHostDetector,
    internal val cpuVitalMonitor: VitalMonitor,
    internal val memoryVitalMonitor: VitalMonitor,
    internal val frameRateVitalMonitor: VitalMonitor,
    private val rumEventSourceProvider: RumEventSourceProvider,
    private val contextProvider: ContextProvider,
    private val buildSdkVersionProvider: BuildSdkVersionProvider = DefaultBuildSdkVersionProvider(),
    private val viewUpdatePredicate: ViewUpdatePredicate = DefaultViewUpdatePredicate(),
    private val featuresContextResolver: FeaturesContextResolver = FeaturesContextResolver(),
    internal val type: RumViewType = RumViewType.FOREGROUND,
    private val trackFrustrations: Boolean
) : RumScope {

    internal val url = key.resolveViewUrl().replace('.', '/')

    internal val keyRef: Reference<Any> = WeakReference(key)
    internal val attributes: MutableMap<String, Any?> = initialAttributes.toMutableMap().apply {
        putAll(GlobalRum.globalAttributes)
    }

    private var sessionId: String = parentScope.getRumContext().sessionId
    internal var viewId: String = UUID.randomUUID().toString()
        private set
    private val startedNanos: Long = eventTime.nanoTime

    internal val serverTimeOffsetInMs = contextProvider.context.time.serverTimeOffsetMs
    internal val eventTimestamp = eventTime.timestamp + serverTimeOffsetInMs

    internal var activeActionScope: RumScope? = null
    internal val activeResourceScopes = mutableMapOf<String, RumScope>()

    private var resourceCount: Long = 0
    private var actionCount: Long = 0
    private var frustrationCount: Int = 0
    private var errorCount: Long = 0
    private var crashCount: Long = 0
    private var longTaskCount: Long = 0
    private var frozenFrameCount: Long = 0

    // TODO RUMM-0000 We have now access to the event write result through the closure,
    // we probably can drop AdvancedRumMonitor#eventSent/eventDropped usage
    internal var pendingResourceCount: Long = 0
    internal var pendingActionCount: Long = 0
    internal var pendingErrorCount: Long = 0
    internal var pendingLongTaskCount: Long = 0
    internal var pendingFrozenFrameCount: Long = 0

    private var version: Long = 1
    private var loadingTime: Long? = null
    private var loadingType: ViewEvent.LoadingType? = null
    private val customTimings: MutableMap<String, Long> = mutableMapOf()

    internal var stopped: Boolean = false

    // region Vitals Fields

    private var cpuTicks: Double? = null
    private var cpuVitalListener: VitalListener = object : VitalListener {
        private var initialTickCount: Double = Double.NaN
        override fun onVitalUpdate(info: VitalInfo) {
            // The CPU Ticks will always grow, as it's the total ticks since the app started
            if (initialTickCount.isNaN()) {
                initialTickCount = info.maxValue
            } else {
                cpuTicks = info.maxValue - initialTickCount
            }
        }
    }

    private var lastMemoryInfo: VitalInfo? = null
    private var memoryVitalListener: VitalListener = object : VitalListener {
        override fun onVitalUpdate(info: VitalInfo) {
            lastMemoryInfo = info
        }
    }

    private var refreshRateScale: Double = 1.0
    private var lastFrameRateInfo: VitalInfo? = null
    private var frameRateVitalListener: VitalListener = object : VitalListener {
        override fun onVitalUpdate(info: VitalInfo) {
            lastFrameRateInfo = info
        }
    }

    private var performanceMetrics: MutableMap<RumPerformanceMetric, VitalInfo> = mutableMapOf()

    // endregion

    init {
        updateOffsetInFeatureContext()
        GlobalRum.updateRumContext(getRumContext())
        attributes.putAll(GlobalRum.globalAttributes)
        cpuVitalMonitor.register(cpuVitalListener)
        memoryVitalMonitor.register(memoryVitalListener)
        frameRateVitalMonitor.register(frameRateVitalListener)

        detectRefreshRateScale(key)
    }

    // region RumScope

    @WorkerThread
    override fun handleEvent(
        event: RumRawEvent,
        writer: DataWriter<Any>
    ): RumScope? {
        when (event) {
            is RumRawEvent.ResourceSent -> onResourceSent(event, writer)
            is RumRawEvent.ActionSent -> onActionSent(event, writer)
            is RumRawEvent.ErrorSent -> onErrorSent(event, writer)
            is RumRawEvent.LongTaskSent -> onLongTaskSent(event, writer)

            is RumRawEvent.ResourceDropped -> onResourceDropped(event)
            is RumRawEvent.ActionDropped -> onActionDropped(event)
            is RumRawEvent.ErrorDropped -> onErrorDropped(event)
            is RumRawEvent.LongTaskDropped -> onLongTaskDropped(event)

            is RumRawEvent.StartView -> onStartView(event, writer)
            is RumRawEvent.StopView -> onStopView(event, writer)
            is RumRawEvent.StartAction -> onStartAction(event, writer)
            is RumRawEvent.StartResource -> onStartResource(event, writer)
            is RumRawEvent.AddError -> onAddError(event, writer)
            is RumRawEvent.AddLongTask -> onAddLongTask(event, writer)

            is RumRawEvent.ApplicationStarted -> onApplicationStarted(event, writer)
            is RumRawEvent.UpdateViewLoadingTime -> onUpdateViewLoadingTime(event, writer)
            is RumRawEvent.AddCustomTiming -> onAddCustomTiming(event, writer)
            is RumRawEvent.KeepAlive -> onKeepAlive(event, writer)

            is RumRawEvent.UpdatePerformanceMetric -> onUpdatePerformanceMetric(event)

            else -> delegateEventToChildren(event, writer)
        }

        return if (isViewComplete()) {
            null
        } else {
            this
        }
    }

    override fun getRumContext(): RumContext {
        val parentContext = parentScope.getRumContext()
        if (parentContext.sessionId != sessionId) {
            sessionId = parentContext.sessionId
            viewId = UUID.randomUUID().toString()
        }

        return parentContext
            .copy(
                viewId = viewId,
                viewName = name,
                viewUrl = url,
                actionId = (activeActionScope as? RumActionScope)?.actionId,
                viewType = type
            )
    }

    override fun isActive(): Boolean {
        return !stopped
    }

    // endregion

    // region Internal

    @WorkerThread
    private fun onStartView(
        event: RumRawEvent.StartView,
        writer: DataWriter<Any>
    ) {
        if (!stopped) {
            // no need to update RUM Context here erasing current view, because this is called
            // only with event starting a new view, which itself will update a context
            // at the construction time
            stopped = true
            sendViewUpdate(event, writer)
            delegateEventToChildren(event, writer)
        }
    }

    @WorkerThread
    private fun onStopView(
        event: RumRawEvent.StopView,
        writer: DataWriter<Any>
    ) {
        delegateEventToChildren(event, writer)
        val startedKey = keyRef.get()
        val shouldStop = (event.key == startedKey) || (startedKey == null)
        if (shouldStop && !stopped) {
            GlobalRum.updateRumContext(
                getRumContext().copy(
                    viewType = RumViewType.NONE,
                    viewId = null,
                    viewName = null,
                    viewUrl = null,
                    actionId = null
                ),
                applyOnlyIf = { currentContext ->
                    when {
                        currentContext.sessionId != this.sessionId -> {
                            // we have a new session, so whatever is in the Global context is
                            // not valid anyway
                            true
                        }
                        currentContext.viewId == this.viewId -> {
                            true
                        }
                        else -> {
                            sdkLogger.debugWithTelemetry(
                                RUM_CONTEXT_UPDATE_IGNORED_AT_STOP_VIEW_MESSAGE
                            )
                            false
                        }
                    }
                }
            )
            attributes.putAll(event.attributes)
            stopped = true
            sendViewUpdate(event, writer)
        }
    }

    @WorkerThread
    private fun onStartAction(
        event: RumRawEvent.StartAction,
        writer: DataWriter<Any>
    ) {
        delegateEventToChildren(event, writer)

        if (stopped) return

        if (activeActionScope != null) {
            if (event.type == RumActionType.CUSTOM && !event.waitForStop) {
                // deliver it anyway, even if there is active action ongoing
                val customActionScope = RumActionScope.fromEvent(
                    this,
                    sdkCore,
                    event,
                    serverTimeOffsetInMs,
                    rumEventSourceProvider,
                    featuresContextResolver,
                    trackFrustrations
                )
                pendingActionCount++
                customActionScope.handleEvent(RumRawEvent.SendCustomActionNow(), writer)
                return
            } else {
                devLogger.w(ACTION_DROPPED_WARNING.format(Locale.US, event.type, event.name))
                return
            }
        }

        updateActiveActionScope(
            RumActionScope.fromEvent(
                this,
                sdkCore,
                event,
                serverTimeOffsetInMs,
                rumEventSourceProvider,
                featuresContextResolver,
                trackFrustrations
            )
        )
        pendingActionCount++
    }

    @WorkerThread
    private fun onStartResource(
        event: RumRawEvent.StartResource,
        writer: DataWriter<Any>
    ) {
        delegateEventToChildren(event, writer)
        if (stopped) return

        val updatedEvent = event.copy(
            attributes = addExtraAttributes(event.attributes)
        )
        activeResourceScopes[event.key] = RumResourceScope.fromEvent(
            this,
            sdkCore,
            updatedEvent,
            firstPartyHostDetector,
            serverTimeOffsetInMs,
            rumEventSourceProvider,
            contextProvider,
            featuresContextResolver
        )
        pendingResourceCount++
    }

    @Suppress("ComplexMethod", "LongMethod")
    @WorkerThread
    private fun onAddError(
        event: RumRawEvent.AddError,
        writer: DataWriter<Any>
    ) {
        delegateEventToChildren(event, writer)
        if (stopped) return

        val rumContext = getRumContext()

        val updatedAttributes = addExtraAttributes(event.attributes)
        val isFatal = updatedAttributes.remove(RumAttributes.INTERNAL_ERROR_IS_CRASH) as? Boolean
        val errorType = event.type ?: event.throwable?.javaClass?.canonicalName
        val throwableMessage = event.throwable?.message ?: ""
        val message = if (throwableMessage.isNotBlank() && event.message != throwableMessage) {
            "${event.message}: $throwableMessage"
        } else {
            event.message
        }

        sdkCore.getFeature(RumFeature.RUM_FEATURE_NAME)
            ?.withWriteContext { datadogContext, eventBatchWriter ->

                val user = datadogContext.userInfo
                val networkInfo = datadogContext.networkInfo
                val hasReplay = featuresContextResolver.resolveHasReplay(datadogContext)

                val errorEvent = ErrorEvent(
                    date = event.eventTime.timestamp + serverTimeOffsetInMs,
                    error = ErrorEvent.Error(
                        message = message,
                        source = event.source.toSchemaSource(),
                        stack = event.stacktrace ?: event.throwable?.loggableStackTrace(),
                        isCrash = event.isFatal || (isFatal ?: false),
                        type = errorType,
                        sourceType = event.sourceType.toSchemaSourceType()
                    ),
                    action = rumContext.actionId?.let { ErrorEvent.Action(listOf(it)) },
                    view = ErrorEvent.View(
                        id = rumContext.viewId.orEmpty(),
                        name = rumContext.viewName,
                        url = rumContext.viewUrl.orEmpty()
                    ),
                    usr = ErrorEvent.Usr(
                        id = user.id,
                        name = user.name,
                        email = user.email,
                        additionalProperties = user.additionalProperties.toMutableMap()
                    ),
                    connectivity = networkInfo.toErrorConnectivity(),
                    application = ErrorEvent.Application(rumContext.applicationId),
                    session = ErrorEvent.ErrorEventSession(
                        id = rumContext.sessionId,
                        type = ErrorEvent.ErrorEventSessionType.USER,
                        hasReplay = hasReplay
                    ),
                    source = rumEventSourceProvider.errorEventSource,
                    os = ErrorEvent.Os(
                        name = datadogContext.deviceInfo.osName,
                        version = datadogContext.deviceInfo.osVersion,
                        versionMajor = datadogContext.deviceInfo.osMajorVersion
                    ),
                    device = ErrorEvent.Device(
                        type = datadogContext.deviceInfo.deviceType.toErrorSchemaType(),
                        name = datadogContext.deviceInfo.deviceName,
                        model = datadogContext.deviceInfo.deviceModel,
                        brand = datadogContext.deviceInfo.deviceBrand,
                        architecture = datadogContext.deviceInfo.architecture
                    ),
                    context = ErrorEvent.Context(additionalProperties = updatedAttributes),
                    dd = ErrorEvent.Dd(
                        session = ErrorEvent.DdSession(plan = ErrorEvent.Plan.PLAN_1)
                    )
                )
                writer.write(eventBatchWriter, errorEvent)
            }

        if (event.isFatal) {
            errorCount++
            crashCount++
            sendViewUpdate(event, writer)
        } else {
            pendingErrorCount++
        }
    }

    @WorkerThread
    private fun onAddCustomTiming(
        event: RumRawEvent.AddCustomTiming,
        writer: DataWriter<Any>
    ) {
        customTimings[event.name] = max(event.eventTime.nanoTime - startedNanos, 1L)
        sendViewUpdate(event, writer)
    }

    private fun onUpdatePerformanceMetric(
        event: RumRawEvent.UpdatePerformanceMetric
    ) {
        if (stopped) return

        val value = event.value
        val vitalInfo = performanceMetrics[event.metric] ?: VitalInfo.EMPTY
        val newSampleCount = vitalInfo.sampleCount + 1

        // Assuming M(n) is the mean value of the first n samples
        // M(n) = ∑ sample(n) / n
        // n⨉M(n) = ∑ sample(n)
        // M(n+1) = ∑ sample(n+1) / (n+1)
        //        = [ sample(n+1) + ∑ sample(n) ] / (n+1)
        //        = (sample(n+1) + n⨉M(n)) / (n+1)
        val meanValue = (value + (vitalInfo.sampleCount * vitalInfo.meanValue)) / newSampleCount
        performanceMetrics[event.metric] = VitalInfo(
            newSampleCount,
            min(value, vitalInfo.minValue),
            max(value, vitalInfo.maxValue),
            meanValue
        )
    }

    @WorkerThread
    private fun onKeepAlive(
        event: RumRawEvent.KeepAlive,
        writer: DataWriter<Any>
    ) {
        delegateEventToChildren(event, writer)
        if (stopped) return

        sendViewUpdate(event, writer)
    }

    @WorkerThread
    private fun delegateEventToChildren(
        event: RumRawEvent,
        writer: DataWriter<Any>
    ) {
        delegateEventToResources(event, writer)
        delegateEventToAction(event, writer)
    }

    @WorkerThread
    private fun delegateEventToAction(
        event: RumRawEvent,
        writer: DataWriter<Any>
    ) {
        val currentAction = activeActionScope
        if (currentAction != null) {
            val updatedAction = currentAction.handleEvent(event, writer)
            if (updatedAction == null) {
                updateActiveActionScope(null)
            }
        }
    }

    private fun updateActiveActionScope(scope: RumScope?) {
        activeActionScope = scope
        // update the Rum Context to make it available for Logs/Trace bundling
        GlobalRum.updateRumContext(getRumContext(), applyOnlyIf = { currentContext ->
            when {
                currentContext.sessionId != this.sessionId -> {
                    true
                }
                currentContext.viewId == this.viewId -> {
                    true
                }
                else -> {
                    sdkLogger.debugWithTelemetry(
                        RUM_CONTEXT_UPDATE_IGNORED_AT_ACTION_UPDATE_MESSAGE
                    )
                    false
                }
            }
        })
    }

    @WorkerThread
    private fun delegateEventToResources(
        event: RumRawEvent,
        writer: DataWriter<Any>
    ) {
        val iterator = activeResourceScopes.iterator()
        @Suppress("UnsafeThirdPartyFunctionCall") // next/remove can't fail: we checked hasNext
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val scope = entry.value.handleEvent(event, writer)
            if (scope == null) {
                iterator.remove()
            }
        }
    }

    @WorkerThread
    private fun onResourceSent(
        event: RumRawEvent.ResourceSent,
        writer: DataWriter<Any>
    ) {
        if (event.viewId == viewId) {
            pendingResourceCount--
            resourceCount++
            sendViewUpdate(event, writer)
        }
    }

    @WorkerThread
    private fun onActionSent(
        event: RumRawEvent.ActionSent,
        writer: DataWriter<Any>
    ) {
        if (event.viewId == viewId) {
            pendingActionCount--
            actionCount++
            frustrationCount += event.frustrationCount
            sendViewUpdate(event, writer)
        }
    }

    @WorkerThread
    private fun onLongTaskSent(
        event: RumRawEvent.LongTaskSent,
        writer: DataWriter<Any>
    ) {
        if (event.viewId == viewId) {
            pendingLongTaskCount--
            longTaskCount++
            if (event.isFrozenFrame) {
                pendingFrozenFrameCount--
                frozenFrameCount++
            }
            sendViewUpdate(event, writer)
        }
    }

    @WorkerThread
    private fun onErrorSent(
        event: RumRawEvent.ErrorSent,
        writer: DataWriter<Any>
    ) {
        if (event.viewId == viewId) {
            pendingErrorCount--
            errorCount++
            sendViewUpdate(event, writer)
        }
    }

    private fun onResourceDropped(event: RumRawEvent.ResourceDropped) {
        if (event.viewId == viewId) {
            pendingResourceCount--
        }
    }

    private fun onActionDropped(event: RumRawEvent.ActionDropped) {
        if (event.viewId == viewId) {
            pendingActionCount--
        }
    }

    private fun onErrorDropped(event: RumRawEvent.ErrorDropped) {
        if (event.viewId == viewId) {
            pendingErrorCount--
        }
    }

    private fun onLongTaskDropped(event: RumRawEvent.LongTaskDropped) {
        if (event.viewId == viewId) {
            pendingLongTaskCount--
            if (event.isFrozenFrame) {
                pendingFrozenFrameCount--
            }
        }
    }

    @Suppress("LongMethod")
    @WorkerThread
    private fun sendViewUpdate(event: RumRawEvent, writer: DataWriter<Any>) {
        val viewComplete = isViewComplete()
        if (!viewUpdatePredicate.canUpdateView(viewComplete, event)) {
            return
        }
        attributes.putAll(GlobalRum.globalAttributes)
        version++
        val updatedDurationNs = resolveViewDuration(event)
        val rumContext = getRumContext()

        val timings = resolveCustomTimings()
        val memoryInfo = lastMemoryInfo
        val refreshRateInfo = lastFrameRateInfo
        val isSlowRendered = resolveRefreshRateInfo(refreshRateInfo)

        sdkCore.getFeature(RumFeature.RUM_FEATURE_NAME)
            ?.withWriteContext { datadogContext, eventBatchWriter ->

                val user = datadogContext.userInfo
                val hasReplay = featuresContextResolver.resolveHasReplay(datadogContext)

                val viewEvent = ViewEvent(
                    date = eventTimestamp,
                    view = ViewEvent.View(
                        id = rumContext.viewId.orEmpty(),
                        name = rumContext.viewName.orEmpty(),
                        url = rumContext.viewUrl.orEmpty(),
                        loadingTime = loadingTime,
                        loadingType = loadingType,
                        timeSpent = updatedDurationNs,
                        action = ViewEvent.Action(actionCount),
                        resource = ViewEvent.Resource(resourceCount),
                        error = ViewEvent.Error(errorCount),
                        crash = ViewEvent.Crash(crashCount),
                        longTask = ViewEvent.LongTask(longTaskCount),
                        frozenFrame = ViewEvent.FrozenFrame(frozenFrameCount),
                        customTimings = timings,
                        isActive = !viewComplete,
                        cpuTicksCount = cpuTicks,
                        cpuTicksPerSecond = cpuTicks?.let { (it * ONE_SECOND_NS) / updatedDurationNs },
                        memoryAverage = memoryInfo?.meanValue,
                        memoryMax = memoryInfo?.maxValue,
                        refreshRateAverage = refreshRateInfo?.meanValue?.let { it * refreshRateScale },
                        refreshRateMin = refreshRateInfo?.minValue?.let { it * refreshRateScale },
                        isSlowRendered = isSlowRendered,
                        frustration = ViewEvent.Frustration(frustrationCount.toLong()),
                        flutterBuildTime = performanceMetrics[RumPerformanceMetric.FLUTTER_BUILD_TIME]
                            ?.toPerformanceMetric(),
                        flutterRasterTime = performanceMetrics[RumPerformanceMetric.FLUTTER_RASTER_TIME]
                            ?.toPerformanceMetric(),
                        jsRefreshRate = performanceMetrics[RumPerformanceMetric.JS_REFRESH_RATE]
                            ?.toPerformanceMetric()
                    ),
                    usr = ViewEvent.Usr(
                        id = user.id,
                        name = user.name,
                        email = user.email,
                        additionalProperties = user.additionalProperties.toMutableMap()
                    ),
                    application = ViewEvent.Application(rumContext.applicationId),
                    session = ViewEvent.ViewEventSession(
                        id = rumContext.sessionId,
                        type = ViewEvent.ViewEventSessionType.USER,
                        hasReplay = hasReplay
                    ),
                    source = rumEventSourceProvider.viewEventSource,
                    os = ViewEvent.Os(
                        name = datadogContext.deviceInfo.osName,
                        version = datadogContext.deviceInfo.osVersion,
                        versionMajor = datadogContext.deviceInfo.osMajorVersion
                    ),
                    device = ViewEvent.Device(
                        type = datadogContext.deviceInfo.deviceType.toViewSchemaType(),
                        name = datadogContext.deviceInfo.deviceName,
                        model = datadogContext.deviceInfo.deviceModel,
                        brand = datadogContext.deviceInfo.deviceBrand,
                        architecture = datadogContext.deviceInfo.architecture
                    ),
                    context = ViewEvent.Context(additionalProperties = attributes),
                    dd = ViewEvent.Dd(
                        documentVersion = version,
                        session = ViewEvent.DdSession(plan = ViewEvent.Plan.PLAN_1)
                    )
                )

                writer.write(eventBatchWriter, viewEvent)
            }
    }

    private fun resolveViewDuration(event: RumRawEvent): Long {
        val duration = event.eventTime.nanoTime - startedNanos
        return if (duration <= 0) {
            devLogger.w(NEGATIVE_DURATION_WARNING_MESSAGE.format(Locale.US, name))
            1
        } else {
            duration
        }
    }

    private fun resolveRefreshRateInfo(refreshRateInfo: VitalInfo?) =
        if (refreshRateInfo == null) {
            null
        } else {
            refreshRateInfo.meanValue < SLOW_RENDERED_THRESHOLD_FPS
        }

    private fun resolveCustomTimings() = if (customTimings.isNotEmpty()) {
        ViewEvent.CustomTimings(LinkedHashMap(customTimings))
    } else {
        null
    }

    private fun addExtraAttributes(
        attributes: Map<String, Any?>
    ): MutableMap<String, Any?> {
        return attributes.toMutableMap()
            .apply { putAll(GlobalRum.globalAttributes) }
    }

    @WorkerThread
    private fun onUpdateViewLoadingTime(
        event: RumRawEvent.UpdateViewLoadingTime,
        writer: DataWriter<Any>
    ) {
        val startedKey = keyRef.get()
        if (event.key != startedKey) {
            return
        }
        loadingTime = event.loadingTime
        loadingType = event.loadingType
        sendViewUpdate(event, writer)
    }

    @WorkerThread
    private fun onApplicationStarted(
        event: RumRawEvent.ApplicationStarted,
        writer: DataWriter<Any>
    ) {
        pendingActionCount++
        val rumContext = getRumContext()

        val attributes = GlobalRum.globalAttributes

        sdkCore.getFeature(RumFeature.RUM_FEATURE_NAME)
            ?.withWriteContext { datadogContext, eventBatchWriter ->
                val user = datadogContext.userInfo

                val actionEvent = ActionEvent(
                    date = eventTimestamp,
                    action = ActionEvent.ActionEventAction(
                        type = ActionEvent.ActionEventActionType.APPLICATION_START,
                        id = UUID.randomUUID().toString(),
                        loadingTime = getStartupTime(event)
                    ),
                    view = ActionEvent.View(
                        id = rumContext.viewId.orEmpty(),
                        name = rumContext.viewName,
                        url = rumContext.viewUrl.orEmpty()
                    ),
                    usr = ActionEvent.Usr(
                        id = user.id,
                        name = user.name,
                        email = user.email,
                        additionalProperties = user.additionalProperties.toMutableMap()
                    ),
                    application = ActionEvent.Application(rumContext.applicationId),
                    session = ActionEvent.ActionEventSession(
                        id = rumContext.sessionId,
                        type = ActionEvent.ActionEventSessionType.USER,
                        hasReplay = false
                    ),
                    source = rumEventSourceProvider.actionEventSource,
                    os = ActionEvent.Os(
                        name = datadogContext.deviceInfo.osName,
                        version = datadogContext.deviceInfo.osVersion,
                        versionMajor = datadogContext.deviceInfo.osMajorVersion
                    ),
                    device = ActionEvent.Device(
                        type = datadogContext.deviceInfo.deviceType.toActionSchemaType(),
                        name = datadogContext.deviceInfo.deviceName,
                        model = datadogContext.deviceInfo.deviceModel,
                        brand = datadogContext.deviceInfo.deviceBrand,
                        architecture = datadogContext.deviceInfo.architecture
                    ),
                    context = ActionEvent.Context(
                        additionalProperties = attributes
                    ),
                    dd = ActionEvent.Dd(session = ActionEvent.DdSession(ActionEvent.Plan.PLAN_1))
                )
                writer.write(eventBatchWriter, actionEvent)
            }
    }

    private fun getStartupTime(event: RumRawEvent.ApplicationStarted): Long {
        val now = event.eventTime.nanoTime
        val startupTime = event.applicationStartupNanos
        return max(now - startupTime, 1L)
    }

    @Suppress("LongMethod")
    @WorkerThread
    private fun onAddLongTask(event: RumRawEvent.AddLongTask, writer: DataWriter<Any>) {
        delegateEventToChildren(event, writer)
        if (stopped) return

        val rumContext = getRumContext()
        val updatedAttributes = addExtraAttributes(
            mapOf(RumAttributes.LONG_TASK_TARGET to event.target)
        )
        val timestamp = event.eventTime.timestamp + serverTimeOffsetInMs
        val isFrozenFrame = event.durationNs > FROZEN_FRAME_THRESHOLD_NS

        sdkCore.getFeature(RumFeature.RUM_FEATURE_NAME)
            ?.withWriteContext { datadogContext, eventBatchWriter ->

                val user = datadogContext.userInfo
                val networkInfo = datadogContext.networkInfo
                val hasReplay = featuresContextResolver.resolveHasReplay(datadogContext)

                val longTaskEvent = LongTaskEvent(
                    date = timestamp - TimeUnit.NANOSECONDS.toMillis(event.durationNs),
                    longTask = LongTaskEvent.LongTask(
                        duration = event.durationNs,
                        isFrozenFrame = isFrozenFrame
                    ),
                    action = rumContext.actionId?.let { LongTaskEvent.Action(listOf(it)) },
                    view = LongTaskEvent.View(
                        id = rumContext.viewId.orEmpty(),
                        name = rumContext.viewName,
                        url = rumContext.viewUrl.orEmpty()
                    ),
                    usr = LongTaskEvent.Usr(
                        id = user.id,
                        name = user.name,
                        email = user.email,
                        additionalProperties = user.additionalProperties.toMutableMap()
                    ),
                    connectivity = networkInfo.toLongTaskConnectivity(),
                    application = LongTaskEvent.Application(rumContext.applicationId),
                    session = LongTaskEvent.LongTaskEventSession(
                        id = rumContext.sessionId,
                        type = LongTaskEvent.LongTaskEventSessionType.USER,
                        hasReplay = hasReplay
                    ),
                    source = rumEventSourceProvider.longTaskEventSource,
                    os = LongTaskEvent.Os(
                        name = datadogContext.deviceInfo.osName,
                        version = datadogContext.deviceInfo.osVersion,
                        versionMajor = datadogContext.deviceInfo.osMajorVersion
                    ),
                    device = LongTaskEvent.Device(
                        type = datadogContext.deviceInfo.deviceType.toLongTaskSchemaType(),
                        name = datadogContext.deviceInfo.deviceName,
                        model = datadogContext.deviceInfo.deviceModel,
                        brand = datadogContext.deviceInfo.deviceBrand,
                        architecture = datadogContext.deviceInfo.architecture
                    ),
                    context = LongTaskEvent.Context(additionalProperties = updatedAttributes),
                    dd = LongTaskEvent.Dd(
                        session = LongTaskEvent.DdSession(LongTaskEvent.Plan.PLAN_1)
                    )
                )
                writer.write(eventBatchWriter, longTaskEvent)
            }

        pendingLongTaskCount++
        if (isFrozenFrame) pendingFrozenFrameCount++
    }

    private fun isViewComplete(): Boolean {
        val pending = pendingActionCount +
            pendingResourceCount +
            pendingErrorCount +
            pendingLongTaskCount
        // we use <= 0 for pending counter as a safety measure to make sure this ViewScope will
        // be closed.
        return stopped && activeResourceScopes.isEmpty() && (pending <= 0L)
    }

    /*
     * The refresh rate needs to be computed with each view because:
     * - it requires a context with a UI (we can't get this from the application context);
     * - it can change between different activities (based on window configuration)
     */
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun detectRefreshRateScale(key: Any) {
        val activity = when (key) {
            is Activity -> key
            is Fragment -> key.activity
            is android.app.Fragment -> key.activity
            else -> null
        } ?: return

        val display = if (buildSdkVersionProvider.version() >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            (activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        } ?: return
        refreshRateScale = 60.0 / display.refreshRate
    }

    enum class RumViewType {
        NONE,
        FOREGROUND,
        BACKGROUND,
        APPLICATION_LAUNCH
    }

    private fun updateOffsetInFeatureContext() {
        sdkCore.updateFeatureContext(
            RumFeature.RUM_FEATURE_NAME,
            mapOf(RumFeature.VIEW_TIMESTAMP_OFFSET_IN_MS_KEY to serverTimeOffsetInMs)
        )
    }

    // endregion

    companion object {
        internal val ONE_SECOND_NS = TimeUnit.SECONDS.toNanos(1)

        internal const val ACTION_DROPPED_WARNING = "RUM Action (%s on %s) was dropped, because" +
            " another action is still active for the same view"

        internal const val RUM_CONTEXT_UPDATE_IGNORED_AT_STOP_VIEW_MESSAGE =
            "Trying to update global RUM context when StopView event arrived, but the context" +
                " doesn't reference this view."
        internal const val RUM_CONTEXT_UPDATE_IGNORED_AT_ACTION_UPDATE_MESSAGE =
            "Trying to update active action in the global RUM context, but the context" +
                " doesn't reference this view."

        internal val FROZEN_FRAME_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(700)
        internal const val SLOW_RENDERED_THRESHOLD_FPS = 55
        internal const val NEGATIVE_DURATION_WARNING_MESSAGE = "The computed duration for your " +
            "view: %s was 0 or negative. In order to keep the view we forced it to 1ns."

        internal fun fromEvent(
            parentScope: RumScope,
            sdkCore: SdkCore,
            event: RumRawEvent.StartView,
            firstPartyHostDetector: FirstPartyHostDetector,
            cpuVitalMonitor: VitalMonitor,
            memoryVitalMonitor: VitalMonitor,
            frameRateVitalMonitor: VitalMonitor,
            rumEventSourceProvider: RumEventSourceProvider,
            contextProvider: ContextProvider,
            trackFrustrations: Boolean
        ): RumViewScope {
            return RumViewScope(
                parentScope,
                sdkCore,
                event.key,
                event.name,
                event.eventTime,
                event.attributes,
                firstPartyHostDetector,
                cpuVitalMonitor,
                memoryVitalMonitor,
                frameRateVitalMonitor,
                rumEventSourceProvider,
                contextProvider,
                trackFrustrations = trackFrustrations
            )
        }

        private fun VitalInfo.toPerformanceMetric(): ViewEvent.FlutterBuildTime {
            return ViewEvent.FlutterBuildTime(
                min = minValue,
                max = maxValue,
                average = meanValue
            )
        }
    }
}
