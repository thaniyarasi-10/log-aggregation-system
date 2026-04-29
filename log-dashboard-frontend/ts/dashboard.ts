import { LogTable } from '../components/logTable.js';
import { MetricsCards } from '../components/metricsCards.js';
import { AlertsPanel } from '../components/alertsPanel.js';
import { DashboardCharts } from './charts.js';
import { RealtimeManager } from './realtime.js';
import { Filters } from '../components/filters.js';
import { FilterOptions, LogEvent } from './types.js';
import { AdminServiceView, AdminUserView, ApiClient } from './api.js';
import { MetricsResponse } from './types.js';

const API_BASE_URL = 'http://localhost:8080';

type AuthPayload = {
    authenticated?: boolean;
    name?: string;
    email?: string;
    role?: string;
    permissions?: string[];
    allowedServices?: string[];
    canManageUsers?: boolean;
    canManageServices?: boolean;
};

document.addEventListener('DOMContentLoaded', () => {
    const logTable = new LogTable('log-table-body');
    const metrics = new MetricsCards();
    const charts = new DashboardCharts();
    const alertsPanel = new AlertsPanel('alerts-list', 'alerts-last-updated');
    
    // Accumulator for overall metrics
    const allLogs: LogEvent[] = [];
    let refreshGeneration = 0;
    let metricsRefreshGeneration = 0;
    let serviceRefreshTimer: number | null = null;
    let metricsRefreshTimer: number | null = null;
    let tableRefreshTimer: number | null = null;
    let isAdminUser = false;
    let lastStableMetrics: MetricsResponse | null = null;
    let currentAdminUsers: AdminUserView[] = [];
    let currentAdminServices: AdminServiceView[] = [];
    let editingUserId: string | null = null;
    const pendingServiceDeletes = new Set<string>();
    const pendingUserDeletes = new Set<string>();
    const pendingUserUpdates = new Set<string>();

    const hasMetricSignal = (data: MetricsResponse): boolean => {
        return data.totalLogs > 0
            || data.errorCount > 0
            || data.avgResponseTime > 0
            || data.p95Latency > 0
            || data.throughputOverTime.length > 0
            || data.levelDistribution.length > 0;
    };

    const hasRenderableTimeSeries = (data: MetricsResponse): boolean => {
        return data.throughputOverTime.length > 0 || data.levelDistribution.length > 0;
    };

    const syncServices = async () => {
        const services = await ApiClient.fetchServices({ size: 5000 });
        if (services.length > 0) {
            filters.setAvailableServices(services);
        }
    };

    const buildMetricsFilters = (selectedFilters: FilterOptions): FilterOptions => {
        const metricFilters: FilterOptions = {
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

    const refreshMetrics = async (selectedFilters: FilterOptions) => {
        metricsRefreshGeneration += 1;
        const generation = metricsRefreshGeneration;
        const metricData = await ApiClient.fetchMetrics(buildMetricsFilters(selectedFilters));
        if (generation !== metricsRefreshGeneration) {
            return;
        }

        if (hasMetricSignal(metricData) && hasRenderableTimeSeries(metricData)) {
            lastStableMetrics = metricData;
            metrics.update(metricData);
            charts.renderMetrics(metricData);
            return;
        }

        if (lastStableMetrics) {
            metrics.update(lastStableMetrics);
            charts.renderMetrics(lastStableMetrics);
            return;
        }

        metrics.update(metricData);
        charts.renderMetrics(metricData);
    };

    let realtime: RealtimeManager;
    const filters = new Filters((opts) => {
        void refreshDashboardData(opts);
    });

    realtime = new RealtimeManager((newLogs: LogEvent[]) => {
        if (!newLogs.length) return;
        
        filters.updateAvailableOptions(newLogs);
        logTable.renderLogs(newLogs);
        
        const isBulk = allLogs.length === 0 && newLogs.length > 0;
        
        newLogs.forEach(l => allLogs.push(l));
        if (allLogs.length > 2000) allLogs.splice(0, allLogs.length - 2000); // keep a rolling window for metrics

        if (isBulk) {
            void refreshMetrics(filters.getCurrentFilters());
        }
    });

    // Apply filters once on initial load to populate logs and metrics with defaults
    try {
        filters.applyFilters();
    } catch (e) {
        // swallow any early errors; refreshDashboardData has its own guards
        console.warn('Initial applyFilters() failed:', e);
    }

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
            if (event.target == modal) modal.style.display = 'none';
        });
    }

    document.addEventListener('showLogModal', async (e: Event) => {
        const customEvent = e as CustomEvent<LogEvent>;
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
                    } else {
                        // Reverse the context logs if backend sends them newest first (we want a chronological view)
                        const sortedContext = contextLogs.sort((a,b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
                        
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
                } catch(e) {
                    console.error(e);
                    contextContent.innerHTML = 'Error loading context.';
                }
            }
            modal.style.display = 'flex';
        }
    });

    document.addEventListener('filterByTrace', (e: Event) => {
        const traceId = (e as CustomEvent).detail;
        const currentOptions = filters.getCurrentFilters();
        void refreshDashboardData({ ...currentOptions, traceId });
    });

    const refreshDashboardData = async (selectedFilters: FilterOptions) => {
        refreshGeneration += 1;
        const generation = refreshGeneration;
        const hasVisibleData = allLogs.length > 0;

        logTable.setSearchTerm(selectedFilters.message);

        realtime.setFilters(selectedFilters, { resetCursor: true, immediateFetch: false });

        const initialLogs = await ApiClient.fetchLogs({
            ...selectedFilters,
            size: 500
        });

        // Ignore stale responses when users switch filters quickly.
        if (generation !== refreshGeneration) {
            return;
        }

        let logsToRender = initialLogs;
        let metricsFilters = selectedFilters;

        if (!initialLogs.length) {
            const lastFetchStatus = ApiClient.getLastLogsFetchStatus();
            const hasTransientFailure = lastFetchStatus !== null && lastFetchStatus !== 200;
            if (hasTransientFailure && hasVisibleData) {
                console.warn(`Skipping table reset due to transient logs fetch failure (status: ${lastFetchStatus}).`);
                await syncServices();
                await refreshMetrics(selectedFilters);
                return;
            }

            // If realtime window is empty, fall back to recent historical logs.
            const historicalFilters: FilterOptions = {
                ...selectedFilters,
                from: undefined,
                to: undefined,
                timePreset: undefined,
                size: 500
            };

            const historicalLogs = await ApiClient.fetchLogs(historicalFilters);
            if (generation !== refreshGeneration) {
                return;
            }
            if (!historicalLogs.length) {
                const historicalStatus = ApiClient.getLastLogsFetchStatus();
                const shouldPreserveExistingData =
                    hasVisibleData
                    && historicalStatus !== null
                    && historicalStatus !== 200;

                if (shouldPreserveExistingData) {
                    console.warn(`Historical fallback failed (status: ${historicalStatus}); preserving existing table data.`);
                    await syncServices();
                    await refreshMetrics(selectedFilters);
                    return;
                }

                logTable.showEmptyState('No logs found in the selected window or recent history.');
                await syncServices();
                await refreshMetrics(selectedFilters);
                return;
            }

            logsToRender = historicalLogs;
            metricsFilters = historicalFilters;
        }

        const normalizedInitialLogs = logsToRender.map(log => {
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
    allLogs.length = 0;
    logTable.clearLogs();
        logTable.renderLogs(normalizedInitialLogs);

        normalizedInitialLogs.forEach(log => allLogs.push(log));
        if (allLogs.length > 2000) {
            allLogs.splice(0, allLogs.length - 2000);
        }

        await syncServices();
        await refreshMetrics(metricsFilters);
        realtime.seedCursorFromLogs(normalizedInitialLogs);
    };

    // Theme Toggling logic
    const themeBtn = document.getElementById('theme-toggle');
    const lightIcon = document.getElementById('theme-icon-light');
    const darkIcon = document.getElementById('theme-icon-dark');
    
    // Check saved
    const applyTheme = (theme: 'light' | 'dark') => {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem('logflow_theme', theme);

        if (theme === 'light') {
            if (lightIcon) lightIcon.style.display = 'none';
            if (darkIcon) darkIcon.style.display = 'block';
        } else {
            if (darkIcon) darkIcon.style.display = 'none';
            if (lightIcon) lightIcon.style.display = 'block';
        }

        charts.refreshTheme();
    };

    const savedTheme = (localStorage.getItem('logflow_theme') as 'light' | 'dark') || 'dark';
    applyTheme(savedTheme);
    
    if (themeBtn && lightIcon && darkIcon) {
        themeBtn.addEventListener('click', () => {
            const nextTheme =
                document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
            applyTheme(nextTheme);
        });
    }

    // Real OAuth Session Persistence
    const oauthOverlay = document.getElementById('oauth-overlay');
    const appContainer = document.getElementById('app-container');
    const userNameEl = document.getElementById('user-name');
    const logoutBtn = document.getElementById('logout-btn');
    const authStatusEl = document.getElementById('auth-status');
    // oauth-login-btn is now an anchor tag - no JS listener needed

    const setAuthStatus = (message: string) => {
        if (authStatusEl) {
            authStatusEl.textContent = message;
        }
    };

    const adminModal = document.getElementById('admin-modal') as HTMLDivElement | null;
    const adminModalTitle = document.getElementById('admin-modal-title') as HTMLHeadingElement | null;
    const adminModalBody = document.getElementById('admin-modal-body') as HTMLDivElement | null;
    const adminCloseButton = document.getElementById('admin-close-button') as HTMLSpanElement | null;

    const escapeHtml = (value: string): string => value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');

    const closeAdminModal = () => {
        if (adminModal) {
            adminModal.style.display = 'none';
            adminModal.classList.remove('admin-modal-fullscreen');
        }
        document.body.classList.remove('admin-modal-open');
        editingUserId = null;
    };

    const openAdminModal = (title: string, html: string) => {
        if (!adminModal || !adminModalTitle || !adminModalBody) {
            return;
        }

        adminModalTitle.textContent = title;
        adminModalBody.innerHTML = html;
        adminModal.classList.add('admin-modal-fullscreen');
        adminModal.style.display = 'flex';
        document.body.classList.add('admin-modal-open');
    };

    const parseCommaSeparatedValues = (rawValue: string): string[] => {
        return rawValue
            .split(',')
            .map(value => value.trim())
            .filter(value => value.length > 0)
            .filter((value, index, source) => source.findIndex(item => item.toLowerCase() === value.toLowerCase()) === index);
    };

    const buildRoleOptions = (): string[] => {
        const defaults = ['ADMIN', 'USER', 'DEVELOPER'];
        const discoveredRoles = currentAdminUsers
            .flatMap(user => user.roles)
            .map(role => role.trim())
            .filter(role => role.length > 0);

        return [...new Set([...defaults, ...discoveredRoles])].sort((a, b) => a.localeCompare(b));
    };

    const buildServiceOptions = (): string[] => {
        const discoveredFromUsers = currentAdminUsers
            .flatMap(user => user.services)
            .map(service => service.trim())
            .filter(service => service.length > 0);
        const discoveredFromServices = currentAdminServices
            .map(service => service.name.trim())
            .filter(service => service.length > 0);

        return [...new Set([...discoveredFromUsers, ...discoveredFromServices])].sort((a, b) => a.localeCompare(b));
    };

    const renderUsersView = (users: AdminUserView[]) => {
        currentAdminUsers = users;
        if (!users.length) {
            openAdminModal('Users', '<div class="admin-empty-state">No users available.</div>');
            return;
        }

        const roleSuggestions = buildRoleOptions().join(', ');
        const serviceSuggestions = buildServiceOptions().join(', ');

        const rows = users.map(user => {
            const services = user.services.length ? user.services.join(', ') : '-';
            const roles = user.roles.length ? user.roles.join(', ') : '-';
            const isEditing = editingUserId === user.id;
            if (isEditing) {
                return `
                    <tr class="admin-editing-row" data-user-row-id="${escapeHtml(user.id)}">
                        <td>${escapeHtml(user.id)}</td>
                        <td><input class="form-control admin-inline-input" data-edit-field="username" value="${escapeHtml(user.username)}" maxlength="80"></td>
                        <td><input class="form-control admin-inline-input" data-edit-field="email" value="${escapeHtml(user.email)}" maxlength="120"></td>
                        <td>
                            <input
                                class="form-control admin-inline-input"
                                data-edit-field="services"
                                value="${escapeHtml(user.services.join(', '))}"
                                placeholder="service-a, service-b"
                                maxlength="500"
                            >
                            <div class="admin-inline-hint">Known services: ${escapeHtml(serviceSuggestions || 'None')}</div>
                        </td>
                        <td>
                            <input
                                class="form-control admin-inline-input"
                                data-edit-field="roles"
                                value="${escapeHtml(user.roles.join(', '))}"
                                placeholder="ADMIN, USER"
                                maxlength="250"
                            >
                            <div class="admin-inline-hint">Known roles: ${escapeHtml(roleSuggestions || 'None')}</div>
                        </td>
                        <td>
                            <button class="btn" data-save-user-id="${escapeHtml(user.id)}">Save</button>
                            <button class="btn" data-cancel-user-id="${escapeHtml(user.id)}">Cancel</button>
                        </td>
                    </tr>
                `;
            }

            return `
                <tr>
                    <td>${escapeHtml(user.id)}</td>
                    <td>${escapeHtml(user.username)}</td>
                    <td>${escapeHtml(user.email)}</td>
                    <td>${escapeHtml(services)}</td>
                    <td>${escapeHtml(roles)}</td>
                    <td>
                        <button class="btn" data-edit-user-id="${escapeHtml(user.id)}">Edit</button>
                        <button class="btn admin-danger-btn" data-delete-user-id="${escapeHtml(user.id)}">Delete</button>
                    </td>
                </tr>
            `;
        }).join('');

        openAdminModal(
            'Users',
            `
            <div id="admin-user-message" class="admin-status-message"></div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>Name</th>
                            <th>Email</th>
                            <th>Service</th>
                            <th>Role</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            `
        );
    };

    const renderServicesView = (services: AdminServiceView[]) => {
        currentAdminServices = services;
        const rows = services.length
            ? services.map(service => `
                <tr>
                    <td>${escapeHtml(service.name)}</td>
                    <td>${escapeHtml(service.description || '-')}</td>
                    <td>
                        <button class="btn admin-danger-btn" data-delete-service-id="${escapeHtml(service.id)}">Delete</button>
                    </td>
                </tr>
            `).join('')
            : '<tr><td colspan="3" class="admin-empty-cell">No active services.</td></tr>';

        openAdminModal(
            'Services',
            `
            <form id="admin-service-form" class="admin-service-form">
                <input id="admin-service-name" class="form-control" placeholder="Service name" maxlength="100" required>
                <input id="admin-service-description" class="form-control" placeholder="Description" maxlength="255">
                <button type="submit" class="btn">Add Service</button>
            </form>
            <div id="admin-service-message" class="admin-status-message"></div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead>
                        <tr>
                            <th>Name</th>
                            <th>Description</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            `
        );
    };

    const loadUsersView = async () => {
        if (!isAdminUser) {
            return;
        }

        if (!currentAdminServices.length) {
            currentAdminServices = await ApiClient.fetchAdminServices();
        }

        openAdminModal('Users', '<div class="admin-empty-state">Loading users...</div>');
        const users = await ApiClient.fetchAdminUsers();
        renderUsersView(users);
    };

    const loadServicesView = async () => {
        if (!isAdminUser) {
            return;
        }

        openAdminModal('Services', '<div class="admin-empty-state">Loading services...</div>');
        const services = await ApiClient.fetchAdminServices();
        renderServicesView(services);
    };

    const updateManagementUI = (auth: AuthPayload) => {
        const container = document.getElementById('management-status') as HTMLDivElement | null;
        const usersBadge = document.getElementById('manage-users-badge') as HTMLButtonElement | null;
        const servicesBadge = document.getElementById('manage-services-badge') as HTMLButtonElement | null;
        if (!container || !usersBadge || !servicesBadge) {
            return;
        }

        const canManageUsers = Boolean(auth.canManageUsers);
        const canManageServices = Boolean(auth.canManageServices);
        const isRoleAdmin = String(auth.role || '').toUpperCase() === 'ADMIN';
        isAdminUser = isRoleAdmin || canManageUsers || canManageServices;

        if (!isAdminUser) {
            container.style.display = 'none';
            return;
        }

        usersBadge.textContent = 'Users: View';
        servicesBadge.textContent = 'Services: View';
        usersBadge.disabled = !canManageUsers;
        servicesBadge.disabled = !canManageServices;
        container.style.display = 'flex';
    };

    const configureSession = async (auth: AuthPayload) => {
        if (oauthOverlay && appContainer && userNameEl && logoutBtn) {
            userNameEl.textContent = auth.name || auth.email || 'User';
            oauthOverlay.style.display = 'none';
            appContainer.style.display = 'flex';
            logoutBtn.style.display = 'block';
            updateManagementUI(auth);

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
                metricsRefreshTimer = null;
            }

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
            setAuthStatus('Not signed in. Use Microsoft Entra ID to continue.');
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

    if (adminCloseButton && adminModal) {
        adminCloseButton.addEventListener('click', closeAdminModal);
        adminModal.addEventListener('click', (event) => {
            if (event.target === adminModal) {
                closeAdminModal();
            }
        });
    }

    const usersBadge = document.getElementById('manage-users-badge') as HTMLButtonElement | null;
    const servicesBadge = document.getElementById('manage-services-badge') as HTMLButtonElement | null;
    if (usersBadge) {
        usersBadge.addEventListener('click', () => {
            void loadUsersView();
        });
    }
    if (servicesBadge) {
        servicesBadge.addEventListener('click', () => {
            void loadServicesView();
        });
    }

    if (adminModalBody) {
        adminModalBody.addEventListener('submit', (event) => {
            const form = event.target as HTMLFormElement | null;
            if (!form || form.id !== 'admin-service-form') {
                return;
            }

            event.preventDefault();
            const nameInput = document.getElementById('admin-service-name') as HTMLInputElement | null;
            const descriptionInput = document.getElementById('admin-service-description') as HTMLInputElement | null;
            const messageEl = document.getElementById('admin-service-message') as HTMLDivElement | null;
            if (!nameInput) {
                return;
            }

            const name = nameInput.value.trim();
            const description = descriptionInput?.value.trim() || '';
            if (!name) {
                if (messageEl) {
                    messageEl.textContent = 'Service name is required.';
                    messageEl.className = 'admin-status-message admin-status-error';
                }
                return;
            }

            void (async () => {
                const result = await ApiClient.createService(name, description);
                if (!result.ok) {
                    if (messageEl) {
                        messageEl.textContent = result.message || 'Unable to add service.';
                        messageEl.className = 'admin-status-message admin-status-error';
                    }
                    return;
                }

                if (messageEl) {
                    messageEl.textContent = 'Service added successfully.';
                    messageEl.className = 'admin-status-message admin-status-success';
                }
                nameInput.value = '';
                if (descriptionInput) {
                    descriptionInput.value = '';
                }
                await syncServices();
                await loadServicesView();
            })();
        });

        adminModalBody.addEventListener('click', (event) => {
            const target = event.target as HTMLElement | null;
            if (!target) {
                return;
            }

            const serviceId = target.getAttribute('data-delete-service-id');
            if (serviceId) {
                if (pendingServiceDeletes.has(serviceId)) {
                    return;
                }
                if (!window.confirm('Delete this service? This action cannot be undone.')) {
                    return;
                }

                pendingServiceDeletes.add(serviceId);
                const button = target as HTMLButtonElement;
                button.disabled = true;

                void (async () => {
                    const result = await ApiClient.deleteService(serviceId);
                    const messageEl = document.getElementById('admin-service-message') as HTMLDivElement | null;
                    if (!result.ok) {
                        if (messageEl) {
                            messageEl.textContent = result.message || 'Unable to delete service.';
                            messageEl.className = 'admin-status-message admin-status-error';
                        }
                        button.disabled = false;
                        pendingServiceDeletes.delete(serviceId);
                        return;
                    }

                    currentAdminServices = currentAdminServices.filter(service => service.id !== serviceId);
                    renderServicesView(currentAdminServices);
                    await syncServices();
                    pendingServiceDeletes.delete(serviceId);
                })();
                return;
            }

            const userId = target.getAttribute('data-delete-user-id');
            if (userId) {
                if (pendingUserDeletes.has(userId)) {
                    return;
                }
                if (!window.confirm('Delete this user? This action will deactivate the user.')) {
                    return;
                }

                pendingUserDeletes.add(userId);
                const button = target as HTMLButtonElement;
                button.disabled = true;

                void (async () => {
                    const result = await ApiClient.deleteAdminUser(userId);
                    if (!result.ok) {
                        window.alert(result.message || 'Unable to delete user.');
                        button.disabled = false;
                        pendingUserDeletes.delete(userId);
                        return;
                    }

                    currentAdminUsers = currentAdminUsers.filter(user => user.id !== userId);
                    renderUsersView(currentAdminUsers);
                    pendingUserDeletes.delete(userId);
                })();
                return;
            }

            const editUserId = target.getAttribute('data-edit-user-id');
            if (editUserId) {
                editingUserId = editUserId;
                renderUsersView(currentAdminUsers);
                return;
            }

            const cancelUserId = target.getAttribute('data-cancel-user-id');
            if (cancelUserId) {
                editingUserId = null;
                renderUsersView(currentAdminUsers);
                return;
            }

            const saveUserId = target.getAttribute('data-save-user-id');
            if (saveUserId) {
                if (pendingUserUpdates.has(saveUserId)) {
                    return;
                }

                const row = target.closest('tr');
                if (!row) {
                    return;
                }

                const usernameInput = row.querySelector('[data-edit-field="username"]') as HTMLInputElement | null;
                const emailInput = row.querySelector('[data-edit-field="email"]') as HTMLInputElement | null;
                const servicesInput = row.querySelector('[data-edit-field="services"]') as HTMLInputElement | null;
                const rolesInput = row.querySelector('[data-edit-field="roles"]') as HTMLInputElement | null;
                const userMessageEl = document.getElementById('admin-user-message') as HTMLDivElement | null;

                if (!usernameInput || !emailInput || !servicesInput || !rolesInput) {
                    return;
                }

                const username = usernameInput.value.trim();
                const email = emailInput.value.trim();
                const services = parseCommaSeparatedValues(servicesInput.value);
                const roles = parseCommaSeparatedValues(rolesInput.value);

                if (!username || !email) {
                    if (userMessageEl) {
                        userMessageEl.textContent = 'Username and email are required.';
                        userMessageEl.className = 'admin-status-message admin-status-error';
                    }
                    return;
                }

                pendingUserUpdates.add(saveUserId);
                const button = target as HTMLButtonElement;
                button.disabled = true;

                void (async () => {
                    const result = await ApiClient.updateAdminUser(saveUserId, {
                        username,
                        email,
                        services,
                        roles
                    });

                    if (!result.ok || !result.data) {
                        if (userMessageEl) {
                            userMessageEl.textContent = result.message || 'Unable to update user.';
                            userMessageEl.className = 'admin-status-message admin-status-error';
                        }
                        button.disabled = false;
                        pendingUserUpdates.delete(saveUserId);
                        return;
                    }

                    editingUserId = null;
                    currentAdminUsers = currentAdminUsers.map(user => user.id === saveUserId ? result.data! : user);
                    if (userMessageEl) {
                        userMessageEl.textContent = 'User updated successfully.';
                        userMessageEl.className = 'admin-status-message admin-status-success';
                    }
                    renderUsersView(currentAdminUsers);
                    pendingUserUpdates.delete(saveUserId);
                })();
            }
        });
    }

    // Auto-login verify
    const verifyAuth = async () => {
        setAuthStatus('Checking existing session...');

        try {
            const response = await fetch(`${API_BASE_URL}/api/auth/me`, {
                credentials: 'include'
            });
            if (response.ok) {
                const data = await response.json();
                setAuthStatus('Authenticated with Microsoft Entra ID.');
                await configureSession(data as AuthPayload);
            } else {
                failSession();
            }
        } catch {
            setAuthStatus('Unable to reach backend. Start backend and try again.');
            failSession();
        }
    };

    // Run automatically on load
    verifyAuth();

    if (logoutBtn) {
        logoutBtn.addEventListener('click', async () => {
            try {
                await fetch(`${API_BASE_URL}/logout`, {
                    method: 'POST',
                    credentials: 'include'
                });
            } catch {
                // Intentionally no-op; UI fallback will still reset below.
            }
            alertsPanel.stop();
            failSession();
        });
    }

});
