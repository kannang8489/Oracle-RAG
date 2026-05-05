package com.oracle.dev.jdbc.langchain4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminFileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(AdminFileUploadController.class);

    @Autowired
    private ChatService chatService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFiles(@RequestParam("files") MultipartFile[] files) {
        logger.info("Received file upload request with {} files", files.length);
        List<String> processedFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            
            try {
                String fileName = file.getOriginalFilename();
                logger.info("Processing file: " + fileName);
                
                chatService.ingestFile(file.getInputStream(), fileName);
                processedFiles.add(fileName);
            } catch (Exception e) {
                logger.error("Failed to process file: " + file.getOriginalFilename(), e);
                failedFiles.add(file.getOriginalFilename() + " (" + e.getMessage() + ")");
            }
        }

        if (failedFiles.isEmpty() && !processedFiles.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Successfully ingested " + processedFiles.size() + " files.",
                "processed", processedFiles
            ));
        } else if (!processedFiles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(Map.of(
                "success", true,
                "message", "Partially ingested files. Some failed.",
                "processed", processedFiles,
                "failed", failedFiles
            ));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "message", "Failed to ingest any files.",
                "failed", failedFiles
            ));
        }
    }

    /**
     * DELETE /api/admin/documents/all
     * Truncates the entire ORACLE_VECTOR_STORE table.
     */
    @DeleteMapping("/documents/all")
    public ResponseEntity<Map<String, Object>> clearAllDocuments() {
        logger.info("Received request to clear all indexed documents");
        try {
            chatService.clearAllDocuments();
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "All documents have been removed from the knowledge base."
            ));
        } catch (Exception e) {
            logger.error("Failed to clear all documents", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "message", "Failed to clear documents: " + e.getMessage()
            ));
        }
    }

    /**
     * DELETE /api/admin/document?name=filename.pdf
     * Removes all vector chunks for the given document from the store.
     */
    @DeleteMapping("/document")
    public ResponseEntity<Map<String, Object>> deleteDocument(@RequestParam("name") String documentName) {
        logger.info("Received delete request for document: {}", documentName);
        try {
            chatService.deleteDocument(documentName);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Document '" + documentName + "' removed from the knowledge base."
            ));
        } catch (Exception e) {
            logger.error("Failed to delete document: {}", documentName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "message", "Failed to delete document: " + e.getMessage()
            ));
        }
    }

    /**
     * POST /api/admin/reembed
     * Deletes all existing chunks for the uploaded file and re-ingests it fresh.
     */
    @PostMapping("/reembed")
    public ResponseEntity<Map<String, Object>> reEmbedDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "No file provided."
            ));
        }
        String fileName = file.getOriginalFilename();
        logger.info("Received re-embed request for: {}", fileName);
        try {
            chatService.reEmbedDocument(file.getInputStream(), fileName);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Document '" + fileName + "' has been re-embedded successfully."
            ));
        } catch (Exception e) {
            logger.error("Failed to re-embed document: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "message", "Failed to re-embed document: " + e.getMessage()
            ));
        }
    }
}
