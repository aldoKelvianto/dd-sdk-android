/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.webview

import android.webkit.WebSettings
import android.webkit.WebView
import com.datadog.android.api.InternalLogger
import com.datadog.android.api.context.DatadogContext
import com.datadog.android.api.feature.Feature
import com.datadog.android.api.feature.FeatureScope
import com.datadog.android.api.feature.FeatureSdkCore
import com.datadog.android.api.feature.StorageBackedFeature
import com.datadog.android.api.net.RequestFactory
import com.datadog.android.api.storage.EventBatchWriter
import com.datadog.android.api.storage.NoOpDataWriter
import com.datadog.android.api.storage.RawBatchEvent
import com.datadog.android.utils.forge.Configurator
import com.datadog.android.utils.verifyLog
import com.datadog.android.webview.internal.DatadogEventBridge
import com.datadog.android.webview.internal.MixedWebViewEventConsumer
import com.datadog.android.webview.internal.NoOpWebViewEventConsumer
import com.datadog.android.webview.internal.log.WebViewLogEventConsumer
import com.datadog.android.webview.internal.log.WebViewLogsFeature
import com.datadog.android.webview.internal.replay.WebViewReplayEventConsumer
import com.datadog.android.webview.internal.replay.WebViewReplayFeature
import com.datadog.android.webview.internal.rum.WebViewRumEventConsumer
import com.datadog.android.webview.internal.rum.WebViewRumFeature
import com.google.gson.JsonObject
import fr.xgouchet.elmyr.Forge
import fr.xgouchet.elmyr.annotation.Forgery
import fr.xgouchet.elmyr.annotation.StringForgery
import fr.xgouchet.elmyr.junit5.ForgeConfiguration
import fr.xgouchet.elmyr.junit5.ForgeExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.net.URL
import java.util.UUID

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class)
)
@MockitoSettings(strictness = Strictness.LENIENT)
@ForgeConfiguration(Configurator::class)
internal class WebViewTrackingTest {

    @Mock
    lateinit var mockCore: FeatureSdkCore

    @Mock
    lateinit var mockInternalLogger: InternalLogger

    @Mock
    lateinit var mockRumFeatureScope: FeatureScope

    @Mock
    lateinit var mockLogsFeatureScope: FeatureScope

    @Mock
    lateinit var mockRumFeature: StorageBackedFeature

    @Mock
    lateinit var mockLogsFeature: StorageBackedFeature

    @Mock
    lateinit var mockReplayFeature: StorageBackedFeature

    @Mock
    lateinit var mockRumRequestFactory: RequestFactory

    @Mock
    lateinit var mockLogsRequestFactory: RequestFactory

    @Mock
    lateinit var mockReplayRequestFactory: RequestFactory

    @Mock
    lateinit var mockWebView: WebView

    @Mock
    lateinit var mockReplayFeatureScope: FeatureScope

    @BeforeEach
    fun `set up`() {
        whenever(
            mockCore.getFeature(Feature.RUM_FEATURE_NAME)
        ) doReturn mockRumFeatureScope
        whenever(
            mockCore.getFeature(Feature.LOGS_FEATURE_NAME)
        ) doReturn mockLogsFeatureScope
        whenever(
            mockCore.getFeature(Feature.SESSION_REPLAY_FEATURE_NAME)
        ) doReturn mockReplayFeatureScope
        whenever(
            mockRumFeatureScope.unwrap<StorageBackedFeature>()
        ) doReturn mockRumFeature
        whenever(
            mockLogsFeatureScope.unwrap<StorageBackedFeature>()
        ) doReturn mockLogsFeature
        whenever(
            mockReplayFeatureScope.unwrap<StorageBackedFeature>()
        ) doReturn mockReplayFeature
        whenever(mockCore.internalLogger) doReturn mockInternalLogger

        whenever(mockRumFeature.requestFactory) doReturn mockRumRequestFactory
        whenever(mockLogsFeature.requestFactory) doReturn mockLogsRequestFactory
        whenever(mockReplayFeature.requestFactory) doReturn mockReplayRequestFactory

        val mockWebViewSettings = mock<WebSettings>()
        whenever(mockWebViewSettings.javaScriptEnabled) doReturn true
        whenever(mockWebView.settings) doReturn mockWebViewSettings
    }

    @Test
    fun `M attach the bridge W enable`(@Forgery fakeUrls: List<URL>) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        val mockSettings: WebSettings = mock {
            whenever(it.javaScriptEnabled).thenReturn(true)
        }
        val mockWebView: WebView = mock {
            whenever(it.settings).thenReturn(mockSettings)
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        verify(mockWebView).addJavascriptInterface(
            argThat { this is DatadogEventBridge },
            eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
        )
    }

    @Test
    fun `M extract and provide the SR privacy level W enable {privacy level provided}`(
        @Forgery fakeUrls: List<URL>,
        @StringForgery fakePrivacyLevel: String
    ) {
        // Given
        val mockSrFeatureContext = mapOf<String, Any>(
            WebViewTracking.SESSION_REPLAY_PRIVACY_KEY to fakePrivacyLevel
        )
        whenever(mockCore.getFeatureContext(Feature.SESSION_REPLAY_FEATURE_NAME)) doReturn
            mockSrFeatureContext
        val fakeHosts = fakeUrls.map { it.host }
        val mockSettings: WebSettings = mock {
            whenever(it.javaScriptEnabled).thenReturn(true)
        }
        val mockWebView: WebView = mock {
            whenever(it.settings).thenReturn(mockSettings)
        }
        val argumentCaptor = argumentCaptor<DatadogEventBridge>()

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        verify(mockWebView).addJavascriptInterface(
            argumentCaptor.capture(),
            eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
        )
        assertThat(argumentCaptor.firstValue.getPrivacyLevel()).isEqualTo(fakePrivacyLevel)
    }

    @Test
    fun `M used the default SR privacy level W enable {privacy level not provided}`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val mockSrFeatureContext = mapOf<String, Any>()
        whenever(mockCore.getFeatureContext(Feature.SESSION_REPLAY_FEATURE_NAME)) doReturn
            mockSrFeatureContext
        val fakeHosts = fakeUrls.map { it.host }
        val mockSettings: WebSettings = mock {
            whenever(it.javaScriptEnabled).thenReturn(true)
        }
        val mockWebView: WebView = mock {
            whenever(it.settings).thenReturn(mockSettings)
        }
        val argumentCaptor = argumentCaptor<DatadogEventBridge>()

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        verify(mockWebView).addJavascriptInterface(
            argumentCaptor.capture(),
            eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
        )
        assertThat(argumentCaptor.firstValue.getPrivacyLevel())
            .isEqualTo(WebViewTracking.SESSION_REPLAY_MASK_ALL_PRIVACY)
    }

    @Test
    fun `M attach the bridge and send a warn log W enable { javascript not enabled }`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        val mockSettings: WebSettings = mock {
            whenever(it.javaScriptEnabled).thenReturn(false)
        }
        val mockWebView: WebView = mock {
            whenever(it.settings).thenReturn(mockSettings)
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        verify(mockWebView).addJavascriptInterface(
            argThat { this is DatadogEventBridge },
            eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
        )
        mockInternalLogger.verifyLog(
            InternalLogger.Level.WARN,
            InternalLogger.Target.USER,
            WebViewTracking.JAVA_SCRIPT_NOT_ENABLED_WARNING_MESSAGE
        )
    }

    @Test
    fun `M create a default WebEventConsumer W enable()`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        argumentCaptor<DatadogEventBridge> {
            verify(mockWebView).addJavascriptInterface(
                capture(),
                eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
            )
            val consumer = lastValue.webViewEventConsumer
            assertThat(consumer).isInstanceOf(MixedWebViewEventConsumer::class.java)
            val mixedConsumer = consumer as MixedWebViewEventConsumer
            assertThat(mixedConsumer.logsEventConsumer)
                .isInstanceOf(WebViewLogEventConsumer::class.java)
            assertThat(mixedConsumer.rumEventConsumer)
                .isInstanceOf(WebViewRumEventConsumer::class.java)
            assertThat(mixedConsumer.replayEventConsumer)
                .isInstanceOf(WebViewReplayEventConsumer::class.java)

            argumentCaptor<Feature> {
                verify(mockCore, times(3)).registerFeature(capture())

                val webViewRumFeature = firstValue
                val webViewLogsFeature = secondValue
                val webViewReplayFeature = thirdValue

                assertThat((webViewRumFeature as WebViewRumFeature).requestFactory)
                    .isSameAs(mockRumRequestFactory)
                assertThat((webViewLogsFeature as WebViewLogsFeature).requestFactory)
                    .isSameAs(mockLogsRequestFactory)
                assertThat((webViewReplayFeature as WebViewReplayFeature).requestFactory)
                    .isSameAs(mockReplayRequestFactory)
            }
        }
    }

    fun `M share the same TimestampOffsetProvider W enable()`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        argumentCaptor<DatadogEventBridge> {
            verify(mockWebView).addJavascriptInterface(
                capture(),
                eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
            )
            val consumer = lastValue.webViewEventConsumer
            assertThat(consumer).isInstanceOf(MixedWebViewEventConsumer::class.java)
            val mixedConsumer = consumer as MixedWebViewEventConsumer
            assertThat(mixedConsumer.rumEventConsumer)
                .isInstanceOf(WebViewRumEventConsumer::class.java)
            assertThat(mixedConsumer.replayEventConsumer)
                .isInstanceOf(WebViewReplayEventConsumer::class.java)
            val webViewReplayEventConsumer = mixedConsumer.replayEventConsumer
                as WebViewReplayEventConsumer
            val webViewRumEventConsumer = mixedConsumer.rumEventConsumer
                as WebViewRumEventConsumer
            assertThat(webViewReplayEventConsumer.webViewReplayEventMapper.offsetProvider)
                .isSameAs(webViewRumEventConsumer.offsetProvider)
        }
    }

    @Test
    fun `M create a default WebEventConsumer W enable() {RUM feature is not registered}`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        whenever(mockCore.getFeature(Feature.RUM_FEATURE_NAME)) doReturn null
        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        argumentCaptor<DatadogEventBridge> {
            verify(mockWebView).addJavascriptInterface(
                capture(),
                eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
            )
            val consumer = lastValue.webViewEventConsumer
            assertThat(consumer).isInstanceOf(MixedWebViewEventConsumer::class.java)
            val mixedConsumer = consumer as MixedWebViewEventConsumer
            assertThat(mixedConsumer.logsEventConsumer)
                .isInstanceOf(WebViewLogEventConsumer::class.java)
            assertThat((mixedConsumer.logsEventConsumer as WebViewLogEventConsumer).userLogsWriter)
                .isNotInstanceOf(NoOpDataWriter::class.java)
            assertThat(mixedConsumer.rumEventConsumer)
                .isInstanceOf(WebViewRumEventConsumer::class.java)
            assertThat((mixedConsumer.rumEventConsumer as WebViewRumEventConsumer).dataWriter)
                .isInstanceOf(NoOpDataWriter::class.java)

            argumentCaptor<Feature> {
                verify(mockCore, times(2)).registerFeature(capture())

                val webViewLogsFeature = firstValue
                val webViewReplayFeature = secondValue

                assertThat((webViewLogsFeature as WebViewLogsFeature).requestFactory)
                    .isSameAs(mockLogsRequestFactory)
                assertThat((webViewReplayFeature as WebViewReplayFeature).requestFactory)
                    .isSameAs(mockReplayRequestFactory)
            }

            mockInternalLogger.verifyLog(
                InternalLogger.Level.INFO,
                InternalLogger.Target.USER,
                WebViewTracking.RUM_FEATURE_MISSING_INFO
            )
        }
    }

    @Test
    fun `M create a default WebEventConsumer W init() {Logs feature is not registered}`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        whenever(mockCore.getFeature(Feature.LOGS_FEATURE_NAME)) doReturn null
        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        argumentCaptor<DatadogEventBridge> {
            verify(mockWebView).addJavascriptInterface(
                capture(),
                eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
            )
            val consumer = lastValue.webViewEventConsumer
            assertThat(consumer).isInstanceOf(MixedWebViewEventConsumer::class.java)
            val mixedConsumer = consumer as MixedWebViewEventConsumer
            assertThat(mixedConsumer.logsEventConsumer)
                .isInstanceOf(WebViewLogEventConsumer::class.java)
            assertThat((mixedConsumer.logsEventConsumer as WebViewLogEventConsumer).userLogsWriter)
                .isInstanceOf(NoOpDataWriter::class.java)
            assertThat(mixedConsumer.rumEventConsumer)
                .isInstanceOf(WebViewRumEventConsumer::class.java)
            assertThat((mixedConsumer.rumEventConsumer as WebViewRumEventConsumer).dataWriter)
                .isNotInstanceOf(NoOpDataWriter::class.java)

            argumentCaptor<Feature> {
                verify(mockCore, times(2)).registerFeature(capture())

                val webViewRumFeature = firstValue
                val webViewReplayFeature = secondValue

                assertThat((webViewRumFeature as WebViewRumFeature).requestFactory)
                    .isSameAs(mockRumRequestFactory)
                assertThat((webViewReplayFeature as WebViewReplayFeature).requestFactory)
                    .isSameAs(mockReplayRequestFactory)
            }

            mockInternalLogger.verifyLog(
                InternalLogger.Level.INFO,
                InternalLogger.Target.USER,
                WebViewTracking.LOGS_FEATURE_MISSING_INFO
            )
        }
    }

    @Test
    fun `M create a default WebEventConsumer W init() { SR feature not registered }()`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }
        whenever(mockCore.getFeature(Feature.SESSION_REPLAY_FEATURE_NAME)) doReturn null

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        argumentCaptor<DatadogEventBridge> {
            verify(mockWebView).addJavascriptInterface(
                capture(),
                eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
            )
            val consumer = lastValue.webViewEventConsumer
            assertThat(consumer).isInstanceOf(MixedWebViewEventConsumer::class.java)
            val mixedConsumer = consumer as MixedWebViewEventConsumer
            assertThat(mixedConsumer.logsEventConsumer)
                .isInstanceOf(WebViewLogEventConsumer::class.java)
            assertThat(mixedConsumer.rumEventConsumer)
                .isInstanceOf(WebViewRumEventConsumer::class.java)
            assertThat((mixedConsumer.replayEventConsumer as WebViewReplayEventConsumer).dataWriter)
                .isInstanceOf(NoOpDataWriter::class.java)

            argumentCaptor<Feature> {
                verify(mockCore, times(2)).registerFeature(capture())

                val webViewRumFeature = firstValue
                val webViewLogsFeature = secondValue
                assertThat((webViewRumFeature as WebViewRumFeature).requestFactory)
                    .isSameAs(mockRumRequestFactory)
                assertThat((webViewLogsFeature as WebViewLogsFeature).requestFactory)
                    .isSameAs(mockLogsRequestFactory)
            }

            mockInternalLogger.verifyLog(
                InternalLogger.Level.INFO,
                InternalLogger.Target.USER,
                WebViewTracking.SESSION_REPLAY_FEATURE_MISSING_INFO
            )
        }
    }

    @Test
    fun `M create a default NoOpEventConsumer W init() {Logs and Rum feature is not registered}`(
        @Forgery fakeUrls: List<URL>
    ) {
        // Given
        val fakeHosts = fakeUrls.map { it.host }
        whenever(mockCore.getFeature(Feature.LOGS_FEATURE_NAME)) doReturn null
        whenever(mockCore.getFeature(Feature.RUM_FEATURE_NAME)) doReturn null
        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }

        // When
        WebViewTracking.enable(mockWebView, fakeHosts, sdkCore = mockCore)

        // Then
        argumentCaptor<DatadogEventBridge> {
            verify(mockWebView).addJavascriptInterface(
                capture(),
                eq(WebViewTracking.DATADOG_EVENT_BRIDGE_NAME)
            )
            val consumer = lastValue.webViewEventConsumer
            assertThat(consumer).isInstanceOf(NoOpWebViewEventConsumer::class.java)
        }
    }

    @Test
    fun `M pass web view event to RumWebEventConsumer W consumeWebViewEvent()`(
        forge: Forge
    ) {
        // Given
        val fakeBundledEvent = forge.getForgery<JsonObject>()
        val fakeRumEventType = forge.anElementFrom(WebViewRumEventConsumer.RUM_EVENT_TYPES)
        val fakeWebEvent = bundleWebEvent(fakeBundledEvent, fakeRumEventType)
        val fakeApplicationId = forge.getForgery<UUID>().toString()
        val fakeSessionId = forge.getForgery<UUID>().toString()
        val mockWebViewRumFeature = mock<FeatureScope>()
        val mockWebViewLogsFeature = mock<FeatureScope>()
        val expectedEvent = fakeBundledEvent.deepCopy().apply {
            add("container", JsonObject().apply { addProperty("source", "android") })
            add("application", JsonObject().apply { addProperty("id", fakeApplicationId) })
            add("session", JsonObject().apply { addProperty("id", fakeSessionId) })
        }

        whenever(
            mockCore.getFeature(WebViewRumFeature.WEB_RUM_FEATURE_NAME)
        ) doReturn mockWebViewRumFeature
        whenever(
            mockCore.getFeature(WebViewLogsFeature.WEB_LOGS_FEATURE_NAME)
        ) doReturn mockWebViewLogsFeature

        whenever(mockCore.registerFeature(any())) doAnswer {
            val feature = it.getArgument<Feature>(0)
            feature.onInitialize(mock())
        }
        val fakeFeaturesContext = mapOf<String, Map<String, Any?>>(
            "rum" to mapOf<String, Any?>(
                "application_id" to fakeApplicationId,
                "session_id" to fakeSessionId,
                "session_state" to "TRACKED"
            )
        )
        val mockDatadogContext = mock<DatadogContext>()
        whenever(mockDatadogContext.featuresContext) doReturn fakeFeaturesContext
        val mockEventBatchWriter = mock<EventBatchWriter>()
        val proxy = WebViewTracking._InternalWebViewProxy(
            mockCore,
            System.identityHashCode(mockWebView).toString()
        )

        // When
        proxy.consumeWebviewEvent(fakeWebEvent.toString())
        argumentCaptor<(DatadogContext, EventBatchWriter) -> Unit> {
            verify(mockWebViewRumFeature).withWriteContext(any(), capture())
            firstValue(mockDatadogContext, mockEventBatchWriter)
        }

        // Then
        argumentCaptor<RawBatchEvent> {
            verify(mockEventBatchWriter).write(capture(), isNull())
            val capturedJson = String(firstValue.data, Charsets.UTF_8)
            assertThat(capturedJson).isEqualTo(expectedEvent.toString())
        }
    }

    @Test
    fun `M use a NoOpWebViewReplayEventConsumer W consumeWebViewEvent{WebView id is missing}`() {
        // When
        val proxy = WebViewTracking._InternalWebViewProxy(mockCore)

        // When
        assertThat((proxy.consumer as MixedWebViewEventConsumer).replayEventConsumer)
            .isInstanceOf(NoOpWebViewEventConsumer::class.java)
    }

    private fun bundleWebEvent(
        fakeBundledEvent: JsonObject?,
        eventType: String?
    ): JsonObject {
        val fakeWebEvent = JsonObject()
        fakeBundledEvent?.let {
            fakeWebEvent.add(MixedWebViewEventConsumer.EVENT_KEY, it)
        }
        eventType?.let {
            fakeWebEvent.addProperty(MixedWebViewEventConsumer.EVENT_TYPE_KEY, it)
        }
        return fakeWebEvent
    }
}
