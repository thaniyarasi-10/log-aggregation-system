import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import Navbar from './components/Navbar';
import LoginOverlay from './components/LoginOverlay';
import FloatingAgent from './components/FloatingAgent.jsx';
import { useAuth } from './context/AuthContext';
import LogsPage from './pages/LogsPage';
import ServicesPage from './pages/ServicesPage';
import UsersPage from './pages/UsersPage';

function RouteLogger() {
  const location = useLocation();

  useEffect(() => {
    console.log('Route changed:', location.pathname);
  }, [location.pathname]);

  return null;
}

export default function App() {
  const { status, login, canAccessUsers, canAccessServices } = useAuth();

  if (status !== 'authenticated') {
    return (
      <LoginOverlay
        login={login}
        statusMessage={status === 'loading' ? 'Checking existing session...' : 'Not signed in. Use Microsoft Entra ID to continue.'}
      />
    );
  }

  return (
    <div id="app-container">
      <RouteLogger />
      <Navbar />
      <Routes>
        <Route path="/logs" element={<LogsPage />} />
        <Route path="/users" element={canAccessUsers ? <UsersPage /> : <Navigate to="/logs" replace />} />
        <Route path="/services" element={canAccessServices ? <ServicesPage /> : <Navigate to="/logs" replace />} />
        <Route path="*" element={<Navigate to="/logs" replace />} />
      </Routes>
      <FloatingAgent />
    </div>
  );
}
