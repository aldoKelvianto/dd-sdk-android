/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.rum.internal.domain

import com.datadog.android.api.InternalLogger
import com.datadog.android.api.storage.EventBatchWriter
import com.datadog.android.api.storage.RawBatchEvent
import com.datadog.android.core.persistence.Serializer
import com.datadog.android.rum.internal.domain.event.RumEventMeta
import com.datadog.android.rum.model.ActionEvent
import com.datadog.android.rum.model.ErrorEvent
import com.datadog.android.rum.model.LongTaskEvent
import com.datadog.android.rum.model.ResourceEvent
import com.datadog.android.rum.model.ViewEvent
import com.datadog.android.rum.utils.config.GlobalRumMonitorTestConfiguration
import com.datadog.android.rum.utils.forge.Configurator
import com.datadog.tools.unit.annotations.TestConfigurationsProvider
import com.datadog.tools.unit.extensions.TestConfigurationExtension
import com.datadog.tools.unit.extensions.config.TestConfiguration
import com.datadog.tools.unit.forge.aThrowable
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class),
    ExtendWith(TestConfigurationExtension::class)
)
@MockitoSettings(strictness = Strictness.LENIENT)
@ForgeConfiguration(Configurator::class)
internal class RumDataWriterTest {

    private lateinit var testedWriter: RumDataWriter

    @Mock
    lateinit var mockSerializer: Serializer<Any>

    @Mock
    lateinit var mockEventMetaSerializer: Serializer<RumEventMeta>

    @Mock
    lateinit var mockInternalLogger: InternalLogger

    @Mock
    lateinit var mockEventBatchWriter: EventBatchWriter

    @StringForgery
    lateinit var fakeSerializedEvent: String
    lateinit var fakeSerializedData: ByteArray

    @BeforeEach
    fun `set up`() {
        fakeSerializedData = fakeSerializedEvent.toByteArray(Charsets.UTF_8)

        whenever(mockEventBatchWriter.write(RawBatchEvent(data = fakeSerializedData), null)) doReturn true
        whenever(rumMonitor.mockSdkCore.internalLogger) doReturn mockInternalLogger

        testedWriter = RumDataWriter(
            mockSerializer,
            mockEventMetaSerializer,
            rumMonitor.mockSdkCore
        )
    }

    @Test
    fun `M write data W write()`(
        forge: Forge
    ) {
        // Given
        val fakeEvent = forge.anElementFrom(
            forge.getForgery(ViewEvent::class.java),
            forge.getForgery(ActionEvent::class.java),
            forge.getForgery(ResourceEvent::class.java),
            forge.getForgery(LongTaskEvent::class.java),
            forge.getForgery(ErrorEvent::class.java)
        )

        whenever(mockSerializer.serialize(fakeEvent)) doReturn fakeSerializedEvent

        // When
        val result = testedWriter.write(mockEventBatchWriter, fakeEvent)

        // Then
        assertThat(result).isTrue

        verify(mockEventBatchWriter).write(
            RawBatchEvent(data = fakeSerializedData),
            null
        )
    }

    @Test
    fun `M write data with event meta W write() {View Event}`(
        @Forgery fakeViewEvent: ViewEvent,
        @StringForgery fakeSerializedViewEventMeta: String
    ) {
        // Given
        whenever(mockSerializer.serialize(fakeViewEvent)) doReturn fakeSerializedEvent
        val eventMeta = RumEventMeta.View(
            viewId = fakeViewEvent.view.id,
            documentVersion = fakeViewEvent.dd.documentVersion
        )
        whenever(mockEventMetaSerializer.serialize(eventMeta)) doReturn fakeSerializedViewEventMeta

        // When
        testedWriter.write(mockEventBatchWriter, fakeViewEvent)

        // Then
        verify(mockEventBatchWriter).write(
            RawBatchEvent(
                data = fakeSerializedData,
                metadata = fakeSerializedViewEventMeta.toByteArray()
            ),
            null
        )
    }

    @Test
    fun `M write data with empty event meta W write() {View Event, meta serialization fails}`(
        @Forgery fakeViewEvent: ViewEvent,
        forge: Forge
    ) {
        // Given
        whenever(mockSerializer.serialize(fakeViewEvent)) doReturn fakeSerializedEvent
        val eventMeta = RumEventMeta.View(
            viewId = fakeViewEvent.view.id,
            documentVersion = fakeViewEvent.dd.documentVersion
        )
        whenever(mockEventMetaSerializer.serialize(eventMeta)) doThrow forge.aThrowable()

        // When
        testedWriter.write(mockEventBatchWriter, fakeViewEvent)

        // Then
        verify(mockEventBatchWriter).write(
            RawBatchEvent(data = fakeSerializedData),
            null
        )
    }

    @Test
    fun `M not write data W write() { exception during serialization }`(
        forge: Forge
    ) {
        // Given
        val fakeEvent = forge.anElementFrom(
            forge.getForgery(ViewEvent::class.java),
            forge.getForgery(ActionEvent::class.java),
            forge.getForgery(ResourceEvent::class.java),
            forge.getForgery(LongTaskEvent::class.java),
            forge.getForgery(ErrorEvent::class.java)
        )

        whenever(mockSerializer.serialize(fakeEvent)) doThrow forge.aThrowable()

        // When
        val result = testedWriter.write(mockEventBatchWriter, fakeEvent)

        // Then
        assertThat(result).isFalse

        verifyNoInteractions(mockEventBatchWriter)
    }

    @Test
    fun `M return false W write() { data was not written }`(
        forge: Forge
    ) {
        // Given
        val fakeEvent = forge.anElementFrom(
            forge.getForgery(ViewEvent::class.java),
            forge.getForgery(ActionEvent::class.java),
            forge.getForgery(ResourceEvent::class.java),
            forge.getForgery(LongTaskEvent::class.java),
            forge.getForgery(ErrorEvent::class.java)
        )

        whenever(mockEventBatchWriter.write(RawBatchEvent(fakeSerializedData), null)) doReturn false

        // When
        val result = testedWriter.write(mockEventBatchWriter, fakeEvent)

        // Then
        assertThat(result).isFalse
    }

    // region onDataWritten

    @Test
    fun `M do not notify the RumMonitor W onDataWritten() { ViewEvent }`(
        @Forgery viewEvent: ViewEvent
    ) {
        // When
        testedWriter.onDataWritten(viewEvent, fakeSerializedData)

        // Then
        verifyNoInteractions(rumMonitor.mockInstance)
    }

    @Test
    fun `M persist the event into the NDK crash folder W onDataWritten(){ViewEvent+dir exists}`(
        @Forgery viewEvent: ViewEvent
    ) {
        // When
        testedWriter.onDataWritten(viewEvent, fakeSerializedData)

        // Then
        verify(rumMonitor.mockSdkCore).writeLastViewEvent(fakeSerializedData)
        verifyNoInteractions(mockInternalLogger)
    }

    // endregion

    companion object {
        val rumMonitor = GlobalRumMonitorTestConfiguration()

        @TestConfigurationsProvider
        @JvmStatic
        fun getTestConfigurations(): List<TestConfiguration> {
            return listOf(rumMonitor)
        }
    }
}
