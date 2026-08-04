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
                favoriteDao.insertToRoomDatabase(
                    Favorite(
                        id = remote.id,
                        address = remote.address,
                        lat = remote.lat,
                        lng = remote.lng
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
            remotes.map {
                Favorite(id = it.id, address = it.address, lat = it.lat, lng = it.lng)
            }
        } catch (e: Exception) {
            Timber.e(e, "Cloud search failed")
            emptyList()
        }
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNewFavorite(favorite: Favorite): Long {
        // Insert ke cloud
        try {
            supabaseApi.insertFavorite(
                apiKey = BuildConfig.SUPABASE_ANON_KEY,
                auth = "Bearer ${BuildConfig.SUPABASE_ANON_KEY}",
                favorite = FavoriteRemote(
                    address = favorite.address,
                    lat = favorite.lat,
                    lng = favorite.lng
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Cloud insert failed, saved locally only")
        }
        // Insert ke Room lokal
        return favoriteDao.insertToRoomDatabase(favorite)
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
