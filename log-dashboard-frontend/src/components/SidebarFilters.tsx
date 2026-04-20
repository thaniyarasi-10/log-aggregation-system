import type { LogFilters } from '../types';

type Props = {
  filters: LogFilters;
  services: string[];
  onChange: (next: LogFilters) => void;
};

export default function SidebarFilters({ filters, services, onChange }: Props) {
  const update = <K extends keyof LogFilters>(key: K, value: LogFilters[K]) => {
    onChange({ ...filters, [key]: value });
  };

  return (
    <aside className="glass-panel filters-sidebar">
      <h2>Filters</h2>

      <div className="filter-group">
        <label htmlFor="filter-search">Search Logs</label>
        <input
          id="filter-search"
          className="form-control"
          type="text"
          value={filters.search}
          onChange={(e) => update('search', e.target.value)}
          placeholder="Search message..."
        />
      </div>

      <div className="filter-group">
        <label htmlFor="filter-time">Time Range</label>
        <select
          id="filter-time"
          className="form-control"
          value={filters.timeRange}
          onChange={(e) => update('timeRange', e.target.value as LogFilters['timeRange'])}
        >
          <option value="5m">Last 5 minutes</option>
          <option value="15m">Last 15 minutes</option>
          <option value="1h">Last 1 hour</option>
          <option value="24h">Last 24 hours</option>
          <option value="7d">Last 7 days</option>
          <option value="15d">Last 15 days</option>
        </select>
      </div>

      <div className="filter-group">
        <label htmlFor="filter-service">Service</label>
        <select
          id="filter-service"
          className="form-control"
          value={filters.service}
          onChange={(e) => update('service', e.target.value)}
        >
          <option value="">All Services</option>
          {services.map((service) => (
            <option key={service} value={service}>{service}</option>
          ))}
        </select>
      </div>

      <div className="filter-group">
        <label htmlFor="filter-level">Level</label>
        <select
          id="filter-level"
          className="form-control"
          value={filters.level || 'ALL'}
          onChange={(e) => update('level', e.target.value === 'ALL' ? '' : e.target.value)}
        >
          <option value="ALL">All</option>
          <option value="ERROR">ERROR</option>
          <option value="WARN">WARN</option>
          <option value="INFO">INFO</option>
          <option value="DEBUG">DEBUG</option>
        </select>
      </div>
    </aside>
  );
}
