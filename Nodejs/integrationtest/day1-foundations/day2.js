const {
  APIError,
  TimeoutError,
  ValidationError,
  HTTPClient,
} = require("./base");

// ============================================================================
// TASK 4: MULTIPLE API CALLS - Complete these TODOs
// ============================================================================

/**
 * Call multiple providers in parallel and handle partial failures
 *
 * @param {Array} providers - Array of { name, url, timeout }
 * @param {object} params - Query parameters
 * @param {Logger} logger - Logger instance from Day 1
 * @returns {Promise<object>} { success: [...], failed: [...] }
 */
async function callMultipleProviders(providers, params, logger) {
  // TODO: Log the start of parallel calls
  // TODO: Use Promise.allSettled to call all providers in parallel
  // Hint: Use providers.map() and callSingleProvider()
  // TODO: Separate results into success and failed arrays
  // Hint: Check result.status === 'fulfilled' and result.value.status
  // TODO: Log completion with counts
  // TODO: Return { success, failed }
  const promises = [];

  //   { name: "Fast", url: "https://httpbin.org/delay/1", timeout: 5000 },
  for (const provider of providers) {
    const client = new HTTPClient();
    promises.push(client.get(provider.url, { timeout: provider.timeout }));
  }
  const res = await Promise.allSettled(promises);
  return {
    success: res.filter((r) => r.status == "fulfilled"),
    failed: res.filter((r) => r.status != "fulfilled"),
  };
}

/**
 * Call a single provider with error handling
 *
 * @param {object} provider - { name, url, timeout }
 * @param {object} params - Query parameters
 * @param {Logger} logger - Logger instance
 * @returns {Promise<object>} { provider, status, data/error, responseTime }
 */
async function callSingleProvider(provider, params, logger) {
  // TODO: Start timer with Date.now()
  // TODO: Log the attempt
  // TODO: Make axios GET request with timeout
  // TODO: Handle success - return { provider, status: 'success', data, responseTime }
  // TODO: Handle errors - check if timeout/network/http error
  // TODO: Return { provider, status: 'failed', error, responseTime }

  return await testAPIClient();
}

// ============================================================================
// TASK 5: VALIDATION - Complete these TODOs
// ============================================================================

/**
 * Validate hotel search parameters
 * Middleware function for Express
 */

async function testAPIClient() {
  console.log("\n========== Testing APIClient ==========\n");

  // Initialize client
  const client = new HTTPClient("https://jsonplaceholder.typicode.com");

  try {
    // Test 1: GET request
    console.log("Test 1: GET Request");
    const getResult = await client.get("/posts/1");
    console.log("✅ GET Success:", getResult.data.title);
    console.log("");

    // Test 2: GET with query params
    console.log("Test 2: GET with Query Parameters");
    const listResult = await client.get("/posts", { userId: 1 });
    console.log(`✅ GET Success: Retrieved ${listResult.data.length} posts`);
    console.log("");

    // Test 3: POST request
    console.log("Test 3: POST Request");
    const postResult = await client.post("/posts", {
      title: "Test Post",
      body: "This is a test",
      userId: 1,
    });
    console.log("✅ POST Success: Created post with ID:", postResult.data.id);
    console.log("");

    // Test 4: PUT request
    console.log("Test 4: PUT Request");
    const putResult = await client.put("/posts/1", {
      id: 1,
      title: "Updated Title",
      body: "Updated body",
      userId: 1,
    });
    console.log("✅ PUT Success:", putResult.data.title);
    console.log("");

    // Test 5: PATCH request
    console.log("Test 5: PATCH Request");
    const patchResult = await client.patch("/posts/1", {
      title: "Partially Updated Title",
    });
    console.log("✅ PATCH Success:", patchResult.data.title);
    console.log("");

    // Test 6: DELETE request
    console.log("Test 6: DELETE Request");
    const deleteResult = await client.delete("/posts/1");
    console.log("✅ DELETE Success: Status", deleteResult.status);
    console.log("");

    // Test 8: Check retryable errors
    console.log("Test 8: Retryable Error Check");
    const error429 = { status: 429 };
    const error400 = { status: 400 };
    console.log("429 is retryable?", client.isRetryableError(error429)); // true
    console.log("400 is retryable?", client.isRetryableError(error400)); // false
    console.log("");

    console.log("========== All Tests Passed! ==========\n");
  } catch (error) {
    console.error("Test failed:", error);
  }
}

function validateHotelSearch(req, res, next) {
  const errors = [];
  const { city, checkIn, checkOut, guests } = req.query;

  // TODO: Validate city (required, non-empty, min 2 chars)

  // TODO: Validate checkIn (required, valid date format, not past)

  // TODO: Validate checkOut (required, valid date format, after checkIn)

  // TODO: Validate guests (required, number, 1-10)

  // TODO: If errors.length > 0, throw ValidationError

  // TODO: If no errors, call next()
}

/**
 * Check if string is valid date (YYYY-MM-DD)
 */
function isValidDate(dateString) {
  // TODO: Check format with regex /^\d{4}-\d{2}-\d{2}$/
  // TODO: Check if it's a real date (not 2024-13-45)
  // Hint: Use new Date() and check if valid
  // TODO: Return true/false
}

/**
 * Check if date1 is after date2
 */
function isDateAfter(date1, date2) {
  // TODO: Compare dates
  // Hint: new Date(date1) > new Date(date2)
}

/**
 * Check if date is in the past
 */
function isPastDate(dateString) {
  // TODO: Compare with today
  // Hint: Compare with new Date() with time set to 00:00:00
}

// ============================================================================
// TASK 6: HTTP REDIRECTS - Complete these TODOs
// ============================================================================

/**
 * Make request and follow redirects with tracking
 *
 * @param {string} url - Initial URL
 * @param {object} options - { maxRedirects, timeout }
 * @returns {Promise<object>} { data, redirectChain, finalUrl, redirectCount }
 */
async function makeRequestWithRedirects(url, options = {}) {
  const maxRedirects = options.maxRedirects || 5;
  const timeout = options.timeout || 5000;
  const redirectChain = [];

  const client = new HTTPClient("", maxRedirects, timeout);
  const data = await client.get(url, options);

  return data;
  // TODO: Make axios request with maxRedirects and timeout
  // TODO: Configure beforeRedirect to track chain
  // TODO: Check for redirect loops using hasRedirectLoop()
  // TODO: Get final URL from response.request.res.responseUrl
  // TODO: Return { data, redirectChain, finalUrl, redirectCount }
}

/**
 * Check if URL already exists in redirect chain (loop detection)
 */
function hasRedirectLoop(redirectChain, newUrl) {
  // TODO: Check if newUrl is already in redirectChain
  // Hint: redirectChain.includes(newUrl)
}

// ============================================================================
// EXPORTS
// ============================================================================

module.exports = {
  callMultipleProviders,
  callSingleProvider,
  validateHotelSearch,
  isValidDate,
  isDateAfter,
  isPastDate,
  makeRequestWithRedirects,
  hasRedirectLoop,
};
