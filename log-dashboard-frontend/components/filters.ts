import { FilterOptions, LogEvent } from '../ts/types.js';

export class Filters {
    private static readonly RETENTION_DAYS = 15;
    private onChangeCallback: (filters: FilterOptions) => void;
    private knownServices = new Set<string>();
    private knownLevels = new Set<string>(['ERROR', 'WARN', 'INFO', 'DEBUG']);
    
    constructor(onChange: (filters: FilterOptions) => void) {
        this.onChangeCallback = onChange;
        this.renderLevels();
        this.bindEvents();
        this.configureRetentionBounds();
        this.updateCustomRangeVisibility();
    }

    public updateAvailableOptions(logs: LogEvent[]) {
        let changedService = false;
        let changedLevel = false;

        logs.forEach(log => {
            if (log.service && !this.knownServices.has(log.service)) {
                this.knownServices.add(log.service);
                changedService = true;
            }
            if (log.level && !this.knownLevels.has(log.level)) {
                this.knownLevels.add(log.level);
                changedLevel = true;
            }
        });

        if (changedService) this.renderServices();
        if (changedLevel) this.renderLevels();
    }

    public setAvailableServices(services: string[]) {
        const unique = new Set<string>(this.knownServices);
        services.forEach(service => {
            if (service && service.trim().length > 0) {
                unique.add(service.trim());
            }
        });

        this.knownServices = unique;
        this.renderServices();
    }

    private renderServices() {
        const select = document.getElementById('filter-service') as HTMLSelectElement;
        if (!select) return;
        const currentVal = select.value;
        select.innerHTML = '<option value="">All Services</option>';
        Array.from(this.knownServices).sort().forEach(svc => {
            const opt = document.createElement('option');
            opt.value = svc;
            opt.textContent = svc;
            if (svc === currentVal) opt.selected = true;
            select.appendChild(opt);
        });
    }

    private renderLevels() {
        const select = document.getElementById('filter-level') as HTMLSelectElement;
        if (!select) return;
        const currentVal = select.value;
        select.innerHTML = '<option value="ALL">All</option>';
        Array.from(this.knownLevels).sort().forEach(lvl => {
            const opt = document.createElement('option');
            opt.value = lvl;
            opt.textContent = lvl;
            if (lvl === currentVal) opt.selected = true;
            select.appendChild(opt);
        });
    }

    private bindEvents() {
        const serviceSelect = document.getElementById('filter-service') as HTMLSelectElement;
        const levelSelect = document.getElementById('filter-level') as HTMLSelectElement;
        const searchInput = document.getElementById('filter-search') as HTMLInputElement;
        const timeSelect = document.getElementById('filter-time') as HTMLSelectElement;
        const customFromInput = document.getElementById('filter-from') as HTMLInputElement;
        const customToInput = document.getElementById('filter-to') as HTMLInputElement;
        
        const attachChange = (el: HTMLElement | null) => {
            if (el) el.addEventListener('change', () => this.applyFilters());
        };

        attachChange(serviceSelect);
        attachChange(levelSelect);
        if (timeSelect) {
            timeSelect.addEventListener('change', () => {
                this.updateCustomRangeVisibility();
                this.applyFilters();
            });
        }

        attachChange(customFromInput);
        attachChange(customToInput);

        if (searchInput) {
            let timeout: any;
            searchInput.addEventListener('input', () => {
                clearTimeout(timeout);
                timeout = setTimeout(() => this.applyFilters(), 300);
            });
        }
    }

    private updateCustomRangeVisibility() {
        const customRangeContainer = document.getElementById('custom-time-range') as HTMLDivElement;
        const timeSelect = document.getElementById('filter-time') as HTMLSelectElement;
        if (!customRangeContainer || !timeSelect) return;

        customRangeContainer.style.display = timeSelect.value === 'custom' ? 'block' : 'none';
    }

    private getCustomRange(): { from?: string; to?: string; isValid: boolean } {
        const fromInput = (document.getElementById('filter-from') as HTMLInputElement)?.value;
        const toInput = (document.getElementById('filter-to') as HTMLInputElement)?.value;

        if (!fromInput || !toInput) {
            return { isValid: false };
        }

        const fromDate = new Date(fromInput);
        const toDate = new Date(toInput);
        if (!Number.isFinite(fromDate.getTime()) || !Number.isFinite(toDate.getTime())) {
            return { isValid: false };
        }

        const now = new Date();
        const minDate = new Date(now.getTime() - Filters.RETENTION_DAYS * 24 * 60 * 60 * 1000);
        const clampedFrom = new Date(Math.max(fromDate.getTime(), minDate.getTime()));
        const clampedTo = new Date(Math.min(toDate.getTime(), now.getTime()));

        if (clampedFrom.getTime() > clampedTo.getTime()) {
            return { isValid: false };
        }

        return {
            from: clampedFrom.toISOString(),
            to: clampedTo.toISOString(),
            isValid: true
        };
    }

    public getCurrentFilters(): FilterOptions {
        const service = (document.getElementById('filter-service') as HTMLSelectElement)?.value;
        const levelVal = (document.getElementById('filter-level') as HTMLSelectElement)?.value;
        const message = (document.getElementById('filter-search') as HTMLInputElement)?.value;
        const timeRange = (document.getElementById('filter-time') as HTMLSelectElement)?.value;
        
        const filters: FilterOptions = {};
        if (service) filters.service = service;
        if (levelVal && levelVal !== 'ALL') filters.level = [levelVal];
        if (message && message.trim().length > 0) filters.message = message.trim();
        if (timeRange) filters.timePreset = timeRange as FilterOptions['timePreset'];
        
        if (timeRange === 'custom') {
            const customRange = this.getCustomRange();
            if (customRange.isValid) {
                filters.from = customRange.from;
                filters.to = customRange.to;
            }
            return filters;
        }

        if (timeRange) {
            const now = new Date();
            let msToSubtract = 0;
            if (timeRange === '5m') msToSubtract = 5 * 60 * 1000;
            else if (timeRange === '15m') msToSubtract = 15 * 60 * 1000;
            else if (timeRange === '1h') msToSubtract = 60 * 60 * 1000;
            else if (timeRange === '24h') msToSubtract = 24 * 60 * 60 * 1000;
            else if (timeRange === '7d') msToSubtract = 7 * 24 * 60 * 60 * 1000;
            else if (timeRange === '15d') msToSubtract = 15 * 24 * 60 * 60 * 1000;

            if (msToSubtract > 0) {
                const fromTime = new Date(now.getTime() - msToSubtract);
                filters.from = fromTime.toISOString();
                filters.to = now.toISOString();
            }
        }
        
        return filters;
    }

    public applyFilters() {
        const timeRange = (document.getElementById('filter-time') as HTMLSelectElement)?.value;
        if (timeRange === 'custom') {
            const customRange = this.getCustomRange();
            if (!customRange.isValid) {
                return;
            }
        }
        this.onChangeCallback(this.getCurrentFilters());
    }

    private configureRetentionBounds() {
        const fromInput = document.getElementById('filter-from') as HTMLInputElement | null;
        const toInput = document.getElementById('filter-to') as HTMLInputElement | null;
        if (!fromInput || !toInput) {
            return;
        }

        const now = new Date();
        const minDate = new Date(now.getTime() - Filters.RETENTION_DAYS * 24 * 60 * 60 * 1000);
        const minValue = this.toDateTimeLocalValue(minDate);
        const maxValue = this.toDateTimeLocalValue(now);

        fromInput.min = minValue;
        fromInput.max = maxValue;
        toInput.min = minValue;
        toInput.max = maxValue;

        if (!fromInput.value) {
            const defaultFrom = new Date(now.getTime() - 60 * 60 * 1000);
            fromInput.value = this.toDateTimeLocalValue(defaultFrom);
        }
        if (!toInput.value) {
            toInput.value = maxValue;
        }
    }

    private toDateTimeLocalValue(date: Date): string {
        const pad = (n: number) => String(n).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }
}
