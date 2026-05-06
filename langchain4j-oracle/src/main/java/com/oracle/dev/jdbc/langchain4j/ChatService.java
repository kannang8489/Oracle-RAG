package com.oracle.dev.jdbc.langchain4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.annotation.PostConstruct;

/**
 * Core RAG service.
 *
 * Vectorization strategy:
 *   - Uses Oracle 23ai's built-in DBMS_VECTOR.UTL_TO_EMBEDDING with the
 *     ONNX model loaded into the database (all_MiniLM_L6_v2).
 *   - Vector similarity search uses Oracle's native VECTOR_DISTANCE function.
 *   - No external embedding service (Ollama) is needed for embeddings.
 *
 * LLM strategy:
 *   - Uses Ollama llama3 (local) for answer generation.
 *   - LLM_MODEL env var can override the model name.
 *
 * Sources:
 *   - ORACLE_VECTOR_STORE  — uploaded documents (PDF, TXT, CSV, JSON, MD)
 *   - GITHUB_VECTOR_STORE  — GitHub repository code (managed by GitHubSearchAgent)
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    // Oracle 23ai ONNX model name loaded into the DB via DBMS_VECTOR
    // This is the model name used in DBMS_VECTOR.UTL_TO_EMBEDDING calls
    private static final String ORACLE_EMBED_MODEL = "ALL_MINILM_L6_V2";

    // Vector dimension for all_MiniLM_L6_v2 = 384
    private static final int VECTOR_DIM = 384;

    @Autowired
    private GitHubSearchAgent gitHubSearchAgent;

    private ChatLanguageModel chatModel;

    @PostConstruct
    public void init() throws SQLException {
        io.github.cdimascio.dotenv.Dotenv dotenv =
            io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();

        String ollamaUrl   = dotenv.get("OLLAMA_BASE_URL", "http://localhost:11434");
        String ollamaModel = dotenv.get("LLM_MODEL", "llama3:latest");

        chatModel = OllamaChatModel.builder()
            .baseUrl(ollamaUrl)
            .modelName(ollamaModel)
            .temperature(0.0)
            .timeout(java.time.Duration.ofMinutes(5))
            .build();

        // Ensure vector store tables exist
        ensureDocumentStoreTable();
        logger.info("ChatService initialized — Oracle 23ai native embeddings, LLM={}", ollamaModel);
    }

    // ── Table bootstrap ──────────────────────────────────────────────────────────

    private void ensureDocumentStoreTable() throws SQLException {
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             Statement stmt = conn.createStatement()) {
            // ORACLE_VECTOR_STORE — documents uploaded via admin dashboard
            stmt.executeUpdate("""
                BEGIN
                  EXECUTE IMMEDIATE 'CREATE TABLE ORACLE_VECTOR_STORE (
                    id       VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
                    content  CLOB,
                    metadata JSON,
                    embedding VECTOR(%d, FLOAT32)
                  )';
                EXCEPTION WHEN OTHERS THEN
                  IF SQLCODE != -955 THEN RAISE; END IF;
                END;
                """.formatted(VECTOR_DIM));

            // IVF vector index for fast ANN search
            try {
                stmt.executeUpdate("""
                    CREATE VECTOR INDEX IF NOT EXISTS idx_doc_embedding
                    ON ORACLE_VECTOR_STORE (embedding)
                    ORGANIZATION NEIGHBOR PARTITIONS
                    DISTANCE COSINE
                    WITH TARGET ACCURACY 95
                    PARAMETERS (TYPE IVF, NEIGHBOR PARTITIONS 10)
                    """);
            } catch (SQLException e) {
                // Index may already exist — not fatal
                logger.debug("Vector index creation skipped: {}", e.getMessage());
            }

            logger.info("ORACLE_VECTOR_STORE table ready (dim={})", VECTOR_DIM);
        }
    }

    // ── Oracle 23ai native embedding via DBMS_VECTOR ─────────────────────────────

    /**
     * Calls Oracle's DBMS_VECTOR.UTL_TO_EMBEDDING to generate a vector
     * for the given text using the ONNX model loaded in the database.
     *
     * Returns the embedding as a float array.
     */
    private float[] embedWithOracle(Connection conn, String text) throws SQLException {
        // Truncate to 512 tokens worth of chars to stay within model limits
        String truncated = text.length() > 2000 ? text.substring(0, 2000) : text;
        String sql = """
            SELECT DBMS_VECTOR.UTL_TO_EMBEDDING(
                ?,
                JSON('{"provider":"database","model":"%s"}')
            ) FROM DUAL
            """.formatted(ORACLE_EMBED_MODEL);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, truncated);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // VECTOR type comes back as a double[] via getObject
                    double[] vec = rs.getObject(1, double[].class);
                    float[] result = new float[vec.length];
                    for (int i = 0; i < vec.length; i++) result[i] = (float) vec[i];
                    return result;
                }
            }
        }
        throw new SQLException("DBMS_VECTOR.UTL_TO_EMBEDDING returned no result");
    }

    // ── Document ingestion ───────────────────────────────────────────────────────

    public void ingestFile(InputStream inputStream, String fileName) throws Exception {
        String content;
        if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) {
            try (PDDocument doc = PDDocument.load(inputStream)) {
                content = new PDFTextStripper().getText(doc);
            }
        } else {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (content == null || content.trim().isEmpty()) {
            throw new Exception("File content is empty.");
        }

        List<TextSegment> segments = splitWithHeadingContext(content, fileName);
        logger.info("Ingesting '{}' — {} chunks", fileName, segments.size());

        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
            conn.setAutoCommit(false);
            String upsert = """
                MERGE INTO ORACLE_VECTOR_STORE t
                USING (SELECT ? AS id FROM DUAL) s ON (t.id = s.id)
                WHEN MATCHED THEN UPDATE SET t.content=?, t.metadata=?, t.embedding=?
                WHEN NOT MATCHED THEN INSERT (id, content, metadata, embedding)
                  VALUES (?, ?, ?, ?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                for (TextSegment seg : segments) {
                    String id = UUID.randomUUID().toString();
                    float[] vec = embedWithOracle(conn, seg.text());
                    oracle.sql.VECTOR oraVec = oracle.sql.VECTOR.ofFloat32Values(vec);
                    oracle.sql.json.OracleJsonObject meta = buildMetaJson(seg.metadata().toMap());

                    ps.setString(1, id);
                    ps.setString(2, seg.text());
                    ps.setObject(3, meta, oracle.jdbc.OracleType.JSON.getVendorTypeNumber());
                    ps.setObject(4, oraVec, oracle.jdbc.OracleType.VECTOR.getVendorTypeNumber());
                    ps.setString(5, id);
                    ps.setString(6, seg.text());
                    ps.setObject(7, meta, oracle.jdbc.OracleType.JSON.getVendorTypeNumber());
                    ps.setObject(8, oraVec, oracle.jdbc.OracleType.VECTOR.getVendorTypeNumber());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
        logger.info("Ingested '{}' — {} chunks stored in Oracle", fileName, segments.size());
    }

    // ── RAG query ────────────────────────────────────────────────────────────────

    public Map<String, Object> askQuestionWithSources(String question) {
        List<GitHubSearchAgent.ScoredChunk> merged = new ArrayList<>();

        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
            float[] queryVec = embedWithOracle(conn, question);
            oracle.sql.VECTOR oraQueryVec = oracle.sql.VECTOR.ofFloat32Values(queryVec);

            // ── Search ORACLE_VECTOR_STORE (documents) ───────────────────────────
            String docSql = """
                SELECT content,
                       JSON_VALUE(metadata, '$.source')          AS source,
                       JSON_VALUE(metadata, '$.section_heading') AS heading,
                       1 - VECTOR_DISTANCE(embedding, ?, COSINE) AS score
                FROM ORACLE_VECTOR_STORE
                ORDER BY score DESC
                FETCH APPROXIMATE FIRST 10 ROWS ONLY WITH TARGET ACCURACY 95
                """;
            try (PreparedStatement ps = conn.prepareStatement(docSql)) {
                ps.setObject(1, oraQueryVec, oracle.jdbc.OracleType.VECTOR.getVendorTypeNumber());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double score = rs.getDouble("score");
                        if (score >= 0.3) {
                            merged.add(new GitHubSearchAgent.ScoredChunk(score, "document",
                                rs.getString("content"),
                                rs.getString("source"),
                                rs.getString("heading")));
                        }
                    }
                }
            }

            // ── Search GITHUB_VECTOR_STORE (repos) via GitHubSearchAgent ────────
            List<GitHubSearchAgent.ScoredChunk> ghChunks =
                gitHubSearchAgent.search(conn, oraQueryVec, 10);
            merged.addAll(ghChunks);

        } catch (Exception e) {
            logger.error("Vector search failed: {}", e.getMessage(), e);
            return Map.of("answer", "Search failed: " + e.getMessage(), "sources", List.of());
        }

        // Sort by score descending, keep top 8
        merged.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<GitHubSearchAgent.ScoredChunk> topChunks =
            merged.stream().limit(8).collect(Collectors.toList());

        logger.info("Query '{}' → {} total chunks (top 8 used)",
            question.substring(0, Math.min(60, question.length())), merged.size());

        // ── Build context ────────────────────────────────────────────────────────
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        StringBuilder ctx = new StringBuilder();

        for (GitHubSearchAgent.ScoredChunk sc : topChunks) {
            String label;
            if ("github".equals(sc.storeType())) {
                label = sc.source() != null ? "GitHub [" + sc.source() + "]" : "GitHub Repository";
            } else {
                label = sc.source() != null ? sc.source() : "Internal Documents";
            }
            sources.add(label);

            ctx.append("--- Source: ").append(label)
               .append(" | Score: ").append(String.format("%.2f", sc.score()));
            if (sc.heading() != null && !sc.heading().isBlank()) {
                ctx.append(" | Section: ").append(sc.heading());
            }
            ctx.append(" ---\n").append(sc.content()).append("\n\n");
        }

        String information = ctx.toString().trim();
        boolean hasDoc    = topChunks.stream().anyMatch(c -> "document".equals(c.storeType()));
        boolean hasGithub = topChunks.stream().anyMatch(c -> "github".equals(c.storeType()));

        String ctxDesc;
        if (hasDoc && hasGithub)  ctxDesc = "The context contains both policy/document content and GitHub code. Use both to give a complete answer.";
        else if (hasGithub)       ctxDesc = "The context contains GitHub code. Explain the business logic and behavior based on the code.";
        else if (hasDoc)          ctxDesc = "The context contains policy/guideline documents. Answer based strictly on the document content.";
        else                      ctxDesc = "No relevant context was found.";

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
                "contextDescription", ctxDesc,
                "information", information.isEmpty() ? "No relevant content found." : information,
                "question", question));

        String answer = chatModel.generate(prompt.toUserMessage()).content().text();
        return Map.of("answer", answer, "sources", new ArrayList<>(sources));
    }

    public String askQuestion(String question) {
        return (String) askQuestionWithSources(question).get("answer");
    }

    // ── Document management ──────────────────────────────────────────────────────

    public void clearAllDocuments() {
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("TRUNCATE TABLE ORACLE_VECTOR_STORE");
            logger.info("Truncated ORACLE_VECTOR_STORE");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear all documents", e);
        }
    }

    public void deleteDocument(String sourceName) {
        String sql = "DELETE FROM ORACLE_VECTOR_STORE WHERE JSON_VALUE(metadata, '$.source') = ?";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceName);
            int deleted = ps.executeUpdate();
            logger.info("Deleted {} chunks for document: {}", deleted, sourceName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete document: " + sourceName, e);
        }
    }

    public void reEmbedDocument(InputStream inputStream, String fileName) throws Exception {
        deleteDocument(fileName);
        ingestFile(inputStream, fileName);
        logger.info("Re-embedded document: {}", fileName);
    }

    public List<Map<String, Object>> getIndexedDocuments() {
        List<Map<String, Object>> docs = new ArrayList<>();
        String sql = """
            SELECT JSON_VALUE(metadata, '$.source') AS source_name,
                   COUNT(*) AS chunk_count
            FROM ORACLE_VECTOR_STORE
            WHERE JSON_VALUE(metadata, '$.source') IS NOT NULL
            GROUP BY JSON_VALUE(metadata, '$.source')
            ORDER BY source_name
            """;
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("source_name");
                if (name != null && !name.isBlank()) {
                    docs.add(Map.of("name", name, "chunks", rs.getInt("chunk_count"), "status", "Indexed"));
                }
            }
        } catch (SQLException e) {
            logger.error("Error retrieving indexed documents: {}", e.getMessage());
        }
        return docs;
    }

    public Map<String, Object> debugSearch(String question) {
        List<String> docMatches = new ArrayList<>();
        List<String> ghMatches  = new ArrayList<>();

        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
            float[] queryVec = embedWithOracle(conn, question);
            oracle.sql.VECTOR oraVec = oracle.sql.VECTOR.ofFloat32Values(queryVec);

            String sql = """
                SELECT content, JSON_VALUE(metadata,'$.source') AS src,
                       JSON_VALUE(metadata,'$.section_heading') AS heading,
                       1 - VECTOR_DISTANCE(embedding, ?, COSINE) AS score
                FROM ORACLE_VECTOR_STORE
                ORDER BY score DESC FETCH FIRST 5 ROWS ONLY
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, oraVec, oracle.jdbc.OracleType.VECTOR.getVendorTypeNumber());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String txt = rs.getString("content");
                        docMatches.add(String.format("[DOC %.3f] section=%s | %s",
                            rs.getDouble("score"), rs.getString("heading"),
                            txt.substring(0, Math.min(120, txt.length()))));
                    }
                }
            }

            List<GitHubSearchAgent.ScoredChunk> ghResults =
                gitHubSearchAgent.search(conn, oraVec, 5);
            for (GitHubSearchAgent.ScoredChunk sc : ghResults) {
                ghMatches.add(String.format("[GH %.3f] source=%s | %s",
                    sc.score(), sc.source(),
                    sc.content().substring(0, Math.min(120, sc.content().length()))));
            }
        } catch (Exception e) {
            logger.error("Debug search failed: {}", e.getMessage());
        }

        return Map.of("doc_matches", docMatches, "github_matches", ghMatches,
                      "total", docMatches.size() + ghMatches.size());
    }

    // ── Text chunking ────────────────────────────────────────────────────────────

    private List<TextSegment> splitWithHeadingContext(String content, String fileName) {
        java.util.regex.Pattern headingPattern = java.util.regex.Pattern.compile(
            "^\\s*((?:I{1,3}|IV|V?I{0,3}|IX|X{0,3}(?:IX|IV|V?I{0,3}))\\.\\s+.+" +
            "|[A-Z]\\.\\s+.+|\\d+\\.\\s+.+)$",
            java.util.regex.Pattern.MULTILINE);

        String[] lines = content.split("\n");
        List<TextSegment> segments = new ArrayList<>();
        String currentHeading = "";
        StringBuilder currentBody = new StringBuilder();
        int chunkSize = 1500, overlap = 300;

        for (String line : lines) {
            java.util.regex.Matcher m = headingPattern.matcher(line);
            if (m.find() && line.trim().length() > 3) {
                if (currentBody.length() > 0) {
                    flushChunks(segments, currentHeading, currentBody.toString(), fileName, chunkSize, overlap);
                    currentBody.setLength(0);
                }
                currentHeading = line.trim();
            } else {
                currentBody.append(line).append("\n");
            }
        }
        if (currentBody.length() > 0) {
            flushChunks(segments, currentHeading, currentBody.toString(), fileName, chunkSize, overlap);
        }
        return segments;
    }

    private void flushChunks(List<TextSegment> segments, String heading,
                              String body, String fileName, int chunkSize, int overlap) {
        String prefix = heading.isEmpty() ? "" : heading + "\n\n";
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + chunkSize, body.length());
            String chunkText = prefix + body.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
                meta.put("source", fileName);
                meta.put("section_heading", heading);
                segments.add(TextSegment.from(chunkText, meta));
            }
            if (end == body.length()) break;
            start = end - overlap;
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────────

    private oracle.sql.json.OracleJsonObject buildMetaJson(Map<String, Object> map) {
        oracle.sql.json.OracleJsonObject obj = new oracle.sql.json.OracleJsonFactory().createObject();
        if (map == null) return obj;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s)       obj.put(e.getKey(), s);
            else if (v instanceof Integer i) obj.put(e.getKey(), i);
            else if (v instanceof Double d)  obj.put(e.getKey(), d);
            else if (v instanceof Boolean b) obj.put(e.getKey(), b);
            else                             obj.put(e.getKey(), String.valueOf(v));
        }
        return obj;
    }
}
