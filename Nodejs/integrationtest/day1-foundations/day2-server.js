// ============================================================================
// DAY 2: SERVER - Test your new implementations
// ============================================================================
// This imports from BOTH Day 1 (base.js) and Day 2 (day2.js)
// Complete Day 2 TODOs, then run this to test
// ============================================================================

const express = require("express");

// Import Day 1 code (base implementations)
const { Logger, ValidationError, APIError, HTTPClient } = require("./base");

// Import Day 2 code (new implementations)
const {
  callMultipleProviders,
  validateHotelSearch,
  makeRequestWithRedirects,
  callSingleProvider,
} = require("./day2");

const app = express();
app.use(express.json());

// ============================================================================
// MIDDLEWARE - Reuses Day 1 Logger
// ============================================================================

app.use((req, res, next) => {
  req.id = `req-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  req.logger = new Logger();
  req.logger.setRequestId(req.id);
  next();
});

// ============================================================================
// TEST ROUTES - These test your Day 2 implementations
// ============================================================================

// ==================== TASK 4 TESTS: Multiple API Calls ====================

// Test 1: All providers succeed
app.get("/test/multi-success", async (req, res, next) => {
  try {
    const providers = [
      { name: "Fast", url: "https://postman-echo.com/delay/1", timeout: 5000 },
      {
        name: "Medium",
        url: "http://localhost:80/delay/2",
        timeout: 5000,
      },
      { name: "Slow", url: "https://postman-echo.com/delay/3", timeout: 5000 },
    ];

    const startTime = Date.now();
    const result = await callSingleProvider(providers, {}, req.logger);
    const totalTime = Date.now() - startTime;

    res.json({
      success: true,
      totalTime: `${totalTime}ms`,
      summary: {
        total: providers.length,
        succeeded: result.success.length,
        failed: result.failed.length,
      },
      result,
    });
  } catch (error) {
    next(error);
  }
});

// Test 2: Partial failure (some succeed, some fail)
app.get("/test/multi-partial", async (req, res, next) => {
  try {
    const providers = [
      {
        name: "FastProvider",
        url: "http://localhost:80/delay/1",
        timeout: 5000,
      },
      {
        name: "TimeoutProvider",
        url: "http://localhost:80/delay/10",
        timeout: 2000,
      },
      {
        name: "ErrorProvider",
        url: "http://localhost:80/status/500",
        timeout: 5000,
      },
      {
        name: "SuccessProvider",
        url: "http://localhost:80/json",
        timeout: 5000,
      },
    ];

    const result = await callMultipleProviders(providers, {}, req.logger);

    res.json({
      success: true,
      message: "Handled partial failure",
      summary: {
        total: providers.length,
        succeeded: result.success.length,
        failed: result.failed.length,
      },
      result,
    });
  } catch (error) {
    next(error);
  }
});

// ==================== TASK 5 TESTS: Validation ====================

// Test 3: Validation passes
app.get("/test/validation-pass", validateHotelSearch, (req, res) => {
  res.json({
    success: true,
    message: "Validation passed!",
    query: req.query,
  });
});

// Test 4: Validation fails - missing fields
app.get("/test/validation-fail", validateHotelSearch, (req, res) => {
  // Should not reach here if validation fails
  res.json({ success: true });
});

// ==================== TASK 6 TESTS: Redirects ====================

// Test 5: Normal redirects (3 redirects)
app.get("/test/redirects-normal", async (req, res, next) => {
  try {
    const result = await makeRequestWithRedirects(
      "http://localhost:80/redirect/3",
      {
        maxRedirects: 5,
        timeout: 5000,
      }
    );

    res.json({
      success: true,
      hasData: !!result,
    });
  } catch (error) {
    next(error);
  }
});

// Test 6: Too many redirects (should fail)
app.get("/test/redirects-toomany", async (req, res, next) => {
  try {
    const result = await makeRequestWithRedirects(
      "http://localhost:80/redirect/15",
      {
        maxRedirects: 5,
        timeout: 5000,
      }
    );
    res.json(result);
  } catch (error) {
    next(error);
  }
});

// Test 7: No redirects needed
app.get("/test/redirects-none", async (req, res, next) => {
  try {
    const result = await makeRequestWithRedirects("http://localhost:80/json", {
      maxRedirects: 5,
      timeout: 5000,
    });

    res.json({
      success: true,
      message: "No redirects needed",
      redirectCount: result.redirectCount,
      hasData: !!result,
    });
  } catch (error) {
    next(error);
  }
});

// ============================================================================
// GLOBAL ERROR HANDLER - Reuses Day 1 error classes
// ============================================================================

function globalErrorHandler(err, req, res, next) {
  const logger = req.logger || new Logger();
  logger.error("Request failed", err);

  const statusCode = err.statusCode || 500;

  const errorResponse = {
    error: {
      message: err.isOperational ? err.message : "Internal server error",
      code: err.errorCode || "INTERNAL_ERROR",
      statusCode,
      requestId: req.id,
    },
  };

  if (err instanceof ValidationError && err.errors) {
    errorResponse.error.errors = err.errors;
  }

  if (err instanceof APIError && err.provider) {
    errorResponse.error.provider = err.provider;
  }

  res.status(statusCode).json(errorResponse);
}

app.use(globalErrorHandler);

// ============================================================================
// START SERVER
// ============================================================================

const PORT = 3002;
app.listen(PORT, () => {
  console.log(`\n${"=".repeat(70)}`);
  console.log(`DAY 2 - Server running on http://localhost:${PORT}`);
  console.log("=".repeat(70));
  console.log("\n📝 Test your Day 2 implementations:\n");
  console.log("TASK 4 - Multiple API Calls:");
  console.log("  1. All Success:       GET /test/multi-success");
  console.log("  2. Partial Failure:   GET /test/multi-partial");
  console.log("");
  console.log("TASK 5 - Validation:");
  console.log(
    "  3. Pass:             GET /test/validation-pass?city=Paris&checkIn=2025-12-01&checkOut=2025-12-05&guests=2"
  );
  console.log("  4. Fail:             GET /test/validation-fail");
  console.log("");
  console.log("TASK 6 - Redirects:");
  console.log("  5. Normal (3):       GET /test/redirects-normal");
  console.log("  6. Too Many:         GET /test/redirects-toomany");
  console.log("  7. None:             GET /test/redirects-none");
  console.log("\n✅ Expected Results:");
  console.log("- Test 1: ~3s total (parallel), all success");
  console.log("- Test 2: Mix of success/failed, returns both");
  console.log("- Test 3: 200 with validated data");
  console.log("- Test 4: 400 with errors array");
  console.log("- Test 5: Returns redirect chain with 3 URLs");
  console.log("- Test 6: 502 error for too many redirects");
  console.log("- Test 7: Returns data with redirectCount: 0");
  console.log("\n" + "=".repeat(70) + "\n");
});
