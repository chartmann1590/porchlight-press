package com.charleshartman.porchlightpress.data.remote

import android.content.Context
import com.charleshartman.porchlightpress.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

object NetworkModule {
    val feedJson: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun baseUrl(): String {
        val raw = BuildConfig.FEED_BASE_URL.trim()
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    /** 50 MB HTTP disk cache honoring ETag/Cache-Control (Phase 5 spec). */
    fun okHttp(context: Context): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http")
        return OkHttpClient.Builder()
            .cache(Cache(cacheDir, 50L * 1024L * 1024L))
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent())
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    fun feedApi(context: Context, client: OkHttpClient = okHttp(context)): FeedApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl())
            .client(client)
            .addConverterFactory(feedJson.asConverterFactory(contentType))
            .build()
            .create(FeedApi::class.java)
    }

    fun userAgent(): String =
        "PorchlightPress/${BuildConfig.VERSION_NAME} " +
            "(+https://github.com/chartmann1590/porchlight-press; me@charleshartman.com)"
}
