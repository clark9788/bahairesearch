package com.bahairesearch.ai;

import com.bahairesearch.config.AppConfig;
import com.bahairesearch.common.model.CorpusSearchHit;
import com.bahairesearch.common.model.QuoteResult;
import com.bahairesearch.common.model.ResearchReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Client for Gemini summary and quote extraction with strict citation fields.
 */
public class GeminiClient {

    public record LocalQueryIntent(
        String author,
        String workTitle,
        String knownPhrase,
        List<String> concepts
    ) {
    }

    private static final String GEMINI_URL_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    /**
     * Create the Gemini client with configured request timeout values.
     */
    public GeminiClient(AppConfig appConfig) {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(appConfig.requestTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(appConfig.requestTimeoutSeconds()))
            .build();
    }

    /**
     * Generate a summary and selected quotes based on topic and prompt profile constraints.
     */
    public ResearchReport generateReport(String topic, AppConfig appConfig) {
        String prompt = buildPrompt(topic, appConfig);
        try {
            String geminiTextResponse = generateText(prompt, appConfig);
            ResearchReport report = parseReport(geminiTextResponse, appConfig.noResultsText());
            return enforceRequestedAuthor(topic, report, appConfig.noResultsText());
        } catch (IllegalStateException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("empty response")) {
                return new ResearchReport(appConfig.noResultsText(), List.of());
            }
            throw exception;
        }
    }

    /**
     * Resolve author/work/phrase/concepts for local constrained retrieval.
     */
    public LocalQueryIntent resolveLocalQueryIntent(String topic, List<String> knownWorkTitles, AppConfig appConfig) {
        if (topic == null || topic.isBlank() || appConfig.geminiApiKey().isBlank()) {
            return new LocalQueryIntent("", "", "", List.of());
        }

        String prompt = buildIntentPrompt(topic, knownWorkTitles);
        try {
            String raw = generateText(prompt, appConfig);
            String cleaned = stripMarkdownCodeFence(raw);
            JsonNode root = objectMapper.readTree(cleaned);

            String author = root.path("author").asText("").trim();
            String workTitle = root.path("workTitle").asText("").trim();
            String knownPhrase = root.path("knownPhrase").asText("").trim();

            Set<String> concepts = new HashSet<>();
            JsonNode conceptsNode = root.path("concepts");
            if (conceptsNode.isArray()) {
                for (JsonNode conceptNode : conceptsNode) {
                    String concept = conceptNode.asText("").trim();
                    if (!concept.isBlank()) {
                        concepts.add(concept);
                    }
                }
            }

            return new LocalQueryIntent(author, workTitle, knownPhrase, new ArrayList<>(concepts));
        } catch (Exception exception) {
            return new LocalQueryIntent("", "", "", List.of());
        }
    }

    /**
     * Re-rank local candidates semantically while forcing evidence-only selection.
     */
    public List<Integer> rerankLocalCandidates(
        String topic,
        List<CorpusSearchHit> candidates,
        int maxQuotes,
        AppConfig appConfig
    ) {
        if (topic == null || topic.isBlank() || candidates.isEmpty() || appConfig.geminiApiKey().isBlank()) {
            return List.of();
        }

        String prompt = buildCandidateRerankPrompt(topic, candidates, maxQuotes);
        try {
            String raw = generateText(prompt, appConfig);
            String cleaned = stripMarkdownCodeFence(raw);
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode idsNode = root.path("selectedIds");
            if (!idsNode.isArray()) {
                return List.of();
            }

            List<Integer> selected = new ArrayList<>();
            for (JsonNode idNode : idsNode) {
                int id = idNode.asInt(-1);
                if (id >= 1 && id <= candidates.size() && !selected.contains(id)) {
                    selected.add(id);
                }
            }
            return selected;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String generateText(String prompt, AppConfig appConfig) {
        var root = objectMapper.createObjectNode();
        root.putArray("contents")
            .addObject()
            .put("role", "user")
            .putArray("parts")
            .addObject()
            .put("text", prompt);

        String payload = root.toPrettyString();
        String endpoint = GEMINI_URL_TEMPLATE.formatted(appConfig.geminiModel(), appConfig.geminiApiKey());

        IOException lastIoException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    String diagnostic = responseBody.isBlank() ? "No response body." : truncate(responseBody, 1200);
                    throw new IllegalStateException(
                        "Gemini request failed with status " + response.code()
                            + ". Model: " + appConfig.geminiModel()
                            + ". Response: " + diagnostic
                    );
                }

                if (responseBody.isBlank()) {
                    if (attempt == 1) {
                        continue;
                    }
                    throw new IllegalStateException("Gemini returned an empty response body.");
                }

                JsonNode responseJson = objectMapper.readTree(responseBody);
                JsonNode textNode = responseJson.path("candidates").path(0).path("content").path("parts").path(0).path("text");
                String responseText = textNode.asText("").trim();
                if (responseText.isEmpty()) {
                    if (attempt == 1) {
                        continue;
                    }
                    throw new IllegalStateException("Gemini returned an empty response.");
                }
                return responseText;
            } catch (IOException exception) {
                lastIoException = exception;
                boolean shouldRetry = attempt == 1
                    && exception.getMessage() != null
                    && exception.getMessage().toLowerCase(Locale.ROOT).contains("timeout");
                if (!shouldRetry) {
                    break;
                }
            }
        }

        String message = lastIoException == null ? "Unknown IO error" : lastIoException.getMessage();
        throw new IllegalStateException("Gemini request error: " + message, lastIoException);
    }

    private ResearchReport parseReport(String rawResponse, String noResultsText) {
        String cleaned = stripMarkdownCodeFence(rawResponse);
        if (cleaned.equalsIgnoreCase(noResultsText)) {
            return new ResearchReport(noResultsText, List.of());
        }

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            String summary = root.path("summary").asText("No summary returned.").trim();
            JsonNode quotesNode = root.path("quotes");

            List<QuoteResult> quoteResults = new ArrayList<>();
            if (quotesNode.isArray()) {
                for (JsonNode quoteNode : quotesNode) {
                    String quote = quoteNode.path("quote").asText("").trim();
                    String author = quoteNode.path("author").asText("").trim();
                    String bookTitle = quoteNode.path("bookTitle").asText("").trim();
                    String paragraphOrPage = quoteNode.path("paragraphOrPage").asText("").trim();
                    String sourceUrl = quoteNode.path("sourceUrl").asText("").trim();

                    if (paragraphOrPage.isEmpty()) {
                        paragraphOrPage = "Not specified";
                    }

                    if (!quote.isEmpty()
                        && !author.isEmpty()
                        && !bookTitle.isEmpty()
                        && !sourceUrl.isEmpty()) {
                        quoteResults.add(new QuoteResult(quote, author, bookTitle, paragraphOrPage, sourceUrl));
                    }
                }
            }

            if (summary.equalsIgnoreCase(noResultsText) || quoteResults.isEmpty()) {
                return new ResearchReport(noResultsText, List.of());
            }

            return new ResearchReport(summary, quoteResults);
        } catch (IOException exception) {
            if (cleaned.toLowerCase(Locale.ROOT).contains(noResultsText.toLowerCase(Locale.ROOT))) {
                return new ResearchReport(noResultsText, List.of());
            }

            return new ResearchReport(
                "The AI response could not be parsed as structured output. Raw response was returned below.",
                List.of(new QuoteResult(rawResponse, "N/A", "Gemini raw output", "N/A", "N/A"))
            );
        }
    }

    private String buildPrompt(String topic, AppConfig appConfig) {
        return """
            You are researching Bahá’í writings.

            Topic:
            %s

            Requirements:
            1) Limit research to site:%s only.
            2) %s
            3) Return only a JSON object OR exactly the text "%s".
            2) JSON schema:
               {
                 "summary": "string",
                 "quotes": [
                   {
                     "quote": "exact quote text",
                     "author": "author name",
                     "bookTitle": "book title",
                     "paragraphOrPage": "paragraph number or page number",
                     "sourceUrl": "https://..."
                   }
                 ]
               }
            4) Include up to %d quotes.
            5) Quotes must come from primary Bahá’í writings, not commentary.
            6) If the user asks for a specific author/body (for example Universal House of Justice, Bahá’u’lláh, ‘Abdu’l-Bahá, Shoghi Effendi), only return quotes from that requested source.
            7) Do not substitute quotes from other authors if requested source has no qualifying quote.
            8) If any required citation field is missing, exclude that quote.
            9) If no qualifying results, return exactly "%s".
            10) Do not add markdown fences or extra text.
            """.formatted(
            topic,
            appConfig.requiredSite(),
            appConfig.promptBoilerplate(),
            appConfig.noResultsText(),
            appConfig.maxQuotes(),
            appConfig.noResultsText()
        );
    }

    private String buildIntentPrompt(String topic, List<String> knownWorkTitles) {
        List<String> limitedTitles = knownWorkTitles == null ? List.of() : knownWorkTitles.stream().limit(150).toList();
        String titlesBlock = String.join("\n", limitedTitles);
        return """
            You are a query intent resolver for a Bahá’í local search engine.

            User query:
            %s

            Known work titles (authoritative local corpus):
            %s

            Return JSON only with this schema:
            {
              "author": "best inferred author/body or empty",
              "workTitle": "best inferred work title from known list or empty",
              "knownPhrase": "phrase user likely quotes (if any) or empty",
              "concepts": ["core concept 1", "core concept 2"]
            }

            Rules:
            - Infer aliases (e.g., UHJ => Universal House of Justice).
            - If no clear work title, return empty workTitle.
            - concepts should be semantic focus terms, not filler words.
            - Output JSON only, no markdown.
            """.formatted(topic, titlesBlock);
    }

    private String buildCandidateRerankPrompt(String topic, List<CorpusSearchHit> candidates, int maxQuotes) {
        StringBuilder items = new StringBuilder();
        int index = 1;
        for (CorpusSearchHit candidate : candidates) {
            items.append("ID ")
                .append(index)
                .append(":\n")
                .append("Author: ").append(candidate.author()).append("\n")
                .append("Book: ").append(candidate.title()).append("\n")
                .append("Locator: ").append(candidate.locator()).append("\n")
                .append("URL: ").append(candidate.sourceUrl()).append("\n")
                .append("Quote: ").append(candidate.quote())
                .append("\n\n");
            index++;
        }

        return """
            Select the most relevant passages for this Bahá’í query.

            Query:
            %s

            Candidate passages:
            %s

            Return JSON only:
            {
              "selectedIds": [id1, id2, id3]
            }

            Rules:
            - Select at most %d IDs.
            - Use only IDs from candidates above.
            - Prefer passages that directly address the user intent.
            - Do not invent text or references.
            - Output JSON only.
            """.formatted(topic, items, maxQuotes);
    }

    private ResearchReport enforceRequestedAuthor(String topic, ResearchReport report, String noResultsText) {
        String requiredAuthor = inferRequiredAuthor(topic);
        if (requiredAuthor == null || report.quotes().isEmpty()) {
            return report;
        }

        List<QuoteResult> filteredQuotes = report.quotes().stream()
            .filter(quote -> quote.author() != null
                && quote.author().toLowerCase(Locale.ROOT).contains(requiredAuthor))
            .toList();

        if (filteredQuotes.isEmpty()) {
            return new ResearchReport(noResultsText, List.of());
        }
        return new ResearchReport(report.summary(), filteredQuotes);
    }

    private String inferRequiredAuthor(String topic) {
        String normalizedTopic = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        if (normalizedTopic.contains("house of justice")) {
            return "house of justice";
        }
        if (normalizedTopic.contains("baha'u'llah") || normalizedTopic.contains("bahá’u’lláh")) {
            return "bahá’u’lláh";
        }
        if (normalizedTopic.contains("abdu'l-baha") || normalizedTopic.contains("‘abdu’l-bahá")
            || normalizedTopic.contains("abdul-baha")) {
            return "abdu";
        }
        if (normalizedTopic.contains("shoghi effendi")) {
            return "shoghi effendi";
        }
        return null;
    }

    private String stripMarkdownCodeFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                return trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }
}