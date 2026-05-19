package com.bahairesearch;

import com.bahairesearch.config.AppConfig;
import com.bahairesearch.config.ConfigLoader;
import com.bahairesearch.corpus.CorpusConnectionFactory;
import com.bahairesearch.corpus.CorpusPaths;
import com.bahairesearch.common.model.QuoteResult;
import com.bahairesearch.common.model.ResearchReport;
import com.bahairesearch.research.ResearchService;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX desktop application for topic-based Bahá'í quote research.
 */
public class BahaiResearch extends Application {

    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 760;

    private final ResearchService researchService = new ResearchService();
    private AppConfig appConfig;
    private HttpServer localFileServer;
    private int localServerPort = -1;

    /**
     * Starts a local HTTP server that serves corpus files from corpusBasePath.
     * Using http://localhost avoids the Windows file:/// + #fragment bug where
     * Desktop.browse() and PowerShell both strip the fragment identifier.
     */
    private void startLocalFileServer() {
        if (appConfig == null) return;
        try {
            Path serveRoot = Path.of(appConfig.corpusBasePath()).toAbsolutePath();
            localFileServer = HttpServer.create(new InetSocketAddress(0), 0);
            localFileServer.createContext("/", exchange -> {
                String reqPath = exchange.getRequestURI().getPath();
                if (reqPath.startsWith("/")) reqPath = reqPath.substring(1);
                Path file = serveRoot.resolve(reqPath).normalize();
                // Security: reject requests that escape the serve root
                if (!file.startsWith(serveRoot) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] bytes = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            localFileServer.setExecutor(null);
            localFileServer.start();
            localServerPort = localFileServer.getAddress().getPort();
        } catch (Exception e) {
            localServerPort = -1;
        }
    }

    @Override
    public void stop() {
        if (localFileServer != null) {
            localFileServer.stop(0);
        }
    }

    /**
     * Build and display the main application window.
     */
    @Override
    public void start(Stage stage) {
        // ── Filter bar (above input) ──────────────────────────────────────────
        // Author ComboBox — hardcoded (Baha'i primary authors are fixed)
        Label authorLabel = new Label("Author:");
        ComboBox<String> authorCombo = new ComboBox<>();
        authorCombo.getItems().addAll(
            "All Authors",
            "'Abdu'l-Baha",
            "Bab",
            "Baha'u'llah",
            "Compilation",
            "Shoghi Effendi",
            "Universal House of Justice"
        );
        authorCombo.setValue("All Authors");

        // Title ComboBox — disabled until an author is chosen; titles loaded from DB for that author.
        Label titleLabel = new Label("Title:");
        ComboBox<String> titleCombo = new ComboBox<>();
        titleCombo.getItems().add("All Titles");
        titleCombo.setValue("All Titles");
        titleCombo.setDisable(true);
        HBox.setHgrow(titleCombo, Priority.ALWAYS);

        try {
            appConfig = ConfigLoader.load();
        } catch (Exception ignored) {
            // Graceful: author-based title loading will be skipped if config unavailable
        }

        // Selecting an author populates and enables the title combo from the DB.
        // Reverting to "All Authors" clears and disables it.
        authorCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || "All Authors".equals(newVal)) {
                titleCombo.getItems().setAll("All Titles");
                titleCombo.setValue("All Titles");
                titleCombo.setDisable(true);
            } else {
                List<String> titles = new ArrayList<>();
                titles.add("All Titles");
                titles.addAll(loadTitlesForAuthor(newVal));
                titleCombo.getItems().setAll(titles);
                titleCombo.setValue("All Titles");
                titleCombo.setDisable(false);
            }
        });

        startLocalFileServer();

        HBox filterBar = new HBox(8, authorLabel, authorCombo, titleLabel, titleCombo);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(0, 0, 4, 0));

        // ── Input area ────────────────────────────────────────────────────────
        Label inputLabel = new Label("Research Topic");

        TextArea topicInputArea = new TextArea();
        topicInputArea.setPromptText("Describe your topic in paragraph form. Research begins only when you click the button.");
        topicInputArea.setWrapText(true);
        topicInputArea.setPrefRowCount(5);

        Button beginResearchButton = new Button("Begin Research →");

        Label hintLabel = new Label("Tip: 2\u20134 key words give the sharpest results. Noise words (the, for, about) are ignored.");
        hintLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #666666;");

        HBox controls = new HBox(12, beginResearchButton, hintLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setSpacing(8);

        // ── Output area ───────────────────────────────────────────────────────
        TextArea summaryArea = new TextArea();
        summaryArea.setEditable(false);
        summaryArea.setWrapText(true);
        summaryArea.setPromptText("Summary will appear here.");
        summaryArea.setPrefRowCount(5);

        VBox resultsBox = new VBox(8);
        ScrollPane outputScrollPane = new ScrollPane(resultsBox);
        outputScrollPane.setFitToWidth(true);
        outputScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // Wire button now that both input and output areas are in scope
        beginResearchButton.setOnAction(event -> {
            String authorFilter = "All Authors".equals(authorCombo.getValue()) ? null : authorCombo.getValue();
            String titleFilter  = "All Titles".equals(titleCombo.getValue())   ? null : titleCombo.getValue();
            runResearch(topicInputArea, summaryArea, resultsBox, beginResearchButton, authorFilter, titleFilter);
        });

        // ── Layout ────────────────────────────────────────────────────────────
        VBox root = new VBox(filterBar, inputLabel, topicInputArea, controls, summaryArea, outputScrollPane);
        root.setSpacing(12);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-font-size: 18px;");
        // Adjust font size here for labels

        VBox.setVgrow(outputScrollPane, Priority.ALWAYS);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

        stage.setTitle("BahaiResearch");
        stage.setScene(scene);
        stage.setMinWidth(840);
        stage.setMinHeight(560);
        stage.setResizable(true);
        stage.show();

        Platform.runLater(topicInputArea::requestFocus);
    }

    private List<String> loadTitlesForAuthor(String author) {
        if (appConfig == null) return List.of();
        String sql = "SELECT DISTINCT title FROM documents "
            + "WHERE lower(author) = lower(?) "
            + "AND title IS NOT NULL AND trim(title) != '' "
            + "ORDER BY title";
        List<String> titles = new ArrayList<>();
        CorpusPaths corpusPaths = CorpusPaths.fromConfig(appConfig);
        try (Connection connection = CorpusConnectionFactory.open(corpusPaths);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, author);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (t != null && !t.isBlank()) titles.add(t.trim());
                }
            }
        } catch (Exception ignored) {
            // DB may not exist yet on first launch before any search
        }
        return titles;
    }

    private void runResearch(
        TextArea topicInputArea,
        TextArea summaryArea,
        VBox resultsBox,
        Button beginResearchButton,
        String selectedAuthor,
        String selectedTitle
    ) {
        String topic = topicInputArea.getText();
        summaryArea.setText("Running research... this may take a moment.");
        resultsBox.getChildren().clear();
        beginResearchButton.setDisable(true);

        Task<ResearchReport> researchTask = new Task<>() {
            @Override
            protected ResearchReport call() {
                return researchService.conductResearch(topic, selectedAuthor, selectedTitle);
            }
        };

        researchTask.setOnSucceeded(workerStateEvent -> {
            ResearchReport report = researchTask.getValue();
            summaryArea.setText(report.summary() == null ? "" : report.summary());
            resultsBox.getChildren().clear();
            if (report.quotes().isEmpty()) {
                resultsBox.getChildren().add(new Label("No quotes were returned. Check topic wording and source availability."));
            } else {
                int[] num = {1};
                for (QuoteResult quote : report.quotes()) {
                    resultsBox.getChildren().add(buildQuoteCard(num[0]++, quote));
                }
            }
            beginResearchButton.setDisable(false);
        });

        researchTask.setOnFailed(workerStateEvent -> {
            Throwable throwable = researchTask.getException();
            String message = throwable == null ? "Unknown error" : throwable.getMessage();
            summaryArea.setText("Research failed:\n" + message);
            beginResearchButton.setDisable(false);
        });

        Thread workerThread = new Thread(researchTask, "research-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private VBox buildQuoteCard(int number, QuoteResult quote) {
        String quoteText = number + ") \u201c" + quote.quote() + "\u201d"
            + "\n   Author: " + quote.author()
            + "  |  Book: " + quote.bookTitle();
        TextArea quoteLabel = new TextArea(quoteText);
        quoteLabel.setEditable(false);
        quoteLabel.setWrapText(true);
        quoteLabel.setStyle("-fx-font-style: normal; -fx-font-size: 18px; -fx-font-weight: normal; -fx-background-color: transparent;");
        quoteLabel.setPrefRowCount(5);
        quoteLabel.setMaxHeight(Double.MAX_VALUE);

        // Build deep link. Use localhost HTTP server so browser handles #fragment correctly —
        // Windows file:/// URIs with fragments are broken by Desktop.browse() and PowerShell.
        String locator = quote.paragraphOrPage();
        String relativeSourceUrl = quote.sourceUrl();
        String deepLink = buildDeepLink(relativeSourceUrl, locator);

        Hyperlink sourceLink = new Hyperlink("Source \u2197");
        sourceLink.setStyle("-fx-font-size: 18px;");
        sourceLink.setOnAction(e -> {
            try {
                if (locator != null && locator.matches("\\d+")) {
                    // xhtml with anchor ID — open in browser via local HTTP server
                    Desktop.getDesktop().browse(new URI(deepLink));
                } else if (appConfig != null && relativeSourceUrl != null) {
                    // docx/pdf — open with registered OS handler (Word, Edge PDF viewer, etc.)
                    java.io.File file = Path.of(appConfig.corpusBasePath())
                        .resolve(relativeSourceUrl).toAbsolutePath().toFile();
                    Desktop.getDesktop().open(file);
                }
            } catch (Exception ex) {
                // ignore — handler unavailable
            }
        });

        VBox card = new VBox(2, quoteLabel, sourceLink);
        card.setPadding(new Insets(6, 0, 6, 0));
        card.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");
        return card;
    }

    /**
     * Constructs the URL to open in the browser for a given passage.
     * Uses the local HTTP server (http://localhost:PORT/relPath#anchor) so that
     * fragment navigation works reliably across all browsers on Windows.
     * Falls back to a file:/// URI if the server failed to start.
     */
    private String buildDeepLink(String relativeSourceUrl, String locator) {
        if (relativeSourceUrl == null || relativeSourceUrl.isBlank()) return null;

        String baseUrl;
        if (localServerPort > 0) {
            baseUrl = "http://localhost:" + localServerPort + "/" + relativeSourceUrl;
        } else if (appConfig != null) {
            baseUrl = Path.of(appConfig.corpusBasePath())
                .resolve(relativeSourceUrl).toAbsolutePath().toUri().toString();
        } else {
            return null;
        }

        return (locator != null && locator.matches("\\d+"))
            ? baseUrl + "#" + locator
            : baseUrl;
    }
}
