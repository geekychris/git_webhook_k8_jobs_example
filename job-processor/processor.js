const { execFile } = require('child_process');
const { createClient } = require('redis');
const path = require('path');

const JOB_ID = process.env.JOB_ID || 'unknown';
const JOB_NAME = process.env.JOB_NAME || 'unknown';
const PR_NUMBER = process.env.PR_NUMBER || '0';
const PR_TITLE = process.env.PR_TITLE || '';
const REPOSITORY = process.env.REPOSITORY || 'unknown';
const HEAD_SHA = process.env.HEAD_SHA || 'unknown';
const ACTION = process.env.ACTION || 'opened';
const REDIS_HOST = process.env.REDIS_HOST || 'redis';
const REDIS_PORT = process.env.REDIS_PORT || '6379';

const ANALYZER_PATH = path.join(__dirname, 'analyzer');

async function main() {
    console.log(`[job] Starting PR review job: ${JOB_NAME}`);
    console.log(`[job] PR #${PR_NUMBER} "${PR_TITLE}" in ${REPOSITORY} (${ACTION})`);
    console.log(`[job] Head SHA: ${HEAD_SHA}`);

    // Connect to Redis to report progress
    const redis = createClient({
        url: `redis://${REDIS_HOST}:${REDIS_PORT}`
    });

    try {
        await redis.connect();
        console.log('[job] Connected to Redis');

        // Report starting
        await redis.hSet('jobs:active', JOB_ID, JSON.stringify({
            jobId: JOB_ID,
            jobName: JOB_NAME,
            repository: REPOSITORY,
            prNumber: parseInt(PR_NUMBER),
            status: 'RUNNING',
            phase: 'calling-analyzer',
            createdAt: new Date().toISOString()
        }));

        // Call the Go analyzer binary
        console.log('[job] Invoking Go analyzer...');
        const analysisResult = await runAnalyzer();

        console.log('[job] Analysis result:');
        console.log(JSON.stringify(analysisResult, null, 2));

        // Post-processing in Node
        console.log('[job] Running post-processing...');
        const review = generateReview(analysisResult);
        console.log('[job] Review generated:', JSON.stringify(review, null, 2));

        // Store result in Redis
        const resultPayload = {
            jobId: JOB_ID,
            jobName: JOB_NAME,
            repository: REPOSITORY,
            prNumber: parseInt(PR_NUMBER),
            status: 'SUCCEEDED',
            analysis: analysisResult,
            review: review,
            completedAt: new Date().toISOString()
        };

        // Write result to a dedicated key for this job
        await redis.set(`job:result:${JOB_ID}`, JSON.stringify(resultPayload), { EX: 3600 });

        console.log(`[job] Job completed successfully: ${JOB_NAME}`);
    } catch (err) {
        console.error(`[job] Job failed: ${err.message}`);
        try {
            await redis.set(`job:result:${JOB_ID}`, JSON.stringify({
                jobId: JOB_ID,
                status: 'FAILED',
                error: err.message,
                completedAt: new Date().toISOString()
            }), { EX: 3600 });
        } catch (redisErr) {
            console.error(`[job] Failed to report error to Redis: ${redisErr.message}`);
        }
        process.exit(1);
    } finally {
        await redis.quit();
    }
}

function runAnalyzer() {
    return new Promise((resolve, reject) => {
        execFile(ANALYZER_PATH, [PR_NUMBER, REPOSITORY, HEAD_SHA], {
            timeout: 60000,
            maxBuffer: 1024 * 1024
        }, (err, stdout, stderr) => {
            // Log stderr (progress output from Go)
            if (stderr) {
                stderr.split('\n').filter(l => l).forEach(line => console.log(line));
            }

            if (err) {
                reject(new Error(`Analyzer failed: ${err.message}`));
                return;
            }

            try {
                resolve(JSON.parse(stdout));
            } catch (parseErr) {
                reject(new Error(`Failed to parse analyzer output: ${parseErr.message}`));
            }
        });
    });
}

function generateReview(analysis) {
    const comments = [];

    // Generate review comments from issues
    for (const issue of (analysis.issues || [])) {
        comments.push({
            path: issue.file,
            line: issue.line,
            body: `**${issue.severity.toUpperCase()}**: ${issue.message}`,
        });
    }

    // Overall review
    let verdict;
    if (analysis.approved) {
        verdict = 'APPROVE';
    } else if (analysis.score >= 60) {
        verdict = 'REQUEST_CHANGES';
    } else {
        verdict = 'REQUEST_CHANGES';
    }

    return {
        verdict: verdict,
        score: analysis.score,
        summary: `Automated review for PR #${PR_NUMBER}: Score ${analysis.score}/100, ${(analysis.issues || []).length} issues found.`,
        comments: comments,
        suggestions: analysis.suggestions || [],
    };
}

main();
