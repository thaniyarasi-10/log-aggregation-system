import { ApiClient } from '../ts/api.js';
import { Alert, AlertsGrouped } from '../ts/types.js';

export class AlertsPanel {
    private readonly listContainer: HTMLElement;
    private readonly refreshTimeElement: HTMLElement;
    private intervalId: number | null = null;
    private knownSignatures = new Set<string>();

    constructor(listContainerId: string, refreshTimeId: string) {
        const listEl = document.getElementById(listContainerId);
        const refreshEl = document.getElementById(refreshTimeId);

        if (!listEl) throw new Error(`Container ${listContainerId} not found`);
        if (!refreshEl) throw new Error(`Container ${refreshTimeId} not found`);

        this.listContainer = listEl;
        this.refreshTimeElement = refreshEl;
    }

    public start(intervalMs: number = 5000) {
        this.stop();
        this.refresh();
        this.intervalId = globalThis.setInterval(() => this.refresh(), intervalMs);
    }

    public stop() {
        if (this.intervalId !== null) {
            globalThis.clearInterval(this.intervalId);
            this.intervalId = null;
        }
    }

    private async refresh() {
        try {
            const groupedAlerts = await ApiClient.fetchAlerts();

            // Handle null / undefined / wrong format safely
            if (!groupedAlerts || typeof groupedAlerts !== "object") {
                this.render([]);
                return;
            }

            const flattened = this.flattenAndSort(groupedAlerts);

            this.render(flattened);

            this.refreshTimeElement.textContent =
                `Last updated: ${new Date().toLocaleTimeString()}`;

            this.notifyOnNewAlerts(flattened);

        } catch (err) {
            console.error("Alert fetch failed:", err);

            // Always show fallback UI
            this.listContainer.innerHTML =
                '<div class="alerts-empty">No alerts</div>';
        }
    }

    private flattenAndSort(grouped: AlertsGrouped): Alert[] {
        const all = Object.values(grouped).flat();
        return all.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    }

    private render(alerts: Alert[]) {
        if (!alerts || alerts.length === 0) {
            this.listContainer.innerHTML = `
                <div class="alerts-empty">
                    No alerts
                </div>
            `;
            return;
        }

        const latestAlerts = alerts.slice(0, 20);

        this.listContainer.innerHTML = latestAlerts
            .map(alert => {
                const sevClass = alert.severity === 'CRITICAL' ? 'critical' : 'warning';
                return `
                    <article class="alert-item ${sevClass}">
                        <div class="alert-top-row">
                            <span class="alert-service">${this.escapeHtml(alert.service)}</span>
                            <span class="alert-severity">${this.escapeHtml(alert.severity)}</span>
                        </div>
                        <div class="alert-meta">
                            <span class="alert-count">${alert.count} errors</span>
                            <span class="alert-time">${this.formatTime(alert.timestamp)}</span>
                        </div>
                        <div class="alert-message">${this.escapeHtml(alert.message)}</div>
                    </article>
                `;
            })
            .join('');
    }

    private notifyOnNewAlerts(alerts: Alert[]) {
        const latestWindow = alerts.slice(0, 5);

        latestWindow.forEach(alert => {
            const signature = `${alert.service}|${alert.severity}|${alert.count}|${alert.timestamp}`;
            if (this.knownSignatures.has(signature)) {
                return;
            }

            this.knownSignatures.add(signature);
            if (this.knownSignatures.size > 500) {
                this.knownSignatures = new Set(Array.from(this.knownSignatures).slice(-300));
            }

            this.showToast(`${alert.severity}: ${alert.service} has ${alert.count} errors`);
            if (alert.severity === 'CRITICAL') {
                this.playCriticalBeep();
            }
        });
    }

    private showToast(message: string) {
        const toast = document.createElement('div');
        toast.className = 'alert-toast';
        toast.textContent = message;
        document.body.appendChild(toast);

        globalThis.setTimeout(() => {
            toast.classList.add('show');
        }, 20);

        globalThis.setTimeout(() => {
            toast.classList.remove('show');
            globalThis.setTimeout(() => toast.remove(), 250);
        }, 3500);
    }

    private playCriticalBeep() {
        const AudioContextImpl = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!AudioContextImpl) return;

        const context = new AudioContextImpl();
        const osc = context.createOscillator();
        const gain = context.createGain();

        osc.type = 'sine';
        osc.frequency.setValueAtTime(880, context.currentTime);
        gain.gain.setValueAtTime(0.0001, context.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.15, context.currentTime + 0.01);
        gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.25);

        osc.connect(gain);
        gain.connect(context.destination);
        osc.start();
        osc.stop(context.currentTime + 0.25);

        globalThis.setTimeout(() => {
            context.close();
        }, 400);
    }

    private escapeHtml(input: string): string {
        return input
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }

    private formatTime(iso: string): string {
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) return 'unknown time';
        return date.toLocaleTimeString();
    }
}
