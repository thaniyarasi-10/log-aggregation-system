

import { LogEvent } from '../ts/types.js';
import { formatDate, escapeHtml, getLevelClass } from '../ts/utils.js';

export class LogTable {
    private container: HTMLElement;
    private maxLogs: number = 500;
    private autoScroll: boolean = true;
    private logs: LogEvent[] = [];
    private searchTerm: string = '';

    constructor(containerId: string) {
        const el = document.getElementById(containerId);
        if (!el) throw new Error(`Container ${containerId} not found`);
        this.container = el;
    }

    public setAutoScroll(value: boolean) {
        this.autoScroll = value;
    }

    public setSearchTerm(value?: string) {
        this.searchTerm = (value || '').trim();
    }

    public renderLogs(newLogs: LogEvent[]) {
        const firstRow = this.container.firstElementChild as HTMLElement | null;
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

    public clearLogs() {
        this.logs = [];
        this.container.innerHTML = '';
    }

    public showEmptyState(message: string) {
        this.logs = [];
        this.container.innerHTML = `
            <tr class="empty-state-row">
                <td colspan="6" style="padding: 1rem; text-align: center; color: var(--text-secondary);">
                    ${escapeHtml(message)}
                </td>
            </tr>
        `;
    }

    private createRow(log: LogEvent): HTMLElement {
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
                <div class="message-cell">${this.getHighlightedMessage(log.message)}</div>
            </td>
            <td>${log.statusCode || '-'}</td>
            <td>${log.responseTime ? log.responseTime + 'ms' : '-'}</td>
        `;

        // Expandable row details
        tr.addEventListener('click', () => this.showModalWrapper(log));

        return tr;
    }
    
    private showModalWrapper(log: LogEvent) {
        const event = new CustomEvent('showLogModal', { detail: log });
        document.dispatchEvent(event);
    }

    private getHighlightedMessage(message: string): string {
        if (!this.searchTerm) {
            return escapeHtml(message);
        }

        const regex = new RegExp(this.escapeRegex(this.searchTerm), 'ig');
        let result = '';
        let lastIndex = 0;

        for (const match of message.matchAll(regex)) {
            const index = match.index ?? 0;
            const value = match[0] || '';
            result += escapeHtml(message.slice(lastIndex, index));
            result += `<mark class="log-search-hit">${escapeHtml(value)}</mark>`;
            lastIndex = index + value.length;
        }

        result += escapeHtml(message.slice(lastIndex));
        return result;
    }

    private escapeRegex(value: string): string {
        return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }
}
