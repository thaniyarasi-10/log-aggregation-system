import express from 'express';
import axios from 'axios';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { readFileSync } from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const app = express();
const PORT = 3000;

// Middleware: Parse JSON body BEFORE routes
app.use(express.json({ limit: '50mb' }));

// Logging middleware
app.use((req, res, next) => {
  console.log(`${req.method} ${req.path}`, {
    headers: req.headers,
    body: req.body
  });
  next();
});

// Proxy route: POST /api/agent/query -> FastAPI POST /ask
app.post('/api/agent/query', async (req, res) => {
  try {
    console.log('=== INCOMING REQUEST TO /api/agent/query ===');
    console.log('req.body:', JSON.stringify(req.body, null, 2));
    console.log('Content-Type:', req.headers['content-type']);

    // Validate required fields (accept both "query" and "question")
    const { query, question, services, role } = req.body;
    const finalQuestion = question || query;
    
    if (!finalQuestion || !services || !role) {
      console.error('Missing required fields:', { finalQuestion, services, role });
      return res.status(400).json({
        error: 'Missing required fields: query/question, services, role'
      });
    }

    const payload = {
      question: finalQuestion,
      services,
      role
    };

    console.log('=== FORWARDING TO FASTAPI /ask ===');
    console.log('Target URL: http://127.0.0.1:8000/ask');
    console.log('Payload:', JSON.stringify(payload, null, 2));

    // Forward to FastAPI with explicit headers
    const response = await axios.post('http://127.0.0.1:8000/ask', payload, {
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      timeout: 30000
    });

    console.log('=== FASTAPI RESPONSE ===');
    console.log('Status:', response.status);
    console.log('Data:', JSON.stringify(response.data, null, 2));

    // Forward response back to client
    res.status(response.status).json(response.data);
  } catch (error) {
    console.error('=== PROXY ERROR ===');
    console.error('Message:', error.message);
    if (error.response) {
      console.error('FastAPI Status:', error.response.status);
      console.error('FastAPI Data:', JSON.stringify(error.response.data, null, 2));
      console.error('FastAPI Headers:', error.response.headers);
      return res.status(error.response.status).json(error.response.data);
    }
    res.status(500).json({
      error: 'Proxy error',
      message: error.message
    });
  }
});

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

// Static files (for serving React after build)
app.use(express.static('dist'));

// Fallback for React Router (SPA)
app.get('*', (req, res) => {
  try {
    const indexPath = join(__dirname, 'dist', 'index.html');
    const html = readFileSync(indexPath, 'utf-8');
    res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.type('text/html').send(html);
  } catch (err) {
    res.status(404).json({ error: 'File not found' });
  }
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`✓ Express proxy server running on http://localhost:${PORT}`);
  console.log(`✓ Proxying POST /api/agent/query → http://127.0.0.1:8000/ask`);
});

