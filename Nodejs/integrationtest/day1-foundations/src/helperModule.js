// ============================================================================
// helperModule.js - Production-Ready HTTP Client
// ============================================================================
// Ready for Stripe Integration (Part 4 onwards)
//
// Features:
// - HTTPClient: Base class with token management, timeout handling
// - ResilientHTTPClient: Extends base with retry, circuit breaker, idempotency
// - Returns structured JSON responses (no throwing errors)
// - Comprehensive logging with request IDs
//
// Dependencies:
// npm install axios axios-retry opossum
// ============================================================================

const axios = require("axios");
const axiosRetry = require("axios-retry").default;
const CircuitBreaker = require("opossum");
const crypto = require("crypto");

// ============================================================================
// TOKEN MANAGEMENT
// ============================================================================

class TokenManager {
  constructor(getToken, tokenStore) {
    this.getToken = getToken || (() => null);
    this.store = tokenStore || new InMemoryStore();
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

// ============================================================================
// ERROR CODES
// ============================================================================

const ERROR_CODES = {
  VALIDATION_ERROR: "VALIDATION_ERROR",
  API_ERROR: "API_ERROR",
  RESOURCE_NOT_FOUND_ERROR: "RESOURCE_NOT_FOUND_ERROR",
  TIMEOUT_ERROR: "TIMEOUT_ERROR",
  INTERNAL_SERVER_ERROR: "INTERNAL_SERVER_ERROR",
  NETWORK_ERROR: "NETWORK_ERROR",
  SERVICE_UNAVAILABLE: "SERVICE_UNAVAILABLE",
};

// ============================================================================
// ERROR CLASSES (For structured error information)
// ============================================================================

class AppError extends Error {
  constructor(message, statusCode, errorCode) {
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

class ValidationError extends AppError {
  constructor(api, errors) {
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

class APIError extends AppError {
  constructor(message, provider) {
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

class TimeoutError extends APIError {
  constructor(provider, timeoutMs) {
    super(`Request timed out after ${timeoutMs || 0}ms`, provider);
    this.statusCode = 504;
    this.errorCode = ERROR_CODES.TIMEOUT_ERROR;
    this.timeout = timeoutMs;
  }

  toJSON() {
    return {
      ...super.toJSON(),
      timeout: this.timeout,
    };
  }
}

class NotFoundError extends AppError {
  constructor(message) {
    super(message, 404, ERROR_CODES.RESOURCE_NOT_FOUND_ERROR);
  }
}

// ============================================================================
// LOGGER - STRUCTURED JSON LOGGING
// ============================================================================

class Logger {
  constructor() {
    this.requestId = null;
  }

  info(message, meta = {}) {
    this._log("INFO", message, meta);
  }

  warn(message, meta = {}) {
    this._log("WARN", message, meta);
  }

  error(message, error = null, meta = {}) {
    if (error) {
      meta.error = error.message;
      meta.stack = error.stack;
    }
    this._log("ERROR", message, meta);
  }

  debug(message, meta = {}) {
    this._log("DEBUG", message, meta);
  }

  setRequestId(requestId) {
    this.requestId = requestId;
  }

  getTimestamp() {
    return new Date().toISOString();
  }

  _log(level, message, meta) {
    const logEntry = {
      timestamp: this.getTimestamp(),
      level,
      requestId: this.requestId || "no-request-id",
      message,
      ...meta,
    };

    const jsonLog = JSON.stringify(logEntry);

    switch (level) {
      case "INFO":
        console.log(jsonLog);
        break;
      case "WARN":
        console.warn(jsonLog);
        break;
      case "ERROR":
        console.error(jsonLog);
        break;
      case "DEBUG":
        console.debug(jsonLog);
        break;
      default:
        console.log(jsonLog);
        break;
    }
  }
}

const logger = new Logger();

// ============================================================================
// HTTP CLIENT - BASE CLASS
// ============================================================================

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
    // Request interceptor - Add token if available
    this.client.interceptors.request.use((config) => {
      if (!this.tokenManager) return config;
      const token = this.tokenManager.get();
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });

    // Response interceptor - Handle 401 and refresh token
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
          } catch (refreshError) {
            return Promise.reject(error);
          }
        }
        return Promise.reject(error);
      }
    );
  }

  async get(endpoint, options = {}) {
    return this.makeRequest(endpoint, "GET", {}, options);
  }

  async put(endpoint, data, options = {}) {
    return this.makeRequest(endpoint, "PUT", { data }, options);
  }

  async patch(endpoint, data, options = {}) {
    return this.makeRequest(endpoint, "PATCH", { data }, options);
  }

  async delete(endpoint, options = {}) {
    return this.makeRequest(endpoint, "DELETE", {}, options);
  }

  async post(endpoint, data, options = {}) {
    return this.makeRequest(endpoint, "POST", { data }, options);
  }

  async makeRequest(
    endpoint,
    method = "GET",
    { data, params } = {},
    options = {}
  ) {
    // Generate request ID for tracking
    const requestId = options.requestId || crypto.randomUUID();
    logger.setRequestId(requestId);

    logger.info(`${method} request to ${endpoint}`, {
      method,
      endpoint,
      hasData: !!data,
      hasParams: !!params,
    });

    // Extract configuration values explicitly
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
      logger.error(`${method} request failed`, error);
      return this.handleError(error, endpoint, options);
    }
  }

  handleError(error, endpoint, options = {}) {
    const url = `${this.baseURL}${endpoint}`;
    const timeout = options?.timeout || this.defaultTimeout;

    // Timeout errors
    if (
      error?.code == "ECONNABORTED" ||
      error?.message?.toLowerCase()?.includes("timeout")
    ) {
      return {
        success: false,
        error: {
          type: "TimeoutError",
          message: `Request timed out after ${timeout}ms`,
          url: url,
          timeout: timeout,
          statusCode: 504,
          errorCode: ERROR_CODES.TIMEOUT_ERROR,
        },
      };
    }

    // HTTP errors
    if (error.response) {
      const status = error.response?.status || 500;
      const message = error.response?.data;
      return {
        success: false,
        error: {
          type: "APIError",
          message: `Error received from api with status: ${status}`,
          url: url,
          statusCode: status,
          data: message,
          errorCode: ERROR_CODES.API_ERROR,
        },
      };
    }

    // Network or other errors
    return {
      success: false,
      error: {
        type: "NetworkError",
        message: error.message || "Something went wrong",
        url: url,
        statusCode: 500,
        errorCode: ERROR_CODES.NETWORK_ERROR,
      },
    };
  }

  handleSuccess(response) {
    return {
      success: true,
      status: response.status,
      data: response.data,
      headers: response.headers,
    };
  }
}

// ============================================================================
// RESILIENT HTTP CLIENT - WITH RETRY & CIRCUIT BREAKER
// ============================================================================

class ResilientHTTPClient extends HTTPClient {
  constructor(baseURL, options = {}, tokenManager) {
    super(baseURL, options, tokenManager);

    // Configure axios-retry with exponential backoff
    axiosRetry(this.client, {
      retries: options.maxRetries ?? 3,
      retryDelay: (retryCount) => {
        const baseDelay = Math.min(1000 * Math.pow(2, retryCount - 1), 30000);
        const jitter = Math.random() * 1000;
        const totalDelay = baseDelay + jitter;

        logger.warn(`Retry attempt ${retryCount} for ${baseURL}`, {
          retryCount,
          delay: totalDelay,
          baseURL,
        });

        return totalDelay;
      },
      retryCondition: (error) => {
        return (
          axiosRetry.isNetworkOrIdempotentRequestError(error) ||
          this.isRetryableError(error)
        );
      },
    });

    // Configure circuit breaker
    const breakerTimeout = options.breakerTimeout || this.defaultTimeout + 500;
    this.breaker = new CircuitBreaker((config) => this.client.request(config), {
      timeout: breakerTimeout,
      errorThresholdPercentage: options.errorThresholdPercentage || 50,
      resetTimeout: options.resetTimeout || 30000,
    });

    // Circuit breaker event handlers for monitoring
    this.breaker.on("open", () => {
      logger.error(
        `[Circuit Breaker] OPEN - Stopping requests to ${this.baseURL}`,
        {
          baseURL: this.baseURL,
          state: "OPEN",
        }
      );
    });

    this.breaker.on("close", () => {
      logger.info(
        `[Circuit Breaker] CLOSED - Resuming requests to ${this.baseURL}`,
        {
          baseURL: this.baseURL,
          state: "CLOSED",
        }
      );
    });

    this.breaker.on("halfOpen", () => {
      logger.warn(`[Circuit Breaker] HALF-OPEN - Testing ${this.baseURL}`, {
        baseURL: this.baseURL,
        state: "HALF-OPEN",
      });
    });
  }

  // Override makeRequest to use circuit breaker
  async makeRequest(
    endpoint,
    method = "GET",
    { data, params } = {},
    options = {}
  ) {
    const requestId = options.requestId || crypto.randomUUID();
    logger.setRequestId(requestId);

    try {
      const response = await this.breaker.fire({
        url: endpoint,
        method,
        data,
        params,
        headers: options.headers,
        timeout: options.timeout || this.defaultTimeout,
        maxRedirects: options.maxRedirects || this.maxRedirects,
      });

      return this.handleSuccess(response);
    } catch (error) {
      // Handle circuit breaker open state
      if (error.message && error.message.includes("OPEN")) {
        return {
          success: false,
          error: {
            type: "ServiceUnavailableError",
            message: `${this.baseURL} is temporarily unavailable (Circuit Breaker OPEN)`,
            url: `${this.baseURL}${endpoint}`,
            statusCode: 503,
            errorCode: ERROR_CODES.SERVICE_UNAVAILABLE,
          },
        };
      }

      // Return error as JSON response
      return this.handleError(error, endpoint, options);
    }
  }

  // Override post to add idempotency
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

  // FIXED: Use error.response.status instead of error.status
  isRetryableError(error) {
    const canRetryStatus = [429, 500, 502, 503, 504];
    const status = error?.response?.status;
    return canRetryStatus.includes(status);
  }

  // Get circuit breaker stats for monitoring
  getStats() {
    return {
      circuitBreakerStats: this.breaker.stats,
      circuitBreakerState: this.breaker.opened ? "OPEN" : "CLOSED",
    };
  }
}

// ============================================================================
// EXPORTS
// ============================================================================

module.exports = {
  // Error Classes
  AppError,
  ValidationError,
  APIError,
  TimeoutError,
  NotFoundError,
  ERROR_CODES,

  // HTTP Clients
  HTTPClient,
  ResilientHTTPClient,

  // Token Management
  TokenManager,
  InMemoryStore,

  // Logger
  Logger,
  logger,
};
