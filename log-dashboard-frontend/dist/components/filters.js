export class Filters {
    onChangeCallback;
    knownServices = new Set();
    knownLevels = new Set(['ERROR', 'WARN', 'INFO', 'DEBUG']);
    constructor(onChange) {
        this.onChangeCallback = onChange;
        this.renderLevels();
        this.bindEvents();
        this.updateCustomRangeVisibility();
    }
    updateAvailableOptions(logs) {
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
        if (changedService)
            this.renderServices();
        if (changedLevel)
            this.renderLevels();
    }
    renderServices() {
        const select = document.getElementById('filter-service');
        if (!select)
            return;
        const currentVal = select.value;
        select.innerHTML = '<option value="">All Services</option>';
        Array.from(this.knownServices).sort().forEach(svc => {
            const opt = document.createElement('option');
            opt.value = svc;
            opt.textContent = svc;
            if (svc === currentVal)
                opt.selected = true;
            select.appendChild(opt);
        });
    }
    renderLevels() {
        const select = document.getElementById('filter-level');
        if (!select)
            return;
        const currentVal = select.value;
        select.innerHTML = '<option value="ALL">All</option>';
        Array.from(this.knownLevels).sort().forEach(lvl => {
            const opt = document.createElement('option');
            opt.value = lvl;
            opt.textContent = lvl;
            if (lvl === currentVal)
                opt.selected = true;
            select.appendChild(opt);
        });
    }
    bindEvents() {
        const serviceSelect = document.getElementById('filter-service');
        const levelSelect = document.getElementById('filter-level');
        const searchInput = document.getElementById('filter-search');
        const timeSelect = document.getElementById('filter-time');
        const customFromInput = document.getElementById('filter-from');
        const customToInput = document.getElementById('filter-to');
        const attachChange = (el) => {
            if (el)
                el.addEventListener('change', () => this.applyFilters());
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
            let timeout;
            searchInput.addEventListener('input', () => {
                clearTimeout(timeout);
                timeout = setTimeout(() => this.applyFilters(), 300);
            });
        }
    }
    updateCustomRangeVisibility() {
        const customRangeContainer = document.getElementById('custom-time-range');
        const timeSelect = document.getElementById('filter-time');
        if (!customRangeContainer || !timeSelect)
            return;
        customRangeContainer.style.display = timeSelect.value === 'custom' ? 'block' : 'none';
    }
    getCustomRange() {
        const fromInput = document.getElementById('filter-from')?.value;
        const toInput = document.getElementById('filter-to')?.value;
        if (!fromInput || !toInput) {
            return { isValid: false };
        }
        const fromDate = new Date(fromInput);
        const toDate = new Date(toInput);
        if (!Number.isFinite(fromDate.getTime()) || !Number.isFinite(toDate.getTime())) {
            return { isValid: false };
        }
        if (fromDate.getTime() > toDate.getTime()) {
            return { isValid: false };
        }
        return {
            from: fromDate.toISOString(),
            to: toDate.toISOString(),
            isValid: true
        };
    }
    getCurrentFilters() {
        const service = document.getElementById('filter-service')?.value;
        const levelVal = document.getElementById('filter-level')?.value;
        const message = document.getElementById('filter-search')?.value;
        const timeRange = document.getElementById('filter-time')?.value;
        const filters = {};
        if (service)
            filters.service = service;
        if (levelVal && levelVal !== 'ALL')
            filters.level = [levelVal];
        if (message && message.trim().length > 0)
            filters.message = message.trim();
        if (timeRange)
            filters.timePreset = timeRange;
        if (timeRange === 'custom') {
            const customRange = this.getCustomRange();
            if (customRange.isValid) {
                filters.from = customRange.from;
                filters.to = customRange.to;
            }
            return filters;
        }
        if (timeRange && timeRange !== 'all') {
            const now = new Date();
            let msToSubtract = 0;
            if (timeRange === '5m')
                msToSubtract = 5 * 60 * 1000;
            else if (timeRange === '15m')
                msToSubtract = 15 * 60 * 1000;
            else if (timeRange === '1h')
                msToSubtract = 60 * 60 * 1000;
            else if (timeRange === '24h')
                msToSubtract = 24 * 60 * 60 * 1000;
            if (msToSubtract > 0) {
                const fromTime = new Date(now.getTime() - msToSubtract);
                filters.from = fromTime.toISOString();
                filters.to = now.toISOString();
            }
        }
        return filters;
    }
    applyFilters() {
        const timeRange = document.getElementById('filter-time')?.value;
        if (timeRange === 'custom') {
            const customRange = this.getCustomRange();
            if (!customRange.isValid) {
                return;
            }
        }
        this.onChangeCallback(this.getCurrentFilters());
    }
}
