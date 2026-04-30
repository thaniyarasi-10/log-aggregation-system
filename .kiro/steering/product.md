# Product Overview

This is a **production-grade log aggregation and analysis platform** built for engineering teams. It collects, stores, and surfaces application logs with role-based access control and AI-powered insights.

## Core Capabilities

- **Real-time log dashboard** — filter logs by service, level, environment, trace ID, and message content
- **Metrics & analytics** — error rates, throughput, latency percentiles, and time-series charts
- **Role-based access control (RBAC)** — Admin and Developer roles; developers are scoped to assigned services only
- **AI assistant** — floating chat interface for natural language log queries and PDF report generation (powered by Google Gemini)
- **Service & user management** — admin interfaces for managing services, user roles, and access requests
- **Real-time streaming** — WebSocket support for live log updates

## User Roles

- **Admin** — full access to all logs, services, and user management
- **Developer** — scoped access limited to their assigned services

## Authentication

OAuth2 via Microsoft Entra ID (Azure AD). All users must authenticate before accessing the dashboard.
