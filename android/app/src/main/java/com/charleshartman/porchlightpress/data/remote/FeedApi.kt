package com.charleshartman.porchlightpress.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/** Static feeds on GitHub Pages. All documents are plain JSON; the app never renders remote HTML. */
interface FeedApi {
    @GET("index.json")
    suspend fun index(): Response<IndexDto>

    @GET
    suspend fun edition(@Url path: String): Response<EditionDto>

    @GET
    suspend fun places(@Url path: String): Response<PlacesDto>

    @GET
    suspend fun postal(@Url path: String): Response<PostalDto>
}
