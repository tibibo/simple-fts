package net.duborenko.fts.core

import net.duborenko.fts.FullTextSearchIndex
import net.duborenko.fts.Keyword
import net.duborenko.fts.SearchResult

/**
 * Primitive imitation of full-text search index.

 * @author Kiryl Dubarenka
 */
internal class InMemoryFullTextSearchIndex<in Id : Comparable<Id>, Doc : Any>(
        private val getId: (Doc) -> Id,
        private val textExtractor: (Doc) -> Map<String, String?>,
        private val wordFilter: (String) -> Boolean = { it.length > 3 },
        private val rank: ((SearchResult<Doc>) -> Int)? = null) : FullTextSearchIndex<Id, Doc> {

    private val keywords = hashMapOf<Id, Map<String, Set<Keyword>>>()
    private val index = hashMapOf<String, MutableMap<Id, Doc>>()

    override fun add(document: Doc) {
        val existingKeywords = keywords.getOrDefault(document.id, mapOf())
                .values
                .asSequence()
                .flatten()
                .map { it.word }
                .toSet()

        val newKeywordsMap = keywordsMap(textExtractor(document))
        val newKeywords = newKeywordsMap
                .values
                .asSequence()
                .flatten()
                .map { it.word }
                .toSet()

        // add new keyword
        (newKeywords - existingKeywords)
                .forEach { addToIndex(document, it) }

        // cleanup old keywords
        (existingKeywords - newKeywords)
                .forEach { removeFromIndex(document.id, it) }

        keywords.put(document.id, newKeywordsMap)
    }

    private fun keywordsMap(data: Map<String, String?>): Map<String, Set<Keyword>> {
        return data
                .asSequence()
                .filterNotNull()
                .map { (field, text) -> field to extractKeywords(text) }    // split into words
                .toMap()
    }

    private fun extractKeywords(text: String?): Set<Keyword> {
        text ?: return emptySet()
        return Regex("""((?!\.\s)[\w(\.)])+""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .filter { wordFilter(it.value) }
                .map { Keyword(it.value.toLowerCase(), it.range) }
                .toSet()
    }

    private fun removeFromIndex(id: Id, word: String) {
        val documents = index[word] ?: return
        documents -= id
        if (documents.isEmpty()) {
            index -= word
        }
    }

    private fun addToIndex(document: Doc, word: String) {
        index.computeIfAbsent(word) { hashMapOf() } += document.id to document
    }

    override fun remove(id: Id) {
        keywords.remove(id)
                ?.values
                ?.asSequence()
                ?.flatten()
                ?.map { it.word }
                ?.toSet()
                ?.forEach { removeFromIndex(id, it) }
    }

    override fun search(searchTerm: String): List<SearchResult<Doc>> {
        var searchResults = extractKeywords(searchTerm)
                .map { it.word }
                .asSequence()
                .map { index[it]?.values }
                .filterNotNull()
                .flatMap { it.asSequence() }
                .distinct()
                .map {
                    SearchResult(it, getMatches(keywords[it.id]!!, extractKeywords(searchTerm)
                            .map { it.word }.toSet()))
                }
        rank?.let {
            searchResults = searchResults.sortedByDescending(it)
        }
        return searchResults
                .toList()
    }

    private fun getMatches(allKeywords: Map<String, Set<Keyword>>, searchKeywords: Set<String>) =
            allKeywords
                    .asSequence()
                    .map { (field, kws) ->
                        field to kws
                                .filter { it.word in searchKeywords }
                                .toSet()
                    }
                    .toMap()

    private val Doc.id
        get() = getId(this)

}
