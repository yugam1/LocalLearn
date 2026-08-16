// ============================================================================
// DAY 1: COMPLETE SOLUTION - Production Ready
// ============================================================================
// This solution demonstrates:
// - Modular, reusable components
// - Comprehensive error handling
// - Production-grade logging
// - Clean separation of concerns
// ============================================================================

const express = require("express");
const axios = require("axios");

const app = express();
app.use(express.json());

// ============================================================================
// MODULE 1: HTTP CLIENT
// ============================================================================

/**
 * Production-ready HTTP Client with timeout and error handling
 *
 * Features:
 * - Configurable base URL and default timeout
 * - Automatic timeout error handling
 * - Network error handling
 * - Clean response extraction
 * - Extensible for additional HTTP methods
 */
class HTTPClient {
  constructor(baseURL, defaultTimeout = 5000) {
    this.baseURL = baseURL;
    this.defaultTimeout = defaultTimeout;
    this.axiosInstance = axios.create({
      baseURL: this.baseURL,
      timeout: this.defaultTimeout,
    });
  }

  /**
   * Make GET request with timeout handling
   *
   * @param {string} endpoint - API endpoint
   * @param {object} options - Request options
   * @param {number} options.timeout - Custom timeout
   * @param {object} options.params - Query parameters
   * @param {object} options.headers - Custom headers
   * @returns {Promise<any>} Response data
   * @throws {TimeoutError} If request times out
   * @throws {APIError} If request fails
   */
  async get(endpoint, options = {}) {
    const timeout = options.timeout || this.defaultTimeout;

    try {
      const response = await this.axiosInstance.get(endpoint, {
        timeout,
        params: options.params,
        headers: options.headers,
      });

      return response.data;
    } catch (error) {
      this._handleError(error, endpoint, timeout);
    }
  }

  /**
   * Make POST request with timeout handling
   *
   * @param {string} endpoint - API endpoint
   * @param {object} data - Request body
   * @param {object} options - Request options
   * @returns {Promise<any>} Response data
   */
  async post(endpoint, data, options = {}) {
    const timeout = options.timeout || this.defaultTimeout;

    try {
      const response = await this.axiosInstance.post(endpoint, data, {
        timeout,
        headers: options.headers,
      });

      return response.data;
    } catch (error) {
      this._handleError(error, endpoint, timeout);
    }
  }

  /**
   * Make PUT request with timeout handling
   *
   * @param {string} endpoint - API endpoint
   * @param {object} data - Request body
   * @param {object} options - Request options
   * @returns {Promise<any>} Response data
   */
  async put(endpoint, data, options = {}) {
    const timeout = options.timeout || this.defaultTimeout;

    try {
      const response = await this.axiosInstance.put(endpoint, data, {
        timeout,
        headers: options.headers,
      });

      return response.data;
    } catch (error) {
      this._handleError(error, endpoint, timeout);
    }
  }

  /**
   * Make DELETE request with timeout handling
   *
   * @param {string} endpoint - API endpoint
   * @param {object} options - Request options
   * @returns {Promise<any>} Response data
   */
  async delete(endpoint, options = {}) {
    const timeout = options.timeout || this.defaultTimeout;

    try {
      const response = await this.axiosInstance.delete(endpoint, {
        timeout,
        headers: options.headers,
      });

      return response.data;
    } catch (error) {
      this._handleError(error, endpoint, timeout);
    }
  }

  /**
   * Handle HTTP errors and convert to appropriate error types
   *
   * @private
   * @param {Error} error - Axios error
   * @param {string} endpoint - API endpoint
   * @param {number} timeout - Request timeout
   * @throws {TimeoutError} If timeout
   * @throws {APIError} If API error
   */
  _handleError(error, endpoint, timeout) {
    const providerName = `${this.baseURL}${endpoint}`;

    // Timeout errors
    if (error.code === "ECONNABORTED" || error.message.includes("timeout")) {
      throw new TimeoutError(providerName, timeout);
    }

    // Network errors
    if (error.code === "ECONNREFUSED" || error.code === "ENOTFOUND") {
      throw new APIError(`Unable to connect to ${providerName}`, providerName);
    }

    // HTTP errors (4xx, 5xx)
    if (error.response) {
      const status = error.response.status;
      const message = error.response.data?.message || error.message;

      throw new APIError(
        `API request failed with status ${status}: ${message}`,
        providerName,
        status
      );
    }

    // Unknown errors
    throw new APIError(`Request failed: ${error.message}`, providerName);
  }
}

// ============================================================================
// MODULE 2: ERROR CLASSES
// ============================================================================

/**
 * Base error class for all application errors
 *
 * Features:
 * - Consistent error structure
 * - Operational flag for error handling
 * - Stack trace capture
 * - Extensible for specific error types
 */
class AppError extends Error {
  constructor(message, statusCode, errorCode) {
    super(message);

    this.name = this.constructor.name;
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.isOperational = true; // Distinguishes from programming errors
    this.timestamp = new Date().toISOString();

    // Capture stack trace
    Error.captureStackTrace(this, this.constructor);
  }

  /**
   * Convert error to JSON format
   * @returns {object} Error object
   */
  toJSON() {
    return {
      name: this.name,
      message: this.message,
      statusCode: this.statusCode,
      errorCode: this.errorCode,
      timestamp: this.timestamp,
    };
  }
}

/**
 * Validation error for invalid user input
 *
 * Use for: Missing fields, invalid formats, business rule violations
 */
class ValidationError extends AppError {
  constructor(errors) {
    super("Validation failed", 400, "VALIDATION_ERROR");
    this.errors = Array.isArray(errors) ? errors : [errors];
  }

  toJSON() {
    return {
      ...super.toJSON(),
      errors: this.errors,
    };
  }
}

/**
 * API error for external service failures
 *
 * Use for: Third-party API failures, integration errors
 */
class APIError extends AppError {
  constructor(message, provider, statusCode = 502) {
    super(message, statusCode, "API_ERROR");
    this.provider = provider;
  }

  toJSON() {
    return {
      ...super.toJSON(),
      provider: this.provider,
    };
  }
}

/**
 * Timeout error for request timeouts
 *
 * Use for: Long-running requests, unresponsive services
 */
class TimeoutError extends APIError {
  constructor(provider, timeoutMs) {
    super(
      `Request to ${provider} timed out after ${timeoutMs}ms`,
      provider,
      504
    );
    this.errorCode = "TIMEOUT_ERROR";
    this.timeout = timeoutMs;
  }

  toJSON() {
    return {
      ...super.toJSON(),
      timeout: this.timeout,
    };
  }
}

/**
 * Not found error for missing resources
 *
 * Use for: 404 scenarios, missing database records
 */
class NotFoundError extends AppError {
  constructor(message = "Resource not found") {
    super(message, 404, "NOT_FOUND");
  }
}

/**
 * Authorization error for authentication/permission issues
 *
 * Use for: Unauthorized access, invalid tokens
 */
class UnauthorizedError extends AppError {
  constructor(message = "Unauthorized access") {
    super(message, 401, "UNAUTHORIZED");
  }
}

/**
 * Rate limit error for throttling
 *
 * Use for: Rate limiting, quota exceeded
 */
class RateLimitError extends AppError {
  constructor(message = "Rate limit exceeded", retryAfter = null) {
    super(message, 429, "RATE_LIMIT_EXCEEDED");
    this.retryAfter = retryAfter;
  }

  toJSON() {
    return {
      ...super.toJSON(),
      retryAfter: this.retryAfter,
    };
  }
}

// ============================================================================
// MODULE 3: LOGGER
// ============================================================================

/**
 * Production-ready structured logger
 *
 * Features:
 * - Multiple log levels (info, warn, error, debug)
 * - Request ID tracking for distributed tracing
 * - JSON formatted output for log aggregation
 * - Stack trace inclusion for errors
 * - Performance timing support
 */
class Logger {
  constructor() {
    this.requestId = null;
    this.context = {};
  }

  /**
   * Set request ID for tracking
   * @param {string} requestId - Unique request identifier
   */
  setRequestId(requestId) {
    this.requestId = requestId;
  }

  /**
   * Set additional context that persists across logs
   * @param {object} context - Context object
   */
  setContext(context) {
    this.context = { ...this.context, ...context };
  }

  /**
   * Clear context
   */
  clearContext() {
    this.context = {};
  }

  /**
   * Log informational message
   * @param {string} message - Log message
   * @param {object} meta - Additional metadata
   */
  info(message, meta = {}) {
    this._log("INFO", message, meta);
  }

  /**
   * Log warning message
   * @param {string} message - Warning message
   * @param {object} meta - Additional metadata
   */
  warn(message, meta = {}) {
    this._log("WARN", message, meta);
  }

  /**
   * Log error message with stack trace
   * @param {string} message - Error message
   * @param {Error} error - Error object
   * @param {object} meta - Additional metadata
   */
  error(message, error = null, meta = {}) {
    const errorMeta = error
      ? {
          error: {
            message: error.message,
            stack: error.stack,
            code: error.code || error.errorCode,
            ...(error.toJSON && error.toJSON()),
          },
        }
      : {};

    this._log("ERROR", message, { ...meta, ...errorMeta });
  }

  /**
   * Log debug message (useful in development)
   * @param {string} message - Debug message
   * @param {object} meta - Additional metadata
   */
  debug(message, meta = {}) {
    if (process.env.NODE_ENV === "development") {
      this._log("DEBUG", message, meta);
    }
  }

  /**
   * Start performance timer
   * @param {string} label - Timer label
   * @returns {function} Function to end timer and log duration
   */
  startTimer(label) {
    const startTime = Date.now();

    return () => {
      const duration = Date.now() - startTime;
      this.info(`${label} completed`, { duration: `${duration}ms` });
      return duration;
    };
  }

  /**
   * Core logging method
   * @private
   * @param {string} level - Log level
   * @param {string} message - Log message
   * @param {object} meta - Metadata
   */
  _log(level, message, meta) {
    const logEntry = {
      timestamp: this.getTimestamp(),
      level,
      message,
      ...(this.requestId && { requestId: this.requestId }),
      ...this.context,
      ...meta,
    };

    // In production, you might send to logging service
    // For now, output to console
    const output = JSON.stringify(logEntry, null, 2);

    switch (level) {
      case "ERROR":
        console.error(output);
        break;
      case "WARN":
        console.warn(output);
        break;
      default:
        console.log(output);
    }
  }

  /**
   * Get current timestamp in ISO format
   * @returns {string} ISO timestamp
   */
  getTimestamp() {
    return new Date().toISOString();
  }
}

// ============================================================================
// MODULE 4: MIDDLEWARE
// ============================================================================

/**
 * Request ID middleware - Adds unique ID to each request
 */
function requestIdMiddleware(req, res, next) {
  req.id = `req-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  res.setHeader("X-Request-ID", req.id);
  next();
}

/**
 * Logger middleware - Attaches logger instance to request
 */
function loggerMiddleware(req, res, next) {
  req.logger = new Logger();
  req.logger.setRequestId(req.id);

  // Log incoming request
  req.logger.info("Incoming request", {
    method: req.method,
    path: req.path,
    query: req.query,
    ip: req.ip,
  });

  // Log response on finish
  const startTime = Date.now();
  res.on("finish", () => {
    const duration = Date.now() - startTime;
    req.logger.info("Request completed", {
      statusCode: res.statusCode,
      duration: `${duration}ms`,
    });
  });

  next();
}

/**
 * Global error handler middleware
 *
 * Features:
 * - Handles all error types consistently
 * - Logs errors with context
 * - Returns clean error responses
 * - Never exposes sensitive information
 */
function globalErrorHandler(err, req, res, next) {
  const logger = req.logger || new Logger();

  // Log the error
  logger.error("Request failed", err, {
    path: req.path,
    method: req.method,
  });

  // Determine status code
  const statusCode = err.statusCode || 500;

  // Build error response
  const errorResponse = {
    error: {
      message: err.isOperational ? err.message : "An unexpected error occurred",
      code: err.errorCode || "INTERNAL_ERROR",
      statusCode,
      requestId: req.id,
      timestamp: new Date().toISOString(),
    },
  };

  // Add additional fields based on error type
  if (err instanceof ValidationError) {
    errorResponse.error.errors = err.errors;
  }

  if (err instanceof APIError) {
    errorResponse.error.provider = err.provider;
  }

  if (err instanceof TimeoutError) {
    errorResponse.error.timeout = err.timeout;
  }

  if (err instanceof RateLimitError && err.retryAfter) {
    errorResponse.error.retryAfter = err.retryAfter;
    res.setHeader("Retry-After", err.retryAfter);
  }

  // Only include stack in development
  if (process.env.NODE_ENV !== "production" && err.stack) {
    errorResponse.error.stack = err.stack;
  }

  res.status(statusCode).json(errorResponse);
}

/**
 * 404 handler - Handles routes that don't exist
 */
function notFoundHandler(req, res, next) {
  next(new NotFoundError(`Route ${req.method} ${req.path} not found`));
}

// ============================================================================
// APPLY MIDDLEWARE
// ============================================================================

app.use(requestIdMiddleware);
app.use(loggerMiddleware);

// ============================================================================
// TEST ROUTES
// ============================================================================

// Test Route 1: HTTP Client Success
app.get("/test/http-success", async (req, res, next) => {
  try {
    const client = new HTTPClient("https://httpbin.org", 5000);
    const endTimer = req.logger.startTimer("HTTP request");

    const data = await client.get("/delay/1");
    endTimer();

    res.json({ success: true, data });
  } catch (error) {
    next(error);
  }
});

// Test Route 2: HTTP Client Timeout
app.get("/test/http-timeout", async (req, res, next) => {
  try {
    const client = new HTTPClient("https://httpbin.org", 2000);
    const data = await client.get("/delay/5");

    res.json({ success: true, data });
  } catch (error) {
    next(error);
  }
});

// Test Route 3: Validation Success
app.get("/test/validation", async (req, res, next) => {
  try {
    const errors = [];

    if (!req.query.email) errors.push("email is required");
    if (!req.query.name) errors.push("name is required");
    if (req.query.email && !req.query.email.includes("@")) {
      errors.push("email must be valid");
    }

    if (errors.length > 0) {
      throw new ValidationError(errors);
    }

    res.json({
      success: true,
      message: "Validation passed",
      data: { email: req.query.email, name: req.query.name },
    });
  } catch (error) {
    next(error);
  }
});

// Test Route 4: API Error
app.get("/test/api-error", async (req, res, next) => {
  try {
    throw new APIError("Payment gateway is unavailable", "StripeAPI");
  } catch (error) {
    next(error);
  }
});

// Test Route 5: Multiple API Calls
app.get("/test/multiple-apis", async (req, res, next) => {
  try {
    const client = new HTTPClient("https://httpbin.org", 3000);

    // Simulate calling multiple providers
    const providers = [
      { name: "Fast", endpoint: "/delay/1" },
      { name: "Medium", endpoint: "/delay/2" },
      { name: "Slow", endpoint: "/delay/5" }, // Will timeout
    ];

    const results = await Promise.allSettled(
      providers.map(async (provider) => {
        const endTimer = req.logger.startTimer(`Provider: ${provider.name}`);
        try {
          const data = await client.get(provider.endpoint);
          endTimer();
          return { provider: provider.name, status: "success", data };
        } catch (error) {
          endTimer();
          req.logger.warn(`Provider ${provider.name} failed`, {
            error: error.message,
          });
          return {
            provider: provider.name,
            status: "failed",
            error: error.message,
          };
        }
      })
    );

    const response = {
      success: results
        .filter((r) => r.value?.status === "success")
        .map((r) => r.value),
      failed: results
        .filter((r) => r.value?.status === "failed")
        .map((r) => r.value),
    };

    res.json(response);
  } catch (error) {
    next(error);
  }
});

// Test Route 6: Logger Features
app.get("/test/logger", (req, res) => {
  req.logger.info("Info level log", { userId: 123, action: "test" });
  req.logger.warn("Warning level log", { memoryUsage: "85%" });
  req.logger.error("Error level log", new Error("Test error"), {
    context: "testing",
  });
  req.logger.debug("Debug level log", {
    debugInfo: "This only shows in development",
  });

  res.json({ success: true, message: "Check console for various log levels" });
});

// Test Route 7: Performance Timing
app.get("/test/performance", async (req, res, next) => {
  try {
    const endTimer = req.logger.startTimer("Complete operation");

    // Simulate some work
    await new Promise((resolve) => setTimeout(resolve, 500));

    const duration = endTimer();

    res.json({ success: true, duration: `${duration}ms` });
  } catch (error) {
    next(error);
  }
});

// ============================================================================
// ERROR HANDLERS (Must be last)
// ============================================================================

app.use(notFoundHandler);
app.use(globalErrorHandler);

// ============================================================================
// START SERVER
// ============================================================================

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  const logger = new Logger();
  logger.info("Server started", {
    port: PORT,
    env: process.env.NODE_ENV || "development",
  });

  console.log(`\n${"=".repeat(70)}`);
  console.log("DAY 1 - SOLUTION - Server running on http://localhost:3000");
  console.log("=".repeat(70));
  console.log("\n📝 Test Endpoints:\n");
  console.log("1. HTTP Success:      GET /test/http-success");
  console.log("2. HTTP Timeout:      GET /test/http-timeout");
  console.log(
    "3. Validation Pass:   GET /test/validation?email=test@example.com&name=John"
  );
  console.log("4. Validation Fail:   GET /test/validation");
  console.log("5. API Error:         GET /test/api-error");
  console.log("6. Multiple APIs:     GET /test/multiple-apis");
  console.log("7. Logger Test:       GET /test/logger");
  console.log("8. Performance:       GET /test/performance");
  console.log("9. Not Found:         GET /test/nonexistent");
  console.log("\n" + "=".repeat(70) + "\n");
});

// Export for testing
module.exports = {
  HTTPClient,
  AppError,
  ValidationError,
  APIError,
  TimeoutError,
  NotFoundError,
  UnauthorizedError,
  RateLimitError,
  Logger,
};
