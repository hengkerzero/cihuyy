package io.github.jqssun.gpssetter.room
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

        // REPLACE: kalau id sudah ada di Room, update datanya (bukan skip)
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insertToRoomDatabase(favorite: Favorite) : Long

        // for update single favorite
        @Update
        suspend fun updateUserDetails(favorite: Favorite)

        //delete single favorite
        @Delete
        suspend fun deleteSingleFavorite(favorite: Favorite)

       //get all Favorite inserted to room database
        @Transaction
        @Query("SELECT * FROM favorite ORDER BY id DESC")
        fun getAllFavorites() : Flow<List<Favorite>>

        //get single favorite inserted to room database
        @Transaction
        @Query("SELECT * FROM favorite WHERE id = :id ORDER BY id DESC")
        fun getSingleFavorite(id: Long) : Favorite

}
