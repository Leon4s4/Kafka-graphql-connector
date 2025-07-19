const { ApolloServer, gql } = require('apollo-server-express');
const express = require('express');

// Mock data
const users = Array.from({ length: 100 }, (_, i) => ({
  id: `user_${i + 1}`,
  name: `User ${i + 1}`,
  email: `user${i + 1}@example.com`,
  createdAt: new Date(Date.now() - Math.random() * 365 * 24 * 60 * 60 * 1000).toISOString(),
  status: ['ACTIVE', 'INACTIVE', 'PENDING'][Math.floor(Math.random() * 3)]
}));

const products = Array.from({ length: 50 }, (_, i) => ({
  id: `product_${i + 1}`,
  name: `Product ${i + 1}`,
  description: `This is a description for product ${i + 1}`,
  price: Math.floor(Math.random() * 1000) + 10,
  category: ['Electronics', 'Books', 'Clothing', 'Home'][Math.floor(Math.random() * 4)],
  inStock: Math.random() > 0.2
}));

// GraphQL schema
const typeDefs = gql`
  type User {
    id: ID!
    name: String!
    email: String!
    createdAt: String!
    status: UserStatus!
  }

  type Product {
    id: ID!
    name: String!
    description: String
    price: Float!
    category: String!
    inStock: Boolean!
  }

  enum UserStatus {
    ACTIVE
    INACTIVE
    PENDING
  }

  type UserConnection {
    edges: [UserEdge!]!
    pageInfo: PageInfo!
  }

  type UserEdge {
    node: User!
    cursor: String!
  }

  type ProductConnection {
    edges: [ProductEdge!]!
    pageInfo: PageInfo!
  }

  type ProductEdge {
    node: Product!
    cursor: String!
  }

  type PageInfo {
    hasNextPage: Boolean!
    hasPreviousPage: Boolean!
    startCursor: String
    endCursor: String
  }

  type Query {
    users(first: Int, after: String, last: Int, before: String): UserConnection!
    products(first: Int, after: String, last: Int, before: String): ProductConnection!
    user(id: ID!): User
    product(id: ID!): Product
  }
`;

// Helper function to create cursor-based pagination
function createConnection(items, first, after, last, before) {
  let startIndex = 0;
  let endIndex = items.length;

  // Handle 'after' cursor
  if (after) {
    const afterIndex = items.findIndex(item => item.id === after);
    if (afterIndex !== -1) {
      startIndex = afterIndex + 1;
    }
  }

  // Handle 'before' cursor
  if (before) {
    const beforeIndex = items.findIndex(item => item.id === before);
    if (beforeIndex !== -1) {
      endIndex = beforeIndex;
    }
  }

  // Apply first/last limits
  if (first) {
    endIndex = Math.min(startIndex + first, endIndex);
  }
  if (last) {
    startIndex = Math.max(endIndex - last, startIndex);
  }

  const slicedItems = items.slice(startIndex, endIndex);
  const edges = slicedItems.map(item => ({
    node: item,
    cursor: item.id
  }));

  const pageInfo = {
    hasNextPage: endIndex < items.length,
    hasPreviousPage: startIndex > 0,
    startCursor: edges.length > 0 ? edges[0].cursor : null,
    endCursor: edges.length > 0 ? edges[edges.length - 1].cursor : null
  };

  return { edges, pageInfo };
}

// GraphQL resolvers
const resolvers = {
  Query: {
    users: (_, { first = 10, after, last, before }) => {
      console.log(`GraphQL Query: users(first: ${first}, after: ${after})`);
      return createConnection(users, first, after, last, before);
    },
    products: (_, { first = 10, after, last, before }) => {
      console.log(`GraphQL Query: products(first: ${first}, after: ${after})`);
      return createConnection(products, first, after, last, before);
    },
    user: (_, { id }) => {
      return users.find(user => user.id === id);
    },
    product: (_, { id }) => {
      return products.find(product => product.id === id);
    }
  }
};

async function startServer() {
  const app = express();
  
  // Add basic health check endpoint
  app.get('/health', (req, res) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
  });

  // Add info endpoint
  app.get('/info', (req, res) => {
    res.json({
      service: 'Mock GraphQL API',
      version: '1.0.0',
      endpoints: {
        graphql: '/graphql',
        playground: '/graphql',
        health: '/health'
      },
      data: {
        users: users.length,
        products: products.length
      }
    });
  });

  const server = new ApolloServer({
    typeDefs,
    resolvers,
    introspection: true,
    playground: true,
    formatResponse: (response, { request }) => {
      console.log(`GraphQL Response: ${JSON.stringify(response.data ? Object.keys(response.data) : 'error')}`);
      return response;
    }
  });

  await server.start();
  server.applyMiddleware({ app, path: '/graphql' });

  const PORT = process.env.PORT || 4000;
  
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 Mock GraphQL API running at http://localhost:${PORT}${server.graphqlPath}`);
    console.log(`📊 Data available: ${users.length} users, ${products.length} products`);
    console.log(`🏥 Health check: http://localhost:${PORT}/health`);
    console.log(`ℹ️  Info endpoint: http://localhost:${PORT}/info`);
  });
}

// Handle graceful shutdown
process.on('SIGTERM', () => {
  console.log('Received SIGTERM, shutting down gracefully');
  process.exit(0);
});

process.on('SIGINT', () => {
  console.log('Received SIGINT, shutting down gracefully');
  process.exit(0);
});

startServer().catch(error => {
  console.error('Error starting server:', error);
  process.exit(1);
});