// ============================================================================
// example-usage.js - Usage Examples for Part 4 (Stripe Integration)
// ============================================================================

const {
  HTTPClient,
  ResilientHTTPClient,
  TokenManager,
  InMemoryStore,
  logger,
} = require("./helperModule");

// ============================================================================
// EXAMPLE 1: Basic HTTPClient (for internal/reliable APIs)
// ============================================================================

async function example1_BasicHTTPClient() {
  console.log("\n=== Example 1: Basic HTTPClient ===");

  const client = new HTTPClient("https://jsonplaceholder.typicode.com", {
    defaultTimeout: 5000,
  });

  // GET request
  const getResult = await client.get("/posts/1");

  if (getResult.success) {
    console.log("✅ GET successful:", getResult.data);
  } else {
    console.error("❌ GET failed:", getResult.error);
  }

  // POST request
  const postResult = await client.post("/posts", {
    title: "foo",
    body: "bar",
    userId: 1,
  });

  if (postResult.success) {
    console.log("✅ POST successful:", postResult.data);
  } else {
    console.error("❌ POST failed:", postResult.error);
  }
}

// ============================================================================
// EXAMPLE 2: ResilientHTTPClient with Retry & Circuit Breaker
// ============================================================================

async function example2_ResilientHTTPClient() {
  console.log("\n=== Example 2: ResilientHTTPClient ===");

  const client = new ResilientHTTPClient("https://httpstat.us", {
    maxRetries: 3,
    defaultTimeout: 5000,
  });

  // Test timeout handling
  const timeoutResult = await client.get("/200?sleep=10000", { timeout: 3000 });

  if (!timeoutResult.success) {
    console.log("❌ Timeout occurred:", timeoutResult.error.message);
    console.log("   Error type:", timeoutResult.error.type);
    console.log("   Status code:", timeoutResult.error.statusCode);
  }

  // Test successful request
  const successResult = await client.get("/200");

  if (successResult.success) {
    console.log("✅ Request successful:", successResult.status);
  }

  // Test retry on 500 error
  const retryResult = await client.get("/500");

  if (!retryResult.success) {
    console.log("❌ After retries, still failed:", retryResult.error.message);
  }
}

// ============================================================================
// EXAMPLE 3: Stripe Client (Ready for Part 4)
// ============================================================================

class StripeClient extends ResilientHTTPClient {
  constructor(apiKey, options = {}) {
    super("https://api.stripe.com/v1", {
      maxRetries: 3,
      defaultTimeout: 10000,
      ...options,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Stripe-Version": "2023-10-16",
        ...(options.headers || {}),
      },
    });
  }

  // Create a charge
  async createCharge(amount, currency, source, options = {}) {
    const result = await this.post(
      "/charges",
      {
        amount,
        currency,
        source,
      },
      options
    );

    return result;
  }

  // Create a customer
  async createCustomer(email, name, options = {}) {
    const result = await this.post(
      "/customers",
      {
        email,
        name,
      },
      options
    );

    return result;
  }

  // Get customer details
  async getCustomer(customerId, options = {}) {
    const result = await this.get(`/customers/${customerId}`, options);
    return result;
  }

  // Create a PaymentIntent
  async createPaymentIntent(amount, currency, options = {}) {
    const result = await this.post(
      "/payment_intents",
      {
        amount,
        currency,
        ...options.metadata,
      },
      options
    );

    return result;
  }

  // Confirm a PaymentIntent
  async confirmPaymentIntent(paymentIntentId, paymentMethod, options = {}) {
    const result = await this.post(
      `/payment_intents/${paymentIntentId}/confirm`,
      {
        payment_method: paymentMethod,
      },
      options
    );

    return result;
  }
}

async function example3_StripeClient() {
  console.log("\n=== Example 3: Stripe Client (Mock) ===");

  // Use test API key from Stripe
  const stripe = new StripeClient("sk_test_YOUR_TEST_KEY");

  // Create customer with idempotency
  const customerResult = await stripe.createCustomer(
    "test@example.com",
    "Test User",
    {
      idempotencyKey: "customer-create-001",
    }
  );

  if (customerResult.success) {
    console.log("✅ Customer created:", customerResult.data.id);
  } else {
    console.error("❌ Customer creation failed:", customerResult.error);
  }

  // Create payment intent with custom idempotency key
  const paymentResult = await stripe.createPaymentIntent(
    5000, // $50.00
    "usd",
    {
      idempotencyKey: `payment-${Date.now()}`,
    }
  );

  if (paymentResult.success) {
    console.log("✅ PaymentIntent created:", paymentResult.data.id);
  } else {
    console.error("❌ Payment creation failed:", paymentResult.error);
  }
}

// ============================================================================
// EXAMPLE 4: Token Management with Auto-Refresh
// ============================================================================

async function example4_TokenManagement() {
  console.log("\n=== Example 4: Token Management ===");

  // Simulate a token refresh function
  let tokenRefreshCount = 0;
  const mockTokenRefresh = async () => {
    tokenRefreshCount++;
    console.log(`🔄 Token refresh called (${tokenRefreshCount} times)`);
    return `token-${Date.now()}`;
  };

  const tokenManager = new TokenManager(mockTokenRefresh, new InMemoryStore());

  // Initialize with a token
  await tokenManager.refreshToken();

  const client = new HTTPClient(
    "https://jsonplaceholder.typicode.com",
    { defaultTimeout: 5000 },
    tokenManager
  );

  // Make request - token automatically added
  const result = await client.get("/posts/1");

  if (result.success) {
    console.log("✅ Request with token successful");
  }
}

// ============================================================================
// EXAMPLE 5: Error Handling Patterns
// ============================================================================

async function example5_ErrorHandling() {
  console.log("\n=== Example 5: Error Handling Patterns ===");

  const client = new ResilientHTTPClient("https://httpstat.us", {
    maxRetries: 2,
    defaultTimeout: 3000,
  });

  // Handle different error types
  const results = [
    await client.get("/404"), // Not found
    await client.get("/500"), // Server error (will retry)
    await client.get("/200?sleep=5000", { timeout: 2000 }), // Timeout
  ];

  results.forEach((result, index) => {
    if (!result.success) {
      console.log(`\n❌ Request ${index + 1} failed:`);
      console.log(`   Type: ${result.error.type}`);
      console.log(`   Message: ${result.error.message}`);
      console.log(`   Status: ${result.error.statusCode}`);
    } else {
      console.log(`\n✅ Request ${index + 1} succeeded`);
    }
  });
}

// ============================================================================
// EXAMPLE 6: Circuit Breaker in Action
// ============================================================================

async function example6_CircuitBreaker() {
  console.log("\n=== Example 6: Circuit Breaker ===");

  const client = new ResilientHTTPClient("https://httpstat.us", {
    maxRetries: 1,
    defaultTimeout: 2000,
    errorThresholdPercentage: 50, // Open after 50% errors
    resetTimeout: 5000, // Try again after 5 seconds
  });

  console.log("Making 10 failing requests to trigger circuit breaker...");

  // Make multiple failing requests to trigger circuit breaker
  for (let i = 0; i < 10; i++) {
    const result = await client.get("/500");
    if (!result.success) {
      console.log(
        `Request ${i + 1}: ${result.error.type} - ${result.error.message}`
      );

      // Check if circuit breaker opened
      if (result.error.type === "ServiceUnavailableError") {
        console.log("\n🔴 Circuit Breaker OPEN - Stopping requests");
        break;
      }
    }
  }

  // Get circuit breaker stats
  const stats = client.getStats();
  console.log("\n📊 Circuit Breaker Stats:", stats);
}

// ============================================================================
// EXAMPLE 7: Complete Stripe Payment Flow (Part 4 Preview)
// ============================================================================

async function example7_StripePaymentFlow() {
  console.log("\n=== Example 7: Stripe Payment Flow ===");

  const stripe = new StripeClient("sk_test_YOUR_TEST_KEY");

  // Step 1: Create customer
  const customerKey = `customer-${Date.now()}`;
  const customer = await stripe.createCustomer(
    "customer@example.com",
    "John Doe",
    { idempotencyKey: customerKey }
  );

  if (!customer.success) {
    console.error("❌ Customer creation failed:", customer.error);
    return;
  }

  console.log("✅ Step 1: Customer created");

  // Step 2: Create payment intent
  const paymentKey = `payment-${Date.now()}`;
  const payment = await stripe.createPaymentIntent(10000, "usd", {
    idempotencyKey: paymentKey,
    metadata: {
      customer: customer.data.id,
    },
  });

  if (!payment.success) {
    console.error("❌ Payment intent creation failed:", payment.error);
    return;
  }

  console.log("✅ Step 2: Payment intent created");

  // Step 3: Confirm payment (would use real payment method in production)
  const confirmation = await stripe.confirmPaymentIntent(
    payment.data.id,
    "pm_card_visa", // Test payment method
    {
      idempotencyKey: `confirm-${paymentKey}`,
    }
  );

  if (confirmation.success) {
    console.log("✅ Step 3: Payment confirmed");
    console.log("💰 Payment successful:", confirmation.data);
  } else {
    console.error("❌ Payment confirmation failed:", confirmation.error);
  }
}

// ============================================================================
// RUN ALL EXAMPLES
// ============================================================================

async function runAllExamples() {
  try {
    await example1_BasicHTTPClient();
    await example2_ResilientHTTPClient();
    // await example3_StripeClient(); // Uncomment with real Stripe key
    await example4_TokenManagement();
    await example5_ErrorHandling();
    await example6_CircuitBreaker();
    // await example7_StripePaymentFlow(); // Uncomment with real Stripe key
  } catch (error) {
    console.error("Example execution error:", error);
  }
}

// Run examples if executed directly
if (require.main === module) {
  runAllExamples();
}

module.exports = {
  StripeClient,
  example1_BasicHTTPClient,
  example2_ResilientHTTPClient,
  example3_StripeClient,
  example4_TokenManagement,
  example5_ErrorHandling,
  example6_CircuitBreaker,
  example7_StripePaymentFlow,
};
