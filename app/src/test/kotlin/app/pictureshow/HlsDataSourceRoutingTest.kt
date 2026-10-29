/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import kotlin.test.Test
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class HlsDataSourceRoutingTest {
    @Test
    fun onlyMediaAndInitializationUseTheBoundedTransport() {
        val ordinary = ByteArrayDataSource(byteArrayOf(1))
        val bounded = ByteArrayDataSource(byteArrayOf(2))
        val factory = HlsPreparationSource.typedDataSources(DataSource.Factory { ordinary }, DataSource.Factory { bounded })
        assertSame(bounded, factory.createDataSource(C.DATA_TYPE_MEDIA))
        assertSame(bounded, factory.createDataSource(C.DATA_TYPE_MEDIA_INITIALIZATION))
        for (type in listOf(C.DATA_TYPE_MANIFEST, C.DATA_TYPE_DRM, C.DATA_TYPE_TIME_SYNCHRONIZATION, C.DATA_TYPE_UNKNOWN)) {
            assertSame(ordinary, factory.createDataSource(type))
        }
    }
}
