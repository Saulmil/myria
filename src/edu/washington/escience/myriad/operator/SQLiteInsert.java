package edu.washington.escience.myriad.operator;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteJob;
import com.almworks.sqlite4java.SQLiteQueue;
import com.almworks.sqlite4java.SQLiteStatement;
import com.google.common.collect.ImmutableMap;

import edu.washington.escience.myriad.DbException;
import edu.washington.escience.myriad.RelationKey;
import edu.washington.escience.myriad.TupleBatch;
import edu.washington.escience.myriad.util.SQLiteUtils;

/**
 * An operator that inserts its tuples into a relation stored in a SQLite Database.
 * 
 * @author dhalperi
 * 
 */
public final class SQLiteInsert extends RootOperator {

  /** Required for Java serialization. */
  private static final long serialVersionUID = 1L;
  /** The SQLite Database that they will be inserted into. */
  private String pathToSQLiteDb;
  /** The name of the table the tuples should be inserted into. */
  private final RelationKey relationKey;
  /** Whether to overwrite an existing table or not. */
  private final boolean overwriteTable;
  /** SQLite queue confines all SQLite operations to the same thread. */
  private SQLiteQueue queue;
  /** The statement used to insert tuples into the database. */
  private String insertString;

  /**
   * Constructs an insertion operator to store the tuples from the specified child in a SQLite database in the specified
   * file. If the table does not exist, it will be created; if it does exist then old data will persist and new data
   * will be inserted.
   * 
   * @param child the source of tuples to be inserted.
   * @param relationKey the key of the table the tuples should be inserted into.
   */
  public SQLiteInsert(final Operator child, final RelationKey relationKey) {
    super(child);
    Objects.requireNonNull(relationKey);
    this.relationKey = relationKey;
    overwriteTable = false;
  }

  /**
   * Constructs an insertion operator to store the tuples from the specified child in a SQLite database in the specified
   * file. If the table does not exist, it will be created. If the table exists and overwriteTable is true, the existing
   * data will be dropped from the database before the new data is inserted. If overwriteTable is false, any existing
   * data will remain and new data will be appended.
   * 
   * @param child the source of tuples to be inserted.
   * @param relationKey the key of the table the tuples should be inserted into.
   * @param overwriteTable whether to overwrite a table that already exists.
   */
  public SQLiteInsert(final Operator child, final RelationKey relationKey, final boolean overwriteTable) {
    super(child);
    Objects.requireNonNull(relationKey);
    this.relationKey = relationKey;
    this.overwriteTable = overwriteTable;
  }

  @Override
  public void cleanup() {
    try {
      queue.stop(true).join();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  protected void consumeTuples(final TupleBatch tupleBatch) throws DbException {
    final SQLiteJob<Object> future = new SQLiteJob<Object>() {
      @Override
      protected Object job(final SQLiteConnection sqliteConnection) throws SQLiteException {
        /* BEGIN TRANSACTION */
        sqliteConnection.exec("BEGIN TRANSACTION");

        SQLiteStatement insertStatement = sqliteConnection.prepare(insertString);

        tupleBatch.getIntoSQLite(insertStatement);

        /* COMMIT TRANSACTION */
        sqliteConnection.exec("COMMIT TRANSACTION");

        insertStatement.dispose();
        insertStatement = null;

        return null;
      }
    };
    queue.execute(future);
    try {
      future.get();
      queue.flush();
    } catch (final InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (final ExecutionException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void init(final ImmutableMap<String, Object> execEnvVars) throws DbException {
    final String sqliteDatabaseFilename = (String) execEnvVars.get("sqliteFile");
    if (sqliteDatabaseFilename == null) {
      throw new DbException("Unable to instantiate SQLiteQueryScan on non-sqlite worker");
    }
    pathToSQLiteDb = sqliteDatabaseFilename;

    final File dbFile = new File(pathToSQLiteDb);

    /* Open a connection to that SQLite database. */
    queue = new SQLiteQueue(dbFile);
    queue.start();

    /* Overwrite and/or create the new table */
    try {
      queue.execute(new SQLiteJob<Integer>() {
        @Override
        protected Integer job(final SQLiteConnection connection) throws SQLiteException {
          /* If we should overwrite, drop the existing table. */
          if (overwriteTable) {
            connection.exec(SQLiteUtils.dropTableIfExistsStatement(relationKey));
          }
          /* Create the table if it does not currently exist. */
          connection.exec(SQLiteUtils.createIfNotExistsStatementFromSchema(getSchema(), relationKey));
          return null;
        }
      }).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new DbException(e);
    }

    /* Set up the insert statement. */
    insertString = SQLiteUtils.insertStatementFromSchema(getSchema(), relationKey);
  }

  @Override
  protected void childEOS() throws DbException {
  }

  @Override
  protected void childEOI() throws DbException {
  }

}