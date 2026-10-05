package com.rlibanez.eplsync.torrent.bulk;

import java.nio.file.*;
import java.sql.*;
import org.springframework.http.HttpStatus;
import com.rlibanez.eplsync.exception.TorrentOperationException;

/** Private disk index: deduplicating a whole selection must not grow a Java HashSet. */
final class SelectionHashIndex implements AutoCloseable {
    private Path path;
    private Connection connection;
    private PreparedStatement insert;
    SelectionHashIndex() {
        try {
            path=Files.createTempFile("eplsync-selection-", ".sqlite");
            connection=DriverManager.getConnection("jdbc:sqlite:"+path);
            try(var statement=connection.createStatement()) {
                statement.execute("PRAGMA page_size=4096"); statement.execute("PRAGMA max_page_count=32768");
                statement.execute("PRAGMA cache_size=-2048"); statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("CREATE TABLE hashes(hash TEXT PRIMARY KEY) WITHOUT ROWID");
            }
            insert=connection.prepareStatement("INSERT OR IGNORE INTO hashes VALUES(?)");
        } catch(Exception ex) { close(); throw failure(ex); }
    }
    boolean add(String hash) {
        try { insert.setString(1,hash); return insert.executeUpdate()==1; }
        catch(SQLException ex) { throw failure(ex); }
    }
    private TorrentOperationException failure(Exception ex) {
        return new TorrentOperationException(ex instanceof SQLException sql && sql.getErrorCode()==13 ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.INSUFFICIENT_STORAGE,"No se pudo preparar el índice temporal de la selección (máximo 128 MiB). Reduce los filtros o comprueba el espacio disponible.");
    }
    @Override public void close() {
        try { if(insert!=null) insert.close(); } catch(SQLException ignored) {}
        try { if(connection!=null) connection.close(); } catch(SQLException ignored) {}
        try { if(path!=null) Files.deleteIfExists(path); }
        catch(java.io.IOException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("No se pudo limpiar el índice temporal de selección",ex); }
    }
}
