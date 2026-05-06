import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import './App.css';

const API = 'http://localhost:8080';

function AdminDashboard() {
  const navigate = useNavigate();

  // ── Document upload state ────────────────────────────────────────────────────
  const [selectedFiles, setSelectedFiles] = useState(null);
  const [uploadStatus, setUploadStatus] = useState('');
  const [isUploading, setIsUploading] = useState(false);
  const [indexedDocuments, setIndexedDocuments] = useState([]);
  const [loadingDocuments, setLoadingDocuments] = useState(true);
  const [docActions, setDocActions] = useState({});
  const [clearingAll, setClearingAll] = useState(false);
  const [clearAllStatus, setClearAllStatus] = useState('');
  const reEmbedInputRef = useRef(null);
  const [reEmbedTarget, setReEmbedTarget] = useState(null);

  // ── GitHub repos state ───────────────────────────────────────────────────────
  const [repos, setRepos] = useState([]);
  const [loadingRepos, setLoadingRepos] = useState(true);
  const [repoUrl, setRepoUrl] = useState('');
  const [repoBranch, setRepoBranch] = useState('main');
  const [repoStatus, setRepoStatus] = useState('');
  const [repoActions, setRepoActions] = useState({});

  useEffect(() => {
    if (!localStorage.getItem('adminLoggedIn')) navigate('/admin/login');
    fetchIndexedDocuments();
    fetchRepos();
  }, [navigate]);

  // ── Document helpers ─────────────────────────────────────────────────────────

  const fetchIndexedDocuments = async () => {
    setLoadingDocuments(true);
    try {
      const res = await fetch(`${API}/api/chat/documents`);
      const data = await res.json();
      if (res.ok && data.documents) setIndexedDocuments(data.documents);
    } catch (e) { console.error(e); }
    finally { setLoadingDocuments(false); }
  };

  const setDocAction = (name, state) =>
    setDocActions(prev => ({ ...prev, [name]: { ...prev[name], ...state } }));

  const handleClearAll = async () => {
    if (!window.confirm('Permanently delete ALL indexed documents and their embeddings?')) return;
    setClearingAll(true); setClearAllStatus('');
    try {
      const res = await fetch(`${API}/api/admin/documents/all`, { method: 'DELETE' });
      const data = await res.json();
      if (res.ok) {
        setIndexedDocuments([]); setDocActions({});
        setClearAllStatus({ text: '✓ All documents cleared.', isError: false });
      }
      else setClearAllStatus({ text: data.message || 'Failed.', isError: true });
    } catch { setClearAllStatus({ text: 'Server error.', isError: true }); }
    finally { setClearingAll(false); }
  };

  const handleRemove = async (docName) => {
    if (!window.confirm(`Remove "${docName}"?`)) return;
    setDocAction(docName, { loading: true, status: 'Removing...', isError: false });
    try {
      const res = await fetch(`${API}/api/admin/document?name=${encodeURIComponent(docName)}`, { method: 'DELETE' });
      const data = await res.json();
      if (res.ok) {
        setDocAction(docName, { loading: false, status: 'Removed ✓', isError: false });
        setTimeout(() => {
          setIndexedDocuments(prev => prev.filter(d => d.name !== docName));
          setDocActions(prev => { const n = { ...prev }; delete n[docName]; return n; });
        }, 1200);
      } else setDocAction(docName, { loading: false, status: data.message || 'Failed.', isError: true });
    } catch { setDocAction(docName, { loading: false, status: 'Server error.', isError: true }); }
  };

  const handleReEmbedClick = (docName) => {
    setReEmbedTarget(docName);
    reEmbedInputRef.current.value = '';
    reEmbedInputRef.current.click();
  };

  const handleReEmbedFileSelected = async (e) => {
    const file = e.target.files[0];
    if (!file || !reEmbedTarget) return;
    const docName = reEmbedTarget;
    setDocAction(docName, { loading: true, status: 'Re-embedding...', isError: false });
    const fd = new FormData(); fd.append('file', file);
    try {
      const res = await fetch(`${API}/api/admin/reembed`, { method: 'POST', body: fd });
      const data = await res.json();
      if (res.ok) {
        setDocAction(docName, { loading: false, status: 'Re-embedded ✓', isError: false });
        setTimeout(() => {
          fetchIndexedDocuments();
          setDocActions(prev => { const n = { ...prev }; delete n[docName]; return n; });
        }, 1500);
      } else setDocAction(docName, { loading: false, status: data.message || 'Failed.', isError: true });
    } catch { setDocAction(docName, { loading: false, status: 'Server error.', isError: true }); }
    finally { setReEmbedTarget(null); }
  };

  const handleUpload = async () => {
    if (!selectedFiles?.length) { setUploadStatus('Please select files first.'); return; }
    setIsUploading(true); setUploadStatus('Uploading and embedding...');
    const fd = new FormData();
    Array.from(selectedFiles).forEach(f => fd.append('files', f));
    try {
      const res = await fetch(`${API}/api/admin/upload`, { method: 'POST', body: fd });
      const data = await res.json();
      if (res.ok) {
        setUploadStatus(`✓ ${data.message}`); setSelectedFiles(null);
        document.getElementById('file-upload').value = ''; fetchIndexedDocuments();
      }
      else setUploadStatus(`Error: ${data.message || 'Upload failed.'}`);
    } catch { setUploadStatus('Failed to connect to server.'); }
    finally { setIsUploading(false); }
  };

  // ── GitHub repo helpers ──────────────────────────────────────────────────────

  const fetchRepos = async () => {
    setLoadingRepos(true);
    try {
      const res = await fetch(`${API}/api/admin/repos`);
      const data = await res.json();
      if (res.ok) setRepos(data);
    } catch (e) { console.error(e); }
    finally { setLoadingRepos(false); }
  };

  const setRepoAction = (id, state) =>
    setRepoActions(prev => ({ ...prev, [id]: { ...prev[id], ...state } }));

  const handleAddRepo = async () => {
    if (!repoUrl.trim()) { setRepoStatus('Please enter a repository URL.'); return; }
    setRepoStatus('Registering repository...');
    try {
      const res = await fetch(`${API}/api/admin/repos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repoUrl: repoUrl.trim(), branch: repoBranch.trim() || 'main' })
      });
      const data = await res.json();
      if (res.ok) {
        setRepoStatus(`✓ ${data.message}`);
        setRepoUrl(''); setRepoBranch('main');
        fetchRepos();
      } else setRepoStatus(`Error: ${data.message}`);
    } catch { setRepoStatus('Failed to connect to server.'); }
  };

  const handleRemoveRepo = async (repoId, repoName) => {
    if (!window.confirm(`Remove repository "${repoName}" and all its embeddings?`)) return;
    setRepoAction(repoId, { loading: true, status: 'Removing...' });
    try {
      const res = await fetch(`${API}/api/admin/repos/${repoId}`, { method: 'DELETE' });
      const data = await res.json();
      if (res.ok) {
        setRepoAction(repoId, { loading: false, status: 'Removed ✓' });
        setTimeout(() => {
          fetchRepos();
          setRepoActions(prev => { const n = { ...prev }; delete n[repoId]; return n; });
        }, 1200);
      } else setRepoAction(repoId, { loading: false, status: data.message || 'Failed.' });
    } catch { setRepoAction(repoId, { loading: false, status: 'Server error.' }); }
  };

  const handleReindexRepo = async (repoId, repoName) => {
    setRepoAction(repoId, { loading: true, status: 'Re-indexing...' });
    try {
      const res = await fetch(`${API}/api/admin/repos/${repoId}/reindex`, { method: 'POST' });
      const data = await res.json();
      if (res.ok) {
        setRepoAction(repoId, { loading: false, status: '✓ Re-indexing started' });
        setTimeout(() => {
          fetchRepos();
          setRepoActions(prev => { const n = { ...prev }; delete n[repoId]; return n; });
        }, 3000);
      } else setRepoAction(repoId, { loading: false, status: data.message || 'Failed.' });
    } catch { setRepoAction(repoId, { loading: false, status: 'Server error.' }); }
  };

  const statusBadge = (status) => {
    const colors = { INDEXED: '#10a37f', INDEXING: '#f59e0b', PENDING: '#6b7280', FAILED: '#ef4444' };
    return <span style={{ color: colors[status] || '#6b7280', fontWeight: 600, fontSize: '12px' }}>{status}</span>;
  };

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <div className="dashboard-container">
      <input ref={reEmbedInputRef} type="file" accept=".pdf,.txt,.csv,.json,.md"
        style={{ display: 'none' }} onChange={handleReEmbedFileSelected} />

      <div className="dashboard-header">
        <h1>Admin Dashboard</h1>
        <div>
          <button onClick={() => navigate('/')} className="back-btn" style={{ marginRight: '10px' }}>Go to Chat</button>
          <button onClick={() => { localStorage.removeItem('adminLoggedIn'); navigate('/admin/login'); }} className="logout-btn">Logout</button>
        </div>
      </div>

      <div className="dashboard-content">

        {/* ── Document Upload ─────────────────────────────────────────────────── */}
        <div className="upload-section">
          <h3>📄 Knowledge Base — Document Upload</h3>
          <p className="upload-desc">
            Upload PDFs or text files. Oracle 23ai will generate vector embeddings using
            its built-in ONNX model and store them in <code>ORACLE_VECTOR_STORE</code>.
          </p>
          <div className="file-input-wrapper">
            <input type="file" id="file-upload" multiple onChange={e => setSelectedFiles(e.target.files)}
              disabled={isUploading} accept=".pdf,.txt,.csv,.json,.md" />
          </div>
          {selectedFiles?.length > 0 && (
            <div className="selected-files-list">
              <h4>Selected:</h4>
              <ul>{Array.from(selectedFiles).map((f, i) =>
                <li key={i}>{f.name} ({(f.size / 1024).toFixed(1)} KB)</li>)}</ul>
            </div>
          )}
          <button className="upload-btn" onClick={handleUpload}
            disabled={isUploading || !selectedFiles?.length}>
            {isUploading ? 'Processing...' : 'Upload to Oracle Vector Store'}
          </button>
          {uploadStatus && (
            <div className={`upload-status ${uploadStatus.startsWith('Error') || uploadStatus.startsWith('Failed') ? 'error' : 'success'}`}>
              {uploadStatus}
            </div>
          )}
        </div>

        {/* ── Indexed Documents ───────────────────────────────────────────────── */}
        <div className="indexed-documents-section" style={{ marginTop: '40px' }}>
          <div className="indexed-docs-header">
            <div>
              <h3>📚 Indexed Documents</h3>
              <p className="doc-desc">Documents stored in <code>ORACLE_VECTOR_STORE</code>:</p>
            </div>
            {indexedDocuments.length > 0 && (
              <button className="clear-all-btn" onClick={handleClearAll} disabled={clearingAll}>
                {clearingAll ? '⏳ Clearing...' : '🗑 Clear All'}
              </button>
            )}
          </div>
          {clearAllStatus && (
            <div className={`upload-status ${clearAllStatus.isError ? 'error' : 'success'}`} style={{ marginBottom: '16px' }}>
              {clearAllStatus.text}
            </div>
          )}
          {loadingDocuments ? <div className="loading-docs">Loading...</div>
            : indexedDocuments.length > 0 ? (
              <div className="documents-list">
                {indexedDocuments.map((doc, i) => {
                  const action = docActions[doc.name] || {};
                  return (
                    <div key={i} className="document-item">
                      <div className="doc-icon">📄</div>
                      <div className="doc-details">
                        <div className="doc-name">{doc.name}</div>
                        <div className="doc-meta">
                          <span className="doc-chunks">Chunks: {doc.chunks}</span>
                          <span className="doc-status">Status: {doc.status}</span>
                        </div>
                        {action.status && (
                          <div className={`doc-action-status ${action.isError ? 'error' : 'success'}`}>{action.status}</div>
                        )}
                      </div>
                      <div className="doc-actions">
                        <button className="doc-action-btn reembed-btn"
                          onClick={() => handleReEmbedClick(doc.name)} disabled={action.loading}>
                          {action.loading && reEmbedTarget === doc.name ? '⏳' : '🔄 Re-embed'}
                        </button>
                        <button className="doc-action-btn remove-btn"
                          onClick={() => handleRemove(doc.name)} disabled={action.loading}>
                          {action.loading && reEmbedTarget !== doc.name ? '⏳' : '🗑 Remove'}
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : <div className="no-documents"><p>No documents indexed yet.</p></div>
          }
        </div>

        {/* ── GitHub Repositories ─────────────────────────────────────────────── */}
        <div className="indexed-documents-section" style={{ marginTop: '50px' }}>
          <h3>🐙 GitHub Repository Agent</h3>
          <p className="doc-desc">
            Register GitHub repositories to index their code into <code>GITHUB_VECTOR_STORE</code>.
            The agent clones the repo, chunks all source files, and embeds them using Oracle 23ai.
            Chat will automatically search both document and code stores.
          </p>

          {/* Add repo form */}
          <div className="upload-section" style={{ marginTop: '16px' }}>
            <h4>Add Repository</h4>
            <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', marginBottom: '10px' }}>
              <input
                type="text"
                placeholder="https://github.com/owner/repo"
                value={repoUrl}
                onChange={e => setRepoUrl(e.target.value)}
                style={{
                  flex: 2, minWidth: '280px', padding: '8px 12px', borderRadius: '6px',
                  border: '1px solid #4d4d4f', background: '#2a2b32', color: '#fff'
                }}
              />
              <input
                type="text"
                placeholder="Branch (default: main)"
                value={repoBranch}
                onChange={e => setRepoBranch(e.target.value)}
                style={{
                  flex: 1, minWidth: '140px', padding: '8px 12px', borderRadius: '6px',
                  border: '1px solid #4d4d4f', background: '#2a2b32', color: '#fff'
                }}
              />
              <button className="upload-btn" onClick={handleAddRepo}
                style={{ flex: 'none', padding: '8px 20px' }}>
                + Add Repo
              </button>
            </div>
            {repoStatus && (
              <div className={`upload-status ${repoStatus.startsWith('Error') ? 'error' : 'success'}`}>
                {repoStatus}
              </div>
            )}
          </div>

          {/* Repo list */}
          <div style={{ marginTop: '20px' }}>
            {loadingRepos ? <div className="loading-docs">Loading repositories...</div>
              : repos.length > 0 ? (
                <div className="documents-list">
                  {repos.map((repo, i) => {
                    const action = repoActions[repo.id] || {};
                    return (
                      <div key={i} className="document-item">
                        <div className="doc-icon">🐙</div>
                        <div className="doc-details">
                          <div className="doc-name">{repo.repoName}</div>
                          <div className="doc-meta">
                            <span className="doc-chunks">Chunks: {repo.chunkCount}</span>
                            <span className="doc-status" style={{ marginLeft: '12px' }}>
                              Status: {statusBadge(repo.status)}
                            </span>
                            <span style={{ marginLeft: '12px', fontSize: '11px', color: '#888' }}>
                              Branch: {repo.branch}
                            </span>
                          </div>
                          <div style={{ fontSize: '11px', color: '#666', marginTop: '2px' }}>
                            {repo.repoUrl}
                          </div>
                          {action.status && (
                            <div className="doc-action-status success">{action.status}</div>
                          )}
                        </div>
                        <div className="doc-actions">
                          <button className="doc-action-btn reembed-btn"
                            onClick={() => handleReindexRepo(repo.id, repo.repoName)}
                            disabled={action.loading || repo.status === 'INDEXING'}>
                            {action.loading ? '⏳' : '🔄 Re-index'}
                          </button>
                          <button className="doc-action-btn remove-btn"
                            onClick={() => handleRemoveRepo(repo.id, repo.repoName)}
                            disabled={action.loading}>
                            {action.loading ? '⏳' : '🗑 Remove'}
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="no-documents">
                  <p>No repositories registered yet. Add a GitHub repo URL above to start indexing code.</p>
                </div>
              )
            }
          </div>
        </div>

      </div>
    </div>
  );
}

export default AdminDashboard;
