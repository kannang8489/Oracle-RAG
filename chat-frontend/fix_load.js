const fs = require('fs');
const path = require('path');
const srcDir = 'd:/FHLB-DM/RAG-CHAT/Oracle-RAG/chat-frontend/src';

let chatJsx = fs.readFileSync(path.join(srcDir, 'Chat.jsx'), 'utf8');

// Fix the issue where older chat messages are not loading due to missing data structures
chatJsx = chatJsx.replace(
  "setChatHistory(data)",
  \if (Array.isArray(data)) {
        setChatHistory(data)
      } else if (data && typeof data === 'object') {
        // If data comes back wrapped in an object instead of a direct array
        setChatHistory(data.messages || data.history || [])
      }\
);

fs.writeFileSync(path.join(srcDir, 'Chat.jsx'), chatJsx);
console.log('Fixed loadSession array handling.');
