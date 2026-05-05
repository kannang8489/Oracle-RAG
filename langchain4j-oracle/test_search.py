import oracledb
conn = oracledb.connect(user='VECTOR_USER', password='Grants@123', dsn='localhost:1521/FREEPDB1')
cursor = conn.cursor()
cursor.execute("SELECT text FROM GITHUB_VECTOR_STORE WHERE text LIKE '%Reservation%' or text LIKE '%reservation%'")
rows = cursor.fetchall()
for r in rows:
    print("---")
    print(r[0].read()[:500])

