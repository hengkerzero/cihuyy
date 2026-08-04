package io.github.jqssun.gpssetter.module

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.network.SupabaseApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Singleton
    @Provides
    @Named("supabase")
    fun provideSupabaseRetrofit(): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.SUPABASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    fun provideSupabaseApi(@Named("supabase") retrofit: Retrofit): SupabaseApi =
        retrofit.create(SupabaseApi::class.java)
}
