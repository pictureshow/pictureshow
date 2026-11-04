/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class NativeHlsLoadErrorPolicyTest {
    private val ordinary = DefaultLoadErrorHandlingPolicy()
    private val native = NativeHlsLoadErrorPolicy()
    private val spec = DataSpec(Uri.parse("https://example.test/segment.ts"))

    @Test
    fun onlyNativeMediaAndInitializationGetFourRetries() {
        assertEquals(4, native.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(4, native.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_INITIALIZATION))
        for (type in listOf(
            C.DATA_TYPE_MANIFEST,
            C.DATA_TYPE_DRM,
            C.DATA_TYPE_TIME_SYNCHRONIZATION,
            C.DATA_TYPE_UNKNOWN,
            C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
        )) {
            assertEquals(ordinary.getMinimumLoadableRetryCount(type), native.getMinimumLoadableRetryCount(type))
        }
    }

    @Test
    fun retryDelaysFallbackExclusionAndNonRetryableErrorsKeepMedia3Defaults() {
        val errors = listOf(
            SocketTimeoutException(),
            IOException("synthetic"),
            FileNotFoundException("synthetic"),
            IOException("wrapped", ParserException.createForMalformedContainer("synthetic", null)),
            DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        ) + listOf(403, 404, 410, 416, 500, 503, 401).map { status ->
            HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), spec, byteArrayOf())
        }
        val options = listOf(
            LoadErrorHandlingPolicy.FallbackOptions(2, 0, 2, 0, false),
            LoadErrorHandlingPolicy.FallbackOptions(2, 0, 2, 0, true),
            LoadErrorHandlingPolicy.FallbackOptions(1, 0, 2, 0, false),
            LoadErrorHandlingPolicy.FallbackOptions(1, 0, 1, 0, false)
        )
        for (error in errors) {
            for (count in 1..16) {
                val info = LoadErrorHandlingPolicy.LoadErrorInfo(
                    LoadEventInfo.Builder(1, spec, 0).build(),
                    MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_DEFAULT, null, 0, null, 0, 3_000),
                    error,
                    count
                )
                assertEquals(ordinary.getRetryDelayMsFor(info), native.getRetryDelayMsFor(info))
                for (option in options) {
                    val expected = ordinary.getFallbackSelectionFor(option, info)
                    val actual = native.getFallbackSelectionFor(option, info)
                    assertEquals(expected?.type, actual?.type)
                    assertEquals(expected?.exclusionDurationMs, actual?.exclusionDurationMs)
                }
            }
        }
    }
}
