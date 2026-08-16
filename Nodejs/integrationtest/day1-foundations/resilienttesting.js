// test-client.js

const { HTTPClient, ResilientHTTPClient } = require("./base");

// Dummy tokenManager that returns expired token once, then new token
class DummyTokenManager {
  constructor() {
    this.token = null;
  }

  get() {
    return this.token; // null initially → 401
  }

  async refreshToken() {
    console.log("🔄 Refreshing token...");
    this.token = "good-token";
    return this.token;
  }
}

async function test401Recovery() {
  const client = new HTTPClient(
    "http://localhost:80",
    {},
    new DummyTokenManager()
  );

  console.log("👉 Expect 401, refresh, then success");

  const resp = await client.get("/bearer");

  console.log("✅ Success:", resp.status);
}

// Use httpbin to simulate 401, then succeed
async function testHttpClient() {
  const client = new HTTPClient(
    "http://localhost:80",
    {},
    new DummyTokenManager()
  );

  test401Recovery();

  try {
    // Then request a normal endpoint
    console.log("👉 2: Expect 200 OK");
    const resp = await client.get("/get");
    console.log("Success:", resp.status, resp.data);
  } catch (err) {
    console.error("Unexpected error:", err);
  }
}

async function testResilientClient() {
  const client = new ResilientHTTPClient(
    "http://localhost:80",
    { defaultTimeout: 3000, maxRetries: 2 },
    new DummyTokenManager()
  );

  try {
    console.log("👉 3: Test retry / get");
    const resp = await client.get("/get");
    console.log("Success:", resp.status, resp.data);
  } catch (err) {
    console.error("Unexpected error:", err);
  }

  // To test retry or failure: you can call /status/500 to simulate server error
  try {
    console.log("👉 4: Simulate server error (500) to test retry");
    const resp = await client.get("/status/500");
    console.log("Unexpected success:", resp);
  } catch (err) {
    console.error("Expected fail after retries:", err.message);
  }

  // To test timeout: use httpbin delay endpoint (e.g. /delay/5) with small timeout
  try {
    console.log("👉 5: Simulate slow response to trigger timeout");
    const resp = await client.get("/delay/5", { timeout: 1000 });
    console.log("Unexpected success:", resp);
  } catch (err) {
    console.error("Expected timeout error:", err.message);
  }
}

(async () => {
  await testHttpClient();
  await testResilientClient();
})();
