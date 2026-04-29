# Floating AI Agent Integration Guide

## Overview
The AgentPanel has been refactored into a **FloatingAgent** - a modern floating assistant UI that appears as a bottom-right circular button with a tooltip. On click, it expands into a chat panel supporting Q&A and Summary modes.

---

## What Changed

### Frontend

#### 1. **New Component: FloatingAgent.jsx**
- Located: `log-dashboard-frontend/src/components/FloatingAgent.jsx`
- Features:
  - Floating button (bottom-right corner)
  - Tooltip: "How can I help you today?"
  - Expandable chat panel with smooth animations
  - Dark theme with Tailwind styling
  - Q&A and Summary modes
  - Auto-scroll messages
  - Auto-download PDF summaries

#### 2. **Removed: AgentPanel from LogsPage**
- **Before:** AgentPanel was inline in LogsPage, taking up vertical space
- **After:** Removed from LogsPage, now appears as floating UI

#### 3. **Updated: App.tsx**
- Added `FloatingAgent` import
- Renders `<FloatingAgent />` at app root level (inside `#app-container`)
- Available on all pages once authenticated

#### 4. **Updated: styles.css**
- New `.floating-agent-*` classes for:
  - Floating button with hover scale animation
  - Chat window with slide-up entrance animation
  - Message bubbles (user vs assistant)
  - Input field and send button
  - Loading spinner
  - Responsive design (mobile-friendly)
  - Dark theme support

---

### Backend

#### 1. **Critical: Error Handling in `/api/agent/query`**
- **Problem:** Uncaught exceptions causing "Service temporarily unavailable"
- **Solution:** Wrapped all logic in try/catch blocks
- **Changes:**
  - Fetch logs: wrapped in try/catch
  - QA mode: wrapped in try/catch with fallback
  - Summary mode: wrapped in try/catch with fallback
  - PDF generation: validates file exists before returning
  - Always returns valid JSON (no 500 errors)

#### 2. **Logging**
- Added logger for debugging agent errors
- Errors logged to console instead of crashing

#### 3. **Fallback Responses**
- If AI processing fails: `{"answer": "Unable to process request. Please try again."}`
- If PDF generation fails: `{"answer": "Failed to generate PDF. Please try again."}`
- If logs unavailable: `{"answer": "No data available for your role"}`

#### 4. **Health Check & Ask Route**
- Added error handling to `/health` endpoint
- Added comprehensive error handling to `/ask` endpoint

---

## Key Files

### Frontend Files
```
log-dashboard-frontend/
├── src/
│   ├── components/
│   │   ├── FloatingAgent.jsx          (NEW - floating UI)
│   │   └── AgentPanel.jsx             (KEPT for reference, not used)
│   ├── pages/
│   │   └── LogsPage.tsx               (UPDATED - removed AgentPanel)
│   ├── App.tsx                        (UPDATED - added FloatingAgent)
│   └── styles.css                     (UPDATED - added floating styles)
```

### Backend Files
```
agent/
├── main.py                            (UPDATED - error handling)
├── role_agent.py                      (UNCHANGED - safe)
├── db.py                              (UNCHANGED - safe)
└── models.py                          (UNCHANGED - safe)
```

---

## Style Highlights

### Button (Floating)
```css
.floating-agent-button {
    position: fixed;
    bottom: 24px;
    right: 24px;
    width: 56px;
    height: 56px;
    border-radius: 50%;
    background: var(--accent-color);
    box-shadow: 0 4px 12px rgba(37, 99, 235, 0.4);
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.floating-agent-button:hover {
    transform: scale(1.1);
    box-shadow: 0 8px 24px rgba(37, 99, 235, 0.6);
}
```

### Chat Panel
```css
.floating-agent-panel {
    position: fixed;
    bottom: 88px;
    right: 24px;
    width: 380px;
    max-height: 600px;
    background: var(--surface);
    border-radius: 12px;
    animation: slideUp 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

@keyframes slideUp {
    from {
        opacity: 0;
        transform: translateY(20px);
    }
    to {
        opacity: 1;
        transform: translateY(0);
    }
}
```

### Responsive
- Mobile: Panel width = `calc(100vw - 32px)` (full width minus padding)
- Tablet: Panel width = 380px
- Tooltip hidden on mobile (shown on hover via :hover sibling selector)

---

## API Specification

### POST /api/agent/query

**Request:**
```json
{
  "query": "What errors occurred in the last hour?",
  "mode": "qa" | "summary",
  "role": "admin" | "developer",
  "services": ["service-1", "service-2"]
}
```

**Response (QA Mode):**
```json
{
  "answer": "I reviewed 150 scoped logs and found 12 errors..."
}
```

**Response (Summary Mode):**
```json
{
  "pdf_url": "/api/agent/reports/role-based-log-summary-20260428125339.pdf"
}
```

**Fallback Responses:**
```json
{
  "answer": "No data available for your role"
}

{
  "answer": "Unable to process request. Please try again."
}

{
  "answer": "Failed to generate PDF. Please try again."
}
```

---

## Frontend Integration

### How It Works

1. **Button Hover**
   - Shows tooltip: "How can I help you today?"

2. **Button Click**
   - Opens chat panel
   - Auto-focuses textarea
   - Shows welcome message

3. **Mode Toggle**
   - Click "Ask" for Q&A mode
   - Click "Summary" for PDF generation

4. **Message Send**
   - Click send button or press Shift+Enter
   - Sends request to `/api/agent/query`
   - Shows loading spinner
   - Appends response to chat history

5. **PDF Download** (Summary mode)
   - Automatic download starts
   - Download link shown at bottom
   - User can retry via link

6. **Error Handling**
   - Displays error message in red
   - Appends error to chat history
   - User can retry

---

## Backend Error Handling

### Try/Catch Structure

```python
try:
    # Fetch logs
    logs = fetch_logs(role, services)
    
    if not logs:
        return {"answer": "No data available for your role"}

    # Process based on mode
    if payload.mode == "qa":
        try:
            answer = answer_query(query, logs)
            return {"answer": answer or "Unable to process request. Please try again."}
        except Exception as e:
            logger.error(f"Error in QA mode: {str(e)}")
            return {"answer": "Unable to process request. Please try again."}
            
    # Summary mode with PDF validation
    elif payload.mode == "summary":
        try:
            summary = generate_summary(logs)
            pdf_path = generate_pdf_report(summary, role)
            
            # Validate PDF was created
            if not pdf_path or not Path(pdf_path).exists():
                return {"answer": "Failed to generate PDF. Please try again."}
            
            pdf_name = Path(pdf_path).name
            return {"pdf_url": f"/api/agent/reports/{pdf_name}"}
        except Exception as e:
            logger.error(f"Error in summary mode: {str(e)}")
            return {"answer": "Unable to generate summary. Please try again."}

except Exception as e:
    logger.error(f"Agent query error: {str(e)}")
    return {"answer": "Service temporarily unavailable. Please try again."}
```

---

## Testing Checklist

### Frontend
- [ ] Button appears bottom-right
- [ ] Tooltip shows on hover
- [ ] Click opens chat panel
- [ ] Click again closes panel
- [ ] Q&A mode works
- [ ] Summary mode downloads PDF
- [ ] Messages scroll auto
- [ ] Errors display properly
- [ ] Mobile responsive
- [ ] Dark theme works

### Backend
- [ ] `/api/agent/query` returns JSON (never crashes)
- [ ] No logs → returns "No data available"
- [ ] Invalid role → returns error
- [ ] Missing services (developer) → returns error
- [ ] QA mode → appends response
- [ ] Summary mode → returns PDF URL
- [ ] PDF file exists when URL returned
- [ ] Network errors → fallback response
- [ ] Large logs → aggregates properly

---

## Deployment Steps

### 1. Install Dependencies (if needed)
```bash
cd agent
pip install -r requirements.txt
```

### 2. Stop Old Backend
```bash
# If running: Ctrl+C in the Python terminal
```

### 3. Start New Backend
```bash
cd agent
python -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### 4. Build Frontend
```bash
cd log-dashboard-frontend
npm run build
```

### 5. Start Dev Server (optional)
```bash
npm run dev
```

---

## Troubleshooting

### "Service temporarily unavailable"
- Check backend logs for `Agent Error:`
- Verify MongoDB connection
- Check if role/services are valid
- Ensure logs exist for the scoped role

### PDF not downloading
- Check `/api/agent/reports/` directory exists
- Verify PDF file was created
- Check file permissions
- Look for `Error in summary mode:` in logs

### Chat not opening
- Check browser console for JS errors
- Verify FloatingAgent component is imported
- Check z-index conflicts with other fixed elements

### Messages not sending
- Verify `/api/agent/query` endpoint is accessible
- Check role normalization in backend
- Ensure query is not empty
- Look for CORS issues (if cross-origin)

---

## Future Enhancements

- [ ] Typing indicators
- [ ] Message persistence (localStorage)
- [ ] Code snippets in responses
- [ ] Chart rendering in chat
- [ ] Voice input
- [ ] Export chat history
- [ ] Keyboard shortcuts (Cmd+K to open)
- [ ] Multiple chat sessions
