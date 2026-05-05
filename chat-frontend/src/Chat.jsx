import { useState, useRef, useEffect } from 'react'
import './App.css'

function Chat() {
  const [question, setQuestion] = useState('')
  const [chatHistory, setChatHistory] = useState([])
  const [isLoading, setIsLoading] = useState(false)
  const [isSidebarOpen, setIsSidebarOpen] = useState(true)
  const [sessions, setSessions] = useState([])
  const [currentSessionId, setCurrentSessionId] = useState(null)
  const chatEndRef = useRef(null)

  const scrollToBottom = () => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }

  useEffect(() => {
    scrollToBottom()
  }, [chatHistory])

  useEffect(() => {
    fetchSessions()
  }, [])

  const fetchSessions = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/chat/sessions')
      const data = await res.json()
      setSessions(data)
    } catch(e) {
      console.error('Error fetching sessions:', e)
    }
  }

  const loadSession = async (sessionId) => {
    setCurrentSessionId(sessionId)
    setChatHistory([])
    try {
      const res = await fetch(`http://localhost:8080/api/chat/session/${sessionId}`)
      const data = await res.json()
      setChatHistory(data)
    } catch(e) {
      console.error('Error loading session:', e)
    }
  }

  const startNewChat = () => {
    setCurrentSessionId(null)
    setChatHistory([])
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!question.trim()) return

    const currentQuestion = question.trim()
    setQuestion('')
    
    setChatHistory(prev => [...prev, { role: 'user', content: currentQuestion }])
    setIsLoading(true)

    let sessionIdToUse = currentSessionId;
    
    // Create new session on first message if none exists
    if (!sessionIdToUse) {
      try {
        const title = currentQuestion.length > 25 ? currentQuestion.substring(0, 25) + '...' : currentQuestion;
        const res = await fetch('http://localhost:8080/api/chat/session', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title: title })
        })
        const data = await res.json()
        sessionIdToUse = data.sessionId;
        setCurrentSessionId(sessionIdToUse);
        
        // Optimistically add to sidebar to avoid waiting for fetch
        setSessions(prev => [{ sessionId: sessionIdToUse, title: title }, ...prev]);
      } catch (e) {
        console.error('Error creating session:', e)
      }
    }

    try {
      const response = await fetch('http://localhost:8080/api/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ question: currentQuestion, sessionId: sessionIdToUse }),
      })

      const data = await response.json()

      if (data.error) {
        setChatHistory(prev => [...prev, { role: 'bot', content: 'Error: ' + data.error, isError: true }])
      } else {
        setChatHistory(prev => [...prev, { role: 'bot', content: data.answer, sources: data.sources }])
      }
    } catch (error) {
      console.error('Error fetching response:', error)
      setChatHistory(prev => [...prev, { role: 'bot', content: 'Failed to connect to the server.', isError: true }])
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="app-layout">
      <aside className={`sidebar ${isSidebarOpen ? 'open' : 'closed'}`}>
        <div className="sidebar-header">
          <button className="new-chat-btn" onClick={startNewChat}>
            <svg stroke="currentColor" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="1em" width="1em" xmlns="http://www.w3.org/2000/svg"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
            New Chat
          </button>
          <button className="close-sidebar-btn" onClick={() => setIsSidebarOpen(false)}>
            <svg stroke="currentColor" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="1em" width="1em" xmlns="http://www.w3.org/2000/svg"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="9" y1="3" x2="9" y2="21"></line></svg>
          </button>
        </div>
        <div className="sidebar-history">
          <p className="history-label">Previous Chats</p>
          {sessions.length === 0 && <div className="history-item" style={{color: '#666', pointerEvents: 'none'}}>No previous chats</div>}
          {sessions.map((s, idx) => (
             <div key={idx} className={`history-item ${currentSessionId === s.sessionId ? 'active' : ''}`} onClick={() => loadSession(s.sessionId)}>
               {s.title}
             </div>
          ))}
        </div>
        <div className="sidebar-footer">
          <a href="/admin/login" className="admin-link">⚙️ Admin Login</a>
        </div>
      </aside>

      <main className="main-content">
        <header className="chat-top-header">
          {!isSidebarOpen && (
            <button className="open-sidebar-btn" onClick={() => setIsSidebarOpen(true)}>
              <svg stroke="currentColor" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="1em" width="1em" xmlns="http://www.w3.org/2000/svg"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="9" y1="3" x2="9" y2="21"></line></svg>
            </button>
          )}
          <h2>Oracle RAG Chat</h2>
        </header>
        
        <div className="chat-box">
          {chatHistory.length === 0 ? (
            <div className="empty-state">
              <div className="empty-logo">
                <svg stroke="currentColor" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="2em" width="2em" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="10"></circle><path d="M12 16v-4"></path><path d="M12 8h.01"></path></svg>
              </div>
              <h2>How can I help you today?</h2>
            </div>
          ) : (
            <div className="messages-container">
              {chatHistory.map((msg, index) => (
                <div key={index} className={`message-wrapper ${msg.role}`}>
                  <div className={`message-avatar ${msg.role}`}>
                    {msg.role === 'user' ? 'U' : 'AI'}
                  </div>
                  <div className={`message ${msg.role} ${msg.isError ? 'error' : ''}`}>
                    <div dangerouslySetInnerHTML={{ __html: msg.content.replace(/\n/g, '<br/>') }} />
                    {msg.sources && msg.sources.length > 0 && (
                      <div className="message-sources" style={{marginTop: '15px', paddingTop: '15px', borderTop: '1px solid rgba(255,255,255,0.1)', fontSize: '12px'}}>
                        <strong style={{display: 'block', marginBottom: '8px', color: '#a3a3a3'}}>Sources:</strong>
                        <div style={{display: 'flex', flexWrap: 'wrap', gap: '8px'}}>
                          {msg.sources.map((src, i) => (
                            <span key={i} style={{display: 'inline-flex', alignItems: 'center', gap: '4px', backgroundColor: '#2a2b32', border: '1px solid #4d4d4f', padding: '4px 10px', borderRadius: '12px', color: '#d1d5db', fontSize: '11px'}}>
                              <svg stroke="#10a37f" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="1em" width="1em" xmlns="http://www.w3.org/2000/svg"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                              {src}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              ))}
              {isLoading && (
                <div className="message-wrapper bot">
                  <div className="message-avatar bot">AI</div>
                  <div className="message bot loading">Thinking...</div>
                </div>
              )}
              <div ref={chatEndRef} />
            </div>
          )}
        </div>

        <div className="chat-input-container">
          <form className="chat-input-area" onSubmit={handleSubmit}>
            <input
              type="text"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="Message Oracle RAG Chat..."
              disabled={isLoading}
            />
            <button type="submit" disabled={isLoading || !question.trim()}>
              <svg stroke="currentColor" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="1em" width="1em" xmlns="http://www.w3.org/2000/svg"><line x1="12" y1="19" x2="12" y2="5"></line><polyline points="5 12 12 5 19 12"></polyline></svg>
            </button>
          </form>
          <p className="disclaimer">AI can make mistakes. Check important info.</p>
        </div>
      </main>
    </div>
  )
}

export default Chat
