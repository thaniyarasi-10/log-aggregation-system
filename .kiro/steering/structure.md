# Project Structure

This is a monorepo with three independent services.

```
/
├── agent/                          # AI agent service (Python/FastAPI)
├── logcontroller/                  # Backend API (Java/Spring Boot)
└── log-dashboard-frontend/         # Frontend (React/TypeScript)
```

---

## `agent/` — AI Agent Service

```
agent/
├── main.py              # FastAPI app entry point; defines all HTTP endpoints
├── ai.py                # Google Gemini integration and tool definitions
├── role_agent.py        # Core logic: answer_query, generate_summary, generate_pdf_report
├── db.py                # MongoDB queries with role-based log filtering
├── models.py            # Pydantic request/response models
├── mcp_mongo.py         # MongoDB aggregation pipelines
├── summary.py           # PDF report generation (ReportLab)
├── requirements.txt     # Python dependencies
└── generated_reports/   # Output directory for generated PDFs
```

---

## `logcontroller/` — Spring Boot Backend

```
logcontroller/
├── pom.xml
└── src/main/
    ├── java/com/kovanlabs/logcontroller/
    │   ├── LogcontrollerApplication.java   # Entry point (@SpringBootApplication)
    │   ├── auth/                           # Auth context, roles, permissions
    │   ├── config/                         # Spring beans: Elastic, Kafka, Security, WebSocket
    │   ├── consumer/                       # Kafka log consumer (LogConsumer.java)
    │   ├── controller/                     # REST controllers (one per domain)
    │   │   ├── LogController.java          # GET /logs, /logs/metrics, /logs/services
    │   │   ├── AdminManagementController.java
    │   │   ├── OAuthController.java
    │   │   ├── ServiceAccessController.java
    │   │   ├── AlertController.java
    │   │   └── ApiExceptionHandler.java    # Global exception handler
    │   ├── model/                          # JPA + MongoDB entity classes
    │   ├── jpa/repository/                 # Spring Data JPA repos (PostgreSQL)
    │   ├── mongo/repository/               # Spring Data MongoDB repos
    │   ├── repository/                     # ElasticRepository (custom ES queries)
    │   ├── service/                        # Business logic layer
    │   │   ├── LogProcessingService.java   # Buffers and routes incoming logs
    │   │   ├── MongoLogPersistenceService.java
    │   │   ├── ServiceAccessAuthorizationService.java
    │   │   ├── AlertService.java
    │   │   └── OAuthUserEmailResolver.java
    │   └── parser/
    │       └── LogParser.java
    └── resources/
        └── application.yml                 # All configuration (DB, Kafka, OAuth, ES)
```

**Package convention**: `com.kovanlabs.logcontroller.<layer>`

---

## `log-dashboard-frontend/` — React Frontend

```
log-dashboard-frontend/
├── package.json
├── vite.config.ts          # Vite config; defines API proxies
├── tsconfig.json
├── index.html
└── src/
    ├── main.tsx             # React entry point
    ├── App.tsx              # Root component; routing setup
    ├── types.ts             # Shared TypeScript interfaces (LogEvent, LogFilters, etc.)
    ├── styles.css           # Global styles
    ├── components/          # Reusable UI components
    │   ├── Navbar.tsx
    │   ├── SidebarFilters.tsx
    │   ├── LogsTable.tsx
    │   ├── MetricsCards.tsx
    │   ├── MetricsCharts.tsx
    │   ├── MetricChart.tsx
    │   ├── LoginOverlay.tsx
    │   ├── FloatingAgent.jsx   # AI assistant chat widget (global)
    │   ├── Modal.tsx
    │   └── AgentPanel.jsx      # Legacy agent panel (kept for reference)
    ├── pages/               # Route-level page components
    │   ├── LogsPage.tsx
    │   ├── ServicesPage.tsx
    │   └── UsersPage.tsx
    ├── context/             # React context providers
    │   ├── AuthContext.tsx  # Auth state and login/logout
    │   └── ThemeContext.tsx # Dark/light theme
    ├── services/
    │   └── api.ts           # Axios API client; all backend calls go here
    ├── hooks/
    │   └── useRealtimeLogs.ts  # WebSocket hook for live log streaming
    └── utils/
        └── time.ts          # Time range helpers
```

---

## Architectural Patterns

**Log ingestion flow**:
```
Kafka → LogConsumer → LogProcessingService → Elasticsearch + MongoDB
```

**Query flow**:
```
Frontend → LogController (Spring Boot) → ElasticRepository / MongoRepository
```

**AI agent flow**:
```
FloatingAgent (React) → FastAPI agent → MongoDB → Gemini API → response / PDF
```

**Auth flow**:
```
LoginOverlay → Azure AD OAuth2 → OAuthController → AuthContext (React)
```

## Key Conventions

- All frontend API calls go through `src/services/api.ts` — don't call `axios` directly in components
- Shared TypeScript types live in `src/types.ts`
- RBAC is enforced at the controller layer in the backend; never assume access in the frontend
- The `FloatingAgent.jsx` component is mounted globally in `App.tsx`, not inside individual pages
- Backend uses dual-repo pattern: separate JPA repos (`jpa/repository/`) and MongoDB repos (`mongo/repository/`) under the same service layer
