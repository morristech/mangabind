package com.github.thibseisel.mangabind.source

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.type.CollectionType
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

/**
 * Loads manga resource list from the JSON catalog bundled with the application.
 *
 * @constructor
 * Creates an instance of a loader that fetches from a given resource file bundled with the application.
 *
 * @param filename The name of a JSON resource file bundled with the application from which manga should be read.
 */
class JsonResourceLoader
@Inject constructor(
    private val mapper: ObjectMapper,
    @Named("catalog") private val filename: String
): MangaRepository {

    private val sourceListType: CollectionType = mapper.typeFactory.constructCollectionType(
            List::class.java,
            MangaSource::class.java
    )

    @Throws(IOException::class)
    override fun getAll(): List<MangaSource> {
        Thread.currentThread().contextClassLoader.getResourceAsStream(filename)?.let {
            return try {
                mapper.readValue(it, sourceListType)
            } catch (jpe: JsonParseException) {
                throw IOException("Cannot read manga catalog: file contains malformed JSON.")
            } catch (jme: JsonMappingException) {
                throw IOException("Cannot read manga catalog: file content cannot be interpreted as manga sources.")
            }

        } ?: throw FileNotFoundException("Cannot read manga catalog: file not found.")
    }

    override fun save(manga: MangaSource) {
        TODO()
    }

    override fun delete(manga: MangaSource) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}