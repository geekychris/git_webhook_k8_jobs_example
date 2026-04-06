package main

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"os"
	"strconv"
	"time"
)

type AnalysisResult struct {
	PRNumber       int      `json:"pr_number"`
	Repository     string   `json:"repository"`
	HeadSHA        string   `json:"head_sha"`
	Score          int      `json:"score"`
	Issues         []Issue  `json:"issues"`
	Suggestions    []string `json:"suggestions"`
	Approved       bool     `json:"approved"`
	AnalysisDurMs  int64    `json:"analysis_duration_ms"`
	AnalyzedAt     string   `json:"analyzed_at"`
}

type Issue struct {
	File     string `json:"file"`
	Line     int    `json:"line"`
	Severity string `json:"severity"`
	Message  string `json:"message"`
}

func main() {
	if len(os.Args) < 4 {
		fmt.Fprintf(os.Stderr, "Usage: analyzer <pr_number> <repository> <head_sha>\n")
		os.Exit(1)
	}

	prNumber, _ := strconv.Atoi(os.Args[1])
	repository := os.Args[2]
	headSHA := os.Args[3]

	fmt.Fprintf(os.Stderr, "[analyzer] Starting deep analysis for PR #%d in %s (sha: %s)\n", prNumber, repository, headSHA)

	start := time.Now()

	// Simulate multi-phase analysis with realistic timing
	phases := []struct {
		name     string
		duration time.Duration
	}{
		{"Cloning repository", 1 * time.Second},
		{"Parsing AST", 2 * time.Second},
		{"Running static analysis", 3 * time.Second},
		{"Checking security patterns", 2 * time.Second},
		{"Generating review", 1 * time.Second},
	}

	for _, phase := range phases {
		fmt.Fprintf(os.Stderr, "[analyzer] Phase: %s...\n", phase.name)
		time.Sleep(phase.duration)
	}

	// Generate deterministic-ish results based on PR number
	rng := rand.New(rand.NewSource(int64(prNumber)))

	score := 60 + rng.Intn(40) // 60-99

	issueFiles := []string{"src/main.go", "lib/utils.js", "app/models/user.rb", "pkg/handler.go", "src/service.ts"}
	severities := []string{"info", "warning", "error"}
	messages := []string{
		"Consider extracting this into a helper function",
		"Potential null pointer dereference",
		"Missing error handling",
		"Variable shadowing detected",
		"Unused import",
		"Cyclomatic complexity exceeds threshold",
		"Consider adding input validation",
		"SQL query could be vulnerable to injection",
	}

	numIssues := rng.Intn(5)
	issues := make([]Issue, numIssues)
	for i := 0; i < numIssues; i++ {
		issues[i] = Issue{
			File:     issueFiles[rng.Intn(len(issueFiles))],
			Line:     rng.Intn(200) + 1,
			Severity: severities[rng.Intn(len(severities))],
			Message:  messages[rng.Intn(len(messages))],
		}
	}

	suggestions := []string{
		"Add unit tests for the new functionality",
		"Consider breaking this PR into smaller changes",
		"Update the README with the new configuration options",
		"Add error handling for edge cases",
		"Performance: consider caching this database query",
	}
	numSuggestions := 1 + rng.Intn(3)

	result := AnalysisResult{
		PRNumber:      prNumber,
		Repository:    repository,
		HeadSHA:       headSHA,
		Score:         score,
		Issues:        issues,
		Suggestions:   suggestions[:numSuggestions],
		Approved:      score >= 75 && numIssues < 3,
		AnalysisDurMs: time.Since(start).Milliseconds(),
		AnalyzedAt:    time.Now().UTC().Format(time.RFC3339),
	}

	fmt.Fprintf(os.Stderr, "[analyzer] Analysis complete: score=%d issues=%d approved=%v\n", result.Score, len(result.Issues), result.Approved)

	// Output JSON result to stdout
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	enc.Encode(result)
}
