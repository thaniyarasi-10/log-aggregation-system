type Props = {
  login: () => void;
  statusMessage: string;
};

export default function LoginOverlay({ login, statusMessage }: Props) {
  const startLogin = () => {
    login();
  };

  return (
    <div className="oauth-overlay">
      <section className="glass-panel oauth-panel">
        <div className="oauth-logo">⏲</div>
        <h2>LogFlow Observability</h2>
        <p>Sign in with Microsoft Entra ID to access the dashboard.</p>
        <button type="button" className="oauth-btn oauth-btn-azure" onClick={startLogin}>
          Continue With Azure
        </button>
        <div className="auth-status">{statusMessage}</div>
      </section>
    </div>
  );
}
