import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import './App.css';

function AdminDashboard() {
  const navigate = useNavigate();
  const [selectedFiles, setSelectedFiles] = useState(null);
  const [uploadStatus, setUploadStatus] = useState('');
  const [isUploading, setIsUploading] = useState(false);
  const [indexedDocuments, setIndexedDocuments] = useState([]);
  const [loadingDocuments, setLoadingDocuments] = useState(true);
  // Per-document action state: { [docName]: { loading: bool, status: string, isError: bool } }
  const [docActions, setDocActions] = useState({});
  const [clearingAll, setClearingAll] = useState(false);
  const [clearAllStatus, setClearAllStatus] = useState('');
  // Hidden file input ref for re-embed
  const reEmbedInputRef = useRef(null);
  const [reEmbedTarget, setReEmbedTarget] = useState(null);

  useEffect(() => {
    const isLoggedIn = localStorage.getItem('adminLoggedIn');
    if (!isLoggedIn) {
      navigate('/admin/login');
    }
    fetchIndexedDocuments();
  }, [navigate]);

  const fetchIndexedDocuments = async () => {
    setLoadingDocuments(true);
    try {
      const response = await fetch('http://localhost:8080/api/chat/documents');
      const data = await response.json();
      if (response.ok && data.documents) {
        setIndexedDocuments(data.documents);
      }
    } catch (error) {
      console.error('Error fetching documents:', error);
    } finally {
      setLoadingDocuments(false);
    }
  };

  const setDocAction = (docName, state) => {
    setDocActions(prev => ({ ...prev, [docName]: { ...prev[docName], ...state } }));
  };

  // ── Clear all documents ──────────────────────────────────────────────────────
  const handleClearAll = async () => {
    if (!window.confirm('This will permanently delete ALL indexed documents and their embeddings from the knowledge base. Are you sure?')) return;

    setClearingAll(true);
    setClearAllStatus('');
    try {
      const response = await fetch('http://localhost:8080/api/admin/documents/all', {
        method: 'DELETE',
      });
      const data = await response.json();
      if (response.ok) {
        setIndexedDocuments([]);
        setDocActions({});
        setClearAllStatus({ text: '✓ All documents cleared successfully.', isError: false });
      } else {
        setClearAllStatus({ text: data.message || 'Failed to clear documents.', isError: true });
      }
    } catch (err) {
      setClearAllStatus({ text: 'Server error while clearing documents.', isError: true });
    } finally {
      setClearingAll(false);
    }
  };

  // ── Remove document ──────────────────────────────────────────────────────────
  const handleRemove = async (docName) => {
    if (!window.confirm(`Remove "${docName}" from the knowledge base? This cannot be undone.`)) return;

    setDocAction(docName, { loading: true, status: 'Removing...', isError: false });
    try {
      const response = await fetch(
        `http://localhost:8080/api/admin/document?name=${encodeURIComponent(docName)}`,
        { method: 'DELETE' }
      );
      const data = await response.json();
      if (response.ok) {
        setDocAction(docName, { loading: false, status: 'Removed ✓', isError: false });
        // Remove from list after a short delay so user sees the confirmation
        setTimeout(() => {
          setIndexedDocuments(prev => prev.filter(d => d.name !== docName));
          setDocActions(prev => { const n = { ...prev }; delete n[docName]; return n; });
        }, 1200);
      } else {
        setDocAction(docName, { loading: false, status: data.message || 'Remove failed.', isError: true });
      }
    } catch (err) {
      setDocAction(docName, { loading: false, status: 'Server error during remove.', isError: true });
    }
  };

  // ── Re-embed: open file picker bound to the target document ─────────────────
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

    const formData = new FormData();
    formData.append('file', file);

    try {
      const response = await fetch('http://localhost:8080/api/admin/reembed', {
        method: 'POST',
        body: formData,
      });
      const data = await response.json();
      if (response.ok) {
        setDocAction(docName, { loading: false, status: 'Re-embedded ✓', isError: false });
        // Refresh list to show updated chunk count
        setTimeout(() => {
          fetchIndexedDocuments();
          setDocActions(prev => { const n = { ...prev }; delete n[docName]; return n; });
        }, 1500);
      } else {
        setDocAction(docName, { loading: false, status: data.message || 'Re-embed failed.', isError: true });
      }
    } catch (err) {
      setDocAction(docName, { loading: false, status: 'Server error during re-embed.', isError: true });
    } finally {
      setReEmbedTarget(null);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('adminLoggedIn');
    navigate('/admin/login');
  };

  const handleFileChange = (e) => {
    setSelectedFiles(e.target.files);
  };

  const handleUpload = async () => {
    if (!selectedFiles || selectedFiles.length === 0) {
      setUploadStatus('Please select files to upload first.');
      return;
    }

    setIsUploading(true);
    setUploadStatus('Uploading and converting files to embeddings...');

    const formData = new FormData();
    for (let i = 0; i < selectedFiles.length; i++) {
      formData.append('files', selectedFiles[i]);
    }

    try {
      const response = await fetch('http://localhost:8080/api/admin/upload', {
        method: 'POST',
        body: formData,
      });

      const data = await response.json();

      if (response.ok) {
        setUploadStatus(`Success: ${data.message}`);
        setSelectedFiles(null);
        document.getElementById('file-upload').value = '';
        fetchIndexedDocuments();
      } else {
        setUploadStatus(`Error: ${data.message || 'Failed to upload files.'}`);
      }
    } catch (error) {
      console.error('Upload error:', error);
      setUploadStatus('Failed to connect to the server for upload.');
    } finally {
      setIsUploading(false);
    }
  };

  return (
    <div className="dashboard-container">
      {/* Hidden file input for re-embed */}
      <input
        ref={reEmbedInputRef}
        type="file"
        accept=".pdf,.txt,.csv,.json,.md"
        style={{ display: 'none' }}
        onChange={handleReEmbedFileSelected}
      />

      <div className="dashboard-header">
        <h1>Welcome Admin!</h1>
        <div>
          <button onClick={() => navigate('/')} className="back-btn" style={{ marginRight: '10px' }}>Go to Chat</button>
          <button onClick={handleLogout} className="logout-btn">Logout</button>
        </div>
      </div>

      <div className="dashboard-content">
        <p>You have successfully authenticated via Spring Boot using hardcoded environment variables.</p>
        <p>This is your protected admin area.</p>

        <div className="upload-section">
          <h3>Knowledge Base File Upload</h3>
          <p className="upload-desc">Upload PDFs or Text files here to automatically convert them into vector embeddings and store them in the Oracle Database. They will immediately become available in the chat.</p>

          <div className="file-input-wrapper">
            <input
              type="file"
              id="file-upload"
              multiple
              onChange={handleFileChange}
              disabled={isUploading}
              accept=".pdf,.txt,.csv,.json,.md"
            />
          </div>

          {selectedFiles && selectedFiles.length > 0 && (
            <div className="selected-files-list">
              <h4>Selected Files:</h4>
              <ul>
                {Array.from(selectedFiles).map((file, index) => (
                  <li key={index}>{file.name} ({(file.size / 1024).toFixed(1)} KB)</li>
                ))}
              </ul>
            </div>
          )}

          <button
            className="upload-btn"
            onClick={handleUpload}
            disabled={isUploading || !selectedFiles || selectedFiles.length === 0}
          >
            {isUploading ? 'Processing...' : 'Upload to Oracle Vector Store'}
          </button>

          {uploadStatus && (
            <div className={`upload-status ${uploadStatus.includes('Error') || uploadStatus.includes('Failed') ? 'error' : 'success'}`}>
              {uploadStatus}
            </div>
          )}
        </div>

        <div className="indexed-documents-section" style={{ marginTop: '40px' }}>
          <div className="indexed-docs-header">
            <div>
              <h3>📚 Indexed Documents</h3>
              <p className="doc-desc">Documents currently available in the knowledge base:</p>
            </div>
            {indexedDocuments.length > 0 && (
              <button
                className="clear-all-btn"
                onClick={handleClearAll}
                disabled={clearingAll}
                title="Permanently delete all documents and their embeddings"
              >
                {clearingAll ? '⏳ Clearing...' : '🗑 Clear All'}
              </button>
            )}
          </div>

          {clearAllStatus && (
            <div className={`upload-status ${clearAllStatus.isError ? 'error' : 'success'}`} style={{ marginBottom: '16px' }}>
              {clearAllStatus.text}
            </div>
          )}

          {loadingDocuments ? (
            <div className="loading-docs">Loading documents...</div>
          ) : indexedDocuments.length > 0 ? (
            <div className="documents-list">
              {indexedDocuments.map((doc, index) => {
                const action = docActions[doc.name] || {};
                return (
                  <div key={index} className="document-item">
                    <div className="doc-icon">📄</div>
                    <div className="doc-details">
                      <div className="doc-name">{doc.name}</div>
                      <div className="doc-meta">
                        <span className="doc-chunks">Chunks: {doc.chunks}</span>
                        <span className="doc-status">Status: {doc.status}</span>
                      </div>
                      {action.status && (
                        <div className={`doc-action-status ${action.isError ? 'error' : 'success'}`}>
                          {action.status}
                        </div>
                      )}
                    </div>
                    <div className="doc-actions">
                      <button
                        className="doc-action-btn reembed-btn"
                        onClick={() => handleReEmbedClick(doc.name)}
                        disabled={action.loading}
                        title="Upload a new version of this file to replace its embeddings"
                      >
                        {action.loading && reEmbedTarget === doc.name ? '⏳ Re-embedding...' : '🔄 Re-embed'}
                      </button>
                      <button
                        className="doc-action-btn remove-btn"
                        onClick={() => handleRemove(doc.name)}
                        disabled={action.loading}
                        title="Remove this document and all its chunks from the knowledge base"
                      >
                        {action.loading && reEmbedTarget !== doc.name ? '⏳ Removing...' : '🗑 Remove'}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="no-documents">
              <p>No documents indexed yet. Upload your first document above!</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default AdminDashboard;
