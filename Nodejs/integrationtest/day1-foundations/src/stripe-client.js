require("dotenv").config();
const Stripe = require("stripe");

/**
 * Stripe Client Wrapper
 * Provides clean interface to Stripe API with error handling
 */
class StripeClient {
  constructor(apiKey = null) {
    // Use provided key or environment variable
    this.apiKey = apiKey || process.env.STRIPE_SECRET_KEY;

    if (!this.apiKey) {
      throw new Error(
        "Stripe API key is required. Set STRIPE_SECRET_KEY in .env"
      );
    }

    // Initialize Stripe SDK
    this.stripe = new Stripe(this.apiKey, {
      apiVersion: "2023-10-16", // Use latest stable version
      timeout: 30000, // 30 second timeout
      maxNetworkRetries: 2, // Auto-retry on network failures
    });

    console.log("✅ Stripe Client initialized");
  }

  /**
   * Handle Stripe errors consistently
   */
  handleStripeError(error, operation) {
    console.error(`[STRIPE ERROR] ${operation}:`, error.message);

    return {
      success: false,
      error: {
        type: error.type, // card_error, api_error, etc.
        message: error.message,
        code: error.code, // card_declined, insufficient_funds, etc.
        statusCode: error.statusCode,
        operation,
        timestamp: new Date().toISOString(),
      },
    };
  }

  /**
   * Format successful response
   */
  formatSuccess(data, operation) {
    return {
      success: true,
      data,
      operation,
      timestamp: new Date().toISOString(),
    };
  }

  // ==================== CUSTOMER OPERATIONS ====================

  /**
   * Create a new customer
   * @param {object} customerData - { email, name, metadata }
   */
  async createCustomer(customerData) {
    try {
      console.log(`[Stripe] Creating customer: ${customerData.email}`);

      const customer = await this.stripe.customers.create({
        email: customerData.email,
        name: customerData.name,
        metadata: customerData.metadata || {},
        description: customerData.description || "",
      });

      console.log(`✅ Customer created: ${customer.id}`);
      return this.formatSuccess(customer, "createCustomer");
    } catch (error) {
      return this.handleStripeError(error, "createCustomer");
    }
  }

  /**
   * Retrieve a customer by ID
   * @param {string} customerId - cus_xxx
   */
  async getCustomer(customerId) {
    try {
      console.log(`[Stripe] Retrieving customer: ${customerId}`);

      const customer = await this.stripe.customers.retrieve(customerId);

      console.log(`✅ Customer retrieved: ${customer.email}`);
      return this.formatSuccess(customer, "getCustomer");
    } catch (error) {
      return this.handleStripeError(error, "getCustomer");
    }
  }

  /**
   * Update customer information
   * @param {string} customerId - cus_xxx
   * @param {object} updateData - Fields to update
   */
  async updateCustomer(customerId, updateData) {
    try {
      console.log(`[Stripe] Updating customer: ${customerId}`);

      const customer = await this.stripe.customers.update(
        customerId,
        updateData
      );

      console.log(`✅ Customer updated: ${customer.id}`);
      return this.formatSuccess(customer, "updateCustomer");
    } catch (error) {
      return this.handleStripeError(error, "updateCustomer");
    }
  }

  /**
   * Delete a customer
   * @param {string} customerId - cus_xxx
   */
  async deleteCustomer(customerId) {
    try {
      console.log(`[Stripe] Deleting customer: ${customerId}`);

      const deleted = await this.stripe.customers.del(customerId);

      console.log(`✅ Customer deleted: ${customerId}`);
      return this.formatSuccess(deleted, "deleteCustomer");
    } catch (error) {
      return this.handleStripeError(error, "deleteCustomer");
    }
  }

  /**
   * List customers with pagination
   * @param {object} options - { limit, starting_after, ending_before }
   */
  async listCustomers(options = {}) {
    try {
      const { limit = 10, starting_after, ending_before } = options;

      console.log(`[Stripe] Listing customers (limit: ${limit})`);

      const customers = await this.stripe.customers.list({
        limit,
        starting_after,
        ending_before,
      });

      console.log(`✅ Retrieved ${customers.data.length} customers`);
      return this.formatSuccess(
        {
          customers: customers.data,
          has_more: customers.has_more,
          count: customers.data.length,
        },
        "listCustomers"
      );
    } catch (error) {
      return this.handleStripeError(error, "listCustomers");
    }
  }

  /**
   * Search customers by email
   * @param {string} email - Customer email
   */
  async findCustomerByEmail(email) {
    try {
      console.log(`[Stripe] Searching customer by email: ${email}`);

      const customers = await this.stripe.customers.list({
        email: email,
        limit: 1,
      });

      if (customers.data.length === 0) {
        return this.formatSuccess(null, "findCustomerByEmail");
      }

      console.log(`✅ Customer found: ${customers.data[0].id}`);
      return this.formatSuccess(customers.data[0], "findCustomerByEmail");
    } catch (error) {
      return this.handleStripeError(error, "findCustomerByEmail");
    }
  }

  /**
   * Get or create customer (idempotent operation)
   * @param {string} email - Customer email
   * @param {object} customerData - Additional customer data
   */
  async getOrCreateCustomer(email, customerData = {}) {
    try {
      // First, try to find existing customer
      const existingResult = await this.findCustomerByEmail(email);

      if (existingResult.success && existingResult.data) {
        console.log(`✅ Using existing customer: ${existingResult.data.id}`);
        return existingResult;
      }

      // Customer doesn't exist, create new one
      console.log(`[Stripe] Customer not found, creating new one`);
      return await this.createCustomer({
        email,
        ...customerData,
      });
    } catch (error) {
      return this.handleStripeError(error, "getOrCreateCustomer");
    }
  }

  /**
   * Create customer with a test payment method
   * @param {object} customerData - Customer info
   * @param {string} paymentMethodId - pm_xxx or test token
   */
  async createCustomerWithPayment(
    customerData,
    paymentMethodId = "pm_card_visa"
  ) {
    try {
      // Create customer
      const customerResult = await this.createCustomer(customerData);

      if (!customerResult.success) {
        return customerResult;
      }

      const customerId = customerResult.data.id;

      // Attach payment method
      await this.stripe.paymentMethods.attach(paymentMethodId, {
        customer: customerId,
      });

      // Set as default payment method
      await this.stripe.customers.update(customerId, {
        invoice_settings: {
          default_payment_method: paymentMethodId,
        },
      });

      console.log(`✅ Payment method attached to customer`);
      return this.formatSuccess(
        {
          customer: customerResult.data,
          paymentMethod: paymentMethodId,
        },
        "createCustomerWithPayment"
      );
    } catch (error) {
      return this.handleStripeError(error, "createCustomerWithPayment");
    }
  }

  async getCustomerStats() {
    try {
      const result = await this.listCustomers({ limit: 100 });

      if (!result.success) return result;

      const customers = result.data.customers;

      return this.formatSuccess(
        {
          total: customers.length,
          withEmail: customers.filter((c) => c.email).length,
          withPaymentMethod: customers.filter(
            (c) =>
              c.default_source || c.invoice_settings?.default_payment_method
          ).length,
          created_today: customers.filter((c) => {
            const created = new Date(c.created * 1000);
            const today = new Date();
            return created.toDateString() === today.toDateString();
          }).length,
        },
        "getCustomerStats"
      );
    } catch (error) {
      return this.handleStripeError(error, "getCustomerStats");
    }
  }
}

module.exports = StripeClient;
