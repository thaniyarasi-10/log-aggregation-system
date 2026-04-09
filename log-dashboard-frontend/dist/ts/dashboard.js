import { LogTable } from '../components/logTable.js';
import { MetricsCards } from '../components/metricsCards.js';
import { AlertsPanel } from '../components/alertsPanel.js';
import { DashboardCharts } from './charts.js';
import { RealtimeManager } from './realtime.js';
import { Filters } from '../components/filters.js';
import { ApiClient } from './api.js';
document.addEventListener('DOMContentLoaded', () => {
    const logTable = new LogTable('log-table-body');
    const metrics = new MetricsCards();
    const charts = new DashboardCharts();
    const alertsPanel = new AlertsPanel('alerts-list', 'alerts-last-updated');
    // Accumulator for overall metrics
    const allLogs = [];
    let refreshGeneration = 0;
    let serviceRefreshTimer = null;
    let metricsRefreshTimer = null;
    let tableRefreshTimer = null;
    const syncServices = async () => {
        const services = await ApiClient.fetchServices({ size: 5000 });
        if (services.length > 0) {
            filters.setAvailableServices(services);
        }
    };
    const buildMetricsFilters = (selectedFilters) => {
        const metricFilters = {
            size: 500
        };
        if (selectedFilters.service) {
            metricFilters.service = selectedFilters.service;
        }
        if (selectedFilters.from) {
            metricFilters.from = selectedFilters.from;
        }
        if (selectedFilters.to) {
            metricFilters.to = selectedFilters.to;
        }
        if (selectedFilters.timePreset) {
            metricFilters.timePreset = selectedFilters.timePreset;
        }
        return metricFilters;
    };
    const refreshMetrics = async (selectedFilters) => {
        const metricData = await ApiClient.fetchMetrics(buildMetricsFilters(selectedFilters));
        metrics.update(metricData);
        charts.renderMetrics(metricData);
    };
    let realtime;
    const filters = new Filters((opts) => {
        if (realtime) {
            void refreshDashboardData(opts);
        }
    });
    realtime = new RealtimeManager((newLogs) => {
        if (!newLogs.length)
            return;
        filters.updateAvailableOptions(newLogs);
        logTable.renderLogs(newLogs);
        const isBulk = allLogs.length === 0 && newLogs.length > 0;
        newLogs.forEach(l => allLogs.push(l));
        if (allLogs.length > 2000)
            allLogs.splice(0, allLogs.length - 2000); // keep a rolling window for metrics
        if (isBulk) {
            void refreshMetrics(filters.getCurrentFilters());
        }
    });
    // Fetch User Profile removed since it was causing loading issue
    // Handle Auto Scroll logic internally
    let autoScroll = true;
    logTable.setAutoScroll(autoScroll);
    // Modal Logic
    const modal = document.getElementById('log-modal');
    const closeBtn = document.querySelector('.close-button');
    if (closeBtn && modal) {
        closeBtn.addEventListener('click', () => {
            modal.style.display = 'none';
        });
        window.addEventListener('click', (event) => {
            if (event.target == modal)
                modal.style.display = 'none';
        });
    }
    document.addEventListener('showLogModal', async (e) => {
        const customEvent = e;
        const log = customEvent.detail;
        if (modal) {
            const content = document.getElementById('modal-details');
            const contextContent = document.getElementById('modal-context');
            if (content) {
                content.innerHTML = `
                    <div style="margin-bottom: 1rem">
                      ${log.traceId ? `<button id="btn-trace" class="btn" style="padding: 0.2rem 0.5rem">Filter by Trace ID: ${log.traceId}</button>` : ''}
                    </div>
                    <pre>${JSON.stringify(log, null, 2)}</pre>
                `;
                const traceBtn = document.getElementById('btn-trace');
                if (traceBtn && log.traceId) {
                    traceBtn.addEventListener('click', () => {
                        modal.style.display = 'none';
                        document.dispatchEvent(new CustomEvent('filterByTrace', { detail: log.traceId }));
                    });
                }
            }
            if (contextContent) {
                contextContent.innerHTML = 'Loading context...';
                try {
                    const logTime = new Date(log.timestamp).getTime();
                    const from = new Date(logTime - 2 * 60 * 1000).toISOString();
                    const to = new Date(logTime + 2 * 60 * 1000).toISOString();
                    const contextLogs = await ApiClient.fetchLogs({
                        service: log.service,
                        traceId: log.traceId,
                        from: from,
                        to: to,
                        size: 200
                    });
                    if (contextLogs.length === 0) {
                        contextContent.innerHTML = 'No context logs found.';
                    }
                    else {
                        // Reverse the context logs if backend sends them newest first (we want a chronological view)
                        const sortedContext = contextLogs.sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
                        let html = '<table class="log-table" style="width:100%; font-size: 0.8rem;"><tbody>';
                        sortedContext.forEach(clog => {
                            const isTarget = clog.timestamp === log.timestamp && clog.message === log.message;
                            const bgColor = isTarget ? 'var(--context-highlight)' : 'transparent';
                            html += `
                                <tr style="background-color: ${bgColor}">
                                    <td style="padding:4px">${clog.timestamp}</td>
                                    <td style="padding:4px">[${clog.level}]</td>
                                    <td style="padding:4px">${clog.message}</td>
                                </tr>
                            `;
                        });
                        html += '</tbody></table>';
                        contextContent.innerHTML = html;
                    }
                }
                catch (e) {
                    console.error(e);
                    contextContent.innerHTML = 'Error loading context.';
                }
            }
            modal.style.display = 'flex';
        }
    });
    document.addEventListener('filterByTrace', (e) => {
        const traceId = e.detail;
        const currentOptions = filters.getCurrentFilters();
        void refreshDashboardData({ ...currentOptions, traceId });
    });
    const refreshDashboardData = async (selectedFilters) => {
        refreshGeneration += 1;
        const generation = refreshGeneration;
        allLogs.length = 0;
        logTable.clearLogs();
        metrics.update({
            totalLogs: 0,
            errorCount: 0,
            errorRate: 0,
            avgResponseTime: 0,
            p95Latency: 0,
            bucketInterval: '1m',
            throughputOverTime: [],
            levelDistribution: []
        });
        charts.clear();
        realtime.setFilters(selectedFilters, { resetCursor: true, immediateFetch: false });
        const initialLogs = await ApiClient.fetchLogs({
            ...selectedFilters,
            size: 500
        });
        // Ignore stale responses when users switch filters quickly.
        if (generation !== refreshGeneration) {
            return;
        }
        if (!initialLogs.length) {
            logTable.showEmptyState('No logs found for the selected service and time range.');
            await syncServices();
            await refreshMetrics(selectedFilters);
            return;
        }
        const normalizedInitialLogs = initialLogs.map(log => {
            const ms = new Date(log.timestamp).getTime();
            if (Number.isFinite(ms)) {
                return log;
            }
            return {
                ...log,
                timestamp: new Date().toISOString()
            };
        });
        normalizedInitialLogs.sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
        filters.updateAvailableOptions(normalizedInitialLogs);
        logTable.renderLogs(normalizedInitialLogs);
        allLogs.length = 0;
        normalizedInitialLogs.forEach(log => allLogs.push(log));
        if (allLogs.length > 2000) {
            allLogs.splice(0, allLogs.length - 2000);
        }
        await syncServices();
        await refreshMetrics(selectedFilters);
        realtime.seedCursorFromLogs(normalizedInitialLogs);
    };
    // Theme Toggling logic
    const themeBtn = document.getElementById('theme-toggle');
    const lightIcon = document.getElementById('theme-icon-light');
    const darkIcon = document.getElementById('theme-icon-dark');
    // Check saved
    const applyTheme = (theme) => {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem('logflow_theme', theme);
        if (theme === 'light') {
            if (lightIcon)
                lightIcon.style.display = 'none';
            if (darkIcon)
                darkIcon.style.display = 'block';
        }
        else {
            if (darkIcon)
                darkIcon.style.display = 'none';
            if (lightIcon)
                lightIcon.style.display = 'block';
        }
        charts.refreshTheme();
    };
    const savedTheme = localStorage.getItem('logflow_theme') || 'dark';
    applyTheme(savedTheme);
    if (themeBtn && lightIcon && darkIcon) {
        themeBtn.addEventListener('click', () => {
            const nextTheme = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
            applyTheme(nextTheme);
        });
    }
    // Real OAuth Session Persistence
    const oauthOverlay = document.getElementById('oauth-overlay');
    const appContainer = document.getElementById('app-container');
    const userNameEl = document.getElementById('user-name');
    const logoutBtn = document.getElementById('logout-btn');
    // oauth-login-btn is now an anchor tag - no JS listener needed
    const configureSession = async (username) => {
        if (oauthOverlay && appContainer && userNameEl && logoutBtn) {
            userNameEl.textContent = username;
            oauthOverlay.style.display = 'none';
            appContainer.style.display = 'flex';
            logoutBtn.style.display = 'block';
            // Populate service options immediately from full historical data.
            await syncServices();
            // Always bootstrap from persisted Elasticsearch logs before live polling.
            await refreshDashboardData(filters.getCurrentFilters());
            if (serviceRefreshTimer !== null) {
                globalThis.clearInterval(serviceRefreshTimer);
            }
            serviceRefreshTimer = globalThis.setInterval(() => {
                void syncServices();
            }, 10000);
            if (metricsRefreshTimer !== null) {
                globalThis.clearInterval(metricsRefreshTimer);
            }
            metricsRefreshTimer = globalThis.setInterval(() => {
                void refreshMetrics(filters.getCurrentFilters());
            }, 3000);
            if (tableRefreshTimer !== null) {
                globalThis.clearInterval(tableRefreshTimer);
            }
            tableRefreshTimer = globalThis.setInterval(() => {
                // Periodic full refresh keeps table accurate for non-realtime/backfilled systems.
                void refreshDashboardData(filters.getCurrentFilters());
            }, 30000);
            realtime.startPooling(2000);
            alertsPanel.start(5000);
        }
    };
    const failSession = () => {
        if (oauthOverlay && appContainer && logoutBtn) {
            oauthOverlay.style.display = 'flex';
            appContainer.style.display = 'none';
            logoutBtn.style.display = 'none';
            if (serviceRefreshTimer !== null) {
                globalThis.clearInterval(serviceRefreshTimer);
                serviceRefreshTimer = null;
            }
            if (metricsRefreshTimer !== null) {
                globalThis.clearInterval(metricsRefreshTimer);
                metricsRefreshTimer = null;
            }
            if (tableRefreshTimer !== null) {
                globalThis.clearInterval(tableRefreshTimer);
                tableRefreshTimer = null;
            }
            alertsPanel.stop();
        }
    };
    // Auto-login verify
    const verifyAuth = async () => {
        const urlParams = new URLSearchParams(window.location.search);
        // ── Error from backend ──────────────────────────────────────────
        if (urlParams.has('error')) {
            alert('OAuth Error: ' + urlParams.get('error'));
            window.history.replaceState({}, document.title, '/');
            failSession();
            return;
        }
        // ── Fresh token arriving from Google callback ───────────────────
        // Backend already validated the user — trust & decode directly.
        // No second API call needed; avoids CORS issues at this point.
        if (urlParams.has('token')) {
            const rawToken = urlParams.get('token');
            localStorage.setItem('logflow_jwt', rawToken);
            window.history.replaceState({}, document.title, '/'); // Clean URL
            try {
                const payload = atob(rawToken.replace(/-/g, '+').replace(/_/g, '/'));
                const parts = payload.split('|');
                const name = parts.length > 1 ? parts[1] : parts[0];
                await configureSession(name || 'User');
            }
            catch {
                await configureSession('User');
            }
            return; // Done - dashboard is showing ✅
        }
        // ── Returning visitor: check stored token ───────────────────────
        const token = localStorage.getItem('logflow_jwt');
        if (!token) {
            failSession();
            return;
        }
        try {
            const response = await fetch('http://localhost:8080/api/auth/me', {
                headers: { 'Authorization': 'Bearer ' + token }
            });
            if (response.ok) {
                const data = await response.json();
                await configureSession(data.name || data.email || 'User');
            }
            else {
                // Token may be stale — clear and show login
                localStorage.removeItem('logflow_jwt');
                failSession();
            }
        }
        catch {
            // Backend unreachable — still show dashboard with stored name
            try {
                const payload = atob(token.replace(/-/g, '+').replace(/_/g, '/'));
                const parts = payload.split('|');
                await configureSession(parts.length > 1 ? parts[1] : parts[0]);
            }
            catch {
                failSession();
            }
        }
    };
    // Run automatically on load
    verifyAuth();
    if (logoutBtn) {
        logoutBtn.addEventListener('click', async () => {
            // Inform backend to end session via /logout mapping or standard flow
            // Full reload and clear JWT
            localStorage.removeItem('logflow_jwt');
            alertsPanel.stop();
            window.location.href = '/';
        });
    }
});
