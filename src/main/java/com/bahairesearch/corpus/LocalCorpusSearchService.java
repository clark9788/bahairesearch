package com.bahairesearch.corpus;

import com.bahairesearch.ai.GeminiClient;
import com.bahairesearch.common.model.CorpusSearchHit;
import com.bahairesearch.common.search.SearchCore;
import com.bahairesearch.config.AppConfig;
import com.bahairesearch.common.model.QuoteResult;
import com.bahairesearch.common.model.ResearchReport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Executes local full-text search and maps verified local passages to report output.
 *
 * <p>Search-building and post-retrieval filtering logic is delegated to {@link SearchCore}
 * to eliminate duplication with the Android version of BahaiResearch.</p>
 */
public final class LocalCorpusSearchService {

    private static final Logger LOGGER = Logger.getLogger(LocalCorpusSearchService.class.getName());

    private record HitsResult(List<CorpusSearchHit> hits, String effectiveQuery, boolean usedFallback) {}

    private LocalCorpusSearchService() {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

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

            String nearQuery = SearchCore.toFtsQueryNear(topic, manualRequiredAuthor);
            String ftsQuery = SearchCore.toFtsQuery(topic, manualRequiredAuthor);
            String orFtsQuery = SearchCore.toFtsQueryOr(topic, manualRequiredAuthor);
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

            List<CorpusSearchHit> filtered = SearchCore.filterByRequestedAuthor(requiredAuthor, hits);
            List<CorpusSearchHit> bookScoped = SearchCore.filterByRequestedBook(filtered, requestedBookTokens);
            List<CorpusSearchHit> topical = SearchCore.filterByContentTerms(bookScoped, conceptTerms);

            // Phrase searches — always run, independent of AI/API key.
            List<String> topicFtsTokens = SearchCore.extractFtsTokens(topic, requiredAuthor);
            boolean nearFired = hitsResult.effectiveQuery().startsWith("NEAR(");
            String topicLikePattern = "%" + SearchCore.normalizeForMatch(topic).replace(" ", "%") + "%";
            List<CorpusSearchHit> combinedPhraseHits = new ArrayList<>();
            if (topicFtsTokens.size() >= 2 && !nearFired) {
                logCount(appConfig, "PhraseQuery topic LIKE: " + topicLikePattern + " →", 0);
                combinedPhraseHits.addAll(fetchPhraseHits(
                    corpusPaths, topic, retrievalPoolSize,
                    requiredAuthor, explicitTitle, requestedBookTokens));
                logCount(appConfig, "PhraseQuery topic hits", combinedPhraseHits.size());
            }
            if (intent.knownPhrase() != null && !intent.knownPhrase().isBlank() && !nearFired) {
                String aiLikePattern = "%" + SearchCore.normalizeForMatch(intent.knownPhrase()).replace(" ", "%") + "%";
                logCount(appConfig, "PhraseQuery AI LIKE: " + aiLikePattern + " →", 0);
                List<CorpusSearchHit> aiPhraseHits = fetchPhraseHits(
                    corpusPaths, intent.knownPhrase(), retrievalPoolSize,
                    requiredAuthor, explicitTitle, requestedBookTokens);
                logCount(appConfig, "PhraseQuery AI hits", aiPhraseHits.size());
                combinedPhraseHits = SearchCore.mergeHits(combinedPhraseHits, aiPhraseHits);
            }
            // Phrase hits first, then FTS hits fill in non-duplicates
            topical = SearchCore.mergeHits(combinedPhraseHits, topical);
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
                topical = SearchCore.mergeHits(topical, additionalBookScopedHits);
            }

            List<CorpusSearchHit> candidatePool =
                SearchCore.rankForDisplay(SearchCore.removeBoilerplateAndDuplicates(topical));
            logCount(appConfig, "candidatePool (main pipeline)", candidatePool.size());

            // Semantic fallback: triggered when the full pipeline yields no candidates.
            if (candidatePool.isEmpty() && intent.concepts() != null && !intent.concepts().isEmpty()) {
                String conceptQuery = String.join(" ", intent.concepts());
                String conceptOrFtsQuery = SearchCore.toFtsQueryOr(conceptQuery, requiredAuthor);
                if (!conceptOrFtsQuery.isBlank() && !conceptOrFtsQuery.equals(orFtsQuery)) {
                    List<CorpusSearchHit> conceptHits = findHits(
                        corpusPaths, "", conceptOrFtsQuery, conceptOrFtsQuery,
                        retrievalPoolSize, requiredAuthor, explicitTitle,
                        List.of(), appConfig
                    ).hits();
                    logCount(appConfig, "semantic fallback conceptHits", conceptHits.size());
                    if (!conceptHits.isEmpty()) {
                        List<String> aiOnlyTerms = inferEffectiveConceptTerms("", requiredAuthor, intent.concepts());
                        List<CorpusSearchHit> conceptFiltered = SearchCore.filterByRequestedAuthor(requiredAuthor, conceptHits);
                        candidatePool = SearchCore.rankForDisplay(SearchCore.removeBoilerplateAndDuplicates(conceptFiltered));
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
                    SearchCore.blankToFallback(hit.author(), "Unknown"),
                    SearchCore.blankToFallback(hit.title(), "Untitled"),
                    SearchCore.blankToFallback(hit.locator(), "Not specified"),
                    SearchCore.blankToFallback(hit.sourceUrl(), "N/A")
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
                        nearHits = SearchCore.applyNearBoost(nearHits);
                    }

                    // 4) Merge: NEAR first (boosted), then AND (deduplicated)
                    hits = SearchCore.mergeHits(nearHits, andHits);

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

    // -------------------------------------------------------------------------
    // Phrase search
    // -------------------------------------------------------------------------

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
            statement.setString(p++, "%" + SearchCore.normalizeForMatch(knownPhrase).replace(" ", "%") + "%");
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
            return SearchCore.filterByRequestedBook(hits, requestedBookTokens);
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

                    if (SearchCore.countBookTokenMatches(hit, requestedBookTokens) == 0) {
                        continue;
                    }
                    if (!contentTerms.isEmpty() && !SearchCore.containsAnyContentTerm(hit.quote(), contentTerms)) {
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

    private static boolean isBusyLock(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("database is locked");
    }

    // -------------------------------------------------------------------------
    // Term and concept inference — local-only logic
    // -------------------------------------------------------------------------

    private static List<String> inferEffectiveConceptTerms(String topic, String requiredAuthor, List<String> aiConcepts) {
        List<String> terms = new ArrayList<>(SearchCore.extractContentTerms(topic, requiredAuthor));
        if (aiConcepts != null) {
            for (String concept : aiConcepts) {
                for (String token : SearchCore.normalizeForMatch(concept).split("\\s+")) {
                    if (token.length() >= 4
                        && !SearchCore.NOISE_TOKENS.contains(token)
                        && !SearchCore.GENERIC_QUERY_TOKENS.contains(token)
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

    private static String inferRequiredAuthor(String topic) {
        String normalized = SearchCore.normalizeForMatch(topic);
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
    @SuppressWarnings("unused")
    private static String inferEffectiveAuthor(String topic, String aiAuthor) {
        String manual = inferRequiredAuthor(topic);
        if (manual != null) {
            return manual;
        }
        if (aiAuthor == null || aiAuthor.isBlank()) {
            return null;
        }
        String normalized = SearchCore.normalizeForMatch(aiAuthor);
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
    // Book token inference — local-only logic
    // -------------------------------------------------------------------------

    private static List<String> inferRequestedBookTokens(String topic, String requiredAuthor) {
        String normalizedTopic = SearchCore.normalizeForMatch(topic);
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
            for (String token : SearchCore.normalizeForMatch(requiredAuthor).split("\\s+")) {
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
            if (SearchCore.NOISE_TOKENS.contains(token)
                || SearchCore.GENERIC_QUERY_TOKENS.contains(token)
                || authorTerms.contains(token)) {
                continue;
            }
            bookTokens.add(token);
        }

        return bookTokens;
    }

    private static boolean startsWithAuthorAlias(String segment) {
        String normalized = SearchCore.normalizeForMatch(segment);
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
        for (String token : SearchCore.normalizeForMatch(aiWorkTitle).split("\\s+")) {
            if (token.length() >= 3
                && !SearchCore.NOISE_TOKENS.contains(token)
                && !SearchCore.GENERIC_QUERY_TOKENS.contains(token)) {
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
        for (String token : SearchCore.normalizeForMatch(explicitTitle).split("\\s+")) {
            if (token.length() >= 3
                && !SearchCore.NOISE_TOKENS.contains(token)
                && !SearchCore.GENERIC_QUERY_TOKENS.contains(token)) {
                merged.add(token);
            }
        }
        return new ArrayList<>(merged);
    }

    // -------------------------------------------------------------------------
    // Gemini reranking
    // -------------------------------------------------------------------------

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
                selectedKeys.add(SearchCore.normalizeForMatch(hit.quote())
                    + "|" + SearchCore.normalizeForMatch(hit.sourceUrl()));
            }

            for (CorpusSearchHit hit : boundedPool) {
                if (selected.size() >= requestedQuotes) {
                    break;
                }
                String key = SearchCore.normalizeForMatch(hit.quote())
                    + "|" + SearchCore.normalizeForMatch(hit.sourceUrl());
                if (selectedKeys.add(key)) {
                    selected.add(hit);
                }
            }
        }

        return selected.stream().limit(requestedQuotes).toList();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

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

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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