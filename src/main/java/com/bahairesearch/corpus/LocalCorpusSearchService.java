package com.bahairesearch.corpus;

import com.bahairesearch.ai.GeminiClient;
import com.bahairesearch.config.AppConfig;
import com.bahairesearch.model.QuoteResult;
import com.bahairesearch.model.ResearchReport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Executes local full-text search and maps verified local passages to report output.
 */
public final class LocalCorpusSearchService {

    private static final Logger LOGGER = Logger.getLogger(LocalCorpusSearchService.class.getName());

    private record HitsResult(List<CorpusSearchHit> hits, String effectiveQuery, boolean usedFallback) {}

    private static final Set<String> NOISE_TOKENS = Set.of(
        "by", "for", "with", "and", "the", "from", "about",
        "quotes", "quote", "please", "show", "find",
        "are", "but", "can", "had", "has", "its", "may", "not", "out", "was",
        "all", "any", "she", "who", "why", "yet", "you", "how", "let", "too", "now"
    );
    private static final Set<String> GENERIC_QUERY_TOKENS = Set.of(
        "book", "books", "most", "issue", "issues"
    );

    private static final int NEAR_DISTANCE = 15;

    /** Score multiplier applied to NEAR proximity hits so they rank above AND/OR FTS5 hits. */
    private static final double NEAR_BOOST_MULTIPLIER = 1000.0;

    /** Scores at or below this threshold are treated as phrase-LIKE matches (ranked by length). */
    private static final double PHRASE_SCORE_THRESHOLD = -99995.0;

    private LocalCorpusSearchService() {
    }

    /**
     * Search local corpus and return a report with local citations only.
     */
    public static ResearchReport search(String topic, AppConfig appConfig) {
        return search(topic, null, null, appConfig);
    }

    /**
     * Search local corpus with optional explicit author/title filters from UI dropdowns.
     *
     * @param explicitAuthor exact canonical author value (e.g. "Baha'u'llah"), or null for inference
     * @param explicitTitle  exact title string from dropdown, or null for no title filter
     */
    public static ResearchReport search(
        String topic,
        String explicitAuthor,
        String explicitTitle,
        AppConfig appConfig
    ) {
        try {
            CorpusPaths corpusPaths = CorpusPaths.fromConfig(appConfig);

            // Determine author early so FTS query can exclude author tokens
            boolean hasExplicitAuthor = explicitAuthor != null && !explicitAuthor.isBlank();
            String manualRequiredAuthor = hasExplicitAuthor ? explicitAuthor : inferRequiredAuthor(topic);

            String nearQuery = toFtsQueryNear(topic, manualRequiredAuthor);
            String ftsQuery = toFtsQuery(topic, manualRequiredAuthor);
            String orFtsQuery = toFtsQueryOr(topic, manualRequiredAuthor);
            if (ftsQuery.isBlank()) {
                return new ResearchReport(appConfig.noResultsText(), List.of());
            }

            List<String> knownWorkTitles = loadKnownWorkTitles(corpusPaths);
            GeminiClient geminiClient = new GeminiClient(appConfig);
            GeminiClient.LocalQueryIntent intent =
                geminiClient.resolveLocalQueryIntent(topic, knownWorkTitles, appConfig);

            int requestedQuotes = Math.max(1, appConfig.maxQuotes());
            int retrievalPoolSize = Math.max(requestedQuotes * 12, 60);

            // Author priority: explicit UI selection > manual topic inference only.
            // AI-inferred author is suppressed — it causes false restrictions when
            // "All Authors" is selected but the topic themes suggest one author.
            String requiredAuthor = manualRequiredAuthor;

            // When the user explicitly selected an author but left Title as "All Titles"
            // (explicitTitle == null), suppress AI work-title inference. The user's intent
            // is to search all of that author's works — the AI must not narrow it to one book
            // just because a search word happens to appear in a title (e.g. "unity" → Tabernacle of Unity).
            // Manual "from X" / "in book X" patterns in the query still work via inferRequestedBookTokens().
            String aiWorkTitle = (hasExplicitAuthor && explicitTitle == null) ? null : intent.workTitle();
            List<String> requestedBookTokens = mergeBookTokens(
                topic, requiredAuthor, explicitTitle, aiWorkTitle);

            if (!hasExplicitAuthor && manualRequiredAuthor == null && !requestedBookTokens.isEmpty()) {
                // When user scopes by a specific work title (often compilations with mixed-attribution content),
                // avoid over-constraining SQL by AI-inferred author.
                requiredAuthor = null;
            }
            List<String> conceptTerms = inferEffectiveConceptTerms(topic, requiredAuthor, intent.concepts());
            logIntentDebug(
                appConfig,
                topic,
                ftsQuery,
                intent,
                requiredAuthor,
                requestedBookTokens,
                conceptTerms
            );

            HitsResult hitsResult = findHits(
                corpusPaths,
                nearQuery,
                ftsQuery,
                orFtsQuery,
                retrievalPoolSize,
                requiredAuthor,
                explicitTitle,
                requestedBookTokens,
                appConfig
            );
            List<CorpusSearchHit> hits = hitsResult.hits();
            logCount(appConfig, "hits", hits.size());

            List<CorpusSearchHit> filtered = filterByRequestedAuthor(requiredAuthor, hits);
            List<CorpusSearchHit> bookScoped = filterByRequestedBook(filtered, requestedBookTokens);
            List<CorpusSearchHit> topical = filterByContentTerms(bookScoped, conceptTerms);

            // Phrase searches — always run, independent of AI/API key.
            // Phrase hits use score=-99999 so they sort before qualityBand ordering and are
            // never displaced by longer passages in rankForDisplay.
            // IMPORTANT: phrase hits are merged FIRST so their score survives deduplication.
            // If HW #5 (short passage) appears in both FTS results and phrase results,
            // mergeHits keeps the first-seen version — we want the phrase version (-99999),
            // not the FTS version (BM25 ~-5) which would be buried behind band-0 passages.
            // Only phrase-search when topic has 2+ significant words — a single word adds
            // no precision over FTS and just duplicates the same 144+ hits.
            List<String> topicFtsTokens = extractFtsTokens(topic, requiredAuthor);
            boolean nearFired = hitsResult.effectiveQuery().startsWith("NEAR(");
            String topicLikePattern = "%" + normalizeForMatch(topic).replace(" ", "%") + "%";
            List<CorpusSearchHit> combinedPhraseHits = new ArrayList<>();
            if (topicFtsTokens.size() >= 2 && !nearFired) {
                logCount(appConfig, "PhraseQuery topic LIKE: " + topicLikePattern + " →", 0);
                combinedPhraseHits.addAll(fetchPhraseHits(
                    corpusPaths, topic, retrievalPoolSize,
                    requiredAuthor, explicitTitle, requestedBookTokens));
                logCount(appConfig, "PhraseQuery topic hits", combinedPhraseHits.size());
            }
            if (intent.knownPhrase() != null && !intent.knownPhrase().isBlank() && !nearFired) {
                String aiLikePattern = "%" + normalizeForMatch(intent.knownPhrase()).replace(" ", "%") + "%";
                logCount(appConfig, "PhraseQuery AI LIKE: " + aiLikePattern + " →", 0);
                List<CorpusSearchHit> aiPhraseHits = fetchPhraseHits(
                    corpusPaths, intent.knownPhrase(), retrievalPoolSize,
                    requiredAuthor, explicitTitle, requestedBookTokens);
                logCount(appConfig, "PhraseQuery AI hits", aiPhraseHits.size());
                combinedPhraseHits = mergeHits(combinedPhraseHits, aiPhraseHits);
            }
            // Phrase hits first, then FTS hits fill in non-duplicates
            topical = mergeHits(combinedPhraseHits, topical);
            logCount(appConfig, "after phrase merge", topical.size());

            if (!requestedBookTokens.isEmpty() && topical.size() < requestedQuotes && !nearFired) {
                List<CorpusSearchHit> additionalBookScopedHits = findAdditionalBookScopedHits(
                    corpusPaths,
                    requiredAuthor,
                    explicitTitle,
                    requestedBookTokens,
                    conceptTerms,
                    Math.max(240, requestedQuotes * 50)
                );
                topical = mergeHits(topical, additionalBookScopedHits);
            }

            List<CorpusSearchHit> candidatePool =
                rankForDisplay(removeBoilerplateAndDuplicates(topical), requiredAuthor);
            logCount(appConfig, "candidatePool (main pipeline)", candidatePool.size());

            // Semantic fallback: triggered when the full pipeline yields no candidates.
            // Two scenarios this catches:
            //   (a) Raw-text FTS finds nothing — e.g. query mentions "Allie Beth Stuckey" which
            //       is never in scripture, OR modern words like "empathy" not in the corpus.
            //   (b) OR FTS found hits but they were all eliminated by content/book filters.
            // Retries using AI-extracted concepts ("love", "compassion") as FTS terms instead
            // of raw text, and uses AI-only conceptTerms so paragraph noise words can't filter out
            // relevant passages. Book tokens intentionally omitted — if AI guessed a wrong title
            // it should not carry forward into the fallback.
            if (candidatePool.isEmpty() && intent.concepts() != null && !intent.concepts().isEmpty()) {
                String conceptQuery = String.join(" ", intent.concepts());
                String conceptOrFtsQuery = toFtsQueryOr(conceptQuery, requiredAuthor);
                // Use the OR concept query directly — semantic fallback is last-resort mode.
                // AND concept queries (e.g. "love* AND empathy* AND unity*") find only 1-2 narrow
                // passages and miss the OR fallback inside findHits because hits.isEmpty() is false.
                // Those 1-2 passages are often boilerplate-filtered, leaving candidatePool empty.
                // Going straight to OR ("love* OR unity* OR compassion*") retrieves a broad pool
                // of the most BM25-relevant passages on these themes across the full corpus,
                // with room for the Gemini reranker to pick the best for the specific question.
                if (!conceptOrFtsQuery.isBlank() && !conceptOrFtsQuery.equals(orFtsQuery)) {
                    List<CorpusSearchHit> conceptHits = findHits(
                        corpusPaths, "", conceptOrFtsQuery, conceptOrFtsQuery,
                        retrievalPoolSize, requiredAuthor, explicitTitle,
                        List.of(), appConfig
                    ).hits();
                    logCount(appConfig, "semantic fallback conceptHits", conceptHits.size());
                    if (!conceptHits.isEmpty()) {
                        List<String> aiOnlyTerms = inferEffectiveConceptTerms("", requiredAuthor, intent.concepts());
                        List<CorpusSearchHit> conceptFiltered = filterByRequestedAuthor(requiredAuthor, conceptHits);
                        // Do NOT apply filterByContentTerms here — the concept FTS already ensures
                        // topical relevance. Exact-token filtering causes false negatives when FTS
                        // prefix (e.g. "love*") matched inflected forms ("beloved", "loveth") that
                        // fail exact-word comparison. The Gemini reranker handles final selection.
                        candidatePool = rankForDisplay(removeBoilerplateAndDuplicates(conceptFiltered), requiredAuthor);
                        logCount(appConfig, "candidatePool (semantic fallback)", candidatePool.size());
                        logIntentDebug(appConfig, topic,
                            "SEMANTIC-FALLBACK:" + conceptOrFtsQuery, intent,
                            requiredAuthor, List.of(), aiOnlyTerms);
                    }
                }
            }

            List<CorpusSearchHit> curated = pickFinalQuotesWithRerank(
                topic,
                candidatePool,
                requestedQuotes,
                geminiClient,
                appConfig
            );
            if (curated.isEmpty()) {
                return new ResearchReport(appConfig.noResultsText(), List.of());
            }

            List<QuoteResult> quotes = curated.stream()
                .map(hit -> new QuoteResult(
                    hit.quote(),
                    blankToFallback(hit.author(), "Unknown"),
                    blankToFallback(hit.title(), "Untitled"),
                    blankToFallback(hit.locator(), "Not specified"),
                    blankToFallback(hit.sourceUrl(), "N/A")
                ))
                .toList();

            String displayQuery = hitsResult.effectiveQuery()
                .replaceAll("NEAR\\(([^,]+),\\s*\\d+\\)", "$1")
                .replace("*", "")
                .replace(" AND ", " and ")
                .replace(" OR ", " or ");
            String summary;
            if (hitsResult.usedFallback()) {
                summary = "Local corpus returned " + quotes.size()
                    + " passage(s) — exact search found nothing; broadened to: " + displayQuery
                    + "  (Tip: try fewer, more specific keywords)"
                    + "\n<ctrl-a> highlight quote <ctrl-c> copy quote (Right Mouse)";
            } else {
                summary = "Local corpus returned " + quotes.size()
                    + " passage(s) — searched: " + displayQuery
                    + "\n<ctrl-a> highlight quote <ctrl-c> copy quote (Right Mouse also)";
            }
            return new ResearchReport(summary, quotes);
        } catch (IllegalStateException exception) {
            return new ResearchReport(appConfig.noResultsText(), List.of());
        }
    }

    private static List<String> loadKnownWorkTitles(CorpusPaths corpusPaths) {
        String sql = "SELECT DISTINCT title FROM documents WHERE title IS NOT NULL AND trim(title) <> ''";
        List<String> titles = new ArrayList<>();
        try (Connection connection = CorpusConnectionFactory.open(corpusPaths);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String title = trimToEmpty(resultSet.getString(1));
                if (!title.isBlank()) {
                    titles.add(title);
                }
            }
        } catch (SQLException exception) {
            // Non-fatal; intent resolver can still work without title hints.
        }
        return titles;
    }

    private static List<CorpusSearchHit> pickFinalQuotesWithRerank(
        String topic,
        List<CorpusSearchHit> candidatePool,
        int requestedQuotes,
        GeminiClient geminiClient,
        AppConfig appConfig
    ) {
        if (candidatePool.isEmpty()) {
            return List.of();
        }

        List<CorpusSearchHit> boundedPool = candidatePool.stream().limit(Math.max(20, requestedQuotes * 6)).toList();
        List<Integer> selectedIds =
            geminiClient.rerankLocalCandidates(topic, boundedPool, requestedQuotes, appConfig);

        if (selectedIds.isEmpty()) {
            return boundedPool.stream().limit(requestedQuotes).toList();
        }

        List<CorpusSearchHit> selected = new ArrayList<>();
        for (Integer id : selectedIds) {
            int index = id - 1;
            if (index >= 0 && index < boundedPool.size()) {
                selected.add(boundedPool.get(index));
            }
        }

        if (selected.isEmpty()) {
            return boundedPool.stream().limit(requestedQuotes).toList();
        }

        if (selected.size() < requestedQuotes) {
            Set<String> selectedKeys = new HashSet<>();
            for (CorpusSearchHit hit : selected) {
                selectedKeys.add(normalizeForMatch(hit.quote()) + "|" + normalizeForMatch(hit.sourceUrl()));
            }

            for (CorpusSearchHit hit : boundedPool) {
                if (selected.size() >= requestedQuotes) {
                    break;
                }
                String key = normalizeForMatch(hit.quote()) + "|" + normalizeForMatch(hit.sourceUrl());
                if (selectedKeys.add(key)) {
                    selected.add(hit);
                }
            }
        }

        return selected.stream().limit(requestedQuotes).toList();
    }

    private static List<CorpusSearchHit> removeBoilerplateAndDuplicates(List<CorpusSearchHit> hits) {
        List<CorpusSearchHit> curated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CorpusSearchHit hit : hits) {
            String reason = boilerplateReason(hit);
            if (reason != null) {
                String snippet = normalizeForMatch(hit.quote());
                String display = snippet.length() > 120 ? snippet.substring(0, 120) + "..." : snippet;
                LOGGER.info(() -> "[BoilerplateFilter] removed (" + reason + "): \"" + display + "\"");
                continue;
            }

            String key = normalizeForMatch(hit.quote());
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            curated.add(hit);
        }
        return curated;
    }

    private static List<CorpusSearchHit> rankForDisplay(List<CorpusSearchHit> hits, String requiredAuthor) {
        return hits.stream()
            .sorted((left, right) -> {
                // Phrase hits (score <= -99990) always sort first — explicit LIKE matches.
                boolean leftIsPhrase  = left.score()  <= -99990;
                boolean rightIsPhrase = right.score() <= -99990;
                if (leftIsPhrase != rightIsPhrase) {
                    return leftIsPhrase ? -1 : 1;
                }
                // Among multiple phrase hits, prefer shorter passages. A short passage where
                // the query covers most of the text is a more precise match than a long passage
                // that incidentally contains the query words in sequence.
                if (leftIsPhrase) {
                    int leftLen  = left.quote()  == null ? 0 : left.quote().length();
                    int rightLen = right.quote() == null ? 0 : right.quote().length();
                    return Integer.compare(leftLen, rightLen);
                }

                int leftSourcePriority = sourcePriority(left, requiredAuthor);
                int rightSourcePriority = sourcePriority(right, requiredAuthor);
                if (leftSourcePriority != rightSourcePriority) {
                    return Integer.compare(leftSourcePriority, rightSourcePriority);
                }

                return Double.compare(left.score(), right.score());
            })
            .toList();
    }

    private static int sourcePriority(CorpusSearchHit hit, String requiredAuthor) {
        String normalizedAuthor = normalizeForMatch(requiredAuthor);
        String normalizedTitle = normalizeForMatch(hit.title());
        String normalizedUrl = normalizeForMatch(hit.sourceUrl());

        if (!"universal house of justice".equals(normalizedAuthor)) {
            return 1;
        }

        if (normalizedUrl.contains("the universal house of justice messages")
            || normalizedUrl.contains("the universal house of justice muhj")
            || normalizedTitle.contains("messages from the universal house of justice")) {
            return 0;
        }

        if (normalizedUrl.contains("authoritative texts compilations")
            || normalizedUrl.contains("other literature")
            || normalizedUrl.contains("official statements commentaries")) {
            return 3;
        }

        return 1;
    }

    private static int qualityBand(String quote) {
        int length = quote == null ? 0 : quote.trim().length();
        if (length >= 200 && length <= 900) {
            return 0;
        }
        if (length >= 120 && length <= 1100) {
            return 1;
        }
        return 2;
    }

    /** Returns a short label identifying which boilerplate check matched, or null if not boilerplate. */
    private static String boilerplateReason(CorpusSearchHit hit) {
        // A passage over 15,000 characters is an entire book ingested as one chunk — not a usable quote.
        // Individual prayers, tablets, and letters are well under this limit even when lengthy.
        if (hit.quote().length() > 15_000) {
            return "too-long";
        }
        String quote = normalizeForMatch(hit.quote());
        if (quote.contains("bahai reference library")) {
            return "bahai-ref-lib";
        }
        if (quote.startsWith("a collection of") || quote.startsWith("a selection of")) {
            return "collection-header";
        }
        if (quote.contains("can be found here")) {
            return "found-here";
        }
        if (quote.contains("downloads about downloads")
            || quote.contains("all downloads in authoritative writings and guidance")
            || quote.contains("copyright and terms of use")
            || quote.contains("read online")
            || quote.contains("bahai org home")
            || quote.contains("search the bahai reference library")) {
            return "nav-element";
        }
        if (quote.contains("see also")) {
            return "see-also";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // SQL query helpers — build SQL string based on active filters
    // -------------------------------------------------------------------------

    private static String buildHitsSql(boolean authorScoped, boolean titleScoped) {
        String authorClause = authorScoped ? "  AND lower(d.author) = lower(?)\n" : "";
        String titleClause  = titleScoped  ? "  AND lower(d.title)  = lower(?)\n" : "";
        return """
            SELECT
                p.text_content,
                d.author,
                d.title,
                p.locator,
                d.canonical_url,
                bm25(passages_fts) AS score
            FROM passages_fts
            JOIN passages p ON p.passage_id = passages_fts.rowid
            JOIN documents d ON d.doc_id = p.doc_id
            WHERE passages_fts MATCH ?
            """ + authorClause + titleClause + """
            ORDER BY score
            LIMIT ?
            """;
    }

    private static String buildPhraseSql(boolean authorScoped, boolean titleScoped) {
        String authorClause = authorScoped ? "  AND lower(d.author) = lower(?)\n" : "";
        String titleClause  = titleScoped  ? "  AND lower(d.title)  = lower(?)\n" : "";
        return """
            SELECT
                p.text_content,
                d.author,
                d.title,
                p.locator,
                d.canonical_url,
                -99999.0 AS score
            FROM passages p
            JOIN documents d ON d.doc_id = p.doc_id
            WHERE lower(p.text_content) LIKE ?
            """ + authorClause + titleClause + """
            LIMIT ?
            """;
    }

    private static String buildBookScopedSql(boolean authorScoped, boolean titleScoped) {
        String authorClause = authorScoped ? "  AND lower(d.author) = lower(?)\n" : "";
        String titleClause  = titleScoped  ? "  AND lower(d.title)  = lower(?)\n" : "";
        return "SELECT\n"
            + "    p.text_content,\n"
            + "    d.author,\n"
            + "    d.title,\n"
            + "    p.locator,\n"
            + "    d.canonical_url,\n"
            + "    -99998.0 AS score\n"
            + "FROM passages p\n"
            + "JOIN documents d ON d.doc_id = p.doc_id\n"
            + "WHERE 1=1\n"
            + authorClause + titleClause
            + "LIMIT ?\n";
    }

    // -------------------------------------------------------------------------
    // Core search — findHits with NEAR/AND/OR fallback
    // -------------------------------------------------------------------------

    private static HitsResult findHits(
        CorpusPaths corpusPaths,
        String nearQuery,
        String ftsQuery,
        String orFtsQuery,
        int limit,
        String requiredAuthor,
        String explicitTitle,
        List<String> requestedBookTokens,
        AppConfig appConfig
    ) {
        boolean authorScoped = requiredAuthor != null && !requiredAuthor.isBlank();
        boolean titleScoped  = explicitTitle  != null && !explicitTitle.isBlank();
        String sql = buildHitsSql(authorScoped, titleScoped);

        SQLException lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection connection = CorpusConnectionFactory.open(corpusPaths)) {
                List<CorpusSearchHit> hits = List.of();
                boolean usedOrFallback = false;
                String effectiveQuery = ftsQuery;
                boolean nearAttempted = false;

                if (nearQuery != null && !nearQuery.isBlank()) {
                    nearAttempted = true;

                    // 1) Run NEAR query (proximity — exacting)
                    logCount(appConfig, "FtsQuery NEAR: " + nearQuery + " →", 0);
                    List<CorpusSearchHit> nearHits = executeHitsQuery(connection, sql, nearQuery,
                        authorScoped, requiredAuthor, titleScoped, explicitTitle, limit);
                    logCount(appConfig, "NEAR hits", nearHits.size());

                    // 2) Run AND query to supplement — NEAR can be thin
                    logCount(appConfig, "FtsQuery AND (supplement): " + ftsQuery + " →", 0);
                    List<CorpusSearchHit> andHits = executeHitsQuery(connection, sql, ftsQuery,
                        authorScoped, requiredAuthor, titleScoped, explicitTitle, limit);
                    logCount(appConfig, "AND supplement hits", andHits.size());

                    // 3) Boost NEAR scores so proximity matches rank above AND hits
                    if (!nearHits.isEmpty()) {
                        nearHits = applyNearBoost(nearHits);
                    }

                    // 4) Merge: NEAR first (boosted), then AND (deduplicated)
                    hits = mergeHits(nearHits, andHits);

                    if (!nearHits.isEmpty()) {
                        effectiveQuery = nearQuery;
                    } else if (!andHits.isEmpty()) {
                        effectiveQuery = ftsQuery;
                    }
                }

                if (!nearAttempted) {
                    logCount(appConfig, "FtsQuery AND: " + ftsQuery + " →", 0);
                    hits = executeHitsQuery(connection, sql, ftsQuery,
                        authorScoped, requiredAuthor, titleScoped, explicitTitle, limit);
                    logCount(appConfig, "AND hits", hits.size());

                    if (hits.isEmpty() && !orFtsQuery.isBlank() && !orFtsQuery.equals(ftsQuery)) {
                        logCount(appConfig, "FtsQuery OR: " + orFtsQuery + " →", 0);
                        hits = executeHitsQuery(connection, sql, orFtsQuery,
                            authorScoped, requiredAuthor, titleScoped, explicitTitle, limit);
                        logCount(appConfig, "OR hits", hits.size());
                        usedOrFallback = true;
                        effectiveQuery = orFtsQuery;
                    }
                }

                // Note: book filter not applied here — it runs in search() after author/content filtering.
                // Keeping it here would be redundant and would double-filter.

                List<CorpusSearchHit> limited = hits.stream()
                    .limit(Math.max(1, limit))
                    .toList();
                return new HitsResult(limited, effectiveQuery, usedOrFallback);
            } catch (SQLException exception) {
                lastException = exception;
                if (!isBusyLock(exception) || attempt == 3) {
                    break;
                }

                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Local corpus query was interrupted.", interruptedException);
                }
            }
        }

        throw new IllegalStateException("Local corpus query failed.", lastException);
    }

    /** Multiplies each hit's BM25 score so NEAR proximity results rank above AND/OR hits. */
    private static List<CorpusSearchHit> applyNearBoost(List<CorpusSearchHit> hits) {
        return hits.stream()
            .map(hit -> new CorpusSearchHit(
                hit.quote(), hit.author(), hit.title(),
                hit.locator(), hit.sourceUrl(),
                hit.score() * NEAR_BOOST_MULTIPLIER))
            .toList();
    }

    private static List<CorpusSearchHit> executeHitsQuery(
        Connection connection,
        String sql,
        String ftsQuery,
        boolean authorScoped,
        String requiredAuthor,
        boolean titleScoped,
        String explicitTitle,
        int limit
    ) throws SQLException {
        List<CorpusSearchHit> hits = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int p = 1;
            statement.setString(p++, ftsQuery);
            if (authorScoped) {
                statement.setString(p++, requiredAuthor);
            }
            if (titleScoped) {
                statement.setString(p++, explicitTitle);
            }
            statement.setInt(p, Math.max(1, limit));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    hits.add(new CorpusSearchHit(
                        trimToEmpty(resultSet.getString(1)),
                        trimToEmpty(resultSet.getString(2)),
                        trimToEmpty(resultSet.getString(3)),
                        trimToEmpty(resultSet.getString(4)),
                        trimToEmpty(resultSet.getString(5)),
                        resultSet.getDouble(6)
                    ));
                }
            }
        }
        return hits;
    }

    private static List<CorpusSearchHit> fetchPhraseHits(
        CorpusPaths corpusPaths,
        String knownPhrase,
        int limit,
        String requiredAuthor,
        String explicitTitle,
        List<String> requestedBookTokens
    ) {
        try (Connection connection = CorpusConnectionFactory.open(corpusPaths)) {
            return findKnownPhraseHits(connection, knownPhrase, limit,
                requiredAuthor, explicitTitle, requestedBookTokens);
        } catch (SQLException exception) {
            return List.of();
        }
    }

    private static List<CorpusSearchHit> findKnownPhraseHits(
        Connection connection,
        String knownPhrase,
        int limit,
        String requiredAuthor,
        String explicitTitle,
        List<String> requestedBookTokens
    ) throws SQLException {
        boolean authorScoped = requiredAuthor != null && !requiredAuthor.isBlank();
        boolean titleScoped  = explicitTitle  != null && !explicitTitle.isBlank();
        String sql = buildPhraseSql(authorScoped, titleScoped);

        List<CorpusSearchHit> hits = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int p = 1;
            statement.setString(p++, "%" + normalizeForMatch(knownPhrase).replace(" ", "%") + "%");
            if (authorScoped) {
                statement.setString(p++, requiredAuthor);
            }
            if (titleScoped) {
                statement.setString(p++, explicitTitle);
            }
            statement.setInt(p, Math.max(1, limit));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    hits.add(new CorpusSearchHit(
                        trimToEmpty(resultSet.getString(1)),
                        trimToEmpty(resultSet.getString(2)),
                        trimToEmpty(resultSet.getString(3)),
                        trimToEmpty(resultSet.getString(4)),
                        trimToEmpty(resultSet.getString(5)),
                        resultSet.getDouble(6)
                    ));
                }
            }
        }

        if (!requestedBookTokens.isEmpty()) {
            return filterByRequestedBook(hits, requestedBookTokens);
        }
        return hits;
    }

    private static List<CorpusSearchHit> findAdditionalBookScopedHits(
        CorpusPaths corpusPaths,
        String requiredAuthor,
        String explicitTitle,
        List<String> requestedBookTokens,
        List<String> contentTerms,
        int limit
    ) {
        boolean authorScoped = requiredAuthor != null && !requiredAuthor.isBlank();
        boolean titleScoped  = explicitTitle  != null && !explicitTitle.isBlank();
        String sql = buildBookScopedSql(authorScoped, titleScoped);

        List<CorpusSearchHit> hits = new ArrayList<>();
        try (Connection connection = CorpusConnectionFactory.open(corpusPaths);
             PreparedStatement statement = connection.prepareStatement(sql)) {

            int p = 1;
            if (authorScoped) {
                statement.setString(p++, requiredAuthor);
            }
            if (titleScoped) {
                statement.setString(p++, explicitTitle);
            }
            statement.setInt(p, Math.max(1, limit));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    CorpusSearchHit hit = new CorpusSearchHit(
                        trimToEmpty(resultSet.getString(1)),
                        trimToEmpty(resultSet.getString(2)),
                        trimToEmpty(resultSet.getString(3)),
                        trimToEmpty(resultSet.getString(4)),
                        trimToEmpty(resultSet.getString(5)),
                        resultSet.getDouble(6)
                    );

                    if (countBookTokenMatches(hit, requestedBookTokens) == 0) {
                        continue;
                    }
                    if (!contentTerms.isEmpty() && !containsAnyContentTerm(hit.quote(), contentTerms)) {
                        continue;
                    }
                    hits.add(hit);
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }

        return hits;
    }

    private static List<CorpusSearchHit> mergeHits(List<CorpusSearchHit> primary, List<CorpusSearchHit> secondary) {
        List<CorpusSearchHit> merged = new ArrayList<>(primary);
        Set<String> seen = new HashSet<>();
        for (CorpusSearchHit hit : primary) {
            seen.add(normalizeForMatch(hit.quote()) + "|" + normalizeForMatch(hit.sourceUrl()));
        }

        for (CorpusSearchHit hit : secondary) {
            String key = normalizeForMatch(hit.quote()) + "|" + normalizeForMatch(hit.sourceUrl());
            if (seen.add(key)) {
                merged.add(hit);
            }
        }
        return merged;
    }

    private static boolean isBusyLock(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("database is locked");
    }

    // -------------------------------------------------------------------------
    // Post-retrieval filters
    // -------------------------------------------------------------------------

    private static List<CorpusSearchHit> filterByRequestedAuthor(String requiredAuthor, List<CorpusSearchHit> hits) {
        if (requiredAuthor == null || requiredAuthor.isBlank()) {
            return hits;
        }
        String normalizedRequired = normalizeForMatch(requiredAuthor);
        return hits.stream()
            .filter(hit -> normalizeForMatch(hit.author()).equals(normalizedRequired))
            .toList();
    }

    private static List<CorpusSearchHit> filterByContentTerms(List<CorpusSearchHit> hits, List<String> contentTerms) {
        if (contentTerms.isEmpty()) {
            return hits;
        }
        return hits.stream()
            .filter(hit -> containsAnyContentTerm(hit.quote(), contentTerms))
            .toList();
    }

    private static List<CorpusSearchHit> filterByRequestedBook(List<CorpusSearchHit> hits, List<String> requestedBookTokens) {
        if (requestedBookTokens.isEmpty()) {
            return hits;
        }

        int requiredMatches = requestedBookTokens.size() <= 2 ? requestedBookTokens.size() : 2;
        return hits.stream()
            .filter(hit -> countBookTokenMatches(hit, requestedBookTokens) >= requiredMatches)
            .toList();
    }

    private static int countBookTokenMatches(CorpusSearchHit hit, List<String> requestedBookTokens) {
        String normalizedTitle = normalizeForMatch(hit.title());
        String normalizedUrl = normalizeForMatch(hit.sourceUrl());
        int matches = 0;
        for (String token : requestedBookTokens) {
            if (normalizedTitle.contains(token) || normalizedUrl.contains(token)) {
                matches++;
            }
        }
        return matches;
    }

    private static boolean containsAnyContentTerm(String quote, List<String> contentTerms) {
        String normalizedQuote = normalizeForMatch(quote);
        Set<String> quoteTokens = new HashSet<>();
        for (String token : normalizedQuote.split("\\s+")) {
            if (!token.isBlank()) {
                quoteTokens.add(token);
            }
        }
        for (String term : contentTerms) {
            if (quoteTokens.contains(term)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Term and concept inference
    // -------------------------------------------------------------------------

    private static List<String> extractContentTerms(String topic, String requiredAuthor) {
        String normalizedTopic = normalizeForMatch(topic);
        if (normalizedTopic.isBlank()) {
            return List.of();
        }

        Set<String> authorTerms = new HashSet<>();
        if (requiredAuthor != null && !requiredAuthor.isBlank()) {
            for (String token : normalizeForMatch(requiredAuthor).split("\\s+")) {
                if (!token.isBlank()) {
                    authorTerms.add(token);
                }
            }
        }

        List<String> terms = new ArrayList<>();
        for (String token : normalizedTopic.split("\\s+")) {
            if (token.length() < 3) {
                continue;
            }
            if (NOISE_TOKENS.contains(token) || GENERIC_QUERY_TOKENS.contains(token) || authorTerms.contains(token)) {
                continue;
            }
            terms.add(token);
        }
        return terms;
    }

    private static List<String> inferRequestedBookTokens(String topic, String requiredAuthor) {
        String normalizedTopic = normalizeForMatch(topic);
        if (normalizedTopic.isBlank()) {
            return List.of();
        }

        String segment = "";
        int inBookIndex = normalizedTopic.indexOf(" in book ");
        if (inBookIndex >= 0) {
            segment = normalizedTopic.substring(inBookIndex + " in book ".length());
        } else {
            int fromIndex = normalizedTopic.indexOf(" from ");
            if (fromIndex >= 0) {
                segment = normalizedTopic.substring(fromIndex + " from ".length());
            }
        }

        if (segment.isBlank()) {
            return List.of();
        }

        if (startsWithAuthorAlias(segment)) {
            return List.of();
        }

        int byIndex = segment.indexOf(" by ");
        if (byIndex >= 0) {
            segment = segment.substring(0, byIndex);
        }

        Set<String> authorTerms = new HashSet<>();
        if (requiredAuthor != null && !requiredAuthor.isBlank()) {
            for (String token : normalizeForMatch(requiredAuthor).split("\\s+")) {
                if (!token.isBlank()) {
                    authorTerms.add(token);
                }
            }
        }

        List<String> bookTokens = new ArrayList<>();
        for (String token : segment.split("\\s+")) {
            if (token.length() < 3) {
                continue;
            }
            if (NOISE_TOKENS.contains(token) || GENERIC_QUERY_TOKENS.contains(token) || authorTerms.contains(token)) {
                continue;
            }
            bookTokens.add(token);
        }

        return bookTokens;
    }

    private static boolean startsWithAuthorAlias(String segment) {
        String normalized = normalizeForMatch(segment);
        return normalized.startsWith("uhj")
            || normalized.startsWith("universal house of justice")
            || normalized.startsWith("house of justice")
            || normalized.startsWith("shoghi effendi")
            || normalized.startsWith("bahaullah")
            || normalized.startsWith("baha u llah")
            || normalized.startsWith("abdul baha")
            || normalized.startsWith("abdu l baha");
    }

    private static List<String> inferEffectiveBookTokens(String topic, String requiredAuthor, String aiWorkTitle) {
        List<String> manualTokens = inferRequestedBookTokens(topic, requiredAuthor);
        if (manualTokens.isEmpty()) {
            // No explicit book reference detected in the query ("from X" / "in book X" pattern).
            // Suppress AI work-title inference entirely — it causes false book restrictions:
            //   "unity"   → AI guesses "Tabernacle of Unity"  → filters out all other books
            //   "empathy" → AI guesses "Paris Talks"          → filters out all other books
            // Precise book selection belongs in the Title dropdown (Phase 2 UI).
            return List.of();
        }
        if (aiWorkTitle == null || aiWorkTitle.isBlank()) {
            return manualTokens;
        }
        // Manual book reference found — supplement with AI title tokens for completeness
        Set<String> merged = new LinkedHashSet<>(manualTokens);
        for (String token : normalizeForMatch(aiWorkTitle).split("\\s+")) {
            if (token.length() >= 3 && !NOISE_TOKENS.contains(token) && !GENERIC_QUERY_TOKENS.contains(token)) {
                merged.add(token);
            }
        }
        return new ArrayList<>(merged);
    }

    /**
     * Merge book tokens from: manual topic parsing + explicit title dropdown + AI work title inference.
     */
    private static List<String> mergeBookTokens(
        String topic,
        String requiredAuthor,
        String explicitTitle,
        String aiWorkTitle
    ) {
        List<String> base = inferEffectiveBookTokens(topic, requiredAuthor, aiWorkTitle);

        if (explicitTitle == null || explicitTitle.isBlank()) {
            return base;
        }

        // Add tokens from the explicitly selected title
        Set<String> merged = new LinkedHashSet<>(base);
        for (String token : normalizeForMatch(explicitTitle).split("\\s+")) {
            if (token.length() >= 3 && !NOISE_TOKENS.contains(token) && !GENERIC_QUERY_TOKENS.contains(token)) {
                merged.add(token);
            }
        }
        return new ArrayList<>(merged);
    }

    private static List<String> inferEffectiveConceptTerms(String topic, String requiredAuthor, List<String> aiConcepts) {
        List<String> terms = new ArrayList<>(extractContentTerms(topic, requiredAuthor));
        if (aiConcepts != null) {
            for (String concept : aiConcepts) {
                for (String token : normalizeForMatch(concept).split("\\s+")) {
                    if (token.length() >= 4
                        && !NOISE_TOKENS.contains(token)
                        && !GENERIC_QUERY_TOKENS.contains(token)
                        && !terms.contains(token)) {
                        terms.add(token);
                    }
                }
            }
        }
        return terms;
    }

    // -------------------------------------------------------------------------
    // Author resolution — returns exact canonical DB values
    // -------------------------------------------------------------------------

    /**
     * Infer required author from the raw topic text.
     * Returns exact values as stored in the DB from manifest.csv (e.g. "Baha'u'llah", not "bah").
     */
    private static String inferRequiredAuthor(String topic) {
        String normalized = normalizeForMatch(topic);
        // Pad so word-boundary checks work for single tokens at start/end
        String padded = " " + normalized + " ";

        if (padded.contains(" universal house of justice ")
                || padded.contains(" house of justice ")
                || padded.contains(" uhj ")) {
            return "Universal House of Justice";
        }
        // Check Baha'u'llah before "bab" to avoid false partial matches
        if (padded.contains(" baha u llah ") || padded.contains(" bahaullah ")) {
            return "Baha'u'llah";
        }
        if (padded.contains(" abdu l baha ") || padded.contains(" abdu baha ")) {
            return "'Abdu'l-Baha";
        }
        if (padded.contains(" shoghi effendi ")) {
            return "Shoghi Effendi";
        }
        // "bab" checked last — whole-word only via padded boundaries
        if (padded.contains(" bab ")) {
            return "Bab";
        }
        return null;
    }

    /**
     * Determine effective author from topic + AI-inferred value.
     * Returns exact canonical DB values (same as manifest.csv author column).
     */
    private static String inferEffectiveAuthor(String topic, String aiAuthor) {
        String manual = inferRequiredAuthor(topic);
        if (manual != null) {
            return manual;
        }
        if (aiAuthor == null || aiAuthor.isBlank()) {
            return null;
        }
        String normalized = normalizeForMatch(aiAuthor);
        String padded = " " + normalized + " ";

        if (padded.contains(" baha u llah ") || padded.contains(" bahaullah ")) {
            return "Baha'u'llah";
        }
        if (padded.contains(" universal house of justice ")
                || padded.contains(" house of justice ")
                || normalized.equals("uhj")) {
            return "Universal House of Justice";
        }
        if (padded.contains(" abdu l baha ") || padded.contains(" abdu baha ")
                || padded.contains(" abdu ")) {
            return "'Abdu'l-Baha";
        }
        if (padded.contains(" shoghi effendi ")) {
            return "Shoghi Effendi";
        }
        if (padded.contains(" bab ") || normalized.equals("bab") || normalized.equals("the bab")) {
            return "Bab";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // FTS query building — AND logic with OR fallback
    // -------------------------------------------------------------------------

    /**
     * Build an AND-based FTS query for precision.
     * Author tokens from the resolved author are excluded (they belong in SQL WHERE, not FTS).
     * For 1–3 terms: all required (AND).
     * For 4+ terms: first 3 required, rest optional (hybrid AND/OR).
     */
    private static String toFtsQuery(String topic, String resolvedAuthor) {
        List<String> uniqueTokens = extractFtsTokens(topic, resolvedAuthor);
        if (uniqueTokens.isEmpty()) {
            return "";
        }
        return buildAndQuery(uniqueTokens);
    }

    /**
     * Build an OR-based FTS fallback query (used when AND query returns no results).
     */
    private static String toFtsQueryOr(String topic, String resolvedAuthor) {
        List<String> uniqueTokens = extractFtsTokens(topic, resolvedAuthor);
        if (uniqueTokens.isEmpty()) {
            return "";
        }
        return String.join(" OR ", uniqueTokens);
    }

    /**
     * Build a NEAR proximity query — for 2–3 token searches.
     * Returns "NEAR(token1 token2, 15)" for 2-token queries.
     * For 3 tokens, appends a standalone OR token so three-word phrases match broadly.
     * When NEAR fires and finds results, phrase/LIKE search is skipped to preserve
     * the tight proximity constraint (LIKE would re-add passages where the terms appear far apart).
     */
    private static String toFtsQueryNear(String topic, String resolvedAuthor) {
        List<String> tokens = extractFtsTokens(topic, resolvedAuthor);
        int tokenCount = tokens.size();
        if (tokenCount < 2 || tokenCount > 3) {
            return "";
        }
        if (tokenCount == 3) {
            return "NEAR(" + tokens.get(0) + " " + tokens.get(1) + ", " + NEAR_DISTANCE + ") OR " + tokens.get(2);
        }
        return "NEAR(" + tokens.get(0) + " " + tokens.get(1) + ", " + NEAR_DISTANCE + ")";
    }

    private static List<String> extractFtsTokens(String topic, String resolvedAuthor) {
        if (topic == null) {
            return List.of();
        }
        Set<String> authorTokens = buildAuthorTokenSet(resolvedAuthor);

        List<String> tokens = new ArrayList<>();
        for (String token : topic.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            String trimmed = token.trim();
            if (trimmed.length() >= 3
                    && !NOISE_TOKENS.contains(trimmed)
                    && !authorTokens.contains(trimmed)) {
                tokens.add(trimmed + "*");
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(tokens));
    }

    private static String buildAndQuery(List<String> uniqueTokens) {
        if (uniqueTokens.size() <= 3) {
            return String.join(" AND ", uniqueTokens);
        }
        List<String> required = uniqueTokens.subList(0, 3);
        List<String> optional = uniqueTokens.subList(3, uniqueTokens.size());
        return String.join(" AND ", required) + " AND (" + String.join(" OR ", optional) + ")";
    }

    /**
     * Build the set of normalized tokens belonging to the resolved author name.
     * These are excluded from the FTS query since author filtering is done via SQL WHERE.
     */
    private static Set<String> buildAuthorTokenSet(String resolvedAuthor) {
        if (resolvedAuthor == null || resolvedAuthor.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        for (String token : normalizeForMatch(resolvedAuthor).split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // -------------------------------------------------------------------------
    // Normalization utilities
    // -------------------------------------------------------------------------

    private static String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }

        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return decomposed
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void logCount(AppConfig appConfig, String label, int count) {
        if (appConfig.debugIntent()) {
            LOGGER.info(() -> "[PipelineCount] " + label + "=" + count);
        }
    }

    private static void logIntentDebug(
        AppConfig appConfig,
        String topic,
        String ftsQuery,
        GeminiClient.LocalQueryIntent intent,
        String requiredAuthor,
        List<String> requestedBookTokens,
        List<String> conceptTerms
    ) {
        if (!appConfig.debugIntent()) {
            return;
        }

        LOGGER.info(() -> "[IntentDebug] topic=\"" + topic + "\""
            + ", ftsQuery=\"" + ftsQuery + "\""
            + ", aiAuthor=\"" + trimToEmpty(intent.author()) + "\""
            + ", aiWorkTitle=\"" + trimToEmpty(intent.workTitle()) + "\""
            + ", aiKnownPhrase=\"" + trimToEmpty(intent.knownPhrase()) + "\""
            + ", aiConcepts=" + (intent.concepts() == null ? List.of() : intent.concepts())
            + ", requiredAuthor=\"" + trimToEmpty(requiredAuthor) + "\""
            + ", requestedBookTokens=" + requestedBookTokens
            + ", conceptTerms=" + conceptTerms);
    }
}
