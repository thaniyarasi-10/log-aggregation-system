import { ApiClient } from '../ts/api.js';
export class AlertsPanel {
    listContainer;
    refreshTimeElement;
    intervalId = null;
    knownSignatures = new Set();
    constructor(listContainerId, refreshTimeId) {
        const listEl = document.getElementById(listContainerId);
        const refreshEl = document.getElementById(refreshTimeId);
        if (!listEl)
            throw new Error(`Container ${listContainerId} not found`);
        if (!refreshEl)
            throw new Error(`Container ${refreshTimeId} not found`);
        this.listContainer = listEl;
        this.refreshTimeElement = refreshEl;
    }
    start(intervalMs = 5000) {
        this.stop();
        this.refresh();
        this.intervalId = globalThis.setInterval(() => this.refresh(), intervalMs);
    }
    stop() {
        if (this.intervalId !== null) {
            globalThis.clearInterval(this.intervalId);
            this.intervalId = null;
        }
    }
    async refresh() {
        const groupedAlerts = await ApiClient.fetchAlerts();
        const flattened = this.flattenAndSort(groupedAlerts);
        this.render(flattened);
        this.refreshTimeElement.textContent = `Last updated: ${new Date().toLocaleTimeString()}`;
        this.notifyOnNewAlerts(flattened);
    }
    flattenAndSort(grouped) {
        const all = Object.values(grouped).flat();
        return all.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    }
    render(alerts) {
        if (!alerts.length) {
            this.listContainer.innerHTML = '<div class="alerts-empty">No active alerts</div>';
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
    notifyOnNewAlerts(alerts) {
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
    showToast(message) {
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
    playCriticalBeep() {
        const AudioContextImpl = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextImpl)
            return;
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
    escapeHtml(input) {
        return input
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }
    formatTime(iso) {
        const date = new Date(iso);
        if (Number.isNaN(date.getTime()))
            return 'unknown time';
        return date.toLocaleTimeString();
    }
}
