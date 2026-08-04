package io.github.jqssun.gpssetter.repository

import androidx.annotation.WorkerThread
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.network.FavoriteRemote
import io.github.jqssun.gpssetter.network.SupabaseApi
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.room.FavoriteDao
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject

class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val supabaseApi: SupabaseApi
) {
    val getAllFavorites: Flow<List<Favorite>>
        get() = favoriteDao.getAllFavorites()

    // Sync semua data dari Supabase ke Room lokal
    suspend fun syncFromCloud() {
        try {
            val remotes = supabaseApi.getFavorites(
                apiKey = BuildConfig.SUPABASE_ANON_KEY,
                auth = "Bearer ${BuildConfig.SUPABASE_ANON_KEY}"
            )
            remotes.forEach { remote ->
                // Skip kalau id atau address null
                val remoteId = remote.id ?: return@forEach
                favoriteDao.insertToRoomDatabase(
                    Favorite(
                        id = remoteId,
                        address = remote.address,
                        lat = remote.lat ?: 0.0,
                        lng = remote.lng ?: 0.0
                    )
                )
            }
            Timber.d("Synced ${remotes.size} favorites from cloud")
        } catch (e: Exception) {
            Timber.e(e, "Cloud sync failed, using local data")
        }
    }

    // Search dari Supabase (by keyword address)
    suspend fun searchCloud(keyword: String): List<Favorite> {
        return try {
            val remotes = supabaseApi.getFavorites(
                apiKey = BuildConfig.SUPABASE_ANON_KEY,
                auth = "Bearer ${BuildConfig.SUPABASE_ANON_KEY}",
                search = "ilike.*${keyword}*"
            )
            remotes.mapNotNull { remote ->
                val remoteId = remote.id ?: return@mapNotNull null
                Favorite(id = remoteId, address = remote.address, lat = remote.lat ?: 0.0, lng = remote.lng ?: 0.0)
            }
        } catch (e: Exception) {
            Timber.e(e, "Cloud search failed")
            emptyList()
        }
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNewFavorite(favorite: Favorite): Long {
        // Insert ke cloud dulu, ambil id yang di-generate Supabase
        val cloudId: Long? = try {
            val result = supabaseApi.insertFavorite(
                apiKey = BuildConfig.SUPABASE_ANON_KEY,
                auth = "Bearer ${BuildConfig.SUPABASE_ANON_KEY}",
                favorite = FavoriteRemote(
                    address = favorite.address,
                    lat = favorite.lat,
                    lng = favorite.lng
                )
            )
            result.firstOrNull()?.id
        } catch (e: Exception) {
            Timber.e(e, "Cloud insert failed, saved locally only")
            null
        }

        // Pakai id dari Supabase kalau berhasil, fallback ke id lokal
        val finalFavorite = if (cloudId != null) favorite.copy(id = cloudId) else favorite
        return favoriteDao.insertToRoomDatabase(finalFavorite)
    }

    suspend fun deleteFavorite(favorite: Favorite) {
        // Hapus dari cloud
        try {
            favorite.id?.let {
                supabaseApi.deleteFavorite(
                    apiKey = BuildConfig.SUPABASE_ANON_KEY,
                    auth = "Bearer ${BuildConfig.SUPABASE_ANON_KEY}",
                    idFilter = "eq.$it"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Cloud delete failed")
        }
        // Hapus dari Room lokal
        favoriteDao.deleteSingleFavorite(favorite)
    }

    fun getSingleFavorite(id: Long): Favorite {
        return favoriteDao.getSingleFavorite(id)
    }
}
