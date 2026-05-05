package com.oracle.dev.jdbc.langchain4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @PostMapping("/session")
    public Map<String, String> createSession(@RequestBody(required = false) Map<String, String> request) {
        String title = request != null ? request.get("title") : "New Chat";
        String sessionId = chatHistoryService.createSession(title);
        return Map.of("sessionId", sessionId);
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> getSessions() {
        return chatHistoryService.getAllSessions();
    }

    @GetMapping("/session/{sessionId}")
    public List<Map<String, String>> getSessionMessages(@PathVariable("sessionId") String sessionId) {
        logger.info("Fetching messages for session: " + sessionId);
        return chatHistoryService.getMessages(sessionId);
    }

    @PostMapping
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String sessionId = request.get("sessionId");
        
        if (question == null || question.trim().isEmpty()) {
            return Map.of("error", "Question cannot be empty");
        }

        // Save user message to DB
        if (sessionId != null) {
            chatHistoryService.saveMessage(sessionId, "user", question);
        }

        try {
            Map<String, Object> result = chatService.askQuestionWithSources(question);
            String answer = (String) result.get("answer");
            
            // Save bot message to DB
            if (sessionId != null) {
                chatHistoryService.saveMessage(sessionId, "bot", answer);
            }
            
            return result;
        } catch (Exception e) {
            return Map.of("error", "An error occurred: " + e.getMessage());
        }
    }

    @GetMapping("/documents")
    public Map<String, Object> getIndexedDocuments() {
        try {
            List<Map<String, Object>> documents = chatService.getIndexedDocuments();
            return Map.of("documents", documents, "total", documents.size());
        } catch (Exception e) {
            logger.error("Error fetching indexed documents: " + e.getMessage());
            return Map.of("error", "Failed to fetch documents", "documents", List.of());
        }
    }

    @GetMapping("/debug")
    public Map<String, Object> debugSearch(@RequestParam("q") String q) {
        return chatService.debugSearch(q);
    }
}
