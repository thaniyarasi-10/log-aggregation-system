import { useEffect, useRef, useState } from 'react';
import Modal from '../components/Modal';
import { useAuth } from '../context/AuthContext';
import { apiService, extractApiErrorMessage } from '../services/api';
import type { ServiceAccessRequest, ServiceRecord } from '../types';

type ServiceForm = {
  name: string;
  description: string;
};

const emptyServiceForm: ServiceForm = {
  name: '',
  description: ''
};

export default function ServicesPage() {
  const { isAdmin } = useAuth();
  const addNameInputRef = useRef<HTMLInputElement | null>(null);
  const [services, setServices] = useState<ServiceRecord[]>([]);
  const [requests, setRequests] = useState<ServiceAccessRequest[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [loadError, setLoadError] = useState<string>('');
  const [requestsLoading, setRequestsLoading] = useState<boolean>(true);
  const [requestsError, setRequestsError] = useState<string>('');
  const [actionError, setActionError] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [showCreateInline, setShowCreateInline] = useState<boolean>(false);
  const [createForm, setCreateForm] = useState<ServiceForm>(emptyServiceForm);
  const [editService, setEditService] = useState<ServiceRecord | null>(null);
  const [editForm, setEditForm] = useState<ServiceForm>(emptyServiceForm);

  const toCleanLoadError = (err: unknown) => {
    const message = extractApiErrorMessage(err, 'Failed to load services').trim();
    if (!message || message.toLowerCase() === 'invalid request data') {
      return 'Failed to load services';
    }
    return message;
  };

  const toCleanRequestsError = (err: unknown) => {
    const message = extractApiErrorMessage(err, 'Failed to load requests').trim();
    if (!message || message.toLowerCase() === 'invalid request data') {
      return 'Failed to load requests';
    }
    return message;
  };

  const loadServices = async () => {
    const data = isAdmin ? await apiService.getAdminServices() : await apiService.getServices();
    setServices(data);
  };

  const loadRequests = async () => {
    try {
      const data = await apiService.getServiceRequests();
      setRequests(data);
      setRequestsError('');
    } catch (err) {
      setRequestsError(toCleanRequestsError(err));
      setRequests([]);
    }
  };

  useEffect(() => {
    let active = true;

    const boot = async (showLoader: boolean) => {
      try {
        if (showLoader) {
          setLoading(true);
        }

        const data = isAdmin ? await apiService.getAdminServices() : await apiService.getServices();
        if (!active) return;
        setServices(data);
        setLoadError('');
      } catch (err) {
        if (!active) return;
        setLoadError(toCleanLoadError(err));
      }

      try {
        const pendingRequests = await apiService.getServiceRequests();
        if (active) {
          setRequests(pendingRequests);
          setRequestsError('');
        }
      } catch (err) {
        if (active) {
          setRequestsError(toCleanRequestsError(err));
          setRequests([]);
        }
      } finally {
        if (showLoader && active) setLoading(false);
        if (showLoader && active) setRequestsLoading(false);
      }
    };

    void boot(true);
    const timer = window.setInterval(() => {
      void boot(false);
    }, 5000);

    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [isAdmin]);

  const normalizedRequestStatus = (status?: string) => String(status || '').trim().toUpperCase();
  const pendingRequests = requests.filter((request) => normalizedRequestStatus(request.status) === 'PENDING');
  const rejectedRequests = requests.filter((request) => normalizedRequestStatus(request.status) === 'REJECTED');

  const getStatusBadgeClass = (status?: string) => {
    const normalized = normalizedRequestStatus(status);
    if (normalized === 'APPROVED') return 'tag tag-info';
    if (normalized === 'REJECTED') return 'tag tag-error';
    return 'tag tag-warn';
  };

  const createService = async () => {
    if (!createForm.name.trim()) {
      setActionError('Service name is required');
      return;
    }

    try {
      setSubmitting(true);
      if (isAdmin) {
        await apiService.createService({
          name: createForm.name.trim(),
          description: createForm.description.trim()
        });
      } else {
        await apiService.requestService({
          serviceName: createForm.name.trim(),
          description: createForm.description.trim()
        });
      }
      setCreateForm(emptyServiceForm);
      setShowCreateInline(false);
      await loadServices();
      await loadRequests();
      setActionError('');
    } catch (err) {
      setActionError(extractApiErrorMessage(err, 'Failed to submit service'));
    } finally {
      setSubmitting(false);
    }
  };

  const openEdit = (service: ServiceRecord) => {
    setEditService(service);
    setEditForm({
      name: service.name,
      description: service.description || ''
    });
  };

  const saveEdit = async () => {
    if (!editService?.id) return;
    if (!editForm.name.trim()) {
      setActionError('Service name is required');
      return;
    }

    try {
      setSubmitting(true);
      await apiService.updateService(editService.id, {
        name: editForm.name.trim(),
        description: editForm.description.trim()
      });
      setEditService(null);
      await loadServices();
      setActionError('');
    } catch (err) {
      setActionError(extractApiErrorMessage(err, 'Failed to update service'));
    } finally {
      setSubmitting(false);
    }
  };

  const removeService = async (serviceId?: string) => {
    if (!serviceId) return;
    if (!window.confirm('Delete this service?')) return;

    try {
      setSubmitting(true);
      await apiService.deleteService(serviceId);
      await loadServices();
      setActionError('');
    } catch (err) {
      setActionError(extractApiErrorMessage(err, 'Failed to delete service'));
    } finally {
      setSubmitting(false);
    }
  };

  const approveRequest = async (request: ServiceAccessRequest) => {
    try {
      setSubmitting(true);
      await apiService.approveService(request.id, {
        description: request.description || '',
        comment: 'Approved via services page'
      });
      await loadServices();
      await loadRequests();
      setActionError('');
    } catch (err) {
      setActionError(extractApiErrorMessage(err, 'Failed to approve request'));
    } finally {
      setSubmitting(false);
    }
  };

  const rejectRequest = async (request: ServiceAccessRequest) => {
    try {
      setSubmitting(true);
      await apiService.rejectService(request.id, { comment: 'Rejected via services page' });
      await loadRequests();
      setActionError('');
    } catch (err) {
      setActionError(extractApiErrorMessage(err, 'Failed to reject request'));
    } finally {
      setSubmitting(false);
    }
  };

  const openCreateInline = () => {
    setShowCreateInline(true);
    window.setTimeout(() => {
      addNameInputRef.current?.focus();
    }, 0);
  };

  return (
    <main className="dashboard-grid">
      <section className="dashboard-main">
        <section className="glass-panel table-container">
          <div className="table-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h2>Approved Services</h2>
            {isAdmin && (
              <button className="btn" disabled={submitting} onClick={openCreateInline}>
                Add Service
              </button>
            )}
          </div>
          {(showCreateInline || !isAdmin) && (
            <div className="admin-form-grid" style={{ borderBottom: '1px solid var(--glass-border)' }}>
              <input
                ref={addNameInputRef}
                className="form-control"
                placeholder="Service name"
                value={createForm.name}
                onChange={(e) => setCreateForm((prev) => ({ ...prev, name: e.target.value }))}
              />
              <input
                className="form-control"
                placeholder="Description"
                value={createForm.description}
                onChange={(e) => setCreateForm((prev) => ({ ...prev, description: e.target.value }))}
              />
              <button className="btn" disabled={submitting} onClick={() => void createService()}>
                {submitting ? 'Saving...' : (isAdmin ? 'Add Service' : 'Request Service')}
              </button>
              {isAdmin && (
                <button
                  className="btn"
                  disabled={submitting}
                  onClick={() => {
                    setShowCreateInline(false);
                    setCreateForm(emptyServiceForm);
                    setActionError('');
                  }}
                >
                  Cancel
                </button>
              )}
            </div>
          )}
          {actionError && <div className="table-scroll-area" style={{ padding: '1rem' }}><p className="error">{actionError}</p></div>}
          {loading && <div className="table-scroll-area state-message">Loading services...</div>}
          {!loading && loadError && (
            <div className="table-scroll-area state-message">
              <p className="error">{loadError}</p>
            </div>
          )}
          {!loading && !loadError && (
            <div className="table-scroll-area">
              <table className="log-table services-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {services.map((service) => (
                <tr key={service.id || service.name} className="clickable-row">
                  <td className="services-cell-name">{service.name}</td>
                  <td className="services-cell-description">{service.description || '-'}</td>
                  <td className="services-cell-status">{service.status || (service.active === false ? 'INACTIVE' : 'ACTIVE')}</td>
                  <td className="services-cell-actions">
                    {isAdmin ? (
                      <div className="table-actions">
                        <button className="btn" onClick={() => openEdit(service)} disabled={!service.id}>
                          Edit
                        </button>
                        <button
                          className="btn btn-danger"
                          onClick={() => void removeService(service.id)}
                          disabled={!service.id}
                        >
                          Delete
                        </button>
                      </div>
                    ) : (
                      <span className="muted-cell">-</span>
                    )}
                  </td>
                </tr>
              ))}
              {!services.length && (
                <tr>
                  <td colSpan={4}>No services available</td>
                </tr>
              )}
            </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="glass-panel table-container">
          <div className="table-header"><h2>{isAdmin ? 'Pending Requests' : 'My Pending Requests'}</h2></div>
          {requestsLoading && <div className="table-scroll-area state-message">Loading requests...</div>}
          {!requestsLoading && requestsError && (
            <div className="table-scroll-area state-message">
              <p className="error">{requestsError}</p>
            </div>
          )}
          {!requestsLoading && !requestsError && (
            <div className="table-scroll-area">
              <table className="log-table">
                <thead>
                  <tr>
                    <th>Service</th>
                    <th>Requested By</th>
                    <th>Status</th>
                    <th>Description</th>
                    {isAdmin && <th>Actions</th>}
                  </tr>
                </thead>
                <tbody>
                  {pendingRequests.map((request) => (
                    <tr key={request.id}>
                      <td>{request.serviceName}</td>
                      <td>{request.requestedByEmail}</td>
                      <td><span className={getStatusBadgeClass(request.status)}>{normalizedRequestStatus(request.status)}</span></td>
                      <td>{request.description || '-'}</td>
                      {isAdmin && (
                        <td>
                          <div className="table-actions">
                            <button
                              className="btn"
                              disabled={submitting}
                              onClick={() => void approveRequest(request)}
                            >
                              Approve
                            </button>
                            <button
                              className="btn btn-danger"
                              disabled={submitting}
                              onClick={() => void rejectRequest(request)}
                            >
                              Reject
                            </button>
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                  {!pendingRequests.length && (
                    <tr>
                      <td colSpan={isAdmin ? 5 : 4}>No requests found</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="glass-panel table-container">
          <div className="table-header"><h2>{isAdmin ? 'Rejected Requests' : 'My Rejected Requests'}</h2></div>
          {requestsLoading && <div className="table-scroll-area state-message">Loading requests...</div>}
          {!requestsLoading && requestsError && (
            <div className="table-scroll-area state-message">
              <p className="error">{requestsError}</p>
            </div>
          )}
          {!requestsLoading && !requestsError && (
            <div className="table-scroll-area">
              <table className="log-table">
                <thead>
                  <tr>
                    <th>Service</th>
                    <th>Requested By</th>
                    <th>Status</th>
                    <th>Comment</th>
                  </tr>
                </thead>
                <tbody>
                  {rejectedRequests.map((request) => (
                    <tr key={request.id}>
                      <td>{request.serviceName}</td>
                      <td>{request.requestedByEmail}</td>
                      <td><span className={getStatusBadgeClass(request.status)}>{normalizedRequestStatus(request.status)}</span></td>
                      <td>{request.reviewComment || request.description || '-'}</td>
                    </tr>
                  ))}
                  {!rejectedRequests.length && (
                    <tr>
                      <td colSpan={4}>No requests found</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </section>

      <Modal
        open={Boolean(editService)}
        title="Edit Service"
        onClose={() => setEditService(null)}
      >
        <div className="admin-form-grid">
          <input
            className="form-control"
            placeholder="Service name"
            value={editForm.name}
            onChange={(e) => setEditForm((prev) => ({ ...prev, name: e.target.value }))}
          />
          <input
            className="form-control"
            placeholder="Description"
            value={editForm.description}
            onChange={(e) => setEditForm((prev) => ({ ...prev, description: e.target.value }))}
          />
          <button className="btn" disabled={submitting} onClick={() => void saveEdit()}>
            {submitting ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </Modal>
    </main>
  );
}
