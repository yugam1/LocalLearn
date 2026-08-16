// ============================================================================
// DAY 1: FOUNDATIONS - HTTP Client, Error Classes, and Logging
// ============================================================================
// Time: 45 minutes total
// Tasks: 3 (15 minutes each)
//
// Setup Instructions:
// 1. npm init -y
// 2. npm install express axios
// 3. Create this file as index.js
// 4. Run: node index.js
// ============================================================================

const axios = require("axios");
const axiosRetry = require("axios-retry").default;
const CircuitBreaker = require("opossum");

// ============================================================================
// TASK 1: HTTP CLIENT WITH TIMEOUT (15 minutes)
// ============================================================================
// Goal: Create a reusable HTTP client that handles timeouts properly
//
// Requirements:
// 1. Create HTTPClient class with get() method
// 2. Support custom timeout per request
// 3. Handle timeout errors specifically
// 4. Handle network errors
// 5. Return clean response data
// ============================================================================

class TokenManager {
  constructor(getToken, tokenStore) {
    this.getToken = getToken || (() => null);
    this.store = tokenStore;
  }

  get() {
    return this.store.get();
  }

  async refreshToken() {
    const token = await this.getToken();
    if (token) {
      this.store.set(token);
    }
    return token;
  }
}

class InMemoryStore {
  constructor() {
    this.token = null;
  }

  get() {
    return this.token;
  }

  set(token) {
    this.token = token;
    return token;
  }

  clear() {
    this.token = null;
  }
}

class HTTPClient {
  constructor(baseURL = "", options = {}, tokenManager = null) {
    this.baseURL = baseURL;
    this.defaultTimeout = options.defaultTimeout || 5000;
    this.maxRedirects = options.maxRedirects || 2;
    this.headers = options.headers || {};
    this.client = axios.create({
      baseURL: this.baseURL,
      timeout: this.defaultTimeout,
      maxRedirects: this.maxRedirects,
      headers: {
        "Content-Type": "application/json",
        ...this.headers,
      },
    });
    this.tokenManager = tokenManager;

    this.setInterceptors();
  }

  setInterceptors() {
    this.client.interceptors.request.use((config) => {
      if (!this.tokenManager) return config;
      const token = this.tokenManager.get();
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });

    this.client.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config;
        if (
          error?.response?.status == 401 &&
          !originalRequest._retried &&
          this.tokenManager
        ) {
          originalRequest._retried = true;
          try {
            const newToken = await this.tokenManager.refreshToken();
            originalRequest.headers.Authorization = `Bearer ${newToken}`;
            return this.client(originalRequest);
          } catch (error) {
            return Promise.reject(error);
          }
        }
        return Promise.reject(error);
      }
    );
  }

  /**
   * TODO: Implement GET request with timeout handling
   *
   * @param {string} endpoint - API endpoint (e.g., '/users')
   * @param {object} options - Request options
   * @param {number} options.timeout - Request timeout in ms
   * @param {object} options.params - Query parameters
   * @returns {Promise<any>} Response data
   *
   * Hints:
   * - Use axios.get() with this.baseURL + endpoint
   * - Set timeout from options or use this.defaultTimeout
   * - Catch ECONNABORTED error for timeouts
   * - Throw meaningful error messages
   * - Return response.data on success
   */
  async get(endpoint, options = {}) {
    // TODO: Your implementation here
    const responseData = await this.makeRequest(endpoint, "GET", {}, options);
    return responseData;
  }
  async put(endpoint, data, options = {}) {
    // TODO: Your implementation here
    const responseData = await this.makeRequest(
      endpoint,
      "PUT",
      { data },
      options
    );
    return responseData;
  }

  async patch(endpoint, data, options = {}) {
    // TODO: Your implementation here
    const responseData = await this.makeRequest(
      endpoint,
      "PATCH",
      { data },
      options
    );
    return responseData;
  }

  async delete(endpoint, options = {}) {
    // TODO: Your implementation here
    const responseData = await this.makeRequest(
      endpoint,
      "DELETE",
      {},
      options
    );
    return responseData;
  }

  /**
   * TODO: Implement POST request with timeout handling
   *
   * @param {string} endpoint - API endpoint
   * @param {object} data - Request body
   * @param {object} options - Request options
   * @returns {Promise<any>} Response data
   */
  async post(endpoint, data, options = {}) {
    // TODO: Your implementation here
    const responseData = await this.makeRequest(
      endpoint,
      "POST",
      { data },
      options
    );
    return responseData;
  }

  async makeRequest(endpoint, method = "GET", { data, params }, options = {}) {
    const url = `${endpoint}`;
    const timeout = options?.timeout || this.defaultTimeout;
    const maxRedirects = options?.maxRedirects || this.maxRedirects;
    const config = {
      url,
      method,
      timeout: timeout,
      maxRedirects,
    };
    if (data) {
      config.data = data;
    }

    if (params) {
      config.params = params;
    }

    try {
      const response = await this.client.request(config);
      return this.handleSuccess(response);
    } catch (error) {
      logger.error(error.message);
      throw this.handleError(error, endpoint, options);
    }
  }

  handleError(error, endpoint, options = {}) {
    const url = `${this.baseURL}${endpoint}`;
    const timeout = options?.timeout || this.defaultTimeout;
    if (
      error?.code == "ECONNABORTED" ||
      error?.message?.toLowerCase()?.includes("timeout")
    ) {
      throw new TimeoutError(url, timeout);
    }
    if (error.response) {
      const status = error.response?.status || 500;
      const message = error.response?.data;
      throw new APIError(
        `Error received from api with status:${status} message:${message}`,
        url
      );
    }

    throw new APIError(`${error.message || "Something went wrong "}`, url);
  }

  handleSuccess(response) {
    return {
      status: response.status,
      data: response.data,
      headers: response.headers,
    };
  }
}

class ResilientHTTPClient extends HTTPClient {
  constructor(baseURL, options, tokenManager) {
    super(baseURL, options, tokenManager);

    axiosRetry(this.client, {
      retries: options.maxRetries ?? 2,
      retryDelay: axiosRetry.exponentialDelay,
      retryCondition: (error) => {
        return (
          axiosRetry.isNetworkOrIdempotentRequestError(error) ||
          this.isRetryableError(error)
        );
      },
    });

    this.breaker = new CircuitBreaker((config) => this.client.request(config), {
      timeout: (options.defaultTimeout ?? 1000) + 500,
      errorThresholdPercentage: 50,
      resetTimeout: 10000,
    });

    this.breaker.on("open", () => {
      console.warn(`[Circuit Breaker] Status Open on ${this.baseURL}`);
    });
    this.breaker.on("close", () => {
      console.warn(`[Circuit Breaker] Status Close on ${this.baseURL}`);
    });
    this.breaker.on("halfOpen", () => {
      console.warn(`[Circuit Breaker] Status half-open on ${this.baseURL}`);
    });
  }

  async makeRequest(endpoint, method = "GET", { data, params }, options = {}) {
    try {
      const response = await this.breaker.fire({
        url: `${endpoint}`,
        method,
        data,
        params,
        headers: options.headers,
        timeout: options.timeout || this.defaultTimeout,
        maxRedirects: options.maxRedirects || this.maxRedirects,
      });

      return this.handleSuccess(response);
    } catch (error) {
      this.handleError(error, endpoint, options);
    }
  }

  async post(endpoint, data, options = {}) {
    const key = options.idempotencyKey || this.generateIdempotencyKey();

    return this.makeRequest(
      endpoint,
      "POST",
      { data },
      {
        ...options,
        headers: {
          ...(options.headers || {}),
          "Idempotency-Key": key,
        },
      }
    );
  }

  generateIdempotencyKey() {
    return crypto.randomUUID();
  }

  isRetryableError(error) {
    const canRetryStatus = [429, 500, 502, 503, 504];
    const status = error?.status;
    return canRetryStatus.includes(status);
  }
}

// ============================================================================
// TASK 2: ERROR CLASSES (15 minutes)
// ============================================================================
// Goal: Create a hierarchy of error classes for different scenarios
//
// Requirements:
// 1. AppError - Base error class with statusCode, errorCode, isOperational
// 2. ValidationError - For request validation (400)
// 3. APIError - For external API failures (502)
// 4. TimeoutError - For timeout scenarios (504)
// 5. NotFoundError - For 404 scenarios
// ============================================================================

/**
 * TODO: Implement AppError base class
 *
 * Properties needed:
 * - message: string
 * - statusCode: number (e.g., 400, 500)
 * - errorCode: string (e.g., 'VALIDATION_ERROR')
 * - isOperational: boolean (always true for our errors)
 *
 * Hints:
 * - Extend Error class
 * - Use Error.captureStackTrace(this, this.constructor)
 * - Set this.name = this.constructor.name
 */

const ERROR_CODES = {
  VALIDATION_ERROR: "VALIDATION_ERROR",
  API_ERROR: "API_ERROR",
  RESOURCE_NOT_FOUND_ERROR: "RESOURCE_NOT_FOUND_ERROR",
  TIMEOUT_ERROR: "TIMEOUT_ERROR",
  INTERNAL_SERVER_ERROR: "INTERNAL_SERVER_ERROR",
};

class AppError extends Error {
  constructor(message, statusCode, errorCode) {
    // TODO: Your implementation here
    super(message);
    this.name = this.constructor.name;
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.isOperational = true;
    this.timestamp = new Date().toISOString();
    Error.captureStackTrace(this, this.constructor);
  }

  toJSON() {
    return {
      status: false,
      name: this.name,
      message: this.message,
      statusCode: this.statusCode,
      errorCode: this.errorCode,
      timestamp: this.timestamp,
      isOperational: this.isOperational,
    };
  }
}

/**
 * TODO: Implement ValidationError
 *
 * Should accept array of validation errors
 * Default statusCode: 400
 * Default errorCode: 'VALIDATION_ERROR'
 *
 * Example usage:
 * throw new ValidationError(['city is required', 'date is invalid'])
 */
class ValidationError extends AppError {
  constructor(api, errors) {
    // TODO: Your implementation here
    super(
      `Invalid payload received on api ${api}`,
      400,
      ERROR_CODES.VALIDATION_ERROR
    );
    this.errors = errors;
  }

  toJSON() {
    const data = super.toJSON();
    return { ...data, errors: this.errors };
  }
}

/**
 * TODO: Implement APIError
 *
 * Should track which external provider/API failed
 * Default statusCode: 502
 * Default errorCode: 'API_ERROR'
 *
 * Example usage:
 * throw new APIError('Provider unavailable', 'HotelFast')
 */
class APIError extends AppError {
  constructor(message, provider) {
    // TODO: Your implementation here
    super(message, 500, ERROR_CODES.API_ERROR);
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
 * TODO: Implement TimeoutError
 *
 * Should extend APIError
 * StatusCode: 504
 * ErrorCode: 'TIMEOUT_ERROR'
 * Should include timeout duration
 *
 * Example usage:
 * throw new TimeoutError('HotelFast', 5000)
 */
class TimeoutError extends APIError {
  constructor(provider, timeoutMs) {
    // TODO: Your implementation here
    super(`Request timedout after ${timeoutMs || 0}`, provider);
    this.statusCode = 504; // ← Add this
    this.errorCode = ERROR_CODES.TIMEOUT_ERROR;
    this.timeout = timeoutMs;
  }
}

/**
 * TODO: Implement NotFoundError
 *
 * StatusCode: 404
 * ErrorCode: 'NOT_FOUND'
 *
 * Example usage:
 * throw new NotFoundError('User not found')
 */
class NotFoundError extends AppError {
  constructor(message) {
    // TODO: Your implementation here
    super(message, 404, ERROR_CODES.RESOURCE_NOT_FOUND_ERROR);
  }
}

// ============================================================================
// TASK 3: STRUCTURED LOGGER (15 minutes)
// ============================================================================
// Goal: Create a production-ready logger with request tracking
//
// Requirements:
// 1. Log levels: info, warn, error
// 2. Include timestamp in ISO format
// 3. Track requestId across logs
// 4. Format as JSON for easy parsing
// 5. Include stack traces for errors
// ============================================================================

/**
 * TODO: Implement Logger class
 *
 * Features needed:
 * - info(message, meta) - Log informational messages
 * - warn(message, meta) - Log warnings
 * - error(message, error, meta) - Log errors with stack traces
 * - setRequestId(id) - Set request ID for tracking
 *
 * Log format:
 * {
 *   timestamp: '2024-11-25T10:30:00.000Z',
 *   level: 'INFO',
 *   requestId: 'req-123',
 *   message: 'User logged in',
 *   ...meta
 * }
 */
class Logger {
  constructor() {
    // TODO: Initialize properties
    this.requestId = "";
  }

  /**
   * TODO: Implement info level logging
   *
   * @param {string} message - Log message
   * @param {object} meta - Additional context
   */
  info(message, meta = {}) {
    // TODO: Your implementation here
    this._log("INFO", message, meta);
  }

  /**
   * TODO: Implement warn level logging
   *
   * @param {string} message - Warning message
   * @param {object} meta - Additional context
   */
  warn(message, meta = {}) {
    // TODO: Your implementation here
    this._log("WARN", message, meta);
  }

  /**
   * TODO: Implement error level logging
   *
   * @param {string} message - Error message
   * @param {Error} error - Error object (optional)
   * @param {object} meta - Additional context
   *
   * Should include error.message and error.stack if error provided
   */
  error(message, error = null, meta = {}) {
    // TODO: Your implementation here
    if (error) {
      meta.error = error.message;
      meta.stack = error.stack; // ← Add stack trace
    }
    this._log("ERROR", message, meta);
  }

  /**
   * TODO: Implement setRequestId
   *
   * @param {string} requestId - Request ID to track
   */
  setRequestId(requestId) {
    // TODO: Your implementation here
    this.requestId = requestId;
  }

  /**
   * Helper: Get current timestamp in ISO format
   */
  getTimestamp() {
    return new Date().toISOString();
  }

  /**
   * TODO: Implement private _log method
   *
   * This should be the core logging method that:
   * 1. Formats the log entry as JSON
   * 2. Includes timestamp, level, requestId, message, meta
   * 3. Outputs to console
   *
   * @param {string} level - Log level
   * @param {string} message - Log message
   * @param {object} meta - Additional data
   */
  _log(level, message, meta) {
    // TODO: Your implementation here
    switch (level) {
      case "INFO":
        console.log(
          `${this.getTimestamp()} [${level}] ${
            this.requestId
          } ${message}, meta: ${JSON.stringify(meta)}`
        );
        break;
      case "WARN":
        console.warn(
          `${this.getTimestamp()} [${level}] ${
            this.requestId
          } ${message}, meta: ${JSON.stringify(meta)}`
        );
        break;
      case "ERROR":
        console.error(
          `${this.getTimestamp()} [${level}] ${
            this.requestId
          } ${message}, meta: ${JSON.stringify(meta)}`
        );
        break;
      case "DEBUG":
        console.debug(
          `${this.getTimestamp()} [${level}] ${
            this.requestId
          } ${message}, meta: ${JSON.stringify(meta)}`
        );
        break;

      default:
        console.log(
          `${this.getTimestamp()} [INFO] ${
            this.requestId
          } ${message}, meta: ${JSON.stringify(meta)}`
        );
        break;
    }
  }
}

const logger = new Logger();

// ============================================================================
// START SERVER
// ============================================================================

module.exports = {
  AppError,
  ValidationError,
  APIError,
  TimeoutError,
  Logger,
  HTTPClient,
  ResilientHTTPClient,
};
