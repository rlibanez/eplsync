package com.rlibanez.eplsync.ordering;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Registers the comparator once per physical pooled connection, including replacement connections. */
public final class TextCollationDataSource extends DelegatingDataSource implements AutoCloseable {
    private final Map<Connection,Boolean> initialized=Collections.synchronizedMap(new WeakHashMap<>());
    public TextCollationDataSource(DataSource source) {super(source);}
    private Connection initialize(Connection connection) throws SQLException {
        try {
            var physical=connection.unwrap(org.sqlite.SQLiteConnection.class);
            synchronized(initialized) {
                if(!initialized.containsKey(physical)) {TextOrdering.register(physical);initialized.put(physical,true);}
            }
            return connection;
        } catch(SQLException|RuntimeException ex) {connection.close();throw ex;}
    }
    @Override public Connection getConnection() throws SQLException {return initialize(super.getConnection());}
    @Override public Connection getConnection(String user,String password) throws SQLException {return initialize(super.getConnection(user,password));}
    @Override public void close() throws Exception {
        if(getTargetDataSource() instanceof AutoCloseable target) target.close();
    }
}
