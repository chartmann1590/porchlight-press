package com.charleshartman.porchlightpress.data.repo

import com.charleshartman.porchlightpress.BuildConfig
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.Edition
import com.charleshartman.porchlightpress.data.local.EditionSection
import com.charleshartman.porchlightpress.data.local.EditionStory
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.local.StoryFts
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.data.remote.EditionDto
import com.charleshartman.porchlightpress.data.remote.FeedApi
import com.charleshartman.porchlightpress.data.remote.IndexDto
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.StoryDto
import com.charleshartman.porchlightpress.domain.EditionResolution
import com.charleshartman.porchlightpress.domain.FeedResult
import com.charleshartman.porchlightpress.domain.Place
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

data class SyncSummary(
    val editionId: String,
    val kind: String,
    val storyCount: Int,
    val feedPath: String,
    val fellBack: Boolean,
    val locationLabel: String,
    val generatedAt: String,
)

data class EditionContent(
    val edition: Edition,
    val sections: List<Pair<EditionSection, List<Story>>>,
)

/**
 * Resolves location -> best feed path via index.json (city -> county/metro ->
 * state -> national fallback), fetches, persists, and exposes Flow from Room.
 * Room is the single source of truth; results carry Stale/Offline/Error state.
 */
class EditionRepository(
    private val db: AppDatabase,
    private val supportedMajor: Int = BuildConfig.SUPPORTED_FEED_API_MAJOR,
) {
    companion object {
        const val UPDATE_MESSAGE = "This edition needs a newer app. Please update Porchlight Press."
    }

    suspend fun sync(
        place: Place,
        kind: String = "latest",
        api: FeedApi,
    ): FeedResult<SyncSummary> {
        val index = try {
            val resp = api.index()
            if (!resp.isSuccessful || resp.body() == null) {
                return cachedOrError(place, "Feed index unavailable (HTTP ${resp.code()})")
            }
            resp.body()!!
        } catch (e: IOException) {
            return FeedResult.Offline(cachedSummary(place, kind))
        } catch (e: Exception) {
            return FeedResult.Error("Couldn't load the feed index: ${e.message}")
        }

        if (index.apiVersion > supportedMajor) return FeedResult.UpdateRequired(UPDATE_MESSAGE)

        val entries = index.editions.map {
            EditionResolution.IndexEntry(
                path = it.path,
                country = it.location.country,
                admin1 = it.location.admin1,
                admin2 = it.location.admin2,
                city = it.location.city,
                metro = it.location.metro,
            )
        }
        val path = EditionResolution.resolve(place, entries, kind)
            ?: return cachedOrError(place, "No feed covers ${place.label} yet")
        val firstChoice = EditionResolution.candidateFeedPaths(place, kind).firstOrNull()

        val edition = try {
            val resp = api.edition(path)
            if (!resp.isSuccessful || resp.body() == null) {
                return cachedOrError(place, "Edition unavailable (HTTP ${resp.code()})")
            }
            resp.body()!!
        } catch (e: IOException) {
            return FeedResult.Offline(cachedSummary(place, kind))
        } catch (e: Exception) {
            return FeedResult.Error("Couldn't load the edition: ${e.message}")
        }

        if (edition.apiVersion > supportedMajor) return FeedResult.UpdateRequired(UPDATE_MESSAGE)
        persist(place, kind, path, edition)
        return FeedResult.Ok(
            SyncSummary(
                editionId = edition.editionId,
                kind = edition.kind,
                storyCount = edition.stories.size,
                feedPath = path,
                fellBack = path != firstChoice,
                locationLabel = edition.location.label.ifBlank { place.label },
                generatedAt = edition.generatedAt,
            ),
        )
    }

    /** Lightweight existence check for the confirm-place step ("local feed yet?"). */
    suspend fun probeFeed(path: String, api: FeedApi): Boolean {
        return try {
            val resp = api.edition(path)
            resp.isSuccessful && resp.body() != null
        } catch (e: Exception) {
            false
        }
    }

    fun observeEdition(locationId: String, kind: String = "latest"): Flow<EditionContent?> {
        // Poll-free: callers re-collect after sync; Room is the source of truth.
        return kotlinx.coroutines.flow.flow {
            val edition = db.editionDao().editionFor(locationId, kind)
            if (edition == null) {
                emit(null)
                return@flow
            }
            emit(contentOf(edition))
        }
    }

    suspend fun cachedContent(locationId: String, kind: String = "latest"): EditionContent? {
        val edition = db.editionDao().editionFor(locationId, kind) ?: return null
        return contentOf(edition)
    }

    private suspend fun cachedSummary(place: Place, kind: String = "latest"): SyncSummary? {
        val content = cachedContent(place.id, kind) ?: return null
        val stories = content.sections.sumOf { it.second.size }
        return SyncSummary(
            editionId = content.edition.id,
            kind = content.edition.kind,
            storyCount = stories,
            feedPath = "",
            fellBack = false,
            locationLabel = place.label,
            generatedAt = content.edition.generatedAt,
        )
    }

    private suspend fun cachedOrError(place: Place, message: String): FeedResult<Nothing> {
        val cached = cachedSummary(place)
        return if (cached != null) {
            FeedResult.Error(message, cached)
        } else {
            FeedResult.Error(message)
        }
    }

    private suspend fun contentOf(edition: Edition): EditionContent {
        val withContent = db.editionDao().editionWithContent(edition.id)
        // The SectionWithStories relation joins on sectionId alone, so links
        // from OTHER editions' same-named sections (e.g. every "top") are
        // included. Scope strictly to this edition or locations leak stories
        // into each other's papers.
        val ownLinks = withContent?.sections?.flatMap { s -> s.links }
            ?.filter { it.editionId == edition.id } ?: emptyList()
        val ids = ownLinks.map { it.storyId }
        val byId = if (ids.isEmpty()) emptyMap() else db.storyDao().storiesByIds(ids).associateBy { it.id }
        val sections = (withContent?.sections ?: emptyList())
            .sortedBy { it.section.order }
            .map { s ->
                val stories = s.links
                    .filter { it.editionId == edition.id }
                    .sortedBy { it.rank }
                    .mapNotNull { byId[it.storyId] }
                s.section to stories
            }
            .filter { it.second.isNotEmpty() }
        return EditionContent(edition, sections)
    }

    suspend fun persist(place: Place, kind: String, path: String, edition: EditionDto) {
        val now = System.currentTimeMillis()
        val editionRow = Edition(
            id = "${place.id}:$kind",
            locationId = place.id,
            kind = kind,
            generatedAt = edition.generatedAt,
            fetchedAt = now,
        )
        val sections = edition.sections.mapIndexed { i, s ->
            EditionSection(editionRow.id, s.id.ifBlank { "section-$i" }, s.title.ifBlank { s.id }, i)
        }
        val links = sections.flatMap { s ->
            val ids = edition.sections.find { it.id == s.sectionId }?.storyIds ?: emptyList()
            ids.mapIndexed { rank, storyId -> EditionStory(editionRow.id, s.sectionId, storyId, rank) }
        }
        val stories = edition.stories.map { it.toRow() }
        val sources = edition.stories.flatMap { s ->
            s.sources.map {
                StorySource(
                    storyId = s.id,
                    publisher = it.publisher,
                    headline = it.headline,
                    url = it.url,
                    publishedAt = it.publishedAt,
                    rightsMode = it.rightsMode,
                )
            }
        }
        val fts = edition.stories.map { it.toFts("en") }
        db.editionDao().upsertEdition(editionRow)
        db.editionDao().upsertSections(sections)
        db.editionDao().upsertLinks(links)
        db.storyDao().upsertStories(stories)
        db.storyDao().upsertSources(sources)
        db.storyDao().upsertFts(fts)
    }

    private fun StoryDto.toRow(): Story {
        val json = NetworkModule.feedJson
        return Story(
            id = id,
            headline = headline,
            dek = dek,
            body = body,
            excerpt = excerpt,
            category = category,
            publishedAt = publishedAt,
            updatedAt = updatedAt,
            generatedAt = generatedAt,
            aiGenerated = aiGenerated,
            aiModel = aiModel,
            breaking = breaking,
            version = version,
            imageJson = image?.let { json.encodeToString(it) },
            locationsJson = json.encodeToString(locations),
        )
    }

    private fun StoryDto.toFts(lang: String): StoryFts {
        val pubs = sources.joinToString(" ") { it.publisher }
        val locs = locations.joinToString(" ") {
            listOfNotNull(it.city, it.admin2, it.metro, it.admin1, it.country).joinToString(" ")
        }
        return StoryFts(
            storyId = id,
            headline = headline,
            dek = dek ?: excerpt ?: "",
            body = body ?: "",
            publisher = pubs,
            locations = locs,
            lang = lang,
        )
    }

    @Suppress("unused")
    fun indexForTest(dto: IndexDto): List<EditionResolution.IndexEntry> = dto.editions.map {
        EditionResolution.IndexEntry(it.path, it.location.country, it.location.admin1, it.location.admin2, it.location.city, it.location.metro)
    }
}
