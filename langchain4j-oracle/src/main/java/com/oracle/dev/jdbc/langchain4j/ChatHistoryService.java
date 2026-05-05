package com.oracle.dev.jdbc.langchain4j;

import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

@Service
public class ChatHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatHistoryService.class);

    @PostConstruct
    public void init() {
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             Statement stmt = conn.createStatement()) {
            
            // Create chat_sessions table
            try {
                stmt.execute("CREATE TABLE chat_sessions (" +
                             "session_id VARCHAR2(36) PRIMARY KEY, " +
                             "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                             "title VARCHAR2(255))");
                logger.info("Created chat_sessions table.");
            } catch (Exception e) {
                if (!e.getMessage().contains("ORA-00955")) { // Name is already used by an existing object
                    logger.error("Error creating chat_sessions table", e);
                }
            }

            // Create chat_messages table
            try {
                stmt.execute("CREATE TABLE chat_messages (" +
                             "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                             "session_id VARCHAR2(36) REFERENCES chat_sessions(session_id) ON DELETE CASCADE, " +
                             "role VARCHAR2(50), " +
                             "content CLOB, " +
                             "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                logger.info("Created chat_messages table.");
            } catch (Exception e) {
                if (!e.getMessage().contains("ORA-00955")) {
                    logger.error("Error creating chat_messages table", e);
                }
            }

        } catch (Exception e) {
            logger.error("Error initializing chat history tables", e);
        }
    }

    public String createSession(String title) {
        String sessionId = UUID.randomUUID().toString();
        String sql = "INSERT INTO chat_sessions (session_id, title) VALUES (?, ?)";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, title == null ? "New Chat" : title);
            pstmt.executeUpdate();
            return sessionId;
        } catch (Exception e) {
            logger.error("Error creating session", e);
            return null;
        }
    }

    public void saveMessage(String sessionId, String role, String content) {
        String sql = "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, role);
            pstmt.setString(3, content);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Error saving message", e);
        }
    }

    public List<Map<String, Object>> getAllSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        String sql = "SELECT session_id, title, created_at FROM chat_sessions ORDER BY created_at DESC";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> session = new HashMap<>();
                session.put("sessionId", rs.getString("session_id"));
                session.put("title", rs.getString("title"));
                session.put("createdAt", rs.getTimestamp("created_at"));
                sessions.add(session);
            }
        } catch (Exception e) {
            logger.error("Error fetching sessions", e);
        }
        return sessions;
    }

    public List<Map<String, String>> getMessages(String sessionId) {
        List<Map<String, String>> messages = new ArrayList<>();
        String sql = "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY id ASC";
        try (Connection conn = OracleDBUtils.getConnectionFromPooledDataSource();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> message = new HashMap<>();
                    message.put("role", rs.getString("role"));
                    message.put("content", rs.getString("content"));
                    messages.add(message);
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching messages", e);
        }
        return messages;
    }
}
