import { useState, useMemo } from 'react';

interface CheckboxFilterProps {
  /** Section label shown above the checkbox list */
  label: string;
  /** All available options */
  options: string[];
  /** Currently selected options (empty = "All") */
  selected: string[];
  /** Called when selection changes */
  onChange: (next: string[]) => void;
  /** Show a search box when option count exceeds this threshold (default: 6) */
  searchThreshold?: number;
  /** Render a colored dot next to level options */
  colorize?: boolean;
}

/** Maps log level names to their CSS variable colors */
const LEVEL_COLORS: Record<string, string> = {
  ERROR: 'var(--level-error)',
  WARN:  'var(--level-warn)',
  INFO:  'var(--level-info)',
  DEBUG: 'var(--level-debug)',
};

/**
 * Reusable multi-select checkbox filter with:
 * - "All" master checkbox (clears specific selections)
 * - Per-option checkboxes
 * - Optional search box for long lists
 * - Collapsible section
 * - Selected-count badge
 */
export default function CheckboxFilter({
  label,
  options,
  selected,
  onChange,
  searchThreshold = 6,
  colorize = false,
}: CheckboxFilterProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [search, setSearch] = useState('');

  const isAllSelected = selected.length === 0;

  const filteredOptions = useMemo(() => {
    if (!search.trim()) return options;
    const q = search.trim().toLowerCase();
    return options.filter((o) => o.toLowerCase().includes(q));
  }, [options, search]);

  const handleAllChange = () => {
    // Clicking "All" clears specific selections → show all
    onChange([]);
  };

  const handleOptionChange = (option: string, checked: boolean) => {
    if (checked) {
      onChange([...selected, option]);
    } else {
      const next = selected.filter((s) => s !== option);
      onChange(next);
    }
  };

  return (
    <div className="checkbox-filter-group">
      {/* Section header with collapse toggle and selected count badge */}
      <button
        className="checkbox-filter-header"
        onClick={() => setCollapsed((c) => !c)}
        aria-expanded={!collapsed}
        type="button"
      >
        <span className="checkbox-filter-label">{label}</span>
        <span className="checkbox-filter-header-right">
          {selected.length > 0 && (
            <span className="checkbox-filter-badge">{selected.length}</span>
          )}
          <span className="checkbox-filter-chevron" aria-hidden="true">
            {collapsed ? '▸' : '▾'}
          </span>
        </span>
      </button>

      {!collapsed && (
        <div className="checkbox-filter-body">
          {/* Search box — only shown when option list is long */}
          {options.length > searchThreshold && (
            <input
              className="form-control checkbox-filter-search"
              type="text"
              placeholder={`Search ${label.toLowerCase()}…`}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label={`Search ${label}`}
            />
          )}

          <ul className="checkbox-filter-list" role="group" aria-label={label}>
            {/* "All" master option */}
            <li className="checkbox-filter-item">
              <label className="checkbox-filter-option">
                <input
                  type="checkbox"
                  checked={isAllSelected}
                  onChange={handleAllChange}
                  aria-label={`All ${label}`}
                />
                <span className="checkbox-filter-option-text">All {label}</span>
              </label>
            </li>

            {/* Individual options */}
            {filteredOptions.map((option) => {
              const isChecked = selected.includes(option);
              const dotColor = colorize ? LEVEL_COLORS[option.toUpperCase()] : undefined;

              return (
                <li key={option} className="checkbox-filter-item">
                  <label className="checkbox-filter-option">
                    <input
                      type="checkbox"
                      checked={isChecked}
                      onChange={(e) => handleOptionChange(option, e.target.checked)}
                      aria-label={option}
                    />
                    {dotColor && (
                      <span
                        className="checkbox-filter-dot"
                        style={{ background: dotColor }}
                        aria-hidden="true"
                      />
                    )}
                    <span className="checkbox-filter-option-text">{option}</span>
                  </label>
                </li>
              );
            })}

            {filteredOptions.length === 0 && search && (
              <li className="checkbox-filter-empty">No matches</li>
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
