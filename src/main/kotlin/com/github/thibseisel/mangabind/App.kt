package com.github.thibseisel.mangabind

import com.github.thibseisel.mangabind.dagger.DaggerAppComponent
import com.github.thibseisel.mangabind.dagger.FilenameProviderModule
import com.github.thibseisel.mangabind.source.MangaSource
import com.github.thibseisel.mangabind.source.SourceLoader
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.actor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Named

class Mangabind
@Inject constructor(
    private val console: ConsoleView,
    private val httpClient: OkHttpClient,
    private val sourceCatalog: SourceLoader,
    @Named("outputDir") outputDirName: String
) {

    private val outputDir = File(outputDirName)
    private val logger: Logger = LogManager.getFormatterLogger("App")

    private val resultReporter = actor<LoadResult>(start = CoroutineStart.LAZY, capacity = 3) {
        for (result in channel) {
            console.writeResult(result)
        }
    }

    /**
     * Execute the application.
     */
    fun run() {

        logger.info("Starting application.")

        // Load manga sources from catalog.
        val sources = try {
            sourceCatalog.loadAll().sortedBy { it.id }

        } catch (ioe: IOException) {
            logger.fatal("Error while loading catalog.", ioe)
            val message = ioe.message ?: "Error while loading manga sources catalog."
            console.showErrorMessage(message)
            return
        }

        if (sources.isNotEmpty()) {
            console.displayMangaList(sources)

            var pickedSource: MangaSource? = null
            while (pickedSource == null) {
                val sourceId = console.askSourceId()
                if (sourceId < 0) return
                pickedSource = sources.firstOrNull { it.id == sourceId }
            }

            outputDir.mkdir()
            val chapterRange = console.askChapterRange()
            for (chapter in chapterRange) {
                logger.info("Start loading of chapter N°%d...", chapter)
                loadChapter(pickedSource, chapter)
            }

        } else {
            logger.warn("Manga catalog is empty. Maybe should be filled ?")
            console.showErrorMessage("No manga sources available.")
        }

        logger.info("Application terminated normally.")
    }

    /**
     * Free up resources allocated by the application.
     * To be called when execution is finished.
     */
    fun cleanup() {
        resultReporter.close()
    }

    private fun loadChapter(source: MangaSource, chapter: Int): Unit = runBlocking {
        val chapterDownloadJob = Job()
        val destFilename = source.title.filterNot(Char::isWhitespace) + "_%02d_%02d.%s"
        val destFilenameDoublePage = source.title.filterNot(Char::isWhitespace) + "_%02d_%02d-%02d.%s"

        // Provide an immutable increasing page number to solve concurrency problems
        val pageIterator = NaturalNumbers(startValue = source.startPage, maxValue = 100)
        var giveLastChance = true

        val singlePages = LinkedList<String>(source.singlePages)
        val doublePages = LinkedList<String>(source.doublePages ?: emptyList())

        try {
            page@ while (pageIterator.hasNext()) {
                val page = pageIterator.nextInt()

                logger.debug("[%d,%02d] Matching single-page urls...", chapter, page)

                url@ for ((index, template) in singlePages.withIndex()) {
                    val url = buildUrl(template, chapter, page)
                    logger.trace(url)
                    val imageStream = attemptConnection(url) ?: continue@url

                    logger.info("[%d,%02d] Found matching URL %s", chapter, page, url)

                    // Promote the url that hit, as it is more likely to hit again.
                    singlePages.removeAt(index)
                    singlePages.addFirst(template)

                    launch(parent = chapterDownloadJob) {
                        val filename = destFilename.format(chapter, page, url.substringAfterLast('.'))
                        val destFile = File("pages", filename)
                        writeTo(destFile, imageStream)
                        resultReporter.send(LoadResult(true, chapter, page..page))
                    }

                    // Restore last chance
                    giveLastChance = true

                    // Skipping to the next page is done at each iteration.
                    continue@page
                }

                logger.debug("[%d,%02d] Matching double-page urls...", chapter, page)

                url@ for ((index, template) in doublePages.withIndex()) {
                    val url = buildUrl(template, chapter, page)
                    logger.trace(url)
                    val imageStream = attemptConnection(url) ?: continue@url

                    logger.info("[%d,%02d] Found matching URL %s", chapter, page, url)

                    // Promote the url that hit, as it is more likely to hit again.
                    doublePages.removeAt(index)
                    doublePages.addFirst(template)

                    launch(parent = chapterDownloadJob) {
                        val filename = destFilenameDoublePage.format(
                            chapter,
                            page,
                            page + 1,
                            url.substringAfterLast('.')
                        )

                        val destFile = File("pages", filename)
                        writeTo(destFile, imageStream)
                        resultReporter.send(LoadResult(true, chapter, page..page + 1))
                    }

                    // Restore last chance
                    giveLastChance = true
                    
                    // Manually skip one more page to increment the page counter by 2.
                    pageIterator.nextInt()
                    continue@page
                }

                if (giveLastChance) {
                    logger.info("[%d,%02d] No matching URL. Check for a \"missing page\" scenario...", chapter, page)
                    resultReporter.send(LoadResult(false, chapter, page..page))
                    giveLastChance = false
                    continue@page
                }

                logger.info("[%d,%02d] No matching URL found.", chapter, page)

                resultReporter.send(LoadResult(false, chapter, page..page))
                break@page
            }

            logger.debug("Waiting for download tasks to complete...")
            chapterDownloadJob.joinChildren()

            logger.info("Finished downloading chapter %s.\n", chapter)

        } catch (ioe: IOException) {
            logger.error("Unexpected error while loading chapter %d.", chapter, ioe)
            val error = "Error while loading chapter $chapter of ${source.title}: ${ioe.message}"
            console.showErrorMessage(error)
            chapterDownloadJob.cancelAndJoin()
        }
    }

    @Throws(IOException::class)
    private fun attemptConnection(url: String): InputStream? {
        val response = httpClient.newCall(
            Request.Builder()
                .url(url)
                .build()
        ).execute()

        return when {
            response.isSuccessful -> response.body()?.byteStream()
            404 == response.code() -> null
            else -> throw IOException("Unexpected HTTP error code: ${response.code()}")
        }
    }

    @Throws(IOException::class)
    private fun writeTo(file: File, imageBytes: InputStream) {
        val buffer = ByteArray(8 * 1024)
        file.outputStream().use { out ->
            var read: Int = imageBytes.read(buffer)
            while (read != -1) {
                out.write(buffer, 0, read)
                read = imageBytes.read(buffer)
            }
        }
    }
}

fun main(args: Array<String>) {
    val appComponent = DaggerAppComponent.builder()
        .filenameProviderModule(FilenameProviderModule("pages"))
        .build()

    with (appComponent.mangabind) {
        run()
        cleanup()
    }

    // Press Enter to continue...
    readLine()
}

class LoadResult(
    val isSuccessful: Boolean,
    val chapter: Int,
    val pages: IntRange
)