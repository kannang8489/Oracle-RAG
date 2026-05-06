package com.oracle.dev.jdbc.langchain4j;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.Properties;

import io.github.cdimascio.dotenv.Dotenv;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

/**
 * Oracle 23ai Autonomous Database connection utility.
 *
 * Uses JKS-based mTLS (truststore.jks + keystore.jks from the wallet folder).
 * This is the most reliable approach for Java 21 without registering the
 * Oracle PKI provider as a JVM security provider.
 *
 * Required .env variables:
 *   DB_TNS_ALIAS       — TNS alias from tnsnames.ora  (e.g. rms_medium)
 *   DB_WALLET_DIR      — Path to extracted wallet folder (forward slashes)
 *   DB_WALLET_PASSWORD — Password set when downloading the wallet from OCI
 *   DB_23AI_USERNAME   — Database username (e.g. ADMIN)
 *   DB_23AI_PASSWORD   — Database password
 */
public class OracleDBUtils {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    private static final String TNS_ALIAS       = dotenv.get("DB_TNS_ALIAS");
    private static final String WALLET_DIR      = dotenv.get("DB_WALLET_DIR");
    private static final String WALLET_PASSWORD = dotenv.get("DB_WALLET_PASSWORD");
    private static final String USERNAME        = dotenv.get("DB_23AI_USERNAME");
    private static final String PASSWORD        = dotenv.get("DB_23AI_PASSWORD");

    // JDBC URL: TNS alias resolved via TNS_ADMIN in the connection properties
    private static final String URL =
        "jdbc:oracle:thin:@" + TNS_ALIAS + "?TNS_ADMIN=" + WALLET_DIR;

    private static PoolDataSource poolDataSource;

    public static synchronized DataSource getPooledDataSource() throws SQLException {
        if (poolDataSource == null) {
            poolDataSource = PoolDataSourceFactory.getPoolDataSource();
            poolDataSource.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
            poolDataSource.setURL(URL);
            poolDataSource.setUser(USERNAME);
            poolDataSource.setPassword(PASSWORD);

            Properties prop = new Properties();
            // Required for Oracle 23ai VECTOR columns to be returned as String
            prop.setProperty("oracle.jdbc.vectorDefaultGetObjectType", "String");
            // JKS-based mTLS — uses truststore.jks and keystore.jks from wallet
            prop.setProperty("javax.net.ssl.trustStore",         WALLET_DIR + "/truststore.jks");
            prop.setProperty("javax.net.ssl.trustStorePassword", WALLET_PASSWORD);
            prop.setProperty("javax.net.ssl.trustStoreType",     "JKS");
            prop.setProperty("javax.net.ssl.keyStore",           WALLET_DIR + "/keystore.jks");
            prop.setProperty("javax.net.ssl.keyStorePassword",   WALLET_PASSWORD);
            prop.setProperty("javax.net.ssl.keyStoreType",       "JKS");
            // Enforce server DN matching (required by ADB)
            prop.setProperty("oracle.net.ssl_server_dn_match", "true");

            poolDataSource.setConnectionProperties(prop);
            poolDataSource.setInitialPoolSize(2);
            poolDataSource.setMaxPoolSize(20);
        }
        return poolDataSource;
    }

    public static Connection getConnectionFromPooledDataSource() throws SQLException {
        return getPooledDataSource().getConnection();
    }
}
