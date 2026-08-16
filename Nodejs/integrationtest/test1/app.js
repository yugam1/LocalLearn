// ==================== STARTER CODE - Fill in the TODOs ====================
// You have 60 minutes to complete this

const express = require("express");
const axios = require("axios");

const app = express();
app.use(express.json());

// ==================== CONFIGURATION ====================

const PROVIDERS = {
  hotelfast: {
    name: "HotelFast",
    url: "https://httpbin.org/delay/1",
    timeout: 5000,
    priority: 1,
    maxRedirects: 5,
  },
  stayquick: {
    name: "StayQuick",
    url: "https://httpbin.org/status/503",
    timeout: 5000,
    priority: 2,
    maxRedirects: 5,
  },
  roomfinder: {
    name: "RoomFinder",
    url: "https://httpbin.org/delay/10",
    timeout: 5000,
    priority: 3,
    maxRedirects: 5,
  },
  quickstay: {
    name: "QuickStay",
    url: "https://httpbin.org/redirect/2",
    timeout: 5000,
    priority: 4,
    maxRedirects: 5,
  },
  budgetstay: {
    name: "BudgetStay",
    url: "https://httpbin.org/delay/2",
    timeout: 5000,
    priority: 5,
    maxRedirects: 5,
  },
  globalrooms: {
    name: "GlobalRooms",
    url: "https://httpbin.org/redirect/10",
    timeout: 5000,
    priority: 6,
    maxRedirects: 5,
  },
};

// ==================== TODO 1: ERROR CLASSES (5 min) ====================

class AppError extends Error {
  constructor(message, statusCode, errorCode) {
    super(message);
    this.status = statusCode;
    this.errorCode = errorCode;
    this.isOperational = true;
    Error.captureStackTrace(this, this.constructor);
  }
}

class ValidationError extends AppError {
  constructor(message, errors) {
    super(message, 403, `VALIDATION_ERROR`);
    this.errors = errors;
  }
}

class APIError extends AppError {
  constructor(message, statusCode, serviceMeta) {
    super(
      message,
      statusCode,
      `API_ERROR_${serviceMeta?.serviceName.toUpperCase()}`
    );
    this.meta = serviceMeta;
  }
}

class RedirectError extends AppError {
  constructor(message, serviceMeta) {
    super(
      message,
      310,
      `TOO_MANY_REDIRECTS_${serviceMeta.serviceName.toUpperCase()}`
    );
    this.meta = serviceMeta;
  }
}

// ==================== TODO 2: LOGGER (5 min) ====================

class Logger {
  log(level, message, meta = {}) {
    console.log(
      `${
        new Date().toISOString
      } [${level.toUpperCase()}] ${message} meta:${JSON.stringify(meta)}`
    );
  }

  info(message, meta) {
    // TODO: Implement info logging
    this.log("INFO", message, meta);
  }

  error(message, meta) {
    // TODO: Implement error logging
    console.error(
      `${
        new Date().toISOString
      } [${level.toUpperCase()}] ${message} meta:${JSON.stringify(meta)}`
    );
  }

  warn(message, meta) {
    // TODO: Implement warning logging
    this.log("WARN", message, meta);
  }
}

const logger = new Logger();

// ==================== TODO 3: REDIRECT TRACKER (3 min) ====================

class RedirectTracker {
  constructor() {
    this.redirects = new Map();
  }

  trackRedirect(provider, fromUrl, toUrl) {
    // TODO: Track redirect from one URL to another
    // Store as array of redirects per provider
    if (!this.redirects[provider]) {
      this.trackRedirect[provider] = [];
    }
    this.trackRedirect[provider].push({ fromUrl, toUrl });
  }

  getRedirectChain(provider) {
    // TODO: Get full redirect chain for a provider
    // Return array of URLs in order

    if (!this.redirects[provider]) {
      this.trackRedirect[provider] = [];
    }
    return this.trackRedirect[provider];
  }

  getRedirectCount(provider) {
    // TODO: Return number of redirects for a provider
    return this.trackRedirect?.[provider]?.length || 0;
  }

  clear(provider) {
    // TODO: Clear redirect history for a provider
    delete this.trackRedirect[provider];
  }
}

const redirectTracker = new RedirectTracker();

// ==================== TODO 4: CACHE (3 min) ====================

class Cache {
  constructor() {
    this.store = new Map();
  }

  get(key) {
    return this.store[key];
  }

  set(key, value, ttl = 300000) {
    // TODO: Implement set with TTL (5 min default)

    this.store[key] = value;
  }

  has(key) {
    // TODO: Check if key exists and not expired
    return Object.keys(this.store).includes(key);
  }
}

const cache = new Cache();

// ==================== TODO 5: CIRCUIT BREAKER (5 min) ====================

class CircuitBreaker {
  constructor(threshold = 3) {
    this.failures = new Map();
    this.threshold = threshold;
  }

  recordFailure(provider) {
    // TODO: Increment failure count
    this.failures[provider]++;
  }

  recordSuccess(provider) {
    // TODO: Reset failure count
    this.failures[provider] = 0;
  }

  isOpen(provider) {
    // TODO: Check if circuit is open (failures >= threshold)
    return this.failures[provider] > this.threshold;
  }

  getStatus() {
    // TODO: Return status of all providers
    return Object.keys(this.failures).map((key) => {
      key: this.failures[key] > this.threshold;
    });
  }
}

const circuitBreaker = new CircuitBreaker(3);

// ==================== TODO 6: HTTP CLIENT (15 min) ====================

class HttpClient {
  constructor(baseURL, serviceName, timeout = 5000) {
    this.baseURL = baseURL;
    this.serviceName = serviceName;
    this.timeout = timeout;
  }
  async request(method, endpoint, { data, params, headers }) {
    try {
      const config = {
        url: `${this.baseURL}${endpoint || ""}`,
        method,
        timeout: this.timeout,
      };

      if (headers) {
        config.headers = headers;
      }
      if (data) {
        config.data = data;
      }
      if (params) {
        config.params = params;
      }
      const response = await axios(config);
      return response.data;
    } catch (error) {
      throw new APIError(error.message, error?.response?.status, {
        serviceName: this.serviceName,
        response: {},
      });
    }
  }
}

class ProviderClient {
  constructor(provider) {
    this.provider = provider;
    this.config = PROVIDERS[provider];
  }

  async search(params, requestId) {
    // TODO: Make HTTP request with timeout and redirect handling
    // Configure axios with:
    // - timeout
    // - maxRedirects
    // - validateStatus to accept 3xx codes

    // TODO: Track redirects using axios interceptors or response data

    // TODO: Handle different error types:
    // - Timeout
    // - Too many redirects
    // - Network errors
    // - HTTP errors

    // TODO: Log request, redirects, and response

    // TODO: Track performance

    const startTime = Date.now();

    try {
      // TODO: Make axios call with redirect config
      // TODO: Check if redirects occurred
      // TODO: Log redirect chain if any
      // TODO: Transform response to standard format
      // TODO: Log success
      // TODO: Record in circuit breaker
    } catch (error) {
      // TODO: Handle different error types
      // TODO: Check if error is due to too many redirects
      // TODO: Log error with context
      // TODO: Record failure in circuit breaker
      // TODO: Throw appropriate error
    }
  }
}

// ==================== TODO 7: HOTEL SERVICE (15 min) ====================

class HotelService {
  async search(params, requestId) {
    const data = PROVIDERS.budgetstay;
    const client = new HttpClient(data.url, this.constructor.name, 5000);
    const res = await client.request("GET", null, {});
    return res;
  }

  validateParams(params) {
    // TODO: Validate city, checkIn, checkOut, guests
    // Throw ValidationError if invalid
  }

  async callProviders(params, requestId) {
    // TODO: Get active providers (circuit not open)
    // TODO: Call all providers in parallel
    // TODO: Return results, failures, and redirect info
  }

  aggregateResults(results) {
    // TODO: Combine hotel results from all providers
    // TODO: Remove duplicates
    // TODO: Sort by price or rating
  }
}

const hotelService = new HotelService();

// ==================== TODO 8: MIDDLEWARE (5 min) ====================

// Request ID middleware
app.use((req, res, next) => {
  // TODO: Generate unique request ID
  // TODO: Attach to request
  // TODO: Set response header
  next();
});

// Request logging middleware
app.use((req, res, next) => {
  // TODO: Log incoming request
  next();
});

// Async handler
const asyncHandler = (fn) => (req, res, next) => {
  return Promise.resolve(fn(req, res, next)).catch(next);
};

// ==================== TODO 9: ROUTES (10 min) ====================

// Main search endpoint
app.get(
  "/api/hotels/search",
  asyncHandler(async (req, res) => {
    res.json(hotelService.search());
    // TODO: Extract query params
    // TODO: Call hotel service
    // TODO: Return formatted response with redirect info
  })
);

// Health check endpoint
app.get(
  "/health",
  asyncHandler(async (req, res) => {
    res.json({ h: "W" });
    // TODO: Check provider health
    // TODO: Include circuit breaker status
    // TODO: Include redirect statistics
    // TODO: Return overall health status
  })
);

// Circuit breaker status endpoint
app.get(
  "/api/status/providers",
  asyncHandler(async (req, res) => {
    // TODO: Return circuit breaker status for all providers
    // TODO: Include redirect counts
  })
);

// Cache stats endpoint
app.get(
  "/api/status/cache",
  asyncHandler(async (req, res) => {
    // TODO: Return cache statistics
  })
);

// Redirect stats endpoint
app.get(
  "/api/status/redirects",
  asyncHandler(async (req, res) => {
    // TODO: Return redirect statistics for all providers
    // TODO: Show redirect chains if available
  })
);

// ==================== TODO 10: ERROR HANDLER (5 min) ====================

// 404 handler
app.use((req, res, next) => {
  // TODO: Return 404 error
});

// Global error handler
app.use((err, req, res, next) => {
  // TODO: Log error with full context
  // TODO: Determine status code
  // TODO: Build error response
  // TODO: Include redirect info if relevant
  // TODO: Send response
});

// ==================== START SERVER ====================

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
  console.log(`🚀 Server running on port ${PORT}`);
  console.log("\n📋 Available endpoints:");
  console.log("  GET /api/hotels/search?city=X&checkIn=Y&checkOut=Z&guests=N");
  console.log("  GET /health");
  console.log("  GET /api/status/providers");
  console.log("  GET /api/status/cache");
  console.log("  GET /api/status/redirects");
  console.log("\n⏱️  You have 60 minutes. Good luck!\n");
});

// ==================== TEST SCENARIOS ====================

/*
Test these scenarios manually or with a testing tool:

1. Success Case with Redirects:
   GET /api/hotels/search?city=Paris&checkIn=2025-12-01&checkOut=2025-12-05&guests=2
   Expected: Should return results from HotelFast, QuickStay (after redirects), and BudgetStay

2. Missing Parameters:
   GET /api/hotels/search?city=Paris
   Expected: 400 ValidationError

3. Too Many Redirects:
   - GlobalRooms should fail due to 10 redirects (max is 5)
   - Other providers should still work

4. All Providers Down (after multiple failures):
   - Call search 3 times to trigger circuit breakers
   - Should fall back to cache

5. Check Health:
   GET /health
   Expected: Health status including redirect stats

6. Provider Status:
   GET /api/status/providers
   Expected: Circuit breaker status with redirect counts

7. Redirect Stats:
   GET /api/status/redirects
   Expected: Redirect chain information for all providers
*/

module.exports = { app };

// ==================== HINTS ====================

/*
IMPLEMENTATION HINTS:

1. Error Classes:
   - RedirectError should track redirect count and limit
   - Include redirect chain in error details

2. Redirect Tracker:
   - Use array to store redirect chain
   - Track: original URL → intermediate URLs → final URL

3. HTTP Client:
   - Configure axios: { maxRedirects: 5, timeout: 5000 }
   - Check error.code === 'ERR_FR_TOO_MANY_REDIRECTS'
   - Log each redirect in the chain

4. Axios Redirect Handling:
   ```javascript
   const config = {
     maxRedirects: this.config.maxRedirects,
     timeout: this.config.timeout,
     validateStatus: (status) => status >= 200 && status < 400
   };
   ```

5. Detect Redirects:
   - Check response.request.res.responseUrl !== original URL
   - Count redirects from response history

6. Logging Redirects:
   - Log: provider, original URL, final URL, redirect count
   - Include redirect chain in detailed logs

7. Circuit Breaker:
   - Count "too many redirects" as failure
   - Track redirect failures separately

8. Meta Response:
   - Include redirectsFollowed: { provider: count }
   - Show redirect chains for debugging

TIME MANAGEMENT:
- Don't spend >5 min on redirect tracking
- Get basic redirect handling working first
- Add detailed tracking as time permits
- Test redirect scenarios early
*/
