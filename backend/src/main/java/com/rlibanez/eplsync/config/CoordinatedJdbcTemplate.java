package com.rlibanez.eplsync.config;

import java.util.Collection;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;

/** JDBC autocommit writes use the same turn as JPA transactions, without making
 * reads or remote work wait for that turn. Existing transactions keep their scope. */
final class CoordinatedJdbcTemplate extends JdbcTemplate {
    private final SqliteWriteCoordinator writer;
    CoordinatedJdbcTemplate(DataSource source,SqliteWriteCoordinator writer) {super(source);this.writer=writer;}
    private int changed(int count) {if(count!=0) WorkerActivity.changed();return count;}
    private int[] changed(int[] counts) {if(java.util.Arrays.stream(counts).anyMatch(n->n!=0)) WorkerActivity.changed();return counts;}
    private int[][] changed(int[][] counts) {for(var batch:counts) changed(batch);return counts;}
    @Override public void execute(String sql) {writer.write(()->{super.execute(sql);WorkerActivity.changed();return null;});}
    @Override public int update(String sql) {return writer.write(()->changed(super.update(sql)));}
    @Override protected int update(PreparedStatementCreator creator,@Nullable PreparedStatementSetter setter) {return writer.write(()->changed(super.update(creator,setter)));}
    @Override public int update(PreparedStatementCreator creator,KeyHolder holder) {return writer.write(()->changed(super.update(creator,holder)));}
    @Override public int[] batchUpdate(String... sql) {return writer.write(()->changed(super.batchUpdate(sql)));}
    @Override public int[] batchUpdate(String sql,BatchPreparedStatementSetter setter) {return writer.write(()->changed(super.batchUpdate(sql,setter)));}
    @Override public int[] batchUpdate(PreparedStatementCreator creator,BatchPreparedStatementSetter setter,@Nullable KeyHolder holder) {return writer.write(()->changed(super.batchUpdate(creator,setter,holder)));}
    @Override public <T> int[][] batchUpdate(String sql,Collection<T> values,int size,ParameterizedPreparedStatementSetter<T> setter) {return writer.write(()->changed(super.batchUpdate(sql,values,size,setter)));}
}
