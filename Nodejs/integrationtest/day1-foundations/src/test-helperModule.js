// ============================================================================
// test-helperModule.js - Quick Tests to Verify Everything Works
// ============================================================================
// Run: node test-helperModule.js
// ============================================================================

const {
  HTTPClient,
  ResilientHTTPClient,
  TokenManager,
  InMemoryStore,
  logger,
  ERROR_CODES,
} = require("./helperModule");

// ============================================================================
// TEST UTILITIES
// ============================================================================

let testsPassed = 0;
let testsFailed = 0;

function assert(condition, testName) {
  if (condition) {
    console.log(`✅ PASS: ${testName}`);
    testsPassed++;
  } else {
    console.error(`❌ FAIL: ${testName}`);
    testsFailed++;
  }
}

function printResults() {
  console.log("\n" + "=".repeat(60));
  console.log(`📊 Test Results: ${testsPassed} passed, ${testsFailed} failed`);
  console.log("=".repeat(60) + "\n");
}

// ============================================================================
// TEST SUITE
// ============================================================================

async function runTests() {
  console.log("\n🧪 Running Tests...\n");

  // Test 1: HTTPClient returns structured success response
  console.log("--- Test 1: HTTPClient Success Response ---");
  const client1 = new HTTPClient("https://jsonplaceholder.typicode.com");
  const result1 = await client1.get("/posts/1");

  assert(result1.success === true, "Response has success flag");
  assert(result1.status === 200, "Response has status code");
  assert(result1.data !== undefined, "Response has data");
  assert(result1.data.id === 1, "Data is correct");

  // Test 2: HTTPClient returns structured error response
  console.log("\n--- Test 2: HTTPClient Error Response ---");
  const client2 = new HTTPClient("https://jsonplaceholder.typicode.com");
  const result2 = await client2.get("/posts/999999");

  assert(result2.success === false, "Error response has success=false");
  assert(result2.error !== undefined, "Error response has error object");
  assert(result2.error.statusCode === 404, "Error has correct status code");
  assert(result2.error.type === "APIError", "Error has correct type");

  // Test 3: Timeout handling
  console.log("\n--- Test 3: Timeout Handling ---");
  const client3 = new HTTPClient("https://httpstat.us", {
    defaultTimeout: 2000,
  });
  const result3 = await client3.get("/200?sleep=5000", { timeout: 1000 });

  assert(result3.success === false, "Timeout returns error");
  assert(result3.error.type === "TimeoutError", "Error type is TimeoutError");
  assert(result3.error.statusCode === 504, "Timeout has 504 status");
  assert(
    result3.error.errorCode === ERROR_CODES.TIMEOUT_ERROR,
    "Has correct error code"
  );

  // Test 4: ResilientHTTPClient retry on 500
  console.log("\n--- Test 4: ResilientHTTPClient Retry ---");
  const client4 = new ResilientHTTPClient("https://httpstat.us", {
    maxRetries: 2,
    defaultTimeout: 3000,
  });
  const result4 = await client4.get("/500");

  assert(result4.success === false, "500 error returns error response");
  assert(result4.error.statusCode === 500, "Has 500 status code");

  // Test 5: Idempotency key generation
  console.log("\n--- Test 5: Idempotency Key Generation ---");
  const client5 = new ResilientHTTPClient(
    "https://jsonplaceholder.typicode.com"
  );
  const key1 = client5.generateIdempotencyKey();
  const key2 = client5.generateIdempotencyKey();

  assert(typeof key1 === "string", "Idempotency key is string");
  assert(key1.length > 0, "Idempotency key is not empty");
  assert(key1 !== key2, "Each key is unique");

  // Test 6: POST with idempotency key
  console.log("\n--- Test 6: POST with Idempotency Key ---");
  const client6 = new ResilientHTTPClient(
    "https://jsonplaceholder.typicode.com"
  );
  const result6 = await client6.post(
    "/posts",
    { title: "test", body: "test", userId: 1 },
    { idempotencyKey: "test-key-123" }
  );

  assert(result6.success === true, "POST succeeds");
  assert(result6.data !== undefined, "POST returns data");

  // Test 7: Token Manager
  console.log("\n--- Test 7: Token Manager ---");
  let tokenRefreshCalled = false;
  const mockGetToken = async () => {
    tokenRefreshCalled = true;
    return "test-token-123";
  };

  const tokenManager = new TokenManager(mockGetToken, new InMemoryStore());
  await tokenManager.refreshToken();
  const token = tokenManager.get();

  assert(tokenRefreshCalled === true, "Token refresh function was called");
  assert(token === "test-token-123", "Token was stored correctly");

  // Test 8: HTTP Methods (GET, POST, PUT, PATCH, DELETE)
  console.log("\n--- Test 8: HTTP Methods ---");
  const client8 = new HTTPClient("https://jsonplaceholder.typicode.com");

  const getResult = await client8.get("/posts/1");
  assert(getResult.success === true, "GET method works");

  const postResult = await client8.post("/posts", { title: "test" });
  assert(postResult.success === true, "POST method works");

  const putResult = await client8.put("/posts/1", { title: "updated" });
  assert(putResult.success === true, "PUT method works");

  const patchResult = await client8.patch("/posts/1", { title: "patched" });
  assert(patchResult.success === true, "PATCH method works");

  const deleteResult = await client8.delete("/posts/1");
  assert(deleteResult.success === true, "DELETE method works");

  // Test 9: Error.response.status (not error.status)
  console.log("\n--- Test 9: Correct Error Status Path ---");
  const client9 = new ResilientHTTPClient("https://httpstat.us");
  const mockError = {
    response: { status: 500 },
  };

  const isRetryable = client9.isRetryableError(mockError);
  assert(isRetryable === true, "Correctly identifies retryable status");

  const nonRetryableError = {
    response: { status: 400 },
  };
  const isNonRetryable = client9.isRetryableError(nonRetryableError);
  assert(isNonRetryable === false, "Correctly identifies non-retryable status");

  // Test 10: Logger format
  console.log("\n--- Test 10: Logger JSON Format ---");
  const originalLog = console.log;
  let loggedJSON = null;

  // Intercept console.log
  console.log = (msg) => {
    try {
      loggedJSON = JSON.parse(msg);
    } catch (e) {
      // Not JSON
    }
    originalLog(msg);
  };

  logger.setRequestId("test-req-123");
  logger.info("Test message", { key: "value" });

  // Restore console.log
  console.log = originalLog;

  assert(loggedJSON !== null, "Logger outputs valid JSON");
  assert(loggedJSON.level === "INFO", "Log has level field");
  assert(loggedJSON.requestId === "test-req-123", "Log has requestId");
  assert(loggedJSON.message === "Test message", "Log has message");
  assert(loggedJSON.timestamp !== undefined, "Log has timestamp");

  // Test 11: Circuit Breaker Stats
  console.log("\n--- Test 11: Circuit Breaker Stats ---");
  const client11 = new ResilientHTTPClient("https://httpstat.us");
  const stats = client11.getStats();

  assert(stats !== undefined, "Stats object exists");
  assert(stats.circuitBreakerStats !== undefined, "Has circuit breaker stats");
  assert(stats.circuitBreakerState !== undefined, "Has circuit breaker state");

  // Test 12: Custom headers in options
  console.log("\n--- Test 12: Custom Headers ---");
  const client12 = new HTTPClient("https://jsonplaceholder.typicode.com");
  const result12 = await client12.get("/posts/1", {
    headers: { "X-Custom-Header": "test-value" },
  });

  assert(result12.success === true, "Request with custom headers succeeds");

  printResults();
}

// ============================================================================
// RUN TESTS
// ============================================================================

runTests()
  .then(() => {
    if (testsFailed === 0) {
      console.log("🎉 All tests passed! Ready for Part 4.");
      process.exit(0);
    } else {
      console.error("⚠️  Some tests failed. Please review.");
      process.exit(1);
    }
  })
  .catch((error) => {
    console.error("💥 Test suite error:", error);
    process.exit(1);
  });
