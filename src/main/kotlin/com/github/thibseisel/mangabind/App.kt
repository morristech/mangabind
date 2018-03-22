package com.github.thibseisel.mangabind

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.OkHttpClient
import kotlin.coroutines.experimental.buildIterator
import kotlin.coroutines.experimental.buildSequence

object MangaBind {

    private val console = ConsoleView
    private val httpClient = OkHttpClient()

    private val resultReporter = actor<LoadResult>(start = CoroutineStart.LAZY) {
        for (result in channel) {
            console.writeResult(result)
        }
    }

    /**
     * Execute the application.
     */
    fun run(): Unit = runBlocking {
        val sources = loadSourcesFromCatalog("mangasource.json")
        console.writeMangaList(sources)

        var pickedSource: MangaSource? = null
        while (pickedSource == null) {
            val sourceId = console.askSourceId()
            if (sourceId < 0) return@runBlocking
            pickedSource = sources.firstOrNull { it.id == sourceId }
        }

        val chapterRange = console.askChapterRange()
        val jobs = arrayListOf<Job>()
        for (chapter in chapterRange) {
            // Fork each chapter download task
            jobs += launch {
                val result = loadChapter(pickedSource, chapter)
                resultReporter.send(result)
            }
        }

        jobs.forEach { it.join() }
    }

    /**
     * Free up resources allocated by the application.
     * To be called when execution is finished.
     */
    fun cleanup() {
        resultReporter.close()
    }

    private fun loadSourcesFromCatalog(filename: String): List<MangaSource> {
        val mapper = ObjectMapper()
        val catalogIs = Thread.currentThread().contextClassLoader.getResourceAsStream(filename)
        return mapper.readValue(catalogIs,
            mapper.typeFactory.constructCollectionType(List::class.java, MangaSource::class.java)
        )
    }

    private fun loadChapter(source: MangaSource, chapterNumber: Int): LoadResult {
        val downloader = ChapterDownloader(httpClient)

        val urlTemplates = buildSequence {
            source.singlePages?.also {
                for (singlePagePart in it) {
                    yield(source.baseUrl + singlePagePart)
                }
            }

            source.doublePages?.also {
                for (doublePagePart in it) {
                    yield(source.baseUrl + doublePagePart)
                }
            }
        }

        for (template in urlTemplates) {

        }

        TODO()
    }

    private fun bindUrlParameters(templateUrl: String, chapter: Int, page: Int): String {
        TODO()
    }
}

fun main(args: Array<String>) {
    MangaBind.run()
    MangaBind.cleanup()
}

class LoadResult(
        val chapter: Int,
        val pages: IntArray,
        val isSuccessful: Boolean,
        val error: String? = null
)