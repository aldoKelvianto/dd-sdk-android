/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.core.internal.thread

import com.datadog.android.api.InternalLogger
import com.datadog.android.core.configuration.BackPressureStrategy
import com.datadog.android.utils.verifyLog
import com.datadog.tools.unit.forge.aThrowable
import fr.xgouchet.elmyr.Forge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verifyNoInteractions
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class LoggingScheduledThreadPoolExecutorTest :
    AbstractExecutorServiceTest<ScheduledThreadPoolExecutor>() {

    override fun createTestedExecutorService(backPressureStrategy: BackPressureStrategy): ScheduledThreadPoolExecutor {
        return LoggingScheduledThreadPoolExecutor(1, mockInternalLogger, backPressureStrategy)
    }

    @Test
    fun `M log nothing W schedule() { task completes normally }`() {
        // When
        val futureTask = testedExecutor.schedule({
            // no-op
        }, 1, TimeUnit.MILLISECONDS)
        Thread.sleep(DEFAULT_SLEEP_DURATION_MS)

        // Then
        assertThat(futureTask.isDone).isTrue

        verifyNoInteractions(mockInternalLogger)
    }

    @Test
    fun `M log nothing W schedule() { worker thread was interrupted }`() {
        // When
        val futureTask = testedExecutor.submit {
            Thread.currentThread().interrupt()
        }
        Thread.sleep(DEFAULT_SLEEP_DURATION_MS)

        // Then
        assertThat(futureTask.isDone).isTrue

        verifyNoInteractions(mockInternalLogger)
    }

    @Test
    fun `M log error + exception W schedule() { task throws an exception }`(
        forge: Forge
    ) {
        // Given
        val throwable = forge.aThrowable()

        // When
        val futureTask = testedExecutor.schedule({
            throw throwable
        }, 1, TimeUnit.MILLISECONDS)
        Thread.sleep(DEFAULT_SLEEP_DURATION_MS)

        // Then
        assertThat(futureTask.isDone).isTrue

        mockInternalLogger.verifyLog(
            InternalLogger.Level.ERROR,
            listOf(InternalLogger.Target.USER, InternalLogger.Target.TELEMETRY),
            ERROR_UNCAUGHT_EXECUTION_EXCEPTION,
            throwable
        )
    }

    @Test
    fun `M log error + exception W schedule() { task is cancelled }`() {
        // When
        val futureTask = testedExecutor.schedule({
            Thread.sleep(500)
        }, 1, TimeUnit.MILLISECONDS)
        futureTask.cancel(true)
        Thread.sleep(DEFAULT_SLEEP_DURATION_MS)

        // Then
        assertThat(futureTask.isCancelled).isTrue

        mockInternalLogger.verifyLog(
            InternalLogger.Level.ERROR,
            listOf(InternalLogger.Target.USER, InternalLogger.Target.TELEMETRY),
            ERROR_UNCAUGHT_EXECUTION_EXCEPTION,
            CancellationException::class.java
        )
    }
}
