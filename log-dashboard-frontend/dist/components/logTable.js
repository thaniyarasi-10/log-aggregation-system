import { formatDate, escapeHtml, getLevelClass } from '../ts/utils.js';
export class LogTable {
    container;
    maxLogs = 500;
    autoScroll = true;
    logs = [];
    constructor(containerId) {
        const el = document.getElementById(containerId);
        if (!el)
            throw new Error(`Container ${containerId} not found`);
        this.container = el;
    }
    setAutoScroll(value) {
        this.autoScroll = value;
    }
    renderLogs(newLogs) {
        const firstRow = this.container.firstElementChild;
        if (firstRow && firstRow.classList.contains('empty-state-row')) {
            this.container.innerHTML = '';
        }
        const fragment = document.createDocumentFragment();
        newLogs.forEach(log => {
            if (this.logs.length >= this.maxLogs) {
                this.logs.shift(); // Remove oldest from array
                if (this.container.firstElementChild) {
                    this.container.removeChild(this.container.firstElementChild); // Remove oldest from DOM
                }
            }
            this.logs.push(log);
            fragment.appendChild(this.createRow(log));
        });
        this.container.appendChild(fragment);
        if (this.autoScroll) {
            const scrollArea = this.container.parentElement?.parentElement;
            if (scrollArea) {
                scrollArea.scrollTop = scrollArea.scrollHeight;
            }
        }
    }
    clearLogs() {
        this.logs = [];
        this.container.innerHTML = '';
    }
    showEmptyState(message) {
        this.logs = [];
        this.container.innerHTML = `
            <tr class="empty-state-row">
                <td colspan="6" style="padding: 1rem; text-align: center; color: var(--text-secondary);">
                    ${escapeHtml(message)}
                </td>
            </tr>
        `;
    }
    createRow(log) {
        const tr = document.createElement('tr');
        tr.className = 'log-row animate-fade-in';
        if (log.responseTime && log.responseTime > 1000) {
            tr.classList.add('slow-log');
        }
        tr.innerHTML = `
            <td>${formatDate(log.timestamp)}</td>
            <td>${escapeHtml(log.service)}</td>
            <td><span class="tag ${getLevelClass(log.level)}">${escapeHtml(log.level)}</span></td>
            <td>
                <div class="message-cell">${escapeHtml(log.message)}</div>
            </td>
            <td>${log.statusCode || '-'}</td>
            <td>${log.responseTime ? log.responseTime + 'ms' : '-'}</td>
        `;
        // Expandable row details
        tr.addEventListener('click', () => this.showModalWrapper(log));
        return tr;
    }
    showModalWrapper(log) {
        const event = new CustomEvent('showLogModal', { detail: log });
        document.dispatchEvent(event);
    }
}
