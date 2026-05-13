import { useEffect, useState } from 'react';
import type { LogFilters } from '../types';
import CheckboxFilter from './CheckboxFilter';

const LOG_LEVELS = ['ERROR', 'WARN', 'INFO', 'DEBUG'];

type Props = {
  filters: LogFilters;
  services: string[];
  onChange: (next: LogFilters) => void;
};

/**
 * Sidebar filter panel.
 *
 * Services and Levels use multi-select checkbox groups:
 *   - Empty selection → "All" (no filter applied)
 *   - Selecting "All" clears specific selections
 *   - Selecting a specific option unchecks "All"
 *
 * Selected filters are shown as removable chips below the filter groups.
 */
export default function SidebarFilters({ filters, services, onChange }: Props) {
  const [searchInput, setSearchInput] = useState(filters.search);

  // Debounce search input — avoids a fetch on every keystroke
  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (searchInput !== filters.search) {
        onChange({ ...filters, search: searchInput });
      }
    }, 300);
    return () => window.clearTimeout(timer);
  }, [searchInput, filters, onChange]);

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchInput(e.target.value);
  };

  const handleServicesChange = (next: string[]) => {
    onChange({
      ...filters,
      services: next,
      // Keep legacy alias in sync for any code that still reads filters.service
      service: next.length === 1 ? next[0] : '',
    });
  };

  const handleLevelsChange = (next: string[]) => {
    onChange({
      ...filters,
      levels: next,
      // Keep legacy alias in sync
      level: next.length === 1 ? next[0] : '',
    });
  };

  const handleTimeRangeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    onChange({ ...filters, timeRange: e.target.value as LogFilters['timeRange'] });
  };

  /** Remove a single service chip */
  const removeService = (svc: string) => {
    handleServicesChange(filters.services.filter((s) => s !== svc));
  };

  /** Remove a single level chip */
  const removeLevel = (lvl: string) => {
    handleLevelsChange(filters.levels.filter((l) => l !== lvl));
  };

  /** Clear all active filters */
  const clearAll = () => {
    onChange({ ...filters, services: [], levels: [], service: '', level: '', search: '' });
    setSearchInput('');
  };

  const hasActiveFilters =
    filters.services.length > 0 || filters.levels.length > 0 || filters.search.trim().length > 0;

  return (
    <aside className="glass-panel filters-sidebar">
      <div className="filters-sidebar-top">
        <h2>Filters</h2>
        {hasActiveFilters && (
          <button className="filters-clear-btn" onClick={clearAll} type="button" title="Clear all filters">
            Clear
          </button>
        )}
      </div>

      {/* ── Search ── */}
      <div className="filter-group">
        <label htmlFor="filter-search">Search Logs</label>
        <input
          id="filter-search"
          className="form-control"
          type="text"
          value={searchInput}
          onChange={handleSearchChange}
          placeholder="Search message…"
        />
      </div>

      {/* ── Time Range ── */}
      <div className="filter-group">
        <label htmlFor="filter-time">Time Range</label>
        <select
          id="filter-time"
          className="form-control"
          value={filters.timeRange}
          onChange={handleTimeRangeChange}
        >
          <option value="5m">Last 5 minutes</option>
          <option value="15m">Last 15 minutes</option>
          <option value="1h">Last 1 hour</option>
          <option value="24h">Last 24 hours</option>
          <option value="7d">Last 7 days</option>
          <option value="15d">Last 15 days</option>
        </select>
      </div>

      {/* ── Services multi-select ── */}
      <CheckboxFilter
        label="Services"
        options={services}
        selected={filters.services}
        onChange={handleServicesChange}
        searchThreshold={6}
      />

      {/* ── Levels multi-select ── */}
      <CheckboxFilter
        label="Levels"
        options={LOG_LEVELS}
        selected={filters.levels}
        onChange={handleLevelsChange}
        colorize
      />

      {/* ── Active filter chips ── */}
      {hasActiveFilters && (
        <div className="filter-chips">
          {filters.services.map((svc) => (
            <span key={svc} className="filter-chip filter-chip-service">
              {svc}
              <button
                className="filter-chip-remove"
                onClick={() => removeService(svc)}
                aria-label={`Remove service filter: ${svc}`}
                type="button"
              >
                ×
              </button>
            </span>
          ))}
          {filters.levels.map((lvl) => (
            <span key={lvl} className={`filter-chip filter-chip-level filter-chip-level-${lvl.toLowerCase()}`}>
              {lvl}
              <button
                className="filter-chip-remove"
                onClick={() => removeLevel(lvl)}
                aria-label={`Remove level filter: ${lvl}`}
                type="button"
              >
                ×
              </button>
            </span>
          ))}
          {filters.search.trim() && (
            <span className="filter-chip filter-chip-search">
              "{filters.search.trim()}"
              <button
                className="filter-chip-remove"
                onClick={() => { onChange({ ...filters, search: '' }); setSearchInput(''); }}
                aria-label="Remove search filter"
                type="button"
              >
                ×
              </button>
            </span>
          )}
        </div>
      )}
    </aside>
  );
}
