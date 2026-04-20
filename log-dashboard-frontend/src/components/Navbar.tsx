import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';

const getClassName = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'header-nav-link active' : 'header-nav-link';

export default function Navbar() {
  const { user, canAccessUsers, canAccessServices, logout } = useAuth();
  const { theme, toggleTheme } = useTheme();

  return (
    <header className="glass-panel dashboard-header">
      <div className="header-logo">
        <span className="logo-icon">◷</span>
        <h1 className="header-title">LogFlow Observability</h1>
      </div>

      <div className="header-actions">
        <nav className="header-nav">
          <NavLink to="/logs" className={getClassName}>Logs</NavLink>
          {canAccessUsers && <NavLink to="/users" className={getClassName}>Users</NavLink>}
          {canAccessServices && <NavLink to="/services" className={getClassName}>Services</NavLink>}
        </nav>
        <button className="btn" onClick={toggleTheme} aria-label="Toggle theme" title="Toggle theme">
          {theme === 'dark' ? '☀' : '☾'}
        </button>
        <div className="header-user">{user?.name || user?.email || 'User'}</div>
        <button className="btn" onClick={() => void logout()}>Logout</button>
      </div>
    </header>
  );
}
