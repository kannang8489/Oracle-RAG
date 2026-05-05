

package com.oracle.dev.jdbc.langchain4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;

public class OracleDb23aiLangChain4JOpenAiRag {

  private static final Logger logger = LoggerFactory
      .getLogger(OracleDb23aiLangChain4JOpenAiRag.class);

  public static void main(String[] args) throws SQLException {

    Path pdfFilePath = Paths.get(
        "src/main/resources/Juarez Barbosa Junior.pdf");

    // Read and extract text from the PDF
    String pdfContent = extractTextFromPdf(pdfFilePath);

    if (pdfContent != null && !pdfContent.isEmpty()) {

      // Initialize the embedding model
      OllamaEmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
          .baseUrl("http://localhost:11434")
          .modelName("nomic-embed-text").build();

      // Initialize the Oracle embedding store for the PDF
      // dropTableFirst=false to preserve data uploaded via the admin dashboard
      OracleEmbeddingStore embeddingStore = new OracleEmbeddingStore(
          OracleDBUtils.getPooledDataSource(), "ORACLE_VECTOR_STORE",
          Integer.valueOf("768"), Integer.valueOf("95"), OracleDistanceType.COSINE,
          OracleIndexType.IVF, Boolean.valueOf(true), Boolean.valueOf(true),
          Boolean.valueOf(false), Boolean.valueOf(true));

      // Initialize the Oracle embedding store for the GitHub Code
      OracleEmbeddingStore githubEmbeddingStore = new OracleEmbeddingStore(
          OracleDBUtils.getPooledDataSource(), "GITHUB_VECTOR_STORE_V",
          Integer.valueOf("768"), Integer.valueOf("95"), OracleDistanceType.COSINE,
          OracleIndexType.IVF, Boolean.valueOf(false), Boolean.valueOf(false),
          Boolean.valueOf(false), Boolean.valueOf(true));

      // Check if table is empty before ingesting
      boolean isTableEmpty = true;
      try (java.sql.Connection conn = OracleDBUtils.getPooledDataSource().getConnection();
           java.sql.Statement stmt = conn.createStatement();
           java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ORACLE_VECTOR_STORE")) {
        if (rs.next()) {
          isTableEmpty = rs.getInt(1) == 0;
        }
      } catch (Exception e) {
        logger.warn("Could not determine if table is empty. Proceeding with ingestion. Error: " + e.getMessage());
      }

      if (isTableEmpty) {
        logger.info("Vector store is empty. Ingesting the PDF content...");
        // Use LangChain4j's native ingestor and splitters for chunking
        // Attach filename as metadata so it appears in the admin dashboard
        Metadata pdfMetadata = new Metadata();
        pdfMetadata.put("source", pdfFilePath.getFileName().toString());
        Document document = new Document(pdfContent, pdfMetadata);
        dev.langchain4j.store.embedding.EmbeddingStoreIngestor ingestor = dev.langchain4j.store.embedding.EmbeddingStoreIngestor.builder()
            .documentSplitter(DocumentSplitters.recursive(500, 50)) // 500 tokens, 50 overlap
            .embeddingModel(embeddingModel)
            .embeddingStore(embeddingStore)
            .build();

        // Ingest the PDF document
        ingestor.ingest(document);
      } else {
        logger.info("Vector store already contains data. Skipping ingestion...");
      }

      // At last but not least RAG with OpenAI (gpt-4o) + Oracle Database 23ai
      RagWithOracleDatabase23ai(embeddingModel, embeddingStore, githubEmbeddingStore);

    } else {
      logger.info("No content extracted from the PDF.");
    }
    
    // Explicitly shut down the JVM to prevent UCP background threads from hanging Maven
    System.exit(0);

  }

  private static String extractTextFromPdf(Path filePath) {
    StringBuilder content = new StringBuilder();
    try {
      // Check if the file exists
      if (Files.exists(filePath)) {
        // Load the PDF document
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
          // Instantiate PDFTextStripper to extract text
          PDFTextStripper pdfStripper = new PDFTextStripper();
          // Extract text from the PDF document
          content.append(pdfStripper.getText(document));
        }
      } else {
        logger
            .info("The specified file does not exist: " + filePath.toString());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    logger.info("\n PDF CONTENT: \n" + content.toString());
    return content.toString();
  }

  private static void RagWithOracleDatabase23ai(
      OllamaEmbeddingModel embeddingModel,
      OracleEmbeddingStore pdfEmbeddingStore,
      OracleEmbeddingStore githubEmbeddingStore) {

    String name = "LoginPage.jsx what are the fields are avaiabel in the login page?";

    Embedding queryEmbedding = embeddingModel.embed(name).content();

    EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest
        .builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(15) // Retrieve top 15 most relevant chunks to improve code context
        .minScore(0.5)  // filter out chunks with less than 50% similarity
        .build();
        
    EmbeddingSearchResult<TextSegment> pdfResult = pdfEmbeddingStore
        .search(embeddingSearchRequest);
        
    EmbeddingSearchResult<TextSegment> githubResult = githubEmbeddingStore
        .search(embeddingSearchRequest);

    // Combine the text of all top matches
    String pdfInformation = pdfResult.matches().stream()
        .map(match -> match.embedded().text())
        .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
        
    // String githubInformation = githubResult.matches().stream()
    //     .map(match -> {
    //          logger.info("Found Github Match with score " + match.score() + ": " + match.embedded().text().substring(0, Math.min(100, match.embedded().text().length())));
    //          return match.embedded().text();
    //     })
    //     .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));

     String githubInformation = null;

    String information = "PDF Context:\n" + pdfInformation + "\n\nGitHub Code Context:\n" + githubInformation;

    Prompt prompt = PromptTemplate.from("""
        You are an expert AI assistant that translates code into clear business logic.
        Use ONLY the following context to answer the question.
        Your goal is to explain how the application behaves, including business rules and conditions for UI elements (like when a button is shown, hidden, or enabled).
        
        When answering questions about business logic or conditions:
        1. Break down the rules into clear, easy-to-understand bullet points.
        2. Explicitly state the conditions required (e.g., specific user roles, data states, or flags).
        3. Keep the explanation focused on the business behavior while being strictly factual based on the code.
        
        If the answer is not contained in the context, simply say "I don't have enough information to answer that."

        Context:
        {{information}}

        Question: Tell me about {{name}}?
        """).apply(Map.of("name", name, "information", information));

    // chat model
    ChatLanguageModel model = OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("llama3.1")
        .temperature(0.0)
        .timeout(java.time.Duration.ofMinutes(5)) // INCREASE TIMEOUT
        .build();

    String answer = model.generate(prompt.toUserMessage()).content().text();
    logger.info("\n ANSWER: \n" + answer);
  }

}
