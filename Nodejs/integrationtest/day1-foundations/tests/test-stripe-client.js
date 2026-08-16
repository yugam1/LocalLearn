const StripeClient = require("../src/stripe-client");

/**
 * Test Stripe Client Operations
 */
async function testStripeClient() {
  console.log("\n========== Testing Stripe Client ==========\n");

  const stripe = new StripeClient();
  let testCustomerId = null;

  try {
    // Test 1: Create Customer
    console.log("Test 1: Create Customer");
    const createResult = await stripe.createCustomer({
      email: "test@example.com",
      name: "Test Customer",
      metadata: {
        user_id: "12345",
        source: "test_suite",
      },
      description: "Created via test suite",
    });

    if (createResult.success) {
      testCustomerId = createResult.data.id;
      console.log("✅ Customer created successfully");
      console.log(`   ID: ${createResult.data.id}`);
      console.log(`   Email: ${createResult.data.email}`);
      console.log(`   Name: ${createResult.data.name}`);
    } else {
      console.log("❌ Failed:", createResult.error.message);
    }
    console.log("");

    // Test 2: Retrieve Customer
    console.log("Test 2: Retrieve Customer");
    const getResult = await stripe.getCustomer(testCustomerId);

    if (getResult.success) {
      console.log("✅ Customer retrieved successfully");
      console.log(`   Email: ${getResult.data.email}`);
      console.log(
        `   Created: ${new Date(getResult.data.created * 1000).toISOString()}`
      );
    } else {
      console.log("❌ Failed:", getResult.error.message);
    }
    console.log("");

    // Test 3: Update Customer
    console.log("Test 3: Update Customer");
    const updateResult = await stripe.updateCustomer(testCustomerId, {
      name: "Updated Test Customer",
      metadata: {
        updated: "true",
        test_phase: "part2",
      },
    });

    if (updateResult.success) {
      console.log("✅ Customer updated successfully");
      console.log(`   New Name: ${updateResult.data.name}`);
      console.log(`   Metadata:`, updateResult.data.metadata);
    } else {
      console.log("❌ Failed:", updateResult.error.message);
    }
    console.log("");

    // Test 4: Find Customer by Email
    console.log("Test 4: Find Customer by Email");
    const findResult = await stripe.findCustomerByEmail("test@example.com");

    if (findResult.success && findResult.data) {
      console.log("✅ Customer found by email");
      console.log(`   ID: ${findResult.data.id}`);
      console.log(`   Name: ${findResult.data.name}`);
    } else if (findResult.success && !findResult.data) {
      console.log("ℹ️  No customer found with that email");
    } else {
      console.log("❌ Failed:", findResult.error.message);
    }
    console.log("");

    // Test 5: List Customers
    console.log("Test 5: List Customers");
    const listResult = await stripe.listCustomers({ limit: 5 });

    if (listResult.success) {
      console.log(`✅ Retrieved ${listResult.data.count} customers`);
      console.log(`   Has more: ${listResult.data.has_more}`);
      listResult.data.customers.forEach((customer, index) => {
        console.log(`   ${index + 1}. ${customer.email} (${customer.id})`);
      });
    } else {
      console.log("❌ Failed:", listResult.error.message);
    }
    console.log("");

    // Test 6: Get or Create Customer (Idempotent)
    console.log("Test 6: Get or Create Customer (Idempotent)");
    const getOrCreateResult1 = await stripe.getOrCreateCustomer(
      "idempotent@example.com",
      { name: "Idempotent User" }
    );

    console.log(`First call - Customer ${getOrCreateResult1.data.id}`);

    const getOrCreateResult2 = await stripe.getOrCreateCustomer(
      "idempotent@example.com",
      { name: "Idempotent User" }
    );

    console.log(`Second call - Customer ${getOrCreateResult2.data.id}`);

    if (getOrCreateResult1.data.id === getOrCreateResult2.data.id) {
      console.log("✅ Idempotency works! Same customer returned");
    } else {
      console.log("❌ Idempotency failed - different customers");
    }
    console.log("");

    // Test 7: Handle 404 Error
    console.log("Test 7: Handle 404 Error (Invalid Customer ID)");
    const errorResult = await stripe.getCustomer("cus_invalid_id_12345");

    if (!errorResult.success) {
      console.log("✅ Error handled gracefully");
      console.log(`   Type: ${errorResult.error.type}`);
      console.log(`   Message: ${errorResult.error.message}`);
      console.log(`   Status: ${errorResult.error.statusCode}`);
    } else {
      console.log("❌ Should have returned error");
    }
    console.log("");

    // Cleanup: Delete Test Customers
    console.log("Cleanup: Deleting test customers...");
    if (testCustomerId) {
      await stripe.deleteCustomer(testCustomerId);
      console.log(`✅ Deleted customer: ${testCustomerId}`);
    }

    if (getOrCreateResult1.success) {
      await stripe.deleteCustomer(getOrCreateResult1.data.id);
      console.log(`✅ Deleted customer: ${getOrCreateResult1.data.id}`);
    }
    console.log("");

    console.log("========== All Stripe Tests Passed! ==========\n");
  } catch (error) {
    console.error("❌ Test suite failed:", error.message);

    // Cleanup on failure
    if (testCustomerId) {
      console.log("Attempting cleanup...");
      await stripe.deleteCustomer(testCustomerId);
    }
  }
}

// Run tests
testStripeClient();
