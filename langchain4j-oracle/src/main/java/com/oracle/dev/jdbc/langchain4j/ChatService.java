package com.oracle.dev.jdbc.langchain4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import jakarta.annotation.PostConstruct;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private OllamaEmbeddingModel embeddingModel;
    private OracleEmbeddingStore pdfEmbeddingStore;
    private OracleEmbeddingStore githubEmbeddingStore;
    private ChatLanguageModel chatModel;

    @PostConstruct
    public void init() throws SQLException {
        embeddingModel = OllamaEmbeddingModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("nomic-embed-text").build();

        pdfEmbeddingStore = new OracleEmbeddingStore(
            OracleDBUtils.getPooledDataSource(), "ORACLE_VECTOR_STORE",
            768, 95, OracleDistanceType.COSINE,
            OracleIndexType.IVF, true, true, false, true);

        githubEmbeddingStore = new OracleEmbeddingStore(
            OracleDBUtils.getPooledDataSource(), "GITHUB_VECTOR_STORE_V",
            768, 95, OracleDistanceType.COSINE,
            OracleIndexType.IVF, false, false, false, true);

        chatModel = OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("llama3.1")
            .temperature(0.0)
            .timeout(java.time.Duration.ofMinutes(5))
            .build();
    }

    public void ingestFile(InputStream inputStream, String fileName) throws Exception {
        String content = "";
        
        if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(inputStream)) {
                PDFTextStripper pdfStripper = new PDFTextStripper();
                content = pdfStripper.getText(document);
            }
        } else {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (content != null && !content.trim().isEmpty()) {
            Metadata metadata = new Metadata();
            metadata.put("source", fileName);

            // Split into heading-aware segments so each chunk carries its section context
            List<TextSegment> segments = splitWithHeadingContext(content, fileName);
            logger.info("Split '{}' into {} heading-aware chunks", fileName, segments.size());

            // Embed and store all segments
            List<dev.langchain4j.data.embedding.Embedding> embeddings =
                embeddingModel.embedAll(segments).content();
            pdfEmbeddingStore.addAll(embeddings, segments);

            logger.info("Successfully ingested file: {} ({} chars, {} chunks)", fileName, content.length(), segments.size());
        } else {
            throw new Exception("File content is empty.");
        }
    }

    /**
     * Splits document text into chunks that each carry their nearest section heading
     * as a prefix. This preserves structural context for policy/guideline documents
     * that use Roman numerals, letter sections, and numbered subsections.
     *
     * Each chunk text becomes:  "[Section heading]\n\n[chunk body]"
     * Each chunk metadata carries: source, section_heading, section_level
     */
    private List<TextSegment> splitWithHeadingContext(String content, String fileName) {
        // Heading patterns ordered from broadest to most specific:
        //   Roman numeral:  "I.", "II.", "III.", "IV." ...
        //   Letter section: "A.", "B.", "C." ...
        //   Numbered sub:   "1.", "2.", "3." ...
        java.util.regex.Pattern headingPattern = java.util.regex.Pattern.compile(
            "^\\s*(" +
            "(?:I{1,3}|IV|V?I{0,3}|IX|X{0,3}(?:IX|IV|V?I{0,3}))\\.\\s+.+" + // Roman
            "|[A-Z]\\.\\s+.+" +                                                  // Letter
            "|\\d+\\.\\s+.+" +                                                   // Numbered
            ")$",
            java.util.regex.Pattern.MULTILINE
        );

        // Walk through lines, track current heading, accumulate body text
        String[] lines = content.split("\n");
        List<TextSegment> segments = new ArrayList<>();

        String currentHeading = "";
        StringBuilder currentBody = new StringBuilder();
        int chunkSize = 1500;   // characters per chunk
        int overlap   = 300;    // overlap between consecutive chunks of the same section

        for (String line : lines) {
            java.util.regex.Matcher m = headingPattern.matcher(line);
            if (m.find() && line.trim().length() > 3) {
                // Flush accumulated body under the previous heading
                if (currentBody.length() > 0) {
                    flushChunks(segments, currentHeading, currentBody.toString(), fileName, chunkSize, overlap);
                    currentBody.setLength(0);
                }
                currentHeading = line.trim();
            } else {
                currentBody.append(line).append("\n");
            }
        }
        // Flush the last section
        if (currentBody.length() > 0) {
            flushChunks(segments, currentHeading, currentBody.toString(), fileName, chunkSize, overlap);
        }

        return segments;
    }

    /** Breaks a section body into fixed-size chunks, each prefixed with the heading. */
    private void flushChunks(List<TextSegment> segments, String heading,
                              String body, String fileName, int chunkSize, int overlap) {
        String prefix = heading.isEmpty() ? "" : heading + "\n\n";
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + chunkSize, body.length());
            String chunkText = prefix + body.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                Metadata meta = new Metadata();
                meta.put("source", fileName);
                meta.put("section_heading", heading);
                segments.add(TextSegment.from(chunkText, meta));
            }
            if (end == body.length()) break;
            start = end - overlap;  // step back by overlap for continuity
        }
    }

    public Map<String, Object> askQuestionWithSources(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest
            .builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(10)   // top 10 per store, then merge-rank down to best 8 total
            .minScore(0.3)
            .build();

        // ── Search PDF/document store ────────────────────────────────────────────
        EmbeddingSearchResult<TextSegment> pdfResult = pdfEmbeddingStore.search(searchRequest);

        // ── Search GitHub store (graceful fallback if table missing) ─────────────
        EmbeddingSearchResult<TextSegment> githubResult;
        try {
            githubResult = githubEmbeddingStore.search(searchRequest);
        } catch (Exception e) {
            logger.warn("GitHub vector store search failed: {}", e.getMessage());
            githubResult = new EmbeddingSearchResult<>(List.of());
        }

        // ── Merge and rank all matches by score descending, keep top 8 ──────────
        // Tag each match with its store type so the LLM prompt is clear
        record ScoredChunk(double score, String storeType, dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match) {}

        List<ScoredChunk> merged = new ArrayList<>();
        pdfResult.matches().forEach(m -> merged.add(new ScoredChunk(m.score(), "document", m)));
        githubResult.matches().forEach(m -> merged.add(new ScoredChunk(m.score(), "github", m)));
        merged.sort((a, b) -> Double.compare(b.score(), a.score())); // highest score first

        List<ScoredChunk> topChunks = merged.stream().limit(8).collect(Collectors.toList());

        logger.info("Query '{}' → {} doc chunks, {} github chunks, {} merged top chunks",
            question.substring(0, Math.min(60, question.length())),
            pdfResult.matches().size(), githubResult.matches().size(), topChunks.size());

        // ── Build context and collect sources ────────────────────────────────────
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        StringBuilder contextBuilder = new StringBuilder();

        for (ScoredChunk sc : topChunks) {
            TextSegment seg = sc.match().embedded();
            if (seg == null) continue;

            String source = seg.metadata().getString("source");
            String heading = seg.metadata().getString("section_heading");
            String label;

            if ("github".equals(sc.storeType())) {
                label = source != null ? "GitHub [" + source + "]" : "GitHub Repository";
                sources.add(label);
            } else {
                label = source != null ? "Document [" + source + "]" : "Internal Documents";
                sources.add(source != null ? source : "Internal Documents");
            }

            contextBuilder
                .append("--- Source: ").append(label)
                .append(" | Score: ").append(String.format("%.2f", sc.score()));
            if (heading != null && !heading.isBlank()) {
                contextBuilder.append(" | Section: ").append(heading);
            }
            contextBuilder.append(" ---\n")
                .append(seg.text())
                .append("\n\n");
        }

        String information = contextBuilder.toString().trim();

        // ── Prompt adapts based on what was actually found ────────────────────────
        boolean hasDocContent    = topChunks.stream().anyMatch(c -> "document".equals(c.storeType()));
        boolean hasGithubContent = topChunks.stream().anyMatch(c -> "github".equals(c.storeType()));

        String contextDescription;
        if (hasDocContent && hasGithubContent) {
            contextDescription = "The context contains both policy/document content and GitHub code. Use both to give a complete answer.";
        } else if (hasGithubContent) {
            contextDescription = "The context contains GitHub code. Explain the business logic and behavior based on the code.";
        } else if (hasDocContent) {
            contextDescription = "The context contains policy/guideline documents. Answer based strictly on the document content.";
        } else {
            contextDescription = "No relevant context was found.";
        }

        Prompt prompt = PromptTemplate.from("""
            You are an expert AI assistant. {{contextDescription}}
            Use ONLY the provided context to answer. Do not use prior knowledge.

            Rules:
            1. Answer factually and directly from the context.
            2. If the context has section headings, use them to structure your answer.
            3. If the context contains code, explain what it does in plain language.
            4. If the answer is not in the context, say exactly: "I don't have enough information to answer that based on the available documents and code."

            Context:
            {{information}}

            Question: {{question}}
            """).apply(Map.of(
                "contextDescription", contextDescription,
                "information", information.isEmpty() ? "No relevant content found." : information,
                "question", question));

        String answer = chatModel.generate(prompt.toUserMessage()).content().text();

        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("sources", new ArrayList<>(sources));
        return result;
    }

    public String askQuestion(String question) {
        return (String) askQuestionWithSources(question).get("answer");
    }

    public void clearAllDocuments() {
        String truncateQuery = "TRUNCATE TABLE ORACLE_VECTOR_STORE";
        try (Connection connection = OracleDBUtils.getPooledDataSource().getConnection();
             PreparedStatement stmt = connection.prepareStatement(truncateQuery)) {
            stmt.executeUpdate();
            logger.info("Truncated ORACLE_VECTOR_STORE — all embeddings removed.");
        } catch (SQLException e) {
            logger.error("Error truncating vector store: " + e.getMessage(), e);
            throw new RuntimeException("Failed to clear all documents", e);
        }
    }

    public void deleteDocument(String sourceName) {
        String deleteQuery = "DELETE FROM ORACLE_VECTOR_STORE WHERE json_value(metadata, '$.source') = ?";
        try (Connection connection = OracleDBUtils.getPooledDataSource().getConnection();
             PreparedStatement stmt = connection.prepareStatement(deleteQuery)) {
            stmt.setString(1, sourceName);
            int deleted = stmt.executeUpdate();
            logger.info("Deleted {} chunks for document: {}", deleted, sourceName);
        } catch (SQLException e) {
            logger.error("Error deleting document: " + e.getMessage(), e);
            throw new RuntimeException("Failed to delete document: " + sourceName, e);
        }
    }

    public void reEmbedDocument(InputStream inputStream, String fileName) throws Exception {
        // Step 1: remove all existing chunks for this document
        deleteDocument(fileName);
        logger.info("Removed old embeddings for: {}", fileName);

        // Step 2: re-ingest with fresh embeddings
        ingestFile(inputStream, fileName);
        logger.info("Re-embedded document: {}", fileName);
    }

    public List<Map<String, Object>> getIndexedDocuments() {
        List<Map<String, Object>> documents = new ArrayList<>();
        // In Oracle 23ai, to extract string from JSON we use json_value
        String query = "SELECT json_value(metadata, '$.source') as source_name, COUNT(*) as chunk_count FROM ORACLE_VECTOR_STORE WHERE json_value(metadata, '$.source') IS NOT NULL GROUP BY json_value(metadata, '$.source') ORDER BY source_name";
        
        try (Connection connection = OracleDBUtils.getPooledDataSource().getConnection();
             PreparedStatement stmt = connection.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                String sourceName = rs.getString("source_name");
                if (sourceName != null && !sourceName.trim().isEmpty()) {
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("name", sourceName);
                    doc.put("chunks", rs.getInt("chunk_count"));
                    doc.put("status", "Indexed");
                    documents.add(doc);
                }
            }
            logger.info("Retrieved " + documents.size() + " indexed documents");
        } catch (SQLException e) {
            logger.error("Error retrieving indexed documents: " + e.getMessage(), e);
        }
        
        return documents;
    }

    public Map<String, Object> debugSearch(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(5)
            .minScore(0.3)
            .build();

        EmbeddingSearchResult<TextSegment> pdfResult = pdfEmbeddingStore.search(req);
        EmbeddingSearchResult<TextSegment> githubResult;
        try {
            githubResult = githubEmbeddingStore.search(req);
        } catch (Exception e) {
            githubResult = new EmbeddingSearchResult<>(List.of());
        }

        List<String> docMatches = pdfResult.matches().stream()
            .map(m -> String.format("[DOC  %.3f] section=%s | %s",
                m.score(),
                m.embedded().metadata().getString("section_heading"),
                m.embedded().text().substring(0, Math.min(120, m.embedded().text().length()))))
            .collect(Collectors.toList());

        List<String> ghMatches = githubResult.matches().stream()
            .map(m -> String.format("[GH   %.3f] source=%s | %s",
                m.score(),
                m.embedded().metadata().getString("source"),
                m.embedded().text().substring(0, Math.min(120, m.embedded().text().length()))))
            .collect(Collectors.toList());

        List<String> all = new ArrayList<>();
        all.addAll(docMatches);
        all.addAll(ghMatches);

        return Map.of(
            "doc_matches", docMatches,
            "github_matches", ghMatches,
            "total", all.size()
        );
    }
}
