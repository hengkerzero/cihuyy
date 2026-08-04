package io.github.jqssun.gpssetter.network

import retrofit2.http.*

interface SupabaseApi {

    @GET("rest/v1/favorites")
    suspend fun getFavorites(
        @Header("apikey") apiKey: String,
        @Header("Authorization") auth: String,
        @Query("select") select: String = "*",
        @Query("address") search: String? = null,
        @Query("order") order: String = "id.desc",
        @Query("limit") limit: Int = 200
    ): List<FavoriteRemote>

    @POST("rest/v1/favorites")
    @Headers("Prefer: return=representation")
    suspend fun insertFavorite(
        @Header("apikey") apiKey: String,
        @Header("Authorization") auth: String,
        @Body favorite: FavoriteRemote
    ): List<FavoriteRemote>

    @DELETE("rest/v1/favorites")
    suspend fun deleteFavorite(
        @Header("apikey") apiKey: String,
        @Header("Authorization") auth: String,
        @Query("id") idFilter: String
    )
}
