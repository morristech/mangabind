package com.github.thibseisel.mangabind

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

class ChapterDownloader(
    private val httpClient: OkHttpClient
) {



    @Throws(IOException::class)
    private fun loadImage(url: String): InputStream {
        val response = httpClient.newCall(Request.Builder()
                .url(url)
                .build()
        ).execute()

        if (!response.isSuccessful) throw IOException("Unexpected code: ${response.code()}")
        return response.body()!!.byteStream()
    }
}