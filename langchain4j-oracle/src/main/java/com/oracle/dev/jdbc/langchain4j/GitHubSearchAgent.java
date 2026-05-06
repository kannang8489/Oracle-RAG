package com.oracle.dev.jdbc.langchain4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * GitHubSearchAgent — dedicated agent for GitHub repository ingestion and search.
 *
 * Responsibilities:
 *   1. Maintain the GITHUB_REPOS registry table (admin-registered repos)
 *   2. Clone / pull repos locally and chunk source files
 *   3. Embed chunks using Oracle 23ai DBMS_VECTOR.UTL_TO_EMBEDDING
 *   4. Store vectors in GITHUB_VECTOR_STORE table
 *   5. Expose a search() method used by ChatService during RAG queries
 *
 * Required .env variables (optional — only needed if using GitHub repos):
 *   GITHUB_TOKEN      — Personal Access Token for private repos (optional)
 *   GITHUB_CLONE_DIR  — Local directory to clone repos into
 *                       (default: System temp dir / oracle-rag-repos)
 *
 * File types indexed: .java .js .jsx .ts .tsx .py .go .cs .cpp .c .h
 *                     .md .txt .yaml .yml .json .xml .sql .sh .properties
 */
@Service
public class GitHubSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(GitHubSearchAgent.class);

    private static final String ORACLE_EMBED_MODEL = "ALL_MINILM_L6_V2";
    private static final int    VECTOR_DIM         = 384;
    private static final int    CHUNK_SIZE         = 1200;  // chars per chunk
    private static final int    CHUNK_OVERLAP      = 200;

    // File extensions to index from repos
    private static final List<String> INDEXED_EXTENSIONS = List.of(
        ".java", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".cs",
        ".cpp", ".c", ".h", ".md", ".txt", ".yaml", ".yml",
        ".json", ".xml", ".sql", ".sh", ".properties"
    );

    private final io.github.cdimascio.dotenv.Dotenv dotenv =
        io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();

    private String cloneBaseDir;
    private String githubToken;

    // ── Exposed record for search results ────────────────────────────────────────
    public record ScoredChunk(double score, String storeType, String content,
                               String source, String heading) {}

    // ── Initialisation ───────────────────────────────────────────────────────────

    @PostConstruct
    public void init() throws SQLException {
        githubToken  = dotenv.get("GITHUB_TOKEN", "");
        cloneBaseDir = dotenv.get("GITHUB_CLONE_DIR",
            System.getProperty("java.io.tmpdir") + "/oracle-rag-repos");

        new File(cloneBaseDir).mkdirs();
        ensureTables();
        logger.info("GitHubSearchAgent ready — clone dir: {}", cloneBaseDir);
    }

    private void ensureTables() throws SQLException {
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             Statement stmt = conn.createStatement()) {

            // Registry of repos registered by admin
            stmt.executeUpdate("""
                BEGIN
                  EXECUTE IMMEDIATE 'CREATE TABLE GITHUB_REPOS (
                    id          VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
                    repo_url    VARCHAR2(500) NOT NULL,
                    repo_name   VARCHAR2(255),
                    branch      VARCHAR2(100) DEFAULT ''main'',
                    status      VARCHAR2(50)  DEFAULT ''PENDING'',
                    chunk_count NUMBER        DEFAULT 0,
                    added_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
                    indexed_at  TIMESTAMP
                  )';
                EXCEPTION WHEN OTHERS THEN
                  IF SQLCODE != -955 THEN RAISE; END IF;
                END;
                """);

            // Vector store for GitHub code chunks
            stmt.executeUpdate("""
                BEGIN
                  EXECUTE IMMEDIATE 'CREATE TABLE GITHUB_VECTOR_STORE (
                    id        VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
                    repo_url  VARCHAR2(500),
                    file_path VARCHAR2(1000),
                    content   CLOB,
                    metadata  JSON,
                    embedding VECTOR(%d, FLOAT32)
                  )';
                EXCEPTION WHEN OTHERS THEN
                  IF SQLCODE != -955 THEN RAISE; END IF;
                END;
                """.formatted(VECTOR_DIM));

            // IVF index for fast ANN search
            try {
                stmt.executeUpdate("""
                    CREATE VECTOR INDEX IF NOT EXISTS idx_gh_embedding
                    ON GITHUB_VECTOR_STORE (embedding)
                    ORGANIZATION NEIGHBOR PARTITIONS
                    DISTANCE COSINE
                    WITH TARGET ACCURACY 95
                    PARAMETERS (TYPE IVF, NEIGHBOR PARTITIONS 10)
                    """);
            } catch (SQLException e) {
                logger.debug("GitHub vector index creation skipped: {}", e.getMessage());
            }

            logger.info("GitHub tables ready (GITHUB_REPOS, GITHUB_VECTOR_STORE)");
        }
    }

    // ── Admin: register a repo ────────────────────────────────────────────────────

    /**
     * Registers a GitHub repo URL for indexing.
     * Triggers async ingestion immediately after registration.
     */
    public Map<String, Object> registerRepo(String repoUrl, String branch) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return Map.of("success", false, "message", "Repository URL is required.");
        }
        branch = (branch == null || branch.isBlank()) ? "main" : branch;

        // Derive a short name from the URL
        String repoName = repoUrl.replaceAll(".*github\\.com/", "").replaceAll("\\.git$", "");

        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
            // Check if already registered
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM GITHUB_REPOS WHERE repo_url = ?")) {
                ps.setString(1, repoUrl);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Map.of("success", false,
                            "message", "Repository already registered: " + repoUrl);
                    }
                }
            }

            String id = UUID.randomUUID().toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO GITHUB_REPOS (id, repo_url, repo_name, branch, status) VALUES (?,?,?,?,'PENDING')")) {
                ps.setString(1, id);
                ps.setString(2, repoUrl);
                ps.setString(3, repoName);
                ps.setString(4, branch);
                ps.executeUpdate();
            }

            logger.info("Registered repo: {} (branch: {})", repoUrl, branch);

            // Trigger ingestion in background thread
            final String finalBranch = branch;
            Thread.ofVirtual().start(() -> ingestRepo(id, repoUrl, repoName, finalBranch));

            return Map.of("success", true,
                "message", "Repository registered and indexing started: " + repoName,
                "repoId", id);

        } catch (SQLException e) {
            logger.error("Failed to register repo: {}", e.getMessage(), e);
            return Map.of("success", false, "message", "Database error: " + e.getMessage());
        }
    }

    /**
     * Returns all registered repos with their status and chunk counts.
     */
    public List<Map<String, Object>> listRepos() {
        List<Map<String, Object>> repos = new ArrayList<>();
        String sql = "SELECT id, repo_url, repo_name, branch, status, chunk_count, added_at, indexed_at " +
                     "FROM GITHUB_REPOS ORDER BY added_at DESC";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                repos.add(Map.of(
                    "id",         rs.getString("id"),
                    "repoUrl",    rs.getString("repo_url"),
                    "repoName",   rs.getString("repo_name"),
                    "branch",     rs.getString("branch"),
                    "status",     rs.getString("status"),
                    "chunkCount", rs.getInt("chunk_count"),
                    "addedAt",    String.valueOf(rs.getTimestamp("added_at")),
                    "indexedAt",  rs.getTimestamp("indexed_at") != null
                                    ? String.valueOf(rs.getTimestamp("indexed_at")) : ""
                ));
            }
        } catch (SQLException e) {
            logger.error("Failed to list repos: {}", e.getMessage());
        }
        return repos;
    }

    /**
     * Removes a repo and all its vector chunks.
     */
    public Map<String, Object> removeRepo(String repoId) {
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
            // Get repo URL first
            String repoUrl = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT repo_url FROM GITHUB_REPOS WHERE id = ?")) {
                ps.setString(1, repoId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) repoUrl = rs.getString("repo_url");
                }
            }
            if (repoUrl == null) {
                return Map.of("success", false, "message", "Repository not found.");
            }

            // Delete vectors
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM GITHUB_VECTOR_STORE WHERE repo_url = ?")) {
                ps.setString(1, repoUrl);
                int deleted = ps.executeUpdate();
                logger.info("Deleted {} chunks for repo: {}", deleted, repoUrl);
            }

            // Delete registry entry
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM GITHUB_REPOS WHERE id = ?")) {
                ps.setString(1, repoId);
                ps.executeUpdate();
            }

            return Map.of("success", true, "message", "Repository removed successfully.");
        } catch (SQLException e) {
            logger.error("Failed to remove repo: {}", e.getMessage(), e);
            return Map.of("success", false, "message", "Database error: " + e.getMessage());
        }
    }

    /**
     * Re-indexes an existing repo (re-clones and re-embeds).
     */
    public Map<String, Object> reindexRepo(String repoId) {
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
            String repoUrl = null, repoName = null, branch = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT repo_url, repo_name, branch FROM GITHUB_REPOS WHERE id = ?")) {
                ps.setString(1, repoId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        repoUrl  = rs.getString("repo_url");
                        repoName = rs.getString("repo_name");
                        branch   = rs.getString("branch");
                    }
                }
            }
            if (repoUrl == null) {
                return Map.of("success", false, "message", "Repository not found.");
            }

            // Delete existing vectors
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM GITHUB_VECTOR_STORE WHERE repo_url = ?")) {
                ps.setString(1, repoUrl);
                ps.executeUpdate();
            }

            // Update status
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE GITHUB_REPOS SET status='PENDING', chunk_count=0 WHERE id=?")) {
                ps.setString(1, repoId);
                ps.executeUpdate();
            }

            final String fUrl = repoUrl, fName = repoName, fBranch = branch, fId = repoId;
            Thread.ofVirtual().start(() -> ingestRepo(fId, fUrl, fName, fBranch));

            return Map.of("success", true, "message", "Re-indexing started for: " + repoName);
        } catch (SQLException e) {
            return Map.of("success", false, "message", "Database error: " + e.getMessage());
        }
    }

    // ── Ingestion pipeline ───────────────────────────────────────────────────────

    private void ingestRepo(String repoId, String repoUrl, String repoName, String branch) {
        logger.info("[GitHubAgent] Starting ingestion: {} (branch: {})", repoUrl, branch);
        updateRepoStatus(repoId, "INDEXING", 0);

        Path repoDir = Path.of(cloneBaseDir, repoName.replace("/", "_"));

        try {
            // Clone or pull
            if (Files.exists(repoDir.resolve(".git"))) {
                runGit(repoDir.toFile(), "git", "pull", "origin", branch);
                logger.info("[GitHubAgent] Pulled latest: {}", repoName);
            } else {
                Files.createDirectories(repoDir);
                String cloneUrl = buildCloneUrl(repoUrl);
                runGit(repoDir.getParent().toFile(), "git", "clone",
                    "--branch", branch, "--depth", "1", cloneUrl, repoDir.toString());
                logger.info("[GitHubAgent] Cloned: {}", repoName);
            }

            // Walk files and ingest
            int totalChunks = 0;
            try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource()) {
                conn.setAutoCommit(false);
                try (Stream<Path> files = Files.walk(repoDir)) {
                    List<Path> codeFiles = files
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("\\.git\\"))
                        .filter(p -> INDEXED_EXTENSIONS.stream()
                            .anyMatch(ext -> p.toString().toLowerCase().endsWith(ext)))
                        .collect(java.util.stream.Collectors.toList());

                    logger.info("[GitHubAgent] {} indexable files found in {}", codeFiles.size(), repoName);

                    for (Path file : codeFiles) {
                        try {
                            String relPath = repoDir.relativize(file).toString().replace("\\", "/");
                            String fileContent = Files.readString(file, StandardCharsets.UTF_8);
                            if (fileContent.isBlank()) continue;

                            List<String> chunks = chunkText(fileContent);
                            for (String chunk : chunks) {
                                storeChunk(conn, repoUrl, relPath, chunk);
                                totalChunks++;
                            }
                        } catch (Exception e) {
                            logger.warn("[GitHubAgent] Skipping file {}: {}", file, e.getMessage());
                        }
                    }
                }
                conn.commit();
            }

            updateRepoStatus(repoId, "INDEXED", totalChunks);
            logger.info("[GitHubAgent] Ingestion complete: {} — {} chunks", repoName, totalChunks);

        } catch (Exception e) {
            logger.error("[GitHubAgent] Ingestion failed for {}: {}", repoName, e.getMessage(), e);
            updateRepoStatus(repoId, "FAILED", 0);
        }
    }

    private void storeChunk(Connection conn, String repoUrl, String filePath, String chunk) throws SQLException {
        float[] vec = embedWithOracle(conn, chunk);
        oracle.sql.VECTOR oraVec = oracle.sql.VECTOR.ofFloat32Values(vec);

        oracle.sql.json.OracleJsonObject meta = new oracle.sql.json.OracleJsonFactory().createObject();
        meta.put("source", filePath);
        meta.put("repo_url", repoUrl);
        meta.put("store_type", "github");

        String sql = """
            INSERT INTO GITHUB_VECTOR_STORE (id, repo_url, file_path, content, metadata, embedding)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, repoUrl);
            ps.setString(3, filePath);
            ps.setString(4, chunk);
            ps.setObject(5, meta, oracle.jdbc.OracleType.JSON.getVendorTypeNumber());
            ps.setObject(6, oraVec, oracle.jdbc.OracleType.VECTOR.getVendorTypeNumber());
            ps.executeUpdate();
        }
    }

    // ── Search (called by ChatService) ───────────────────────────────────────────

    /**
     * Searches GITHUB_VECTOR_STORE using Oracle VECTOR_DISTANCE.
     * Returns scored chunks ready for RAG context assembly.
     */
    public List<ScoredChunk> search(Connection conn, oracle.sql.VECTOR queryVec, int maxResults) {
        List<ScoredChunk> results = new ArrayList<>();
        String sql = """
            SELECT content, file_path,
                   JSON_VALUE(metadata, '$.repo_url') AS repo_url,
                   1 - VECTOR_DISTANCE(embedding, ?, COSINE) AS score
            FROM GITHUB_VECTOR_STORE
            ORDER BY score DESC
            FETCH APPROXIMATE FIRST ? ROWS ONLY WITH TARGET ACCURACY 95
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, queryVec, oracle.jdbc.OracleType.VECTOR.getVendorTypeNumber());
            ps.setInt(2, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double score = rs.getDouble("score");
                    if (score >= 0.3) {
                        results.add(new ScoredChunk(
                            score, "github",
                            rs.getString("content"),
                            rs.getString("file_path"),
                            null
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            // Table may not exist yet or be empty — not fatal
            logger.warn("[GitHubAgent] Search failed (table may be empty): {}", e.getMessage());
        }
        return results;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private float[] embedWithOracle(Connection conn, String text) throws SQLException {
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
                    double[] vec = rs.getObject(1, double[].class);
                    float[] result = new float[vec.length];
                    for (int i = 0; i < vec.length; i++) result[i] = (float) vec[i];
                    return result;
                }
            }
        }
        throw new SQLException("DBMS_VECTOR.UTL_TO_EMBEDDING returned no result");
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) chunks.add(chunk);
            if (end == text.length()) break;
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }

    private String buildCloneUrl(String repoUrl) {
        if (!githubToken.isBlank() && repoUrl.startsWith("https://github.com/")) {
            return repoUrl.replace("https://github.com/",
                "https://" + githubToken + "@github.com/");
        }
        return repoUrl;
    }

    private void runGit(File workDir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            br.lines().forEach(line -> logger.debug("[git] {}", line));
        }
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("git command failed with exit code " + exit);
    }

    private void updateRepoStatus(String repoId, String status, int chunkCount) {
        String sql = "UPDATE GITHUB_REPOS SET status=?, chunk_count=?, indexed_at=CURRENT_TIMESTAMP WHERE id=?";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, chunkCount);
            ps.setString(3, repoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update repo status: {}", e.getMessage());
        }
    }
}
