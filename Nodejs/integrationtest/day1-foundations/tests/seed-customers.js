// tests/seed-customers.js
const StripeClient = require("../src/stripe-client");

async function seedTestCustomers() {
  const stripe = new StripeClient();

  const testCustomers = [
    { email: "alice@example.com", name: "Alice Johnson" },
    { email: "bob@example.com", name: "Bob Smith" },
    { email: "carol@example.com", name: "Carol Davis" },
    { email: "david@example.com", name: "David Wilson" },
    { email: "eve@example.com", name: "Eve Martinez" },
  ];

  console.log(`Creating ${testCustomers.length} test customers...`);

  for (const customer of testCustomers) {
    const result = await stripe.createCustomer(customer);
    if (result.success) {
      console.log(`✅ ${customer.email} - ${result.data.id}`);
    }
  }

  console.log("\nSeeding complete!");
}

seedTestCustomers();
