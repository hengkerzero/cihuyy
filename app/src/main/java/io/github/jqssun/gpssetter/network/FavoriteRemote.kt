package io.github.jqssun.gpssetter.network

import com.google.gson.annotations.SerializedName

data class FavoriteRemote(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("address") val address: String?,
    @SerializedName("lat") val lat: Double?,
    @SerializedName("lng") val lng: Double?
)
