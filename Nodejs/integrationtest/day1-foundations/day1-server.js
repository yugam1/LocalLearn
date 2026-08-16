// ============================================================================
// DAY 1: SERVER - Test your base implementations
// ============================================================================
// Run this after completing base.js TODOs
// This tests your Error Classes, Logger, and HTTP Client
// ============================================================================

const express = require("express");
const {
  AppError,
  ValidationError,
  APIError,
  TimeoutError,
  Logger,
  HTTPClient,
} = require("./base");

const app = express();
app.use(express.json());

// ============================================================================
// MIDDLEWARE
// ============================================================================

app.use((req, res, next) => {
  req.id = `req-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  req.logger = new Logger();
  req.logger.setRequestId(req.id);
  next();
});

// ============================================================================
// TEST ROUTES - These test your implementations
// ============================================================================

// Test 1: HTTP Client - Success
app.get("/test/http-success", async (req, res, next) => {
  try {
    const client = new HTTPClient("https://httpbin.org", 5000);
    const data = await client.get("/delay/1");

    req.logger.info("HTTP request succeeded");
    res.json({ success: true, data });
  } catch (error) {
    next(error);
  }
});

// Test 2: HTTP Client - Timeout
app.get("/test/http-timeout", async (req, res, next) => {
  try {
    const client = new HTTPClient("https://httpbin.org", 2000);
    const data = await client.get("/delay/5"); // Will timeout
    res.json({ success: true, data });
  } catch (error) {
    next(error);
  }
});

// Test 3: Validation Error
app.get("/test/validation-error", async (req, res, next) => {
  try {
    throw new ValidationError("/test/validation-error", [
      "email is required",
      "password is required",
    ]);
  } catch (error) {
    next(error);
  }
});

// Test 4: API Error
app.get("/test/api-error", async (req, res, next) => {
  try {
    throw new APIError("Provider unavailable", "PaymentGateway");
  } catch (error) {
    next(error);
  }
});

// Test 5: Logger Test
app.get("/test/logger", (req, res) => {
  req.logger.info("Info message", { userId: 123 });
  req.logger.warn("Warning message", { usage: "85%" });
  req.logger.error("Error message", new Error("Test error"), {
    action: "test",
  });

  res.json({ success: true, message: "Check console for logs" });
});

// ============================================================================
// GLOBAL ERROR HANDLER
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

  // Add additional fields based on error type
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

const PORT = 3001;
app.listen(PORT, () => {
  console.log(`\n${"=".repeat(70)}`);
  console.log(`DAY 1 - Server running on http://localhost:${PORT}`);
  console.log("=".repeat(70));
  console.log("\n📝 Test your base implementations:\n");
  console.log("1. HTTP Success:      GET /test/http-success");
  console.log("2. HTTP Timeout:      GET /test/http-timeout");
  console.log("3. Validation Error:  GET /test/validation-error");
  console.log("4. API Error:         GET /test/api-error");
  console.log("5. Logger Test:       GET /test/logger");
  console.log("\n✅ Expected Results:");
  console.log("- Test 1: Returns data (200)");
  console.log("- Test 2: Returns timeout error (504)");
  console.log("- Test 3: Returns validation error with errors array (400)");
  console.log("- Test 4: Returns API error with provider (502)");
  console.log("- Test 5: Check console for JSON formatted logs");
  console.log("\n" + "=".repeat(70) + "\n");
});
