package io.github.jqssun.gpscihuyy.update

import retrofit2.Call
import retrofit2.http.GET


interface GitHubService {

    @GET("releases/latest")
    fun getReleases(): Call<GitHubRelease>

}

