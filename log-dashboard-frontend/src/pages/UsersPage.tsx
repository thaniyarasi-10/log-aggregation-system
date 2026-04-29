import { useEffect, useState } from 'react';
import Modal from '../components/Modal';
import { apiService, extractApiErrorMessage } from '../services/api';
import type { UserRecord } from '../types';

type UserForm = {
  id: string;
  username: string;
  email: string;
  roles: string;
  services: string;
};

const emptyForm: UserForm = {
  id: '',
  username: '',
  email: '',
  roles: 'DEV',
  services: ''
};

function parseCsv(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export default function UsersPage() {
  const [users, setUsers] = useState<UserRecord[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [createForm, setCreateForm] = useState<UserForm>(emptyForm);
  const [editingUser, setEditingUser] = useState<UserRecord | null>(null);
  const [editForm, setEditForm] = useState<UserForm>(emptyForm);

  const loadUsers = async () => {
    try {
      setLoading(true);
      const data = await apiService.getUsers();
      setUsers(data);
      setError('');
    } catch (err) {
      const message = extractApiErrorMessage(err, 'Failed to load users');
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let active = true;

    const boot = async () => {
      try {
        setLoading(true);
        const data = await apiService.getUsers();
        if (!active) return;
        setUsers(data);
        setError('');
      } catch (err) {
        if (!active) return;
        const message = extractApiErrorMessage(err, 'Failed to load users');
        setError(message);
      } finally {
        if (active) setLoading(false);
      }
    };

    void boot();

    return () => {
      active = false;
    };
  }, []);

  const createUser = async () => {
    if (!createForm.id.trim()) {
      setError('User ID is required');
      return;
    }

    if (!createForm.email.trim()) {
      setError('Email is required');
      return;
    }

    try {
      setSubmitting(true);
      await apiService.createUser({
        id: createForm.id.trim().toUpperCase(),
        username: createForm.username.trim(),
        email: createForm.email.trim(),
        roles: parseCsv(createForm.roles),
        services: parseCsv(createForm.services)
      });
      setCreateForm(emptyForm);
      setError('');
      await loadUsers();
    } catch (err) {
      setError(extractApiErrorMessage(err, 'Failed to create user'));
    } finally {
      setSubmitting(false);
    }
  };

  const openEdit = (user: UserRecord) => {
    setEditingUser(user);
    setEditForm({
      id: user.id,
      username: user.username || user.name || '',
      email: user.email || '',
      roles: (user.roles || (user.role ? [user.role] : [])).join(', '),
      services: (user.services || []).join(', ')
    });
  };

  const saveEdit = async () => {
    if (!editingUser) return;
    if (!editForm.email.trim()) {
      setError('Email is required');
      return;
    }

    try {
      setSubmitting(true);
      await apiService.updateUser(editingUser.id, {
        username: editForm.username.trim(),
        email: editForm.email.trim(),
        roles: parseCsv(editForm.roles),
        services: parseCsv(editForm.services)
      });
      setEditingUser(null);
      setError('');
      await loadUsers();
    } catch (err) {
      setError(extractApiErrorMessage(err, 'Failed to update user'));
    } finally {
      setSubmitting(false);
    }
  };

  const removeUser = async (userId: string) => {
    const confirmed = window.confirm('Delete this user?');
    if (!confirmed) return;

    try {
      setSubmitting(true);
      await apiService.deleteUser(userId);
      setError('');
      await loadUsers();
    } catch (err) {
      setError(extractApiErrorMessage(err, 'Failed to delete user'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="dashboard-grid">
      <section className="dashboard-main">
        <section className="glass-panel table-container" style={{ minHeight: 'auto' }}>
          <div className="table-header"><h2>Add User</h2></div>
          <div className="admin-form-grid">
            <input
              className="form-control"
              placeholder="User ID (e.g., KL10004)"
              value={createForm.id}
              onChange={(e) => setCreateForm((prev) => ({ ...prev, id: e.target.value }))}
            />
            <input
              className="form-control"
              placeholder="Username"
              value={createForm.username}
              onChange={(e) => setCreateForm((prev) => ({ ...prev, username: e.target.value }))}
            />
            <input
              className="form-control"
              placeholder="Email"
              value={createForm.email}
              onChange={(e) => setCreateForm((prev) => ({ ...prev, email: e.target.value }))}
            />
            <input
              className="form-control"
              placeholder="Roles (comma separated)"
              value={createForm.roles}
              onChange={(e) => setCreateForm((prev) => ({ ...prev, roles: e.target.value }))}
            />
            <input
              className="form-control"
              placeholder="Services (comma separated)"
              value={createForm.services}
              onChange={(e) => setCreateForm((prev) => ({ ...prev, services: e.target.value }))}
            />
            <button className="btn" disabled={submitting} onClick={() => void createUser()}>
              {submitting ? 'Saving...' : 'Add User'}
            </button>
          </div>
        </section>

        <section className="glass-panel table-container">
          <div className="table-header"><h2>Users</h2></div>
          {loading && <div className="table-scroll-area" style={{ padding: '1rem' }}>Loading users...</div>}
          {error && <div className="table-scroll-area" style={{ padding: '1rem' }}><p className="error">{error}</p></div>}
          {!loading && !error && (
            <div className="table-scroll-area">
              <table className="log-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Services</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id} className="clickable-row">
                  <td>{user.id}</td>
                  <td>{user.name || user.username || '-'}</td>
                  <td>{user.email}</td>
                  <td>{(user.roles && user.roles.join(', ')) || user.role || '-'}</td>
                  <td>{(user.services && user.services.join(', ')) || '-'}</td>
                  <td>
                    <div className="table-actions">
                      <button className="btn" onClick={() => openEdit(user)}>Edit</button>
                      <button className="btn btn-danger" onClick={() => void removeUser(user.id)}>Delete</button>
                    </div>
                  </td>
                </tr>
              ))}
              {!users.length && (
                <tr>
                  <td colSpan={6}>No users found.</td>
                </tr>
              )}
            </tbody>
              </table>
            </div>
          )}
        </section>
      </section>

      <Modal
        open={Boolean(editingUser)}
        title="Edit User"
        onClose={() => setEditingUser(null)}
      >
        <div className="admin-form-grid">
          <input
            className="form-control"
            placeholder="Username"
            value={editForm.username}
            onChange={(e) => setEditForm((prev) => ({ ...prev, username: e.target.value }))}
          />
          <input
            className="form-control"
            placeholder="Email"
            value={editForm.email}
            onChange={(e) => setEditForm((prev) => ({ ...prev, email: e.target.value }))}
          />
          <input
            className="form-control"
            placeholder="Roles (comma separated)"
            value={editForm.roles}
            onChange={(e) => setEditForm((prev) => ({ ...prev, roles: e.target.value }))}
          />
          <input
            className="form-control"
            placeholder="Services (comma separated)"
            value={editForm.services}
            onChange={(e) => setEditForm((prev) => ({ ...prev, services: e.target.value }))}
          />
          <button className="btn" disabled={submitting} onClick={() => void saveEdit()}>
            {submitting ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </Modal>
    </main>
  );
}
