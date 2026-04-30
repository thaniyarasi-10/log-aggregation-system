# Tech Stack

## Languages
- **Java 17** — backend service
- **Python 3.x** — AI agent service
- **TypeScript 5.8 / React 18** — frontend

---

## Modules & Their Stacks

### `logcontroller/` — Spring Boot Backend
- **Framework**: Spring Boot 3.3.5
- **Build tool**: Maven (`pom.xml`)
- **Key libraries**:
  - `spring-boot-starter-web` — REST API
  - `spring-boot-starter-security` + `spring-boot-starter-oauth2-client` — Azure AD auth
  - `spring-boot-starter-data-jpa` — PostgreSQL ORM
  - `spring-boot-starter-data-mongodb` — MongoDB integration
  - `spring-kafka` — Kafka consumer
  - `spring-boot-starter-websocket` — real-time updates
  - `elasticsearch-java` 8.13.4 — Elasticsearch client
  - `logstash-logback-encoder` 7.4 — structured logging

### `agent/` — FastAPI AI Agent
- **Framework**: FastAPI + Uvicorn
- **Key libraries**:
  - `pymongo` — MongoDB queries
  - `google-generativeai` — Google Gemini API
  - `reportlab` + `matplotlib` — PDF report generation
  - `dateparser` + `pytz` — date/time handling

### `log-dashboard-frontend/` — React Frontend
- **Framework**: React 18 + TypeScript
- **Build tool**: Vite 5.4
- **Key libraries**:
  - `react-router-dom` 6 — client-side routing
  - `axios` — HTTP client
  - `chart.js` + `react-chartjs-2` — charts
  - `express` — static file serving in production

---

## Databases
| Database | Purpose |
|---|---|
| **Elasticsearch** 8.13.4 | Full-text log search and analytics |
| **MongoDB** | Log archival and role-based queries |
| **PostgreSQL** (Supabase) | User/role/service metadata |

## Infrastructure
- **Kafka** — log ingestion broker
- **Azure AD / Entra ID** — OAuth2 identity provider
- **Google Gemini API** — AI analysis engine

---

## Common Commands

### Backend (Java)
```bash
# Run in dev
mvn spring-boot:run

# Build JAR
mvn clean package

# Run JAR
java -jar target/logcontroller-1.0-SNAPSHOT.jar
```
Runs on **port 8080**.

### AI Agent (Python)
```bash
# Install dependencies
pip install -r agent/requirements.txt

# Run dev server
cd agent
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```
Runs on **port 8000**.

### Frontend (TypeScript/React)
```bash
cd log-dashboard-frontend

# Install dependencies
npm install

# Dev server
npm run dev

# Type check
npm run typecheck

# Production build
npm run build

# Preview production build
npm run preview
```
Runs on **port 3000**. Dev server proxies `/api` → `localhost:8080` and `/api/agent/query` → `localhost:8000`.

---

## Configuration
Backend config lives in `logcontroller/src/main/resources/application.yml`. Key environment variables:
- `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AZURE_TENANT_ID`
- `MONGO_URI`
- `GOOGLE_API_KEY`
- Elasticsearch and Kafka connection details are also configured there
