package com.oracle.dev.jdbc.langchain4j;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private final String expectedUsername = dotenv.get("ADMIN_USERNAME", "admin"); // Default to admin if missing
    private final String expectedPassword = dotenv.get("ADMIN_PASSWORD", "admin"); // Default if missing

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (expectedUsername.equals(username) && expectedPassword.equals(password)) {
            return Map.of("success", true, "message", "Welcome Admin!");
        } else {
            return Map.of("success", false, "error", "Invalid credentials");
        }
    }
}
