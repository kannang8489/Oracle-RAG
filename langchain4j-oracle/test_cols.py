import oracledb
conn = oracledb.connect(user='VECTOR_USER', password='Grants@123', dsn='localhost:1521/FREEPDB1')
cursor = conn.cursor()
cursor.execute("CREATE OR REPLACE VIEW GITHUB_VECTOR_STORE_V AS SELECT id, text as content, metadata, CAST(embedding AS VECTOR(*, FLOAT64)) as embedding FROM GITHUB_VECTOR_STORE")
conn.commit()
print("View created with CAST.")
