package com.charleshartman.porchlightpress.data.repo

import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.Story

/** Searches downloaded stories, including cached translations in Room FTS. */
class StorySearchRepository(private val db: AppDatabase) {
    suspend fun search(
        text: String,
        category: String = "",
        locationId: String = "",
    ): List<Story> {
        val query = ftsQuery(text)
        if (query.isEmpty()) return emptyList()
        return db.storyDao().searchFtsFiltered(query, category, locationId)
    }

    companion object {
        /** FTS operators in user input are text, never query syntax. */
        fun ftsQuery(input: String): String =
            Regex("[\\p{L}\\p{N}]+").findAll(input)
                .take(8)
                .map { "${it.value}*" }
                .joinToString(" AND ")
    }
}
