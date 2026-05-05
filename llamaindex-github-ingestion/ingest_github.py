import os
import oracledb
from dotenv import load_dotenv
from llama_index.core import VectorStoreIndex, StorageContext, Settings
from llama_index.readers.github import GithubRepositoryReader, GithubClient
from llama_index.vector_stores.oracledb import OraLlamaVS, DistanceStrategy
from llama_index.embeddings.ollama import OllamaEmbedding
from llama_index.core.node_parser import CodeSplitter, SentenceSplitter

# 1. Load Environment Variables
load_dotenv()

# Optional: Only needed if using OpenAI for something else
# OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")

# Oracle DB configuration
ORACLE_USER = os.getenv("ORACLE_USER", "VECTOR_USER")
ORACLE_PASSWORD = os.getenv("ORACLE_PASSWORD", "password")
ORACLE_DSN = os.getenv("ORACLE_DSN", "localhost:1521/FREEPDB1")

# Target Github Repository
GITHUB_OWNER = os.getenv("GITHUB_OWNER", "your-organization")
GITHUB_REPO = os.getenv("GITHUB_REPO", "your-repo")
GITHUB_BRANCH = os.getenv("GITHUB_BRANCH", "main")

def main():
    print(f"Connecting to Oracle Database at {ORACLE_DSN}...")
    # 2. Connect to Oracle Database
    connection = oracledb.connect(
        user=ORACLE_USER,
        password=ORACLE_PASSWORD,
        dsn=ORACLE_DSN
    )
    print("Connected successfully. Truncating existing GITHUB_VECTOR_STORE table...")
    with connection.cursor() as cursor:
        try:
            cursor.execute("TRUNCATE TABLE GITHUB_VECTOR_STORE")
        except Exception as e:
            print(f"Truncate failed (table might not exist yet): {e}")

    # 3. Setup LlamaIndex Oracle Vector Store
    # We map LlamaIndex defaults to the columns we created for LangChain4j compatibility
    vector_store = OraLlamaVS(
        _client=connection,
        table_name="GITHUB_VECTOR_STORE",
        distance_strategy=DistanceStrategy.COSINE
    )
    
    # Configure LlamaIndex to use the same Ollama embedding model as LangChain4j
    embed_model = OllamaEmbedding(
        model_name="nomic-embed-text",
        base_url=OLLAMA_BASE_URL
    )
    Settings.embed_model = embed_model
    
    storage_context = StorageContext.from_defaults(vector_store=vector_store)

    # 4. Initialize GitHub Client and Reader
    print(f"Fetching repository {GITHUB_OWNER}/{GITHUB_REPO} branch {GITHUB_BRANCH}...")
    github_client = GithubClient(github_token=GITHUB_TOKEN)
    
    # You can configure the reader to ignore specific files or folders
    reader = GithubRepositoryReader(
        github_client=github_client,
        owner=GITHUB_OWNER,
        repo=GITHUB_REPO,
        use_parser=False, # We will use advanced code splitters below
        verbose=True,
        filter_file_extensions=([".py", ".java", ".md", ".txt", ".js", ".ts", ".jsx", ".tsx", ".json", ".html", ".css"], GithubRepositoryReader.FilterType.INCLUDE)
    )
    
    documents = reader.load_data(branch=GITHUB_BRANCH)
    print(f"Loaded {len(documents)} documents from GitHub.")

    # 5. Advanced Chunking Strategy
    # Here we can configure specific parsers for code vs text, but for simplicity
    # we'll use a robust SentenceSplitter that works well for markdown and generic code.
    # For a purely code-heavy repo, consider CodeSplitter from llama_index.core.node_parser
    parser = SentenceSplitter(chunk_size=1024, chunk_overlap=100)
    nodes = parser.get_nodes_from_documents(documents)
    print(f"Split into {len(nodes)} chunks.")

    # 6. Generate Embeddings and Store in Oracle
    print("Generating embeddings and writing to Oracle Database...")
    index = VectorStoreIndex(
        nodes, 
        storage_context=storage_context,
        show_progress=True
    )
    
    print("Ingestion Complete. The data is now ready for LangChain4j to query!")
    
    # Close connection
    connection.close()

if __name__ == "__main__":
    main()
