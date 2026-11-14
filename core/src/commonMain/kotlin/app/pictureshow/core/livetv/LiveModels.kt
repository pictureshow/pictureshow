/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.livetv

import app.pictureshow.core.ItemUserData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Serializable
data class LivePage<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0
)

@Serializable
data class LiveProgram(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "Untitled program",
    @SerialName("ChannelId") val channelId: String = "",
    @SerialName("ChannelName") val channelName: String? = null,
    @SerialName("StartDate") val startDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("IsSeries") val isSeries: Boolean = false,
    @SerialName("IsRepeat") val isRepeat: Boolean? = null,
    @SerialName("TimerId") val timerId: String? = null,
    @SerialName("SeriesTimerId") val seriesTimerId: String? = null,
    @SerialName("EpisodeTitle") val episodeTitle: String? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList()
)

@Serializable
data class LiveChannel(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "Unnamed channel",
    @SerialName("ChannelNumber") val number: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("UserData") val userData: ItemUserData = ItemUserData(),
    @SerialName("CurrentProgram") val currentProgram: LiveProgram? = null
)

/** A missing policy flag does not establish permission. The server remains authoritative after every call. */
data class LivePolicy(
    val access: Boolean = false,
    val manage: Boolean = false,
    val deleteContent: Boolean = false,
    val userData: Boolean = false
)

@Serializable
data class LiveInfo(
    @SerialName("IsEnabled") val enabled: Boolean = false,
    @SerialName("Services") val services: List<JsonObject> = emptyList()
)

@Serializable
data class LiveGuideInfo(@SerialName("StartDate") val startDate: String? = null, @SerialName("EndDate") val endDate: String? = null)

/** A timer/rule is kept intact, including provider-specific fields the editor cannot understand. */
data class LiveSchedule(val fields: JsonObject, val dirtyFields: Set<String> = emptySet()) {
    val id: String get() = string("Id").orEmpty()
    val name: String get() = string("Name") ?: "Recording"
    val channelId: String get() = string("ChannelId").orEmpty()
    val channelName: String? get() = string("ChannelName")
    val programId: String get() = string("ProgramId").orEmpty()
    val startDate: String? get() = string("StartDate")
    val endDate: String? get() = string("EndDate")
    val status: String get() = string("Status") ?: "Unknown"
    val errorMessage: String? get() = string("ErrorMessage") ?: string("Error")
    val seriesTimerId: String? get() = string("SeriesTimerId")
    fun string(key: String): String? = (fields[key] as? JsonPrimitive)?.contentOrNull
    fun boolean(key: String): Boolean? = (fields[key] as? JsonPrimitive)?.booleanOrNull
    override fun toString(): String = "LiveSchedule(id=$id, status=$status, fields=${fields.keys})"
}

/** "unknown" means a write may have reached the server: reconcile it, never silently resubmit. */
data class LiveWrite(val outcome: String, val schedule: LiveSchedule? = null, val message: String? = null)
