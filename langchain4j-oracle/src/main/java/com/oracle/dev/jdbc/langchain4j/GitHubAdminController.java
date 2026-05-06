package com.oracle.dev.jdbc.langchain4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for GitHub repository management.
 *
 * Endpoints:
 *   GET    /api/admin/repos              — list all registered repos
 *   POST   /api/admin/repos              — register a new repo for indexing
 *   DELETE /api/admin/repos/{id}         — remove a repo and its vectors
 *   POST   /api/admin/repos/{id}/reindex — re-clone and re-embed a repo
 */
@RestController
@RequestMapping("/api/admin/repos")
@CrossOrigin(origins = "*")
public class GitHubAdminController {

    @Autowired
    private GitHubSearchAgent gitHubSearchAgent;

    /** List all registered GitHub repositories and their indexing status. */
    @GetMapping
    public List<Map<String, Object>> listRepos() {
        return gitHubSearchAgent.listRepos();
    }

    /**
     * Register a new GitHub repository.
     * Body: { "repoUrl": "https://github.com/owner/repo", "branch": "main" }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> registerRepo(
            @RequestBody Map<String, String> body) {
        String repoUrl = body.get("repoUrl");
        String branch  = body.getOrDefault("branch", "main");
        Map<String, Object> result = gitHubSearchAgent.registerRepo(repoUrl, branch);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        return success
            ? ResponseEntity.ok(result)
            : ResponseEntity.badRequest().body(result);
    }

    /** Remove a registered repository and all its vector embeddings. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> removeRepo(@PathVariable("id") String repoId) {
        Map<String, Object> result = gitHubSearchAgent.removeRepo(repoId);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        return success
            ? ResponseEntity.ok(result)
            : ResponseEntity.badRequest().body(result);
    }

    /** Re-index an existing repository (re-clone + re-embed). */
    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindexRepo(@PathVariable("id") String repoId) {
        Map<String, Object> result = gitHubSearchAgent.reindexRepo(repoId);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        return success
            ? ResponseEntity.ok(result)
            : ResponseEntity.badRequest().body(result);
    }
}
