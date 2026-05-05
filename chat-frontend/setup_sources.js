const fs = require('fs');
const path = require('path');
const srcDir = 'd:/FHLB-DM/RAG-CHAT/Oracle-RAG/chat-frontend/src';

let chatJsx = fs.readFileSync(path.join(srcDir, 'Chat.jsx'), 'utf8');

// Add sources rendering right below the main answer
const newContentHtml = \
                  <div className={\\\message \\\ \\\\\\}>
                    <div dangerouslySetInnerHTML={{ __html: msg.content.replace(/\\n/g, '<br/>') }} />
                    {msg.sources && msg.sources.length > 0 && (
                      <div className="message-sources">
                        <strong>Sources:</strong>
                        <div className="source-badges">
                          {msg.sources.map((src, i) => (
                            <span key={i} className="source-badge">
                              <svg stroke="currentColor" fill="none" strokeWidth="2" viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round" height="1em" width="1em" xmlns="http://www.w3.org/2000/svg"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                              {src}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
\;

// Replace the old message rendering with the new one that includes sources
chatJsx = chatJsx.replace(
  "{msg.content}",
  newContentHtml
);

// Update how state is set to include sources
chatJsx = chatJsx.replace(
  "setChatHistory(prev => [...prev, { role: 'bot', content: data.answer }])",
  "setChatHistory(prev => [...prev, { role: 'bot', content: data.answer, sources: data.sources }])"
);

fs.writeFileSync(path.join(srcDir, 'Chat.jsx'), chatJsx);
console.log('Chat.jsx updated with sources UI.');

const appCssPath = path.join(srcDir, 'App.css');
let css = fs.readFileSync(appCssPath, 'utf8');
css += \
.message-sources {
  margin-top: 15px;
  padding-top: 15px;
  border-top: 1px solid rgba(255,255,255,0.1);
  font-size: 12px;
}
.message-sources strong {
  display: block;
  margin-bottom: 8px;
  color: #a3a3a3;
}
.source-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.source-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background-color: #2a2b32;
  border: 1px solid #4d4d4f;
  padding: 4px 10px;
  border-radius: 12px;
  color: #d1d5db;
  font-size: 11px;
}
.source-badge svg {
  color: #10a37f;
}
\;
fs.writeFileSync(appCssPath, css);
console.log('App.css updated for sources UI.');
