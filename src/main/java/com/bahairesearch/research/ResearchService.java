package com.bahairesearch.research;

import com.bahairesearch.ai.GeminiClient;
import com.bahairesearch.corpus.CorpusBootstrapService;
import com.bahairesearch.corpus.CorpusIngestService;
import com.bahairesearch.corpus.LocalCorpusSearchService;
import com.bahairesearch.config.AppConfig;
import com.bahairesearch.config.ConfigLoader;
import com.bahairesearch.common.model.ResearchReport;

import java.util.List;

/**
 * Orchestrates configuration and Gemini synthesis for research requests.
 */
public class ResearchService {

    /**
     * Execute full research flow and return a structured report.
     */
    public ResearchReport conductResearch(String topic) {
        return conductResearch(topic, null, null);
    }

    /**
     * Execute full research flow with optional explicit author/title filters from UI dropdowns.
     *
     * @param selectedAuthor exact canonical author from dropdown (e.g. "Baha'u'llah"), or null
     * @param selectedTitle  exact title from dropdown, or null
     */
    public ResearchReport conductResearch(String topic, String selectedAuthor, String selectedTitle) {
        if (topic == null || topic.trim().isEmpty()) {
            return new ResearchReport("Please enter a topic before starting research.", List.of());
        }

        AppConfig appConfig = ConfigLoader.load();
        CorpusBootstrapService.initializeIfEnabled(appConfig);
        CorpusIngestService.ingestIfConfigured(appConfig);

        ResearchReport localReport = LocalCorpusSearchService.search(
            topic.trim(), selectedAuthor, selectedTitle, appConfig);
        if (!localReport.quotes().isEmpty() || appConfig.localOnlyMode()) {
            return localReport;
        }

        GeminiClient geminiClient = new GeminiClient(appConfig);
        return geminiClient.generateReport(topic.trim(), appConfig);
    }
}