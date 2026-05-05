-- Run this script in your Oracle 23ai Database as the VECTOR_USER

-- Drop table if it exists
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE GITHUB_VECTOR_STORE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN
            RAISE;
        END IF;
END;
/

-- Create a table that is compatible with BOTH LlamaIndex and LangChain4j
-- LlamaIndex uses: id, text, metadata, embedding
-- LangChain4j expects: ID, CONTENT, METADATA, VECTOR_DATA (by default, or configurable)
CREATE TABLE GITHUB_VECTOR_STORE (
    ID VARCHAR2(64) PRIMARY KEY,
    METADATA CLOB, -- Used CLOB instead of VARCHAR2(256) to accommodate rich LlamaIndex metadata
    CONTENT CLOB,
    VECTOR_DATA VECTOR(768, FLOAT32) -- 768 for Ollama nomic-embed-text
);

-- Note: We use METADATA CLOB instead of VARCHAR2(256) because LlamaIndex 
-- GitHub Reader extracts a lot of metadata (file paths, links, commit hashes, etc)
-- which will exceed 256 characters. LangChain4j can easily read CLOB.
