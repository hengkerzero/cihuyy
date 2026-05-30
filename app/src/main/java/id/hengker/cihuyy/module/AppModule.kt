package id.hengker.cihuyy.module

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.hengker.cihuyy.module.util.ApplicationScope
import id.hengker.cihuyy.room.AppDatabase
import id.hengker.cihuyy.room.FavoriteDao
import id.hengker.cihuyy.update.GitHubService
import id.hengker.cihuyy.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule{

    @Singleton
    @Provides
    fun createGitHubService(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/repos/hengkerzero/cihuyy/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    fun provideDownloadManger(application: Application) =
        application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager


    @Singleton
    @Provides
    fun provideGithubService(retrofit: Retrofit): GitHubService =
        retrofit.create(GitHubService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(application: Application, callback: AppDatabase.Callback)
            = Room.databaseBuilder(application, AppDatabase::class.java, "user_database")
        .allowMainThreadQueries()
        .fallbackToDestructiveMigration()
        .addCallback(callback)
        .build()


    @Singleton
    @Provides
    fun providesUserDao(favoriteDatabase: AppDatabase) : FavoriteDao =
        favoriteDatabase.favoriteDao()

    @Singleton
    @Provides
    fun provideSettingRepo() : PrefManager =
        PrefManager

    @ApplicationScope
    @Provides
    @Singleton
    fun providesApplicationScope() = CoroutineScope(SupervisorJob())

}
