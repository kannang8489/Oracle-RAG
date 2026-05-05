const fs = require('fs');
const path = require('path');
const srcDir = 'd:/FHLB-DM/RAG-CHAT/Oracle-RAG/chat-frontend/src';

const dashboardJsx = import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import './App.css';

function AdminDashboard() {
  const navigate = useNavigate();
  const [selectedFiles, setSelectedFiles] = useState(null);
  const [uploadStatus, setUploadStatus] = useState('');
  const [isUploading, setIsUploading] = useState(false);

  useEffect(() => {
    const isLoggedIn = localStorage.getItem('adminLoggedIn');
    if (!isLoggedIn) {
      navigate('/admin/login');
    }
  }, [navigate]);

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
        setUploadStatus(\Success: \\);
        setSelectedFiles(null);
        // Clear the file input
        document.getElementById('file-upload').value = '';
      } else {
        setUploadStatus(\Error: \\);
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
      <div className="dashboard-header">
        <h1>Welcome Admin!</h1>
        <div>
          <button onClick={() => navigate('/')} className="back-btn" style={{marginRight: '10px'}}>Go to Chat</button>
          <button onClick={handleLogout} className="logout-btn">Logout</button>
        </div>
      </div>
      <div className="dashboard-content">
        <p>You have successfully authenticated via Spring Boot.</p>
        <p>This is your protected admin area to manage the RAG knowledge base.</p>
        
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
            <div className={\upload-status \\}>
              {uploadStatus}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
export default AdminDashboard;
;

const cssAppend = \
.upload-section {
  margin-top: 40px;
  background-color: var(--code-bg);
  padding: 25px;
  border-radius: 8px;
  border: 1px solid var(--border);
}

.upload-section h3 {
  margin-top: 0;
  color: var(--text-h);
}

.upload-desc {
  font-size: 14px;
  margin-bottom: 20px;
}

.file-input-wrapper {
  margin-bottom: 20px;
}

.file-input-wrapper input {
  width: 100%;
  padding: 10px;
  background-color: var(--bg);
  border: 1px dashed var(--border);
  border-radius: 4px;
  color: var(--text-h);
}

.selected-files-list {
  background-color: rgba(0,0,0,0.1);
  padding: 15px;
  border-radius: 4px;
  margin-bottom: 20px;
}

.selected-files-list h4 {
  margin: 0 0 10px 0;
  font-size: 14px;
}

.selected-files-list ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
}

.upload-btn {
  background-color: #10a37f;
  color: white;
  border: none;
  padding: 12px 24px;
  border-radius: 6px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  transition: opacity 0.2s;
  width: 100%;
}

.upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.upload-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.upload-status {
  margin-top: 15px;
  padding: 10px;
  border-radius: 4px;
  font-size: 14px;
  text-align: center;
}

.upload-status.success {
  background-color: #e8f5e9;
  color: #2e7d32;
  border: 1px solid #a5d6a7;
}

.upload-status.error {
  background-color: #ffebee;
  color: #c62828;
  border: 1px solid #ef9a9a;
}
\;

fs.writeFileSync(path.join(srcDir, 'AdminDashboard.jsx'), dashboardJsx);
fs.appendFileSync(path.join(srcDir, 'App.css'), cssAppend);

console.log('Successfully updated Admin Dashboard with File Upload functionality.');
