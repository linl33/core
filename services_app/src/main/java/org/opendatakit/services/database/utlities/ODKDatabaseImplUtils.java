/*
 * Copyright (C) 2014 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.services.database.utlities;

import android.content.ContentValues;
import android.database.Cursor;
import android.provider.BaseColumns;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.opendatakit.aggregate.odktables.rest.*;
import org.opendatakit.aggregate.odktables.rest.entity.Column;
import org.opendatakit.aggregate.odktables.rest.entity.RowFilterScope;
import org.opendatakit.database.DatabaseConstants;
import org.opendatakit.database.LocalKeyValueStoreConstants;
import org.opendatakit.database.RoleConsts;
import org.opendatakit.database.data.*;
import org.opendatakit.database.queries.QueryBounds;
import org.opendatakit.database.utilities.CursorUtils;
import org.opendatakit.database.utilities.KeyValueStoreUtils;
import org.opendatakit.database.utilities.QueryUtil;
import org.opendatakit.exception.ActionNotAuthorizedException;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.provider.*;
import org.opendatakit.services.database.AndroidConnectFactory;
import org.opendatakit.services.database.OdkConnectionInterface;
import org.opendatakit.utilities.LocalizationUtils;
import org.opendatakit.utilities.ODKFileUtils;
import org.opendatakit.utilities.StaticStateManipulator;
import org.opendatakit.utilities.StaticStateManipulator.IStaticFieldManipulator;
import org.sqlite.database.sqlite.SQLiteException;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TODO what does this class do?
 */
public final class ODKDatabaseImplUtils {

  private static final String TAG = ODKDatabaseImplUtils.class.getSimpleName();

  /**
   * Constants to minimize creation of String objects on the stack.
   * <p>
   * Used with StringBuilder to reduce GC overhead
   */
  private static final String K_SELECT_FROM = "SELECT * FROM ";
  private static final String S_AND = " AND ";
  private static final String S_EQUALS_PARAM = " =?";
  private static final String S_IS_NULL = " IS NULL";
  private static final String S_IS_NOT_NULL = " IS NOT NULL";
  private static final String K_WHERE = " WHERE ";
  private static final String K_LIMIT = " LIMIT ";
  private static final String K_OFFSET = " OFFSET ";

  private static final String K_TABLE_DEFS_TABLE_ID_EQUALS_PARAM =
      TableDefinitionsColumns.TABLE_ID + S_EQUALS_PARAM;

  private static final String K_COLUMN_DEFS_TABLE_ID_EQUALS_PARAM =
      ColumnDefinitionsColumns.TABLE_ID + S_EQUALS_PARAM;

  private static final String K_KVS_TABLE_ID_EQUALS_PARAM =
      KeyValueStoreColumns.TABLE_ID + S_EQUALS_PARAM;
  private static final String K_KVS_PARTITION_EQUALS_PARAM =
      KeyValueStoreColumns.PARTITION + S_EQUALS_PARAM;
  private static final String K_KVS_ASPECT_EQUALS_PARAM =
      KeyValueStoreColumns.ASPECT + S_EQUALS_PARAM;
  private static final String K_KVS_KEY_EQUALS_PARAM = KeyValueStoreColumns.KEY + S_EQUALS_PARAM;

  private static final String K_DATATABLE_ID_EQUALS_PARAM = DataTableColumns.ID + S_EQUALS_PARAM;
  private static final List<String> cachedAdminRolesArray;
  private static final TypeReference<ArrayList<String>> arrayListTypeReference;
  /*
   * These are the columns that are present in any row in the database. Each row
   * should have these in addition to the user-defined columns. If you add a
   * column here you have to be sure to also add it in the create table
   * statement, which can't be programmatically created easily.
   */
  private static final List<String> ADMIN_COLUMNS;
  /**
   * These are the columns that should be exported
   */
  private static final List<String> EXPORT_COLUMNS;
  /**
   * When a KVS change is made, enforce in the database layer that the
   * value_type of some KVS entries is a specific type.  Log an error
   * if the user attempts to do something differently, but correct
   * the error. This is largely for migration / forward compatibility.
   */
  private static final Collection<Object[]> knownKVSValueTypeRestrictions = new ArrayList<>();
  /**
   * Same as above, but quick access via the key. For now, we know that the keys are all unique.
   * Eventually this might need to be a MultiMap.
   */
  private static final Map<String, ArrayList<Object[]>> keyToKnownKVSValueTypeRestrictions = new TreeMap<>();
  /**
   * The rolesList expansion is very time consuming.
   * Implement a simple 1-deep cache and a
   * special expansion of the privileged user roles list.
   */
  private static String cachedRolesList = null;
  private static List<String> cachedRolesArray = null;
  private static ODKDatabaseImplUtils databaseUtil = new ODKDatabaseImplUtils();

  static {
    arrayListTypeReference = new TypeReference<ArrayList<String>>() {
    };

    ArrayList<String> rolesArray;
    {
      try {
        rolesArray = ODKFileUtils.mapper
            .readValue(RoleConsts.ADMIN_ROLES_LIST, arrayListTypeReference);
      } catch (IOException ignored) {
        throw new IllegalStateException("this should never happen");
      }
    }
    cachedAdminRolesArray = Collections.unmodifiableList(rolesArray);
  }

  static {
    List<String> adminColumns = new ArrayList<>(
        Arrays.asList(DataTableColumns.ID, DataTableColumns.ROW_ETAG, DataTableColumns.SYNC_STATE,
            // not exportable
            DataTableColumns.CONFLICT_TYPE, // not exportable
            DataTableColumns.DEFAULT_ACCESS, DataTableColumns.ROW_OWNER,
            DataTableColumns.GROUP_READ_ONLY, DataTableColumns.GROUP_MODIFY,
            DataTableColumns.GROUP_PRIVILEGED, DataTableColumns.FORM_ID, DataTableColumns.LOCALE,
            DataTableColumns.SAVEPOINT_TYPE, DataTableColumns.SAVEPOINT_TIMESTAMP,
            DataTableColumns.SAVEPOINT_CREATOR));
    Collections.sort(adminColumns);
    ADMIN_COLUMNS = Collections.unmodifiableList(adminColumns);

    List<String> exportColumns = new ArrayList<>(Arrays
        .asList(DataTableColumns.ID, DataTableColumns.ROW_ETAG, DataTableColumns.DEFAULT_ACCESS,
            DataTableColumns.ROW_OWNER, DataTableColumns.GROUP_READ_ONLY,
            DataTableColumns.GROUP_MODIFY, DataTableColumns.GROUP_PRIVILEGED,
            DataTableColumns.FORM_ID, DataTableColumns.LOCALE, DataTableColumns.SAVEPOINT_TYPE,
            DataTableColumns.SAVEPOINT_TIMESTAMP, DataTableColumns.SAVEPOINT_CREATOR));
    Collections.sort(exportColumns);
    EXPORT_COLUMNS = Collections.unmodifiableList(exportColumns);

    // declare the KVS value_type restrictions we know about...
    // This is a list of triples: ( required value type, partition_label, key_label )
    {
      Object[] fields;

      // for columns
      fields = new Object[3];
      fields[0] = ElementDataType.string.name();
      fields[1] = KeyValueStoreConstants.PARTITION_COLUMN;
      fields[2] = KeyValueStoreConstants.COLUMN_DISPLAY_CHOICES_LIST;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.string.name();
      fields[1] = KeyValueStoreConstants.PARTITION_COLUMN;
      fields[2] = KeyValueStoreConstants.COLUMN_DISPLAY_FORMAT;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.object.name();
      fields[1] = KeyValueStoreConstants.PARTITION_COLUMN;
      fields[2] = KeyValueStoreConstants.COLUMN_DISPLAY_NAME;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.bool.name();
      fields[1] = KeyValueStoreConstants.PARTITION_COLUMN;
      fields[2] = KeyValueStoreConstants.COLUMN_DISPLAY_VISIBLE;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.array.name();
      fields[1] = KeyValueStoreConstants.PARTITION_COLUMN;
      fields[2] = KeyValueStoreConstants.COLUMN_JOINS;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      // and for the table...
      fields = new Object[3];
      fields[0] = ElementDataType.array.name();
      fields[1] = KeyValueStoreConstants.PARTITION_TABLE;
      fields[2] = KeyValueStoreConstants.TABLE_COL_ORDER;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.object.name();
      fields[1] = KeyValueStoreConstants.PARTITION_TABLE;
      fields[2] = KeyValueStoreConstants.TABLE_DISPLAY_NAME;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.array.name();
      fields[1] = KeyValueStoreConstants.PARTITION_TABLE;
      fields[2] = KeyValueStoreConstants.TABLE_GROUP_BY_COLS;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.string.name();
      fields[1] = KeyValueStoreConstants.PARTITION_TABLE;
      fields[2] = KeyValueStoreConstants.TABLE_INDEX_COL;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.object.name();
      fields[1] = KeyValueStoreConstants.PARTITION_TABLE;
      fields[2] = KeyValueStoreConstants.TABLE_SORT_COL;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      fields = new Object[3];
      fields[0] = ElementDataType.object.name();
      fields[1] = KeyValueStoreConstants.PARTITION_TABLE;
      fields[2] = KeyValueStoreConstants.TABLE_SORT_ORDER;
      knownKVSValueTypeRestrictions.add(fields);
      updateKeyToKnownKVSValueTypeRestrictions(fields);

      // TODO: color rule groups
    }

    // Used to ensure that the singleton has been initialized properly
    AndroidConnectFactory.configure();
  }

  static {
    // register a state-reset manipulator for 'databaseUtil' field.
    StaticStateManipulator.get().register(new IStaticFieldManipulator() {

      @Override
      public void reset() {
        databaseUtil = new ODKDatabaseImplUtils();
      }

    });
  }

  private ODKDatabaseImplUtils() {
  }

  private static List<String> getRolesArray(String rolesList) {

    if (rolesList == null || rolesList.isEmpty()) {
      return null;
    } else if (RoleConsts.ADMIN_ROLES_LIST.equals(rolesList)) {
      return cachedAdminRolesArray;
    } else if (rolesList.equals(cachedRolesList)) {
      return cachedRolesArray;
    }
    // figure out whether we have a privileged user or not
    ArrayList<String> rolesArray;
    {
      try {
        rolesArray = ODKFileUtils.mapper.readValue(rolesList, arrayListTypeReference);
      } catch (IOException ignored) {
        throw new IllegalStateException("this should never happen");
      }
    }
    cachedRolesArray = Collections.unmodifiableList(rolesArray);
    cachedRolesList = rolesList;
    return cachedRolesArray;
  }

  private static void updateKeyToKnownKVSValueTypeRestrictions(Object[] field) {
    // I agree with the linter here, this is suspicious
    ArrayList<Object[]> fields = keyToKnownKVSValueTypeRestrictions.get(field[2]);
    if (fields == null) {
      fields = new ArrayList<>();
      keyToKnownKVSValueTypeRestrictions.put((String) field[2], fields);
    }
    fields.add(field);
  }

  /**
   * Public accessor for the database util object
   *
   * @return a databaseutil object
   */
  public static ODKDatabaseImplUtils get() {
    return databaseUtil;
  }

  /**
   * For mocking -- supply a mocked object.
   *
   * @param util the util to set
   */
  public static void set(ODKDatabaseImplUtils util) {
    databaseUtil = util;
  }

  private static boolean sameValue(Object a, Object b) {
    if (b == null) {
      return a == null;
    } else {
      return b.equals(a);
    }
  }

  /**
   * Thin wrapper for {@link #commonTableDefn(OdkConnectionInterface)}
   *
   * @param db an active database connection to use
   */
  public static void initializeDatabase(OdkConnectionInterface db) {
    commonTableDefn(db);
  }

  private static void commonTableDefn(OdkConnectionInterface db) {
    WebLogger.getLogger(db.getAppName()).i("commonTableDefn", "starting");
    //WebLogger.getLogger(db.getAppName()).i("commonTableDefn", DatabaseConstants.UPLOADS_TABLE_NAME);
    //db.execSQL(InstanceColumns.getTableCreateSql(DatabaseConstants.UPLOADS_TABLE_NAME), null);
    WebLogger.getLogger(db.getAppName()).i("commonTableDefn", DatabaseConstants.FORMS_TABLE_NAME);
    db.execSQL(FormsColumns.getTableCreateSql(DatabaseConstants.FORMS_TABLE_NAME), null);
    WebLogger.getLogger(db.getAppName())
        .i("commonTableDefn", DatabaseConstants.COLUMN_DEFINITIONS_TABLE_NAME);
    db.execSQL(
        ColumnDefinitionsColumns.getTableCreateSql(DatabaseConstants.COLUMN_DEFINITIONS_TABLE_NAME),
        null);
    WebLogger.getLogger(db.getAppName())
        .i("commonTableDefn", DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME);
    db.execSQL(
        KeyValueStoreColumns.getTableCreateSql(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME),
        null);
    WebLogger.getLogger(db.getAppName())
        .i("commonTableDefn", DatabaseConstants.TABLE_DEFS_TABLE_NAME);
    db.execSQL(TableDefinitionsColumns.getTableCreateSql(DatabaseConstants.TABLE_DEFS_TABLE_NAME),
        null);
    WebLogger.getLogger(db.getAppName())
        .i("commonTableDefn", DatabaseConstants.SYNC_ETAGS_TABLE_NAME);
    db.execSQL(SyncETagColumns.getTableCreateSql(DatabaseConstants.SYNC_ETAGS_TABLE_NAME), null);
    WebLogger.getLogger(db.getAppName())
        .i("commonTableDefn", DatabaseConstants.CHOICE_LIST_TABLE_NAME);
    db.execSQL(ChoiceListColumns.getTableCreateSql(DatabaseConstants.CHOICE_LIST_TABLE_NAME), null);
    WebLogger.getLogger(db.getAppName()).i("commonTableDefn", "done");
  }

  /**
   * Return an unmodifiable list of the admin columns that must be present in
   * every database table.
   *
   * @return
   */
  public static List<String> getAdminColumns() {
    return ADMIN_COLUMNS;
  }

  /**
   * Return an unmodifiable list of the admin columns that should be exported to
   * a CSV file. This list excludes the SYNC_STATE and CONFLICT_TYPE columns.
   *
   * @return
   */
  public static List<String> getExportColumns() {
    return EXPORT_COLUMNS;
  }

  private static String applyQueryBounds(String sqlCommand, QueryBounds sqlQueryBounds) {
    if (sqlCommand == null || sqlQueryBounds == null) {
      return sqlCommand;
    }

    return sqlCommand + K_LIMIT + sqlQueryBounds.mLimit + K_OFFSET + sqlQueryBounds.mOffset;
  }

  /**
   * Optionally add the _effective_access column to the SELECT statement.
   *
   * @param b              A stringbuilder to add rights to
   * @param wrappedSqlArgs a collection to put groups into
   * @param accessContext  An AccessContext object that gets what kind of rights the user has
   */
  private static void buildAccessRights(StringBuilder b, Collection<Object> wrappedSqlArgs,
      AccessContext accessContext) {

    if (accessContext.accessColumnType == AccessColumnType.NO_EFFECTIVE_ACCESS_COLUMN) {
      return;
    }

    b.append(", ");
    if (accessContext.isPrivilegedUser) {
      // privileged user
      b.append("\"rwdp\" as ").append(DataTableColumns.EFFECTIVE_ACCESS);
    } else if (accessContext.isUnverifiedUser) {
      // un-verified user or anonymous user
      if (accessContext.accessColumnType == AccessColumnType.UNLOCKED_EFFECTIVE_ACCESS_COLUMN) {
        // unlocked tables have r, rw (modify) and rwd (full defaultAccess or new_row) options
        b.append("case when T.").append(DataTableColumns.SYNC_STATE).append("= \"")
            .append(SyncState.new_row.name()).append("\" then \"rwd\" ").append(" when T.")
            .append(DataTableColumns.DEFAULT_ACCESS).append("= \"")
            .append(RowFilterScope.Access.FULL.name()).append("\" then \"rwd\" ").append(" when T.")
            .append(DataTableColumns.DEFAULT_ACCESS).append("= \"")
            .append(RowFilterScope.Access.MODIFY.name()).append("\" then \"rw\" ")
            .append(" else \"r\" end as ").append(DataTableColumns.EFFECTIVE_ACCESS);
      } else {
        // locked tables have just rwd (new_row) and r options
        b.append("case when T.").append(DataTableColumns.SYNC_STATE).append("= \"")
            .append(SyncState.new_row.name()).append("\" then \"rwd\" ")
            .append(" else \"r\" end as ").append(DataTableColumns.EFFECTIVE_ACCESS);
      }
    } else {
      // ordinary user
      if (accessContext.accessColumnType == AccessColumnType.UNLOCKED_EFFECTIVE_ACCESS_COLUMN) {
        // unlocked tables have r, rw (modify), rwd (full defaultAccess), rwdp (rowOwner,
        // groupPrivileged) options
        b.append("case when T.").append(DataTableColumns.SYNC_STATE).append("= \"")
            .append(SyncState.new_row.name()).append("\" then \"rwdp\" ").append(" when T.")
            .append(DataTableColumns.ROW_OWNER).append("= ?").append(" then \"rwdp\" ");

        wrappedSqlArgs.add(accessContext.activeUser);

        // Add in _group_privileged
        List<String> groups = accessContext.getGroupsArray();
        for (String group : groups) {
          b.append(" when T.").append(DataTableColumns.GROUP_PRIVILEGED).append(" = ?")
              .append(" then \"rwdp\" ");
          wrappedSqlArgs.add(group);
        }

        b.append(" when T.").append(DataTableColumns.DEFAULT_ACCESS).append("= \"")
            .append(RowFilterScope.Access.FULL.name()).append("\" then \"rwd\" ");

        b.append(" when T.").append(DataTableColumns.DEFAULT_ACCESS).append("= \"")
            .append(RowFilterScope.Access.MODIFY.name()).append("\" then \"rw\" ");

        // Add in _group_modify
        for (String group : groups) {
          b.append(" when T.").append(DataTableColumns.GROUP_MODIFY).append(" = ?")
              .append(" then \"rw\" ");
          wrappedSqlArgs.add(group);
        }

        b.append(" else \"r\" end as ").append(DataTableColumns.EFFECTIVE_ACCESS);

      } else {
        // locked tables have just rwdp (new_row and groupPrivileged),
        // rw (rowOwner), and r options
        b.append("case when T.").append(DataTableColumns.SYNC_STATE).append("= \"")
            .append(SyncState.new_row.name()).append("\" then \"rwdp\" ");

        // Add in _group_privileged
        List<String> groups = accessContext.getGroupsArray();
        for (String group : groups) {
          b.append(" when T.").append(DataTableColumns.GROUP_PRIVILEGED).append(" = ?")
              .append(" then \"rwdp\" ");
          wrappedSqlArgs.add(group);
        }

        b.append(" when T.").append(DataTableColumns.ROW_OWNER).append("= ?")
            .append(" then \"rw\" ");
        wrappedSqlArgs.add(accessContext.activeUser);

        b.append(" else \"r\" end as ").append(DataTableColumns.EFFECTIVE_ACCESS);
      }
    }
  }

  /**
   * Perform a raw query with bind parameters.
   *
   * @param db             an open database connection to use
   * @param sqlCommand     the sql query to run
   * @param selectionArgs  The args to replace the ?s in the final query
   * @param sqlQueryBounds offset and max number of rows to return (zero is infinite)
   * @param accessContext  for managing what effective accesses to return
   * @return A cursor with the results of the first row of the query
   */
  public static Cursor rawQuery(OdkConnectionInterface db, String sqlCommand,
      Object[] selectionArgs, QueryBounds sqlQueryBounds, AccessContext accessContext) {

    Cursor c = db.rawQuery(sqlCommand + " LIMIT 1", selectionArgs);
    if (c.moveToFirst()) {
      // see if we have the columns needed to apply row-level filtering
      boolean hasDefaultAccess = c.getColumnIndex(DataTableColumns.DEFAULT_ACCESS) != -1;
      boolean hasOwner = c.getColumnIndex(DataTableColumns.ROW_OWNER) != -1;
      boolean hasSyncState = c.getColumnIndex(DataTableColumns.SYNC_STATE) != -1;
      boolean hasGroupReadOnly = c.getColumnIndex(DataTableColumns.GROUP_READ_ONLY) != -1;
      boolean hasGroupModify = c.getColumnIndex(DataTableColumns.GROUP_MODIFY) != -1;
      boolean hasGroupPrivileged = c.getColumnIndex(DataTableColumns.GROUP_PRIVILEGED) != -1;

      c.close();

      if (!(hasDefaultAccess && hasOwner && hasSyncState && hasGroupReadOnly && hasGroupModify
          && hasGroupPrivileged)) {
        // nope. we require all 6 to apply row-level filtering

        // no need to filter this resultset
        String sql = applyQueryBounds(sqlCommand, sqlQueryBounds);
        c = db.rawQuery(sql, selectionArgs);
        return c;
      }

      // augment query result list with the effective access controls for the row ("r", "rw", or "rwd")
      StringBuilder b = new StringBuilder();
      Collection<Object> wrappedSqlArgs = new ArrayList<>();

      b.append("SELECT *");
      buildAccessRights(b, wrappedSqlArgs, accessContext);
      b.append(" FROM (").append(sqlCommand).append(") AS T");
      if (selectionArgs != null) {
        Collections.addAll(wrappedSqlArgs, selectionArgs);
      }
      // apply row-level visibility filter only if we are not privileged
      // privileged users see everything.
      if (!accessContext.isPrivilegedUser) {
        b.append(" WHERE T.").append(DataTableColumns.DEFAULT_ACCESS).append(" != \"")
            .append(RowFilterScope.Access.HIDDEN.name()).append("\" OR T.")
            .append(DataTableColumns.SYNC_STATE).append(" = \"").append(SyncState.new_row.name())
            .append("\"");
        if (!accessContext.isUnverifiedUser && accessContext.hasRole(RoleConsts.ROLE_USER)) {
          // visible if activeUser matches the filter value
          b.append(" OR T.").append(DataTableColumns.ROW_OWNER).append(" = ?");
          wrappedSqlArgs.add(accessContext.activeUser);
        }

        {
          // row is visible if group_read_only is one of the groups the user belongs to.
          List<String> groups = accessContext.getGroupsArray();
          for (String group : groups) {
            b.append(" OR T.").append(DataTableColumns.GROUP_READ_ONLY).append(" = ?");
            wrappedSqlArgs.add(group);
          }
        }

        {
          // row is visible if group_modify is one of the groups the user belongs to.
          List<String> groups = accessContext.getGroupsArray();
          for (String group : groups) {
            b.append(" OR T.").append(DataTableColumns.GROUP_MODIFY).append(" = ?");
            wrappedSqlArgs.add(group);
          }
        }

        {
          // row is visible if group_privileged is one of the groups the user belongs to.
          List<String> groups = accessContext.getGroupsArray();
          for (String group : groups) {
            b.append(" OR T.").append(DataTableColumns.GROUP_PRIVILEGED).append(" = ?");
            wrappedSqlArgs.add(group);
          }
        }
      }
      String wrappedSql = b.toString();
      String limitAppliedSql = applyQueryBounds(wrappedSql, sqlQueryBounds);
      c = db.rawQuery(limitAppliedSql, wrappedSqlArgs.toArray());
      return c;
    } else {
      // cursor is empty!
      return c;
    }
  }

  /*
   * Build the start of a create table statement -- specifies all the metadata
   * columns. Caller must then add all the user-defined column definitions and
   * closing parentheses.
   */
  private static void addMetadataFieldsToTableCreationStatement(StringBuilder b) {
    /*
     * Resulting string should be the following String createTableCmd =
     * "CREATE TABLE IF NOT EXISTS " + tableId + " (" + DataTableColumns.ID +
     * " TEXT NOT NULL, " + DataTableColumns.ROW_ETAG + " TEXT NULL, " +
     * DataTableColumns.SYNC_STATE + " TEXT NOT NULL, " +
     * DataTableColumns.CONFLICT_TYPE + " INTEGER NULL," +
     * DataTableColumns.DEFAULT_ACCESS + " TEXT NULL," +
     * DataTableColumns.ROW_OWNER + " TEXT NULL," +
     * DataTableColumns.GROUP_READ_ONLY + " TEXT NULL," +
     * DataTableColumns.GROUP_MODIFY + " TEXT NULL," +
     * DataTableColumns.GROUP_PRIVILEGED + " TEXT NULL," +
     * DataTableColumns.FORM_ID + " TEXT NULL," +
     * DataTableColumns.LOCALE + " TEXT NULL," +
     * DataTableColumns.SAVEPOINT_TYPE + " TEXT NULL," +
     * DataTableColumns.SAVEPOINT_TIMESTAMP + " TEXT NOT NULL," +
     * DataTableColumns.SAVEPOINT_CREATOR + " TEXT NULL";
     */

    List<String> cols = getAdminColumns();

    String endSeq = ", ";
    for (int i = 0; i < cols.size(); ++i) {
      if (i == cols.size() - 1) {
        endSeq = "";
      }
      String colName = cols.get(i);
      switch (colName) {
      case DataTableColumns.ID:
      case DataTableColumns.SYNC_STATE:
      case DataTableColumns.SAVEPOINT_TIMESTAMP:
        b.append(colName).append(" TEXT NOT NULL").append(endSeq);
        continue;
      case DataTableColumns.ROW_ETAG:
      case DataTableColumns.DEFAULT_ACCESS:
      case DataTableColumns.ROW_OWNER:
      case DataTableColumns.GROUP_READ_ONLY:
      case DataTableColumns.GROUP_MODIFY:
      case DataTableColumns.GROUP_PRIVILEGED:
      case DataTableColumns.FORM_ID:
      case DataTableColumns.LOCALE:
      case DataTableColumns.SAVEPOINT_TYPE:
      case DataTableColumns.SAVEPOINT_CREATOR:
        b.append(colName).append(" TEXT NULL").append(endSeq);
        continue;
      case DataTableColumns.CONFLICT_TYPE:
        b.append(colName).append(" INTEGER NULL").append(endSeq);
        continue;
      default:
      }
    }
  }

  /**
   * TESTING ONLY
   * <p/>
   * Perform a query with the given parameters.
   *
   * @param db            an open database connection to use
   * @param columns       the columns to return from the query
   * @param selection     TODO what is this?
   * @param selectionArgs The strings to replace ?s in the query
   * @param orderBy       the column to order by
   * @param table         the table id
   * @param groupBy       an array of elementKeys
   * @param having        part of the sql query
   * @param limit         the maximum number of rows to return
   * @return a cursor with the result of the query
   * @throws SQLiteException if there's a sqlite problem
   */
  public static Cursor queryDistinctForTest(OdkConnectionInterface db, String table,
      String[] columns, String selection, Object[] selectionArgs, String groupBy, String having,
      String orderBy, String limit) throws SQLiteException {
    return db
        .queryDistinct(table, columns, selection, selectionArgs, groupBy, having, orderBy, limit);
  }

  /**
   * TESTING ONLY
   *
   * @param db            an open database connection to use
   * @param columns       the columns to return from the query
   * @param selection     TODO what is this?
   * @param selectionArgs The strings to replace ?s in the query
   * @param orderBy       the column to order by
   * @param table         the table id
   * @param groupBy       an array of elementKeys
   * @param having        part of the sql query
   * @param limit         the maximum number of rows to return
   * @return a cursor with the result of the query
   * @throws SQLiteException if there's a sqlite problem
   */
  public static Cursor queryForTest(OdkConnectionInterface db, String table, String[] columns,
      String selection, Object[] selectionArgs, String groupBy, String having, String orderBy,
      String limit) throws SQLiteException {
    return db.query(table, columns, selection, selectionArgs, groupBy, having, orderBy, limit);
  }

  /**
   * Privileged execute of an arbitrary SQL command.
   * For obvious reasons, this is very dangerous!
   * <p>
   * The sql command can be any valid SQL command that does not return a result set.
   * No data is returned (e.g., insert into table ... or similar).
   *
   * @param db          an open database connection to use
   * @param sqlCommand  the raw query to execute
   * @param sqlBindArgs the objects to replace the ?s in the query
   */
  public static void privilegedExecute(OdkConnectionInterface db, String sqlCommand,
      Object[] sqlBindArgs) {
    db.execSQL(sqlCommand, sqlBindArgs);
  }

  /**
   * Drop the given local only table
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   */
  public static void deleteLocalOnlyTable(OdkConnectionInterface db, String tableId) {

    boolean dbWithinTransaction = db.inTransaction();

    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      // Drop the table used for the formId
      db.execSQL("DROP TABLE IF EXISTS " + tableId + ";", null);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }

    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Insert a row into a local only table
   *
   * @param db        an open database connection to use
   * @param tableId   the table to update
   * @param rowValues the data for the new row
   */
  public static void insertLocalOnlyRow(OdkConnectionInterface db, String tableId,
      ContentValues rowValues) {

    if (rowValues == null || rowValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>();
    for (String key : rowValues.keySet()) {
      cvDataTableVal.put(key, rowValues.get(key));
    }

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.insertOrThrow(tableId, null, cvDataTableVal);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Update a row in a local only table
   *
   * @param db          an open database connection to use
   * @param tableId     the table to update
   * @param rowValues   the data to be written over the existing row
   * @param whereClause a clause that should limit the query to only the row to be updated
   * @param bindArgs    the values to replace the ?s in the query
   */
  public static void updateLocalOnlyRow(OdkConnectionInterface db, String tableId,
      ContentValues rowValues, String whereClause, Object[] bindArgs) {

    if (rowValues == null || rowValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>();
    for (String key : rowValues.keySet()) {
      cvDataTableVal.put(key, rowValues.get(key));
    }

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.update(tableId, cvDataTableVal, whereClause, bindArgs);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Delete a row in a local only table
   *
   * @param db          an open database connection to use
   * @param tableId     the table to update
   * @param whereClause a clause that should limit the query to only the rows to be deleted
   * @param bindArgs    the values to replace the ?s in the query
   */
  public static void deleteLocalOnlyRow(OdkConnectionInterface db, String tableId,
      String whereClause, Object[] bindArgs) {

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.delete(tableId, whereClause, bindArgs);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  private static boolean hasConflictRows(BaseTable table) {
    List<Row> rows = table.getRows();
    for (Row row : rows) {
      String conflictType = row.getDataByKey(DataTableColumns.CONFLICT_TYPE);
      if (conflictType != null && !conflictType.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Return all the columns in the given table, including any metadata columns.
   * This does a direct query against the database and is suitable for accessing
   * non-managed tables. It does not access any metadata and therefore will not
   * report non-unit-of-retention (grouping) columns.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @return the list of column names in an array
   */
  public static String[] getAllColumnNames(OdkConnectionInterface db, Object tableId) {
    Cursor cursor = null;
    try {
      cursor = db.rawQuery(K_SELECT_FROM + tableId + K_LIMIT + "1", null);
      // If this query has been executed before, the cursor is created using the
      // previously-constructed PreparedStatement for the query. There is no actual
      // interaction with the database itself at the time the Cursor is constructed.
      // The first database interaction is when the content of the cursor is fetched.
      //
      // This can be triggered by a call to getCount().
      // At that time, if the table does not exist, it will throw an exception.
      cursor.moveToFirst();
      cursor.getCount();
      // Otherwise, when cached, getting the column names doesn't call into the database
      // and will not, itself, detect that the table has been dropped.
      return cursor.getColumnNames();
    } finally {
      if (cursor != null && !cursor.isClosed()) {
        cursor.close();
      }
    }
  }

  /**
   * Retrieve the list of user-defined columns for a tableId using the metadata
   * for that table. Returns the unit-of-retention and non-unit-of-retention
   * (grouping) columns.
   *
   * @param db      an open database connection to use
   * @param tableId the table to query
   * @return the list of non-admin columns in the table
   */
  public static OrderedColumns getUserDefinedColumns(OdkConnectionInterface db, String tableId) {
    ArrayList<Column> userDefinedColumns = new ArrayList<>();
    Object[] selectionArgs = { tableId };
    //@formatter:off
    String[] cols = {
        ColumnDefinitionsColumns.ELEMENT_KEY,
        ColumnDefinitionsColumns.ELEMENT_NAME,
        ColumnDefinitionsColumns.ELEMENT_TYPE,
        ColumnDefinitionsColumns.LIST_CHILD_ELEMENT_KEYS
      };
    //@formatter:on
    Cursor c = null;
    try {
      c = db.query(DatabaseConstants.COLUMN_DEFINITIONS_TABLE_NAME, cols,
          K_COLUMN_DEFS_TABLE_ID_EQUALS_PARAM, selectionArgs, null, null,
          ColumnDefinitionsColumns.ELEMENT_KEY + " ASC", null);

      int elemKeyIndex = c.getColumnIndexOrThrow(ColumnDefinitionsColumns.ELEMENT_KEY);
      int elemNameIndex = c.getColumnIndexOrThrow(ColumnDefinitionsColumns.ELEMENT_NAME);
      int elemTypeIndex = c.getColumnIndexOrThrow(ColumnDefinitionsColumns.ELEMENT_TYPE);
      int listChildrenIndex = c
          .getColumnIndexOrThrow(ColumnDefinitionsColumns.LIST_CHILD_ELEMENT_KEYS);
      c.moveToFirst();
      while (!c.isAfterLast()) {
        String elementKey = CursorUtils.getIndexAsString(c, elemKeyIndex);
        String elementName = CursorUtils.getIndexAsString(c, elemNameIndex);
        String elementType = CursorUtils.getIndexAsString(c, elemTypeIndex);
        String listOfChildren = CursorUtils.getIndexAsString(c, listChildrenIndex);
        userDefinedColumns.add(new Column(elementKey, elementName, elementType, listOfChildren));
        c.moveToNext();
      }
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
    return new OrderedColumns(db.getAppName(), tableId, userDefinedColumns);
  }

  /**
   * Verifies that the tableId exists in the database.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @return true if table is listed in table definitions.
   */
  public static boolean hasTableId(OdkConnectionInterface db, String tableId) {
    Cursor c = null;
    try {
      //@formatter:off
      c = db.query(DatabaseConstants.TABLE_DEFS_TABLE_NAME, null,
          K_TABLE_DEFS_TABLE_ID_EQUALS_PARAM,
          new Object[] { tableId }, null, null, null, null);
      //@formatter:on
      // we know about the table...
      // tableId is the database table name...
      return c != null && c.moveToFirst() && c.getCount() != 0;
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
  }

  /**
   * Return the health of a data table. The health can be one of
   * <ul>
   * <li>TABLE_HEALTH_IS_CLEAN = 0</li>
   * <li>TABLE_HEALTH_HAS_CONFLICTS = 1</li>
   * <li>TABLE_HEALTH_HAS_CHECKPOINTS = 2</li>
   * <li>TABLE_HEALTH_HAS_CHECKPOINTS_AND_CONFLICTS = 3</li>
   * <ul>
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @return the table health
   */
  public static int getTableHealth(OdkConnectionInterface db, String tableId) {
    StringBuilder b = new StringBuilder();
    b.append("SELECT SUM(case when _savepoint_type is null then 1 else 0 end) as checkpoints,")
        .append("SUM(case when _conflict_type is not null then 1 else 0 end) as conflicts,").append(
        "SUM(case when _sync_state is 'synced' then 0 when _sync_state is "
            + "'synced_pending_files' then 0 else 1 end) as changes FROM ").append(tableId);

    Cursor c = null;
    try {
      c = db.rawQuery(b.toString(), null);
      Integer checkpoints = null;
      Integer conflicts = null;
      Integer changes = null;
      if (c != null) {
        if (c.moveToFirst()) {
          int idxCheckpoints = c.getColumnIndex("checkpoints");
          int idxConflicts = c.getColumnIndex("conflicts");
          int idxChanges = c.getColumnIndex("changes");
          checkpoints = CursorUtils.getIndexAsType(c, Integer.class, idxCheckpoints);
          conflicts = CursorUtils.getIndexAsType(c, Integer.class, idxConflicts);
          changes = CursorUtils.getIndexAsType(c, Integer.class, idxChanges);
        }
        c.close();
      }

      int outcome = CursorUtils.TABLE_HEALTH_IS_CLEAN;
      if (checkpoints != null && checkpoints != 0) {
        outcome = CursorUtils.setTableHealthHasCheckpoints(outcome);
      }
      if (conflicts != null && conflicts != 0) {
        outcome = CursorUtils.setTableHealthHasConflicts(outcome);
      }
      if (changes != null && changes != 0) {
        outcome = CursorUtils.setTableHealthHasChanges(outcome);
      }
      return outcome;
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
  }

  /**
   * Return all the tableIds in the database.
   *
   * @param db an open database connection to use
   * @return an ArrayList<String> of tableIds
   */
  public static ArrayList<String> getAllTableIds(OdkConnectionInterface db) {
    ArrayList<String> tableIds = new ArrayList<>();
    Cursor c = null;
    try {
      c = db.query(DatabaseConstants.TABLE_DEFS_TABLE_NAME,
          new String[] { TableDefinitionsColumns.TABLE_ID }, null, null, null, null,
          TableDefinitionsColumns.TABLE_ID + " ASC", null);

      if (c.moveToFirst()) {
        int idxId = c.getColumnIndex(TableDefinitionsColumns.TABLE_ID);
        do {
          String tableId = c.getString(idxId);
          if (tableId == null || tableId.isEmpty()) {
            c.close();
            throw new IllegalStateException("getAllTableIds: Unexpected tableId found!");
          }
          tableIds.add(tableId);
        } while (c.moveToNext());
      }
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
    return tableIds;
  }

  /**
   * Drop the given tableId and remove all the files (both configuration and
   * data attachments) associated with that table.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   */
  public static void deleteTableAndAllData(OdkConnectionInterface db, final String tableId) {

    SyncETagsUtils seu = new SyncETagsUtils();
    boolean dbWithinTransaction = db.inTransaction();

    Object[] whereArgs = { tableId };

    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      // Drop the table used for the formId
      db.execSQL("DROP TABLE IF EXISTS " + tableId + ";", null);

      // Delete the server sync ETags associated with this table
      seu.deleteAllSyncETagsForTableId(db, tableId);

      // Delete the table definition for the tableId
      {

        db.delete(DatabaseConstants.TABLE_DEFS_TABLE_NAME, K_TABLE_DEFS_TABLE_ID_EQUALS_PARAM,
            whereArgs);
      }

      // Delete the column definitions for this tableId
      {

        db.delete(DatabaseConstants.COLUMN_DEFINITIONS_TABLE_NAME,
            K_COLUMN_DEFS_TABLE_ID_EQUALS_PARAM, whereArgs);
      }

      // Delete the uploads for the tableId
      {
        //String uploadWhereClause = InstanceColumns.DATA_TABLE_TABLE_ID + " = ?";
        //db.delete(DatabaseConstants.UPLOADS_TABLE_NAME, uploadWhereClause, whereArgs);
      }

      // Delete the values from the 4 key value stores
      {

        db.delete(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME, K_KVS_TABLE_ID_EQUALS_PARAM,
            whereArgs);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }

    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }

    // And delete the files from the SDCard...
    String tableDir = ODKFileUtils.getTablesFolder(db.getAppName(), tableId);
    try {
      ODKFileUtils.deleteDirectory(new File(tableDir));
    } catch (IOException e) {
      WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      throw new IllegalStateException("Unable to delete the " + tableDir + " directory", e);
    }

    String assetsCsvDir = ODKFileUtils.getAssetsCsvFolder(db.getAppName());
    try {
      File file = new File(assetsCsvDir);
      if (file.exists()) {
        Collection<File> files = ODKFileUtils.listFiles(file, new IOFileFilter() {

          @Override
          public boolean accept(File file) {
            String[] parts = file.getName().split("\\.");
            //noinspection UnnecessaryParentheses
            return (parts[0].equals(tableId) && "csv".equals(parts[parts.length - 1]) && (
                parts.length == 2 || parts.length == 3 || (parts.length == 4 && "properties"
                    .equals(parts[parts.length - 2]))));
          }

          @Override
          public boolean accept(File dir, String name) {
            String[] parts = name.split("\\.");
            //noinspection UnnecessaryParentheses
            return (parts[0].equals(tableId) && "csv".equals(parts[parts.length - 1]) && (
                parts.length == 2 || parts.length == 3 || (parts.length == 4 && "properties"
                    .equals(parts[parts.length - 2]))));
          }
        }, new IOFileFilter() {

          // don't traverse into directories
          @Override
          public boolean accept(File arg0) {
            return false;
          }

          // don't traverse into directories
          @Override
          public boolean accept(File arg0, String arg1) {
            return false;
          }
        });

        ODKFileUtils.deleteDirectory(new File(tableDir));
        for (File f : files) {
          ODKFileUtils.deleteQuietly(f);
        }
      }
    } catch (IOException e) {
      WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      throw new IllegalStateException("Unable to delete the " + tableDir + " directory", e);
    }
  }

  /**
   * Update the schema and data-modification ETags of a given tableId.
   *
   * @param db           an open database connection to use
   * @param tableId      the table to update
   * @param schemaETag   TODO
   * @param lastDataETag TODO
   */
  public static void privilegedUpdateTableETags(OdkConnectionInterface db, CharSequence tableId,
      String schemaETag, String lastDataETag) {
    if (tableId == null || tableId.length() <= 0) {
      throw new IllegalArgumentException(
          TAG + ": application name and table name must be specified");
    }

    Map<String, Object> cvTableDef = new TreeMap<>();
    cvTableDef.put(TableDefinitionsColumns.SCHEMA_ETAG, schemaETag);
    cvTableDef.put(TableDefinitionsColumns.LAST_DATA_ETAG, lastDataETag);

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }
      db.update(DatabaseConstants.TABLE_DEFS_TABLE_NAME, cvTableDef,
          K_TABLE_DEFS_TABLE_ID_EQUALS_PARAM, new Object[] { tableId });

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }

    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Update the timestamp of the last entirely-successful synchronization
   * attempt of this table.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   */
  public static void privilegedUpdateTableLastSyncTime(OdkConnectionInterface db,
      CharSequence tableId) {
    if (tableId == null || tableId.length() <= 0) {
      throw new IllegalArgumentException(
          TAG + ": application name and table name must be specified");
    }

    Map<String, Object> cvTableDef = new TreeMap<>();
    cvTableDef.put(TableDefinitionsColumns.LAST_SYNC_TIME,
        TableConstants.nanoSecondsFromMillis(System.currentTimeMillis()));

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }
      db.update(DatabaseConstants.TABLE_DEFS_TABLE_NAME, cvTableDef,
          K_TABLE_DEFS_TABLE_ID_EQUALS_PARAM, new Object[] { tableId });

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Get the table definition entry for a tableId. This specifies the schema
   * ETag, the data-modification ETag, and the date-time of the last successful
   * sync of the table to the server.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @return the requested table definition entry
   */
  public static TableDefinitionEntry getTableDefinitionEntry(OdkConnectionInterface db,
      String tableId) {

    TableDefinitionEntry e = null;
    Cursor c = null;
    try {
      Collection<String> selArgs = new ArrayList<>();
      selArgs.add(tableId);

      c = db.query(DatabaseConstants.TABLE_DEFS_TABLE_NAME, null, K_KVS_TABLE_ID_EQUALS_PARAM,
          selArgs.toArray(new String[selArgs.size()]), null, null, null, null);
      if (c.moveToFirst()) {
        int idxSchemaETag = c.getColumnIndex(TableDefinitionsColumns.SCHEMA_ETAG);
        int idxLastDataETag = c.getColumnIndex(TableDefinitionsColumns.LAST_DATA_ETAG);
        int idxLastSyncTime = c.getColumnIndex(TableDefinitionsColumns.LAST_SYNC_TIME);
        int idxRevId = c.getColumnIndex(TableDefinitionsColumns.REV_ID);

        if (c.getCount() != 1) {
          throw new IllegalStateException(
              "Two or more TableDefinitionEntry records found for tableId " + tableId);
        }

        e = new TableDefinitionEntry(tableId);
        e.setSchemaETag(c.getString(idxSchemaETag));
        e.setLastDataETag(c.getString(idxLastDataETag));
        e.setLastSyncTime(c.getString(idxLastSyncTime));
        e.setRevId(c.getString(idxRevId));
      }
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
    return e;
  }

  /**
   * gets the rev id of the given table
   *
   * @param db      a database connection to use
   * @param tableId the id of the table to get the rev id for
   * @return the rev id of the requested table
   */
  public static String getTableDefinitionRevId(OdkConnectionInterface db, String tableId) {
    String revId = null;
    Cursor c = null;
    try {
      Collection<String> selArgs = new ArrayList<>();
      selArgs.add(tableId);

      c = db.query(DatabaseConstants.TABLE_DEFS_TABLE_NAME, null, K_KVS_TABLE_ID_EQUALS_PARAM,
          selArgs.toArray(new String[selArgs.size()]), null, null, null, null);
      if (c.moveToFirst()) {
        int idxRevId = c.getColumnIndex(TableDefinitionsColumns.REV_ID);

        if (c.getCount() != 1) {
          throw new IllegalStateException(
              "Two or more TableDefinitionEntry records found for tableId " + tableId);
        }

        revId = c.getString(idxRevId);
      }
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
    return revId;
  }

  /**
   * Ensure that the kvs entry is valid.
   *
   * @param appName the app name
   * @param tableId the table to update
   * @param kvs     a key value store entry to validate
   * @throws IllegalArgumentException if there was a problem validating the kvs entry
   */
  private static void validateKVSEntry(String appName, String tableId, KeyValueStoreEntry kvs)
      throws IllegalArgumentException {

    if (kvs.tableId == null || kvs.tableId.trim().isEmpty()) {
      throw new IllegalArgumentException("KVS entry has a null or empty tableId");
    }

    if (!kvs.tableId.equals(tableId)) {
      throw new IllegalArgumentException("KVS entry has a mismatched tableId");
    }

    if (kvs.partition == null || kvs.partition.trim().isEmpty()) {
      throw new IllegalArgumentException("KVS entry has a null or empty partition");
    }

    if (kvs.aspect == null || kvs.aspect.trim().isEmpty()) {
      throw new IllegalArgumentException("KVS entry has a null or empty aspect");
    }

    if (kvs.key == null || kvs.key.trim().isEmpty()) {
      throw new IllegalArgumentException("KVS entry has a null or empty key");
    }

    // a null value will remove the entry from the KVS
    if (kvs.value != null && !kvs.value.trim().isEmpty()) {
      // validate the type....
      if (kvs.type == null || kvs.type.trim().isEmpty()) {
        throw new IllegalArgumentException("KVS entry has a null or empty type");
      }

      // find subset matching the key...
      ArrayList<Object[]> kvsValueTypeRestrictions = keyToKnownKVSValueTypeRestrictions
          .get(kvs.key);

      if (kvsValueTypeRestrictions != null) {
        for (Object[] restriction : kvsValueTypeRestrictions) {
          if (kvs.partition.equals(restriction[1]) && kvs.key.equals(restriction[2])) {
            // see if the client specified an incorrect type
            if (!kvs.type.equals(restriction[0])) {
              String type = kvs.type;
              kvs.type = (String) restriction[0];

              // TODO: detect whether the value conforms to the specified type.
              enforceKVSValueType(kvs, ElementDataType.valueOf(kvs.type));

              WebLogger.getLogger(appName).w("validateKVSEntry",
                  "Client Error: KVS value type reset from " + type + " to " + restriction[0]
                      + " table: " + kvs.tableId + " partition: " + restriction[1] + " key: "
                      + restriction[2]);
            }
          }
        }
      }
    } else {
      // makes later tests easier...
      kvs.value = null;
    }
  }

  /**
   * REVISIT THESE TO ENFORCE SAFE UPDATES OF KVS database
   * *********************************************************************************************
   * *********************************************************************************************
   * *********************************************************************************************
   * *********************************************************************************************
   */

  @SuppressWarnings("StatementWithEmptyBody")
  private static void enforceKVSValueType(KeyValueStoreEntry e, ElementDataType type) {
    e.type = type.name();
    if (e.value != null) {
      if (type.equals(ElementDataType.integer) || type.equals(ElementDataType.number) || type
          .equals(ElementDataType.bool) || type.equals(ElementDataType.string) || type
          .equals(ElementDataType.rowpath) || type.equals(ElementDataType.configpath)) {
        // TODO: can add matcher if we want to
      } else if (type.equals(ElementDataType.array)) {
        // minimal test for valid representation
        if (!e.value.startsWith("[") || !e.value.endsWith("]")) {
          throw new IllegalArgumentException(
              "array value type is not an array! " + "TableId: " + e.tableId + " Partition: "
                  + e.partition + " Aspect: " + e.aspect + " Key: " + e.key);
        }
      } else if (type.equals(ElementDataType.object)) {
        // this could be any value type
        // TODO: test for any of the above values...
        if (e.value.startsWith("\"") && !e.value.endsWith("\"")) {
          throw new IllegalArgumentException(
              "object value type is a malformed string! " + "TableId: " + e.tableId + " Partition: "
                  + e.partition + " Aspect: " + e.aspect + " Key: " + e.key);
        }
        if (e.value.startsWith("[") && !e.value.endsWith("]")) {
          throw new IllegalArgumentException(
              "object value type is a malformed array! " + "TableId: " + e.tableId + " Partition: "
                  + e.partition + " Aspect: " + e.aspect + " Key: " + e.key);
        }
        if (e.value.startsWith("{") && !e.value.endsWith("}")) {
          throw new IllegalArgumentException(
              "object value type is a malformed object! " + "TableId: " + e.tableId + " Partition: "
                  + e.partition + " Aspect: " + e.aspect + " Key: " + e.key);
        }
      } else {
        // and who knows what goes here...
      }
    }
  }

  /**
   * Insert or update a single table-level metadata KVS entry.
   * The tableId, partition, aspect and key cannot be null or empty strings.
   * If e.value is null or an empty string, the entry is deleted.
   *
   * @param db an open database connection to use
   * @param e  a KeyValueStoreEntry. If e.value is null or an empty string, the entry is deleted.
   */
  public static void replaceTableMetadata(OdkConnectionInterface db, KeyValueStoreEntry e) {
    validateKVSEntry(db.getAppName(), e.tableId, e);

    Map<String, Object> values = new TreeMap<>();
    values.put(KeyValueStoreColumns.TABLE_ID, e.tableId);
    values.put(KeyValueStoreColumns.PARTITION, e.partition);
    values.put(KeyValueStoreColumns.ASPECT, e.aspect);
    values.put(KeyValueStoreColumns.KEY, e.key);
    values.put(KeyValueStoreColumns.VALUE_TYPE, e.type);
    values.put(KeyValueStoreColumns.VALUE, e.value);

    Map<String, Object> metadataRev = new TreeMap<>();
    metadataRev.put(TableDefinitionsColumns.REV_ID, UUID.randomUUID().toString());

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }
      if (e.value == null || e.value.trim().isEmpty()) {
        deleteTableMetadata(db, e.tableId, e.partition, e.aspect, e.key);
      } else {
        db.replaceOrThrow(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME, null, values);
      }

      // Update the table definition table with a new revision ID, essentially telling all caches
      // of this table's metadata that they are dirty.
      db.update(DatabaseConstants.TABLE_DEFS_TABLE_NAME, metadataRev,
          K_TABLE_DEFS_TABLE_ID_EQUALS_PARAM, new Object[] { e.tableId });

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * The deletion filter includes all non-null arguments. If all arguments
   * (except the db) are null, then all properties are removed.
   *
   * @param db        an open database connection to use
   * @param tableId   the table to update
   * @param partition part of the kvs triplet
   * @param aspect    part of the kvs triplet
   * @param key       part of the kvs triplet
   */
  public static void deleteTableMetadata(OdkConnectionInterface db, String tableId,
      String partition, String aspect, String key) {

    StringBuilder b = new StringBuilder();
    Collection<String> selArgs = new ArrayList<>();
    if (tableId != null) {
      b.append(K_KVS_TABLE_ID_EQUALS_PARAM);
      selArgs.add(tableId);
    }
    if (partition != null) {
      if (b.length() != 0) {
        b.append(S_AND);
      }
      b.append(K_KVS_PARTITION_EQUALS_PARAM);
      selArgs.add(partition);
    }
    if (aspect != null) {
      if (b.length() != 0) {
        b.append(S_AND);
      }
      b.append(K_KVS_ASPECT_EQUALS_PARAM);
      selArgs.add(aspect);
    }
    if (key != null) {
      if (b.length() != 0) {
        b.append(S_AND);
      }
      b.append(K_KVS_KEY_EQUALS_PARAM);
      selArgs.add(key);
    }

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.delete(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME, b.toString(),
          selArgs.toArray(new String[selArgs.size()]));

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Filters results by all non-null field values.
   *
   * @param db        an open database connection to use
   * @param tableId   the table to update
   * @param partition part of the kvs triplet
   * @param aspect    part of the kvs triplet
   * @param key       part of the kvs triplet
   * @return the entire metadata for the table
   */
  public static TableMetaDataEntries getTableMetadata(OdkConnectionInterface db, String tableId,
      String partition, String aspect, String key) {

    TableMetaDataEntries metadata = new TableMetaDataEntries(tableId,
        getTableDefinitionRevId(db, tableId));

    Cursor c = null;
    try {
      StringBuilder b = new StringBuilder();
      Collection<String> selArgs = new ArrayList<>();
      if (tableId != null) {
        b.append(K_KVS_TABLE_ID_EQUALS_PARAM);
        selArgs.add(tableId);
      }
      if (partition != null) {
        if (b.length() != 0) {
          b.append(S_AND);
        }
        b.append(K_KVS_PARTITION_EQUALS_PARAM);
        selArgs.add(partition);
      }
      if (aspect != null) {
        if (b.length() != 0) {
          b.append(S_AND);
        }
        b.append(K_KVS_ASPECT_EQUALS_PARAM);
        selArgs.add(aspect);
      }
      if (key != null) {
        if (b.length() != 0) {
          b.append(S_AND);
        }
        b.append(K_KVS_KEY_EQUALS_PARAM);
        selArgs.add(key);
      }

      c = db.query(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME, null, b.toString(),
          selArgs.toArray(new String[selArgs.size()]), null, null, null, null);
      if (c.moveToFirst()) {
        int idxTableId = c.getColumnIndex(KeyValueStoreColumns.TABLE_ID);
        int idxPartition = c.getColumnIndex(KeyValueStoreColumns.PARTITION);
        int idxAspect = c.getColumnIndex(KeyValueStoreColumns.ASPECT);
        int idxKey = c.getColumnIndex(KeyValueStoreColumns.KEY);
        int idxType = c.getColumnIndex(KeyValueStoreColumns.VALUE_TYPE);
        int idxValue = c.getColumnIndex(KeyValueStoreColumns.VALUE);

        do {
          KeyValueStoreEntry e = new KeyValueStoreEntry();
          e.tableId = c.getString(idxTableId);
          e.partition = c.getString(idxPartition);
          e.aspect = c.getString(idxAspect);
          e.key = c.getString(idxKey);
          e.type = c.getString(idxType);
          e.value = c.getString(idxValue);
          metadata.addEntry(e);
        } while (c.moveToNext());
      }
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
    return metadata;
  }

  /**
   * Clean up the KVS row data types. This simplifies the migration process by
   * enforcing the proper data types regardless of what the values are in the
   * imported CSV files.
   *
   * @param db an open database connection to use
   */
  private static void enforceTypesTableMetadata(OdkConnectionInterface db) {

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      String sql = "UPDATE " + DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME + " SET "
          + KeyValueStoreColumns.VALUE_TYPE + S_EQUALS_PARAM + K_WHERE
          + K_KVS_PARTITION_EQUALS_PARAM + S_AND + K_KVS_KEY_EQUALS_PARAM;

      for (Object[] fields : knownKVSValueTypeRestrictions) {
        db.execSQL(sql, fields);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /*
   * Create a user defined database table metadata - table definition and KVS
   * values
   */
  private static void createTableMetadata(OdkConnectionInterface db, CharSequence tableId) {
    if (tableId == null || tableId.length() <= 0) {
      throw new IllegalArgumentException(
          TAG + ": application name and table name must be specified");
    }

    // Add the table id into table definitions
    Map<String, Object> cvTableDef = new TreeMap<>();
    cvTableDef.put(TableDefinitionsColumns.TABLE_ID, tableId);
    cvTableDef.put(TableDefinitionsColumns.REV_ID, UUID.randomUUID().toString());
    cvTableDef.put(TableDefinitionsColumns.SCHEMA_ETAG, null);
    cvTableDef.put(TableDefinitionsColumns.LAST_DATA_ETAG, null);
    cvTableDef.put(TableDefinitionsColumns.LAST_SYNC_TIME, -1);

    db.replaceOrThrow(DatabaseConstants.TABLE_DEFS_TABLE_NAME, null, cvTableDef);
  }

  /**
   * Create a user defined database table metadata - table definition and KVS
   * values
   *
   * @param db          an open database connection to use
   * @param tableId     the table to update
   * @param orderedDefs the columns that should be in the table
   */
  private static void createTableWithColumns(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedDefs, boolean isSynchronized) {

    if (tableId == null || tableId.length() <= 0) {
      throw new IllegalArgumentException(
          TAG + ": application name and table name must be specified");
    }

    StringBuilder createTableCmdWithCols = new StringBuilder();
    createTableCmdWithCols.append("CREATE TABLE IF NOT EXISTS ").append(tableId).append(" (");

    if (isSynchronized) {
      addMetadataFieldsToTableCreationStatement(createTableCmdWithCols);
    }

    boolean first = true;
    for (ColumnDefinition column : orderedDefs.getColumnDefinitions()) {
      if (!column.isUnitOfRetention()) {
        continue;
      }
      ElementType elementType = column.getType();

      ElementDataType dataType = elementType.getDataType();
      String dbType;
      if (dataType.equals(ElementDataType.array)) {
        dbType = "TEXT";
      } else if (dataType.equals(ElementDataType.bool)) {
        dbType = "INTEGER";
      } else if (dataType.equals(ElementDataType.configpath)) {
        dbType = "TEXT";
      } else if (dataType.equals(ElementDataType.integer)) {
        dbType = "INTEGER";
      } else if (dataType.equals(ElementDataType.number)) {
        dbType = "REAL";
      } else if (dataType.equals(ElementDataType.object) || dataType.equals(ElementDataType.rowpath)
          || dataType.equals(ElementDataType.string)) {
        dbType = "TEXT";
      } else {
        throw new IllegalStateException("unexpected ElementDataType: " + dataType.name());
      }

      if (!first || isSynchronized) {
        createTableCmdWithCols.append(", ");
      }

      createTableCmdWithCols.append(column.getElementKey()).append(" ").append(dbType)
          .append(" NULL");
      first = false;
    }

    createTableCmdWithCols.append(");");

    db.execSQL(createTableCmdWithCols.toString(), null);

    if (isSynchronized) {
      // Create the metadata for the table - table def and KVS
      createTableMetadata(db, tableId);

      // Now need to call the function to write out all the column values
      for (ColumnDefinition column : orderedDefs.getColumnDefinitions()) {
        createNewColumnMetadata(db, tableId, column);
      }
    }
  }

  /*
   * Create a new column metadata in the database - add column values to KVS and
   * column definitions
   */
  private static void createNewColumnMetadata(OdkConnectionInterface db, String tableId,
      ColumnDefinition column) {
    String colName = column.getElementKey();

    // Create column definition
    Map<String, Object> cvColDefVal = new TreeMap<>();
    cvColDefVal.put(ColumnDefinitionsColumns.TABLE_ID, tableId);
    cvColDefVal.put(ColumnDefinitionsColumns.ELEMENT_KEY, colName);
    cvColDefVal.put(ColumnDefinitionsColumns.ELEMENT_NAME, column.getElementName());
    cvColDefVal.put(ColumnDefinitionsColumns.ELEMENT_TYPE, column.getElementType());
    cvColDefVal
        .put(ColumnDefinitionsColumns.LIST_CHILD_ELEMENT_KEYS, column.getListChildElementKeys());

    // Now add this data into the database
    db.replaceOrThrow(DatabaseConstants.COLUMN_DEFINITIONS_TABLE_NAME, null, cvColDefVal);
  }

  /**
   * Verifies that the schema the client has matches that of the given tableId.
   *
   * @param db          an open database connection to use
   * @param tableId     the table to update
   * @param orderedDefs the columns that should be in the table
   */
  private static void verifyTableSchema(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedDefs) {
    // confirm that the column definitions are unchanged...
    OrderedColumns existingDefns = getUserDefinedColumns(db, tableId);
    if (existingDefns.getColumnDefinitions().size() != orderedDefs.getColumnDefinitions().size()) {
      throw new IllegalStateException(
          "Unexpectedly found tableId with different column definitions that already exists!");
    }
    for (ColumnDefinition ci : orderedDefs.getColumnDefinitions()) {
      ColumnDefinition existingDefn;
      try {
        existingDefn = existingDefns.find(ci.getElementKey());
      } catch (IllegalArgumentException e) {
        WebLogger.getLogger(db.getAppName()).printStackTrace(e);
        throw new IllegalStateException(
            "Unexpectedly failed to match elementKey: " + ci.getElementKey());
      }
      if (!existingDefn.getElementName().equals(ci.getElementName())) {
        throw new IllegalStateException(
            "Unexpected mis-match of elementName for elementKey: " + ci.getElementKey());
      }
      List<ColumnDefinition> refList = existingDefn.getChildren();
      List<ColumnDefinition> ciList = ci.getChildren();
      if (refList.size() != ciList.size()) {
        throw new IllegalStateException(
            "Unexpected mis-match of listOfStringElementKeys for elementKey: " + ci
                .getElementKey());
      }
      for (int i = 0; i < ciList.size(); ++i) {
        if (!refList.contains(ciList.get(i))) {
          throw new IllegalStateException(
              "Unexpected mis-match of listOfStringElementKeys[" + i + "] for elementKey: " + ci
                  .getElementKey());
        }
      }
      ElementType type = ci.getType();
      ElementType existingType = existingDefn.getType();
      if (!existingType.equals(type)) {
        throw new IllegalStateException(
            "Unexpected mis-match of elementType for elementKey: " + ci.getElementKey());
      }
    }
  }

  /**
   * Compute the app-global choiceListId for this choiceListJSON
   * and register the tuple of (choiceListId, choiceListJSON).
   * Return choiceListId.
   *
   * @param db             an open database connection to use
   * @param choiceListJSON -- the actual JSON choice list text.
   * @return choiceListId -- the unique code mapping to the choiceListJSON
   */
  public static String setChoiceList(OdkConnectionInterface db, String choiceListJSON) {
    ChoiceListUtils utils = new ChoiceListUtils();
    boolean dbWithinTransaction = db.inTransaction();
    boolean success = false;
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }
      if (choiceListJSON == null || choiceListJSON.trim().isEmpty()) {
        return null;
      }

      String choiceListId = ODKFileUtils.getNakedMd5Hash(db.getAppName(), choiceListJSON);

      utils.setChoiceList(db, choiceListId, choiceListJSON);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
      success = true;
      return choiceListId;
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
      if (!success) {

        WebLogger.getLogger(db.getAppName())
            .e(TAG, "setChoiceList: Error while updating choiceList entry " + choiceListJSON);
      }
    }
  }

  /**
   * Return the choice list JSON corresponding to the choiceListId
   *
   * @param db           an open database connection to use
   * @param choiceListId -- the md5 hash of the choiceListJSON
   * @return choiceListJSON -- the actual JSON choice list text.
   */
  public static String getChoiceList(OdkConnectionInterface db, String choiceListId) {
    ChoiceListUtils utils = new ChoiceListUtils();

    if (choiceListId == null || choiceListId.trim().isEmpty()) {
      return null;
    }
    return ChoiceListUtils.getChoiceList(db, choiceListId);
  }

  /**
   * If the tableId is not recorded in the TableDefinition metadata table, then
   * create the tableId with the indicated columns. This will synthesize
   * reasonable metadata KVS entries for table.
   * <p/>
   * If the tableId is present, then this is a no-op.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @param columns the columns of the new table
   * @return the ArrayList<ColumnDefinition> of the user columns in the table.
   */
  public static OrderedColumns createOrOpenTableWithColumns(OdkConnectionInterface db,
      String tableId, List<Column> columns) {
    boolean dbWithinTransaction = db.inTransaction();
    boolean success = false;

    OrderedColumns orderedDefs = new OrderedColumns(db.getAppName(), tableId, columns);
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }
      if (!hasTableId(db, tableId)) {
        createTableWithColumns(db, tableId, orderedDefs, true);
      } else {
        verifyTableSchema(db, tableId, orderedDefs);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
      success = true;
      return orderedDefs;
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
      if (!success) {

        // Get the names of the columns
        StringBuilder colNames = new StringBuilder();
        if (columns != null) {
          for (Column column : columns) {
            colNames.append(" ").append(column.getElementKey()).append(",");
          }
          if (colNames.length() > 0) {
            colNames.deleteCharAt(colNames.length() - 1);
            WebLogger.getLogger(db.getAppName()).e(TAG,
                "createOrOpenTableWithColumns: Error while adding table " + tableId
                    + " with columns:" + colNames);
          }
        } else {
          WebLogger.getLogger(db.getAppName()).e(TAG,
              "createOrOpenTableWithColumns: Error while adding table " + tableId
                  + " with columns: null");
        }
      }
    }
  }

  /**
   * SYNC only
   * <p/>
   * Call this when the schema on the server has changed w.r.t. the schema on
   * the device. In this case, we do not know whether the rows on the device
   * match those on the server.
   * <p/>
   * <ul>
   * <li>Reset all 'in_conflict' rows to their original local state (changed or
   * deleted).</li>
   * <li>Leave all 'deleted' rows in 'deleted' state.</li>
   * <li>Leave all 'changed' rows in 'changed' state.</li>
   * <li>Reset all 'synced' rows to 'new_row' to ensure they are sync'd to the
   * server.</li>
   * <li>Reset all 'synced_pending_files' rows to 'new_row' to ensure they are
   * sync'd to the server.</li>
   * </ul>
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   */
  private static void changeDataRowsToNewRowState(OdkConnectionInterface db, String tableId) {

    StringBuilder b = new StringBuilder();

    // remove server conflicting rows
    b.setLength(0);
    b.append("DELETE FROM ").append(tableId).append(K_WHERE).append(DataTableColumns.SYNC_STATE)
        .append(S_EQUALS_PARAM).append(S_AND).append(DataTableColumns.CONFLICT_TYPE)
        .append(" IN (?, ?)");

    String sqlConflictingServer = b.toString();
    //@formatter:off
    String argsConflictingServer[] = {
        SyncState.in_conflict.name(),
        Integer.toString(ConflictType.SERVER_DELETED_OLD_VALUES),
        Integer.toString(ConflictType.SERVER_UPDATED_UPDATED_VALUES)
      };
    //@formatter:on

    // update local delete conflicts to deletes
    b.setLength(0);
    //@formatter:off
    b.append("UPDATE ").append(tableId).append(" SET ")
      .append(DataTableColumns.SYNC_STATE).append(S_EQUALS_PARAM).append(", ")
      .append(DataTableColumns.CONFLICT_TYPE).append(" = null").append(K_WHERE)
      .append(DataTableColumns.CONFLICT_TYPE).append(S_EQUALS_PARAM);
    //@formatter:on

    String sqlConflictingLocalDeleting = b.toString();
    //@formatter:off
    String argsConflictingLocalDeleting[] = {
        SyncState.deleted.name(),
        Integer.toString(ConflictType.LOCAL_DELETED_OLD_VALUES)
      };
    //@formatter:on

    // update local update conflicts to updates
    //@formatter:off
    String argsConflictingLocalUpdating[] = {
        SyncState.changed.name(),
        Integer.toString(ConflictType.LOCAL_UPDATED_UPDATED_VALUES)
      };
    //@formatter:on

    // reset all 'rest' rows to 'insert'
    b.setLength(0);
    //@formatter:off
    b.append("UPDATE ").append(tableId).append(" SET ")
      .append(DataTableColumns.SYNC_STATE).append(S_EQUALS_PARAM).append(K_WHERE)
      .append(DataTableColumns.SYNC_STATE).append(S_EQUALS_PARAM);
    //@formatter:on

    String sqlRest = b.toString();
    //@formatter:off
    String argsRest[] = {
        SyncState.new_row.name(),
        SyncState.synced.name()
      };
    //@formatter:on

    //@formatter:off
    String argsRestPendingFiles[] = {
        SyncState.new_row.name(),
        SyncState.synced_pending_files.name()
      };
    //@formatter:on

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.execSQL(sqlConflictingServer, argsConflictingServer);
      db.execSQL(sqlConflictingLocalDeleting, argsConflictingLocalDeleting);
      db.execSQL(sqlConflictingLocalDeleting, argsConflictingLocalUpdating);
      db.execSQL(sqlRest, argsRest);
      db.execSQL(sqlRest, argsRestPendingFiles);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * SYNC
   * <p/>
   * Clean up this table and set the dataETag to null.
   * <p/>
   * changeDataRowsToNewRowState(sc.getAppName(), db, tableId);
   * <p/>
   * we need to clear out the dataETag so
   * that we will pull all server changes and sync our properties.
   * <p/>
   * updateTableETags(sc.getAppName(), db, tableId, null, null);
   * <p/>
   * Although the server does not recognize this tableId, we can
   * keep our record of the ETags for the table-level files and
   * manifest. These may enable us to short-circuit the restoration
   * of the table-level files should another client be simultaneously
   * trying to restore those files to the server.
   * <p/>
   * However, we do need to delete all the instance-level files,
   * as these are tied to the schemaETag we hold, and that is now
   * invalid.
   * <p/>
   * if the local table ever had any server sync information for this
   * host then clear it. If the user changed the server URL, we have
   * already cleared this information.
   * <p/>
   * Clearing it here handles the case where an admin deleted the
   * table on the server and we are now re-pushing that table to
   * the server.
   * <p/>
   * We do not know whether the rows on the device match those on the server.
   * We will find out later, in the course of the sync.
   * <p/>
   * if (tableInstanceFilesUri != null) {
   * deleteAllSyncETagsUnderServer(sc.getAppName(), db, tableInstanceFilesUri);
   * }
   *
   * @param db                    an open database connection to use
   * @param tableId               the table to update
   * @param schemaETag            The new schema etag
   * @param tableInstanceFilesUri TODO
   */
  public static void serverTableSchemaETagChanged(OdkConnectionInterface db, String tableId,
      String schemaETag, String tableInstanceFilesUri) {

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      changeDataRowsToNewRowState(db, tableId);

      privilegedUpdateTableETags(db, tableId, schemaETag, null);

      if (tableInstanceFilesUri != null) {
        SyncETagsUtils seu = new SyncETagsUtils();
        seu.deleteAllSyncETagsUnderServer(db, tableInstanceFilesUri);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Deletes the server conflict row (if any) for this rowId in this tableId.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @param rowId   which row in the table to update
   */
  private static void deleteServerConflictRowWithId(OdkConnectionInterface db, String tableId,
      String rowId) {
    // delete the old server-values in_conflict row if it exists
    StringBuilder b = new StringBuilder();
    b.append(K_DATATABLE_ID_EQUALS_PARAM).append(S_AND).append(DataTableColumns.SYNC_STATE)
        .append(S_EQUALS_PARAM).append(S_AND).append(DataTableColumns.CONFLICT_TYPE)
        .append(" IN ( ?, ? )");
    Object[] whereArgs = { rowId, SyncState.in_conflict.name(),
        String.valueOf(ConflictType.SERVER_DELETED_OLD_VALUES),
        String.valueOf(ConflictType.SERVER_UPDATED_UPDATED_VALUES) };

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.delete(tableId, b.toString(), whereArgs);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Checks if the two strings have the same value
   *
   * @param localValue  the local cell
   * @param serverValue the server cell
   * @param dt          the data type
   * @return whether the values are the same or not
   */
  @SuppressWarnings("MagicNumber")
  public static boolean identicalValue(String localValue, String serverValue, ElementDataType dt) {

    if (localValue == null && serverValue == null) {
      return true;
    } else if (localValue == null || serverValue == null) {
      return false;
    } else if (localValue.equals(serverValue)) {
      return true;
    }

    // NOT textually identical.
    //
    // Everything must be textually identical except possibly number fields
    // which may have rounding due to different database implementations,
    // data representations, and marshaling libraries.
    //
    if (dt.equals(ElementDataType.number)) {
      // !!Important!! Double.valueOf(str) handles NaN and +/-Infinity
      Double localNumber = Double.valueOf(localValue);
      Double serverNumber = Double.valueOf(serverValue);

      if (localNumber.equals(serverNumber)) {
        // simple case -- trailing zeros or string representation mix-up
        //
        return true;
      } else if (localNumber.isInfinite() && serverNumber.isInfinite()) {
        // if they are both plus or both minus infinity, we have a match
        //noinspection FloatingPointEquality
        return Math.signum(localNumber) == Math.signum(serverNumber);
      } else if (localNumber.isNaN() || localNumber.isInfinite() || serverNumber.isNaN()
          || serverNumber.isInfinite()) {
        // one or the other is special1
        return false;
      } else {
        double localDbl = localNumber;
        double serverDbl = serverNumber;
        //noinspection FloatingPointEquality
        if (localDbl == serverDbl) {
          return true;
        }
        // OK. We have two values like 9.80 and 9.8
        // consider them equal if they are adjacent to each other.
        double localNear = localDbl;
        int idist;
        int idistMax = 128;
        for (idist = 0; idist < idistMax; ++idist) {
          localNear = Math.nextAfter(localNear, serverDbl);
          //noinspection FloatingPointEquality
          if (localNear == serverDbl) {
            break;
          }
        }
        return idist < idistMax;
      }
    } else {
      // textual identity is required!
      return false;
    }
  }

  /**
   * Change the conflictType for the given row from null (not in conflict) to
   * the specified one.
   *
   * @param db           an open database connection to use
   * @param tableId      the table to update
   * @param rowId        which row in the table to update
   * @param conflictType expected to be one of ConflictType.LOCAL_DELETED_OLD_VALUES (0) or
   *                     ConflictType.LOCAL_UPDATED_UPDATED_VALUES (1)
   */
  private static void placeRowIntoConflict(OdkConnectionInterface db, String tableId, String rowId,
      int conflictType) {

    StringBuilder b = new StringBuilder();
    b.append(K_DATATABLE_ID_EQUALS_PARAM).append(S_AND).append(DataTableColumns.CONFLICT_TYPE)
        .append(S_IS_NULL);
    Object[] whereArgs = { rowId };

    Map<String, Object> cv = new TreeMap<>();
    cv.put(DataTableColumns.SYNC_STATE, SyncState.in_conflict.name());
    cv.put(DataTableColumns.CONFLICT_TYPE, conflictType);

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.update(tableId, cv, b.toString(), whereArgs);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Changes the conflictType for the given row from the specified one to null
   * and set the sync state of this row to the indicated value. In general, you
   * should first update the local conflict record with its new values, then
   * call deleteServerConflictRowWithId(...) and then call this method.
   *
   * @param db           an open database connection to use
   * @param tableId      the table to update
   * @param rowId        which row in the table to update
   * @param syncState    the current status of the sync
   * @param conflictType the type of conflict
   */
  private static void restoreRowFromConflict(OdkConnectionInterface db, String tableId,
      String rowId, @SuppressWarnings("TypeMayBeWeakened") SyncState syncState,
      Integer conflictType) {

    // TODO: is roleList applicable here?

    StringBuilder b = new StringBuilder();
    Object[] whereArgs;

    if (conflictType == null) {
      b.append(K_DATATABLE_ID_EQUALS_PARAM).append(S_AND).append(DataTableColumns.CONFLICT_TYPE)
          .append(S_IS_NULL);
      whereArgs = new Object[] { rowId };
    } else {
      b.append(K_DATATABLE_ID_EQUALS_PARAM).append(S_AND).append(DataTableColumns.CONFLICT_TYPE)
          .append(S_EQUALS_PARAM);
      whereArgs = new Object[] { rowId, conflictType };
    }

    Map<String, Object> cv = new TreeMap<>();
    cv.put(DataTableColumns.CONFLICT_TYPE, null);
    cv.put(DataTableColumns.SYNC_STATE, syncState.name());
    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.update(tableId, cv, b.toString(), whereArgs);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @param rowId   which row in the table to update
   * @return the sync state of the row (see {@link SyncState}), or null if the
   * row does not exist.  Rows are required to have non-null sync states.
   * @throws IllegalStateException if the row has a null sync state or has
   *                               2+ conflicts or checkpoints and
   *                               those do not have matching sync states!
   */
  public static SyncState getSyncState(OdkConnectionInterface db, String tableId, String rowId)
      throws IllegalStateException {
    Cursor c = null;
    try {
      c = db
          .query(tableId, new String[] { DataTableColumns.SYNC_STATE }, K_DATATABLE_ID_EQUALS_PARAM,
              new Object[] { rowId }, null, null, null, null);

      if (c.moveToFirst()) {
        int syncStateIndex = c.getColumnIndex(DataTableColumns.SYNC_STATE);
        if (c.isNull(syncStateIndex)) {
          throw new IllegalStateException(TAG + ": row had a null sync state!");
        }
        String val = CursorUtils.getIndexAsString(c, syncStateIndex);
        while (c.moveToNext()) {
          if (c.isNull(syncStateIndex)) {
            throw new IllegalStateException(TAG + ": row had a null sync state!");
          }
          String otherVal = CursorUtils.getIndexAsString(c, syncStateIndex);
          if (otherVal != null && !otherVal.equals(val)) {
            throw new IllegalStateException(TAG + ": row with 2+ conflicts or checkpoints does "
                + "not have matching sync states!");
          }
        }
        return SyncState.valueOf(val);
      }
      return null;
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
  }

  /**
   * Update all rows for the given rowId to SavepointType 'INCOMPLETE' and
   * remove all but the most recent row. When used with a rowId that has
   * checkpoints, this updates to the most recent checkpoint and removes any
   * earlier checkpoints, incomplete or complete savepoints. Otherwise, it has
   * the general effect of resetting the rowId to an INCOMPLETE state.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @param rowId   which row in the table to update
   */
  public static void saveAsIncompleteMostRecentCheckpointRowWithId(OdkConnectionInterface db,
      String tableId, String rowId) {

    // TODO: if user becomes unverified, we still allow them to save-as-incomplete ths record.
    // Is this the behavior we want?  I think it would be difficult to explain otherwise.

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      StringBuilder b = new StringBuilder();
      b.append("UPDATE ").append(tableId).append(" SET ").append(DataTableColumns.SAVEPOINT_TYPE)
          .append(S_EQUALS_PARAM).append(K_WHERE).append(K_DATATABLE_ID_EQUALS_PARAM);
      db.execSQL(b.toString(), new Object[] { SavepointTypeManipulator.incomplete(), rowId });
      b.setLength(0);
      b.append(K_DATATABLE_ID_EQUALS_PARAM).append(S_AND)
          .append(DataTableColumns.SAVEPOINT_TIMESTAMP).append(" NOT IN (SELECT MAX(")
          .append(DataTableColumns.SAVEPOINT_TIMESTAMP).append(") FROM ").append(tableId)
          .append(K_WHERE).append(K_DATATABLE_ID_EQUALS_PARAM).append(")");
      db.delete(tableId, b.toString(), new Object[] { rowId, rowId });

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Update all rows for the given rowId to SavepointType 'COMPLETE' and
   * remove all but the most recent row. When used with a rowId that has
   * checkpoints, this updates to the most recent checkpoint and removes any
   * earlier checkpoints, incomplete or complete savepoints. Otherwise, it has
   * the general effect of resetting the rowId to an COMPLETE state.
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @param rowId   which row in the table to update
   */
  public static void saveAsCompleteMostRecentCheckpointRowWithId(OdkConnectionInterface db,
      String tableId, String rowId) {

    // TODO: if user becomes unverified, we still allow them to save-as-complete ths record.
    // Is this the behavior we want?  I think it would be difficult to explain otherwise.

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      StringBuilder b = new StringBuilder();
      b.append("UPDATE ").append(tableId).append(" SET ").append(DataTableColumns.SAVEPOINT_TYPE)
          .append(S_EQUALS_PARAM).append(K_WHERE).append(K_DATATABLE_ID_EQUALS_PARAM);
      db.execSQL(b.toString(), new Object[] { SavepointTypeManipulator.complete(), rowId });

      b.setLength(0);
      b.append(K_DATATABLE_ID_EQUALS_PARAM).append(S_AND)
          .append(DataTableColumns.SAVEPOINT_TIMESTAMP).append(" NOT IN (SELECT MAX(")
          .append(DataTableColumns.SAVEPOINT_TIMESTAMP).append(") FROM ").append(tableId)
          .append(K_WHERE).append(K_DATATABLE_ID_EQUALS_PARAM).append(")");
      db.delete(tableId, b.toString(), new Object[] { rowId, rowId });

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Gets the users permission on a particular table
   *
   * @param db         A database connection to use
   * @param tableId    the id of the table to get the access context for
   * @param activeUser the current user
   * @param rolesList  the list of roles the user has
   * @return the access context for that user on that table
   */
  public static AccessContext getAccessContext(OdkConnectionInterface db, String tableId,
      String activeUser, String rolesList) {

    // figure out whether we have a privileged user or not
    List<String> rolesArray = getRolesArray(rolesList);

    if (tableId == null) {
      return new AccessContext(AccessColumnType.NO_EFFECTIVE_ACCESS_COLUMN, false, activeUser,
          rolesArray);
    }
    if (tableId.trim().isEmpty()) {
      throw new IllegalArgumentException("tableId can be null but cannot be blank");
    }

    Boolean isLocked = false;
    {
      ArrayList<KeyValueStoreEntry> lockedList = getTableMetadata(db, tableId,
          KeyValueStoreConstants.PARTITION_TABLE, LocalKeyValueStoreConstants.TableSecurity.ASPECT,
          LocalKeyValueStoreConstants.TableSecurity.KEY_LOCKED).getEntries();

      if (!lockedList.isEmpty()) {
        if (lockedList.size() != 1) {
          throw new IllegalStateException("should be impossible");
        }

        isLocked = KeyValueStoreUtils.getBoolean(lockedList.get(0));
      }
    }

    AccessColumnType accessColumnType = isLocked ?
        AccessColumnType.LOCKED_EFFECTIVE_ACCESS_COLUMN :
        AccessColumnType.UNLOCKED_EFFECTIVE_ACCESS_COLUMN;
    boolean canCreateRow = false;
    if (isLocked) {
      // only super-user or tables administrator can create rows in locked tables.
      if (rolesList != null) {
        canCreateRow = rolesList.contains(RoleConsts.ROLE_SUPER_USER) || rolesList
            .contains(RoleConsts.ROLE_ADMINISTRATOR);
      }

    } else if (rolesList == null) {
      // this is the unverified user case. By default, they can create rows.
      // Administrator can use table properties to manage that capability.
      canCreateRow = true;
      ArrayList<KeyValueStoreEntry> canUnverifiedCreateList = getTableMetadata(db, tableId,
          KeyValueStoreConstants.PARTITION_TABLE, LocalKeyValueStoreConstants.TableSecurity.ASPECT,
          LocalKeyValueStoreConstants.TableSecurity.KEY_UNVERIFIED_USER_CAN_CREATE).getEntries();

      if (!canUnverifiedCreateList.isEmpty()) {
        if (canUnverifiedCreateList.size() != 1) {
          throw new IllegalStateException("should be impossible");
        }

        // TODO why doesn't the linter like this?
        canCreateRow = KeyValueStoreUtils.getBoolean(canUnverifiedCreateList.get(0));
      }
    } else {
      canCreateRow = true;
    }

    return new AccessContext(accessColumnType, canCreateRow, activeUser, rolesArray);
  }

  /**
   * Get a {@link BaseTable} for this table based on the given sql query. All
   * columns from the table are returned.  Up to sqlLimit rows are returned
   * (zero is infinite).
   * <p/>
   * The result set is filtered according to the supplied rolesList if there
   * is a DEFAULT_ACCESS column present in the result set.
   *
   * @param db             an open database connection to use
   * @param tableId        the id of the table to query on
   * @param sqlCommand     the query to run
   * @param sqlBindArgs    the selection parameters
   * @param sqlQueryBounds offset and max number of rows to return (zero is infinite)
   * @param accessContext  for managing what effective accesses to return
   * @return a table with the query results in it
   */
  public static BaseTable query(OdkConnectionInterface db, String tableId, String sqlCommand,
      Object[] sqlBindArgs, QueryBounds sqlQueryBounds, AccessContext accessContext) {

    Cursor c = null;
    try {
      c = rawQuery(db, sqlCommand, sqlBindArgs, sqlQueryBounds, accessContext);
      return buildBaseTable(db, c, tableId, accessContext.canCreateRow);
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
  }

  /**
   * Get a {@link BaseTable} for this table based on the given sql query. All
   * columns from the table are returned.
   * <p/>
   * The number of rows returned are limited to no greater than the sqlLimit (zero is infinite).
   *
   * @param db             an open database connection to use
   * @param tableId        the table id to query
   * @param sqlCommand     the query to run
   * @param sqlBindArgs    the selection parameters
   * @param sqlQueryBounds the number of rows to return (zero is infinite)
   * @param accessContext  for managing what effective accesses to return
   * @return the results of the query in a BaseTable
   */
  public static BaseTable privilegedQuery(OdkConnectionInterface db, String tableId,
      String sqlCommand, Object[] sqlBindArgs, QueryBounds sqlQueryBounds,
      AccessContext accessContext) {

    if (!accessContext.isPrivilegedUser) {
      accessContext = accessContext.copyAsPrivilegedUser();
    }
    return query(db, tableId, sqlCommand, sqlBindArgs, sqlQueryBounds, accessContext);
  }

  /************** LOCAL ONLY TABLE OPERATIONS ***************/

  private static BaseTable buildBaseTable(OdkConnectionInterface db, Cursor c, String tableId,
      boolean canCreateRow) {

    HashMap<String, Integer> mElementKeyToIndex = null;
    String[] mElementKeyForIndex = null;

    if (!c.moveToFirst()) {

      // Attempt to retrieve the columns from the cursor.
      // These may not be available if there were no rows returned.
      // It depends upon the cursor implementation.
      try {
        int columnCount = c.getColumnCount();
        mElementKeyForIndex = new String[columnCount];
        mElementKeyToIndex = new HashMap<>(columnCount);
        int i;

        for (i = 0; i < columnCount; ++i) {
          String columnName = c.getColumnName(i);
          mElementKeyForIndex[i] = columnName;
          mElementKeyToIndex.put(columnName, i);
        }
      } catch (Exception ignored) {
        // ignore.
      }

      // if they were not available, declare an empty array.
      if (mElementKeyForIndex == null) {
        mElementKeyForIndex = new String[0];
      }
      c.close();

      // we have no idea what the table should contain because it has no rows...
      BaseTable table = new BaseTable(null, mElementKeyForIndex, mElementKeyToIndex, 0);
      table.setEffectiveAccessCreateRow(canCreateRow);
      return table;
    }

    int rowCount = c.getCount();
    int columnCount = c.getColumnCount();

    BaseTable table;

    // These maps will map the element key to the corresponding index in
    // either data or metadata. If the user has defined a column with the
    // element key _my_data, and this column is at index 5 in the data
    // array, dataKeyToIndex would then have a mapping of _my_data:5.
    // The sync_state column, if present at index 7, would have a mapping
    // in metadataKeyToIndex of sync_state:7.
    mElementKeyForIndex = new String[columnCount];
    mElementKeyToIndex = new HashMap<>(columnCount);

    int i;

    for (i = 0; i < columnCount; ++i) {
      String columnName = c.getColumnName(i);
      mElementKeyForIndex[i] = columnName;
      mElementKeyToIndex.put(columnName, i);
    }

    table = new BaseTable(null, mElementKeyForIndex, mElementKeyToIndex, rowCount);

    String[] rowData = new String[columnCount];
    do {
      // First get the user-defined data for this row.
      for (i = 0; i < columnCount; i++) {
        String value = CursorUtils.getIndexAsString(c, i);
        rowData[i] = value;
      }

      Row nextRow = new Row(rowData.clone(), table);
      table.addRow(nextRow);
    } while (c.moveToNext());
    c.close();

    table.setEffectiveAccessCreateRow(canCreateRow);

    if (tableId != null) {
      table.setMetaDataRev(getTableDefinitionRevId(db, tableId));
    }
    return table;
  }

  /**
   * Create a local only table and prepend the given id with an "L_"
   *
   * @param db      an open database connection to use
   * @param tableId the table to update
   * @param columns the columns we want in the results
   * @return the columns that were created in an OrderedColumns object
   */
  public static OrderedColumns createLocalOnlyTableWithColumns(OdkConnectionInterface db,
      String tableId, List<Column> columns) {

    boolean dbWithinTransaction = db.inTransaction();
    boolean success = false;

    OrderedColumns orderedDefs = new OrderedColumns(db.getAppName(), tableId, columns);
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      createTableWithColumns(db, tableId, orderedDefs, false);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
      success = true;
      return orderedDefs;
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
      if (!success) {

        // Get the names of the columns
        StringBuilder colNames = new StringBuilder();
        if (columns != null) {
          for (Column column : columns) {
            colNames.append(" ").append(column.getElementKey()).append(",");
          }
          if (colNames.length() > 0) {
            colNames.deleteCharAt(colNames.length() - 1);
            WebLogger.getLogger(db.getAppName()).e(TAG,
                "createLocalOnlyTableWithColumns: Error while adding table " + tableId
                    + " with columns:" + colNames);
          }
        } else {
          WebLogger.getLogger(db.getAppName()).e(TAG,
              "createLocalOnlyTableWithColumns: Error while adding table " + tableId
                  + " with columns: null");
        }
      }
    }
  }

  /**
   * Return the row(s) for the given tableId and rowId. If the row has
   * checkpoints or conflicts, the returned BaseTable will have more than one
   * Row returned. Otherwise, it will contain a single row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles that that user has
   * @return the requested rows in a BaseTable
   */
  public static BaseTable getRowsWithId(OdkConnectionInterface db, String tableId, String rowId,
      String activeUser, String rolesList) {

    AccessContext accessContext = getAccessContext(db, tableId, activeUser, rolesList);

    return query(db, tableId, QueryUtil.buildSqlStatement(tableId, QueryUtil.GET_ROWS_WITH_ID_WHERE,
        QueryUtil.GET_ROWS_WITH_ID_GROUP_BY, QueryUtil.GET_ROWS_WITH_ID_HAVING,
        QueryUtil.GET_ROWS_WITH_ID_ORDER_BY_KEYS, QueryUtil.GET_ROWS_WITH_ID_ORDER_BY_DIR),
        new String[] { rowId }, null, accessContext);
  }

  /**
   * Return the row(s) for the given tableId and rowId. If the row has
   * checkpoints or conflicts, the returned BaseTable will have more than one
   * Row returned. Otherwise, it will contain a single row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @return the requested rows in a BaseTable
   */
  public static BaseTable privilegedGetRowsWithId(OdkConnectionInterface db, String tableId,
      String rowId, String activeUser) {

    AccessContext accessContext = getAccessContext(db, tableId, activeUser,
        RoleConsts.ADMIN_ROLES_LIST);

    return privilegedQuery(db, tableId, QueryUtil
            .buildSqlStatement(tableId, QueryUtil.GET_ROWS_WITH_ID_WHERE,
                QueryUtil.GET_ROWS_WITH_ID_GROUP_BY, QueryUtil.GET_ROWS_WITH_ID_HAVING,
                QueryUtil.GET_ROWS_WITH_ID_ORDER_BY_KEYS, QueryUtil.GET_ROWS_WITH_ID_ORDER_BY_DIR),
        new String[] { rowId }, null, accessContext);
  }

  /**
   * Return the row with the most recent changes for the given tableId and rowId.
   * If the rowId does not exist, it returns an empty BaseTable for this tableId.
   * If the row has conflicts, it throws an exception. Otherwise, it returns the
   * most recent checkpoint or non-checkpoint value; it will contain a single row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles that the user has
   * @return the requested row in a base table
   */
  public static BaseTable getMostRecentRowWithId(OdkConnectionInterface db, String tableId,
      String rowId, String activeUser, String rolesList) {

    BaseTable table = getRowsWithId(db, tableId, rowId, activeUser, rolesList);

    if (table.getNumberOfRows() == 0) {
      return table;
    }

    // most recent savepoint timestamp...
    BaseTable t = new BaseTable(table, Collections.singletonList(0));

    if (hasConflictRows(t)) {
      throw new IllegalStateException("row is in conflict");
    }
    return t;
  }

  /**
   * Return the row with the most recent changes for the given tableId and rowId.
   * If the rowId does not exist, it returns an empty BaseTable for this tableId.
   * If the row has conflicts, it throws an exception. Otherwise, it returns the
   * most recent checkpoint or non-checkpoint value; it will contain a single row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @return the requested row in a base table
   */
  public static BaseTable privilegedGetMostRecentRowWithId(OdkConnectionInterface db,
      String tableId, String rowId, String activeUser) {

    BaseTable table = privilegedGetRowsWithId(db, tableId, rowId, activeUser);

    if (table.getNumberOfRows() == 0) {
      return table;
    }

    // most recent savepoint timestamp...
    BaseTable t = new BaseTable(table, Collections.singletonList(0));

    if (hasConflictRows(t)) {
      throw new IllegalStateException("row is in conflict");
    }
    return t;
  }

  /**
   * Insert or update a list of table-level metadata KVS entries. If clear is
   * true, then delete the existing set of values for this tableId before
   * inserting the new values.
   *
   * @param db       an open database connection to use
   * @param tableId  the table to update
   * @param metadata a List<KeyValueStoreEntry>
   * @param clear    if true then delete the existing set of values for this tableId
   *                 before inserting the new ones.
   */
  public static void replaceTableMetadata(OdkConnectionInterface db, String tableId,
      Iterable<KeyValueStoreEntry> metadata, boolean clear) {

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      if (clear) {
        db.delete(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME, K_KVS_TABLE_ID_EQUALS_PARAM,
            new Object[] { tableId });
      }

      for (KeyValueStoreEntry e : metadata) {
        replaceTableMetadata(db, e);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * replaces one partition of the the table metadata
   *
   * @param db        a database interface to use
   * @param tableId   the id of the table to update
   * @param partition part of the kvs triplet
   * @param aspect    part of the kvs triplet
   * @param metadata  the new metadata
   */
  public static void replaceTableMetadataSubList(OdkConnectionInterface db, String tableId,
      String partition, String aspect, Iterable<KeyValueStoreEntry> metadata) {

    StringBuilder b = new StringBuilder();
    Collection<Object> whereArgsList = new ArrayList<>();

    if (tableId == null || tableId.trim().isEmpty()) {
      throw new IllegalArgumentException("tableId cannot be null or an empty string");
    }
    b.append(K_KVS_TABLE_ID_EQUALS_PARAM);
    whereArgsList.add(tableId);
    if (partition != null) {
      b.append(S_AND).append(K_KVS_PARTITION_EQUALS_PARAM);
      whereArgsList.add(partition);
    }
    if (aspect != null) {
      b.append(S_AND).append(K_KVS_ASPECT_EQUALS_PARAM);
      whereArgsList.add(aspect);
    }
    String whereClause = b.toString();
    Object[] whereArgs = whereArgsList.toArray(new Object[whereArgsList.size()]);

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      db.delete(DatabaseConstants.KEY_VALUE_STORE_ACTIVE_TABLE_NAME, whereClause, whereArgs);

      for (KeyValueStoreEntry e : metadata) {
        replaceTableMetadata(db, e);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * If the tableId is not recorded in the TableDefinition metadata table, then
   * create the tableId with the indicated columns. This will synthesize
   * reasonable metadata KVS entries for table.
   * <p/>
   * If the tableId is present, then this is a no-op.
   *
   * @param db       an open database connection to use
   * @param tableId  the table to update
   * @param columns  the columns that should be in the table
   * @param metaData the table metadata
   * @param clear    whether or not to clear the existing metadata
   * @return the ArrayList<ColumnDefinition> of the user columns in the table.
   */
  public static OrderedColumns createOrOpenTableWithColumnsAndProperties(OdkConnectionInterface db,
      String tableId, List<Column> columns, Iterable<KeyValueStoreEntry> metaData, boolean clear) {
    boolean dbWithinTransaction = db.inTransaction();
    boolean success = false;

    OrderedColumns orderedDefs = new OrderedColumns(db.getAppName(), tableId, columns);

    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }
      boolean created = false;
      if (!hasTableId(db, tableId)) {
        createTableWithColumns(db, tableId, orderedDefs, true);
        created = true;
      } else {
        // confirm that the column definitions are unchanged...
        verifyTableSchema(db, tableId, orderedDefs);
      }

      replaceTableMetadata(db, tableId, metaData, clear || created);
      enforceTypesTableMetadata(db);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
      success = true;
      return orderedDefs;
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
      if (!success) {

        // Get the names of the columns
        StringBuilder colNames = new StringBuilder();
        if (columns != null) {
          for (Column column : columns) {
            colNames.append(" ").append(column.getElementKey()).append(",");
          }
          if (colNames.length() > 0) {
            colNames.deleteCharAt(colNames.length() - 1);
            WebLogger.getLogger(db.getAppName()).e(TAG,
                "createOrOpenTableWithColumnsAndProperties: Error while adding table " + tableId
                    + " with columns:" + colNames);
          }
        } else {
          WebLogger.getLogger(db.getAppName()).e(TAG,
              "createOrOpenTableWithColumnsAndProperties: Error while adding table " + tableId
                  + " with columns: null");
        }
      }
    }
  }

  private static void insertValueIntoContentValues(Map<String, Object> cv, Class<?> theClass,
      String name, Object obj) {

    if (obj == null) {
      cv.put(name, null);
      return;
    }

    // Couldn't use the CursorUtils.getIndexAsType
    // because assigning the result to Object v
    // would not work for the currValues.put function
    if (theClass == Long.class || theClass == Integer.class || theClass == Double.class
        || theClass == String.class || theClass == Boolean.class || theClass == ArrayList.class
        || theClass == HashMap.class) {
      cv.put(name, obj);
    } else {
      throw new IllegalStateException("Unexpected data type in SQLite table " + theClass);
    }
  }

  /**
   * Update the ETag and SyncState of a given rowId. There should be exactly one
   * record for this rowId in the database (i.e., no conflicts or checkpoints).
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param rowETag    the new eTag for the row
   * @param state      the current sync state
   * @param activeUser the currently logged in user
   * @return true if rowId exists. False otherwise.
   */
  public static boolean privilegedUpdateRowETagAndSyncState(OdkConnectionInterface db,
      String tableId, String rowId, String rowETag,
      @SuppressWarnings("TypeMayBeWeakened") SyncState state, String activeUser) {

    String whereClause = K_DATATABLE_ID_EQUALS_PARAM;
    Object[] whereArgs = { rowId };

    Map<String, Object> cvDataTableVal = new TreeMap<>();

    cvDataTableVal.put(DataTableColumns.ROW_ETAG, rowETag);
    cvDataTableVal.put(DataTableColumns.SYNC_STATE, state.name());

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      AccessContext accessContext = getAccessContext(db, tableId, activeUser,
          RoleConsts.ADMIN_ROLES_LIST);

      BaseTable data = privilegedQuery(db, tableId, K_SELECT_FROM + tableId + K_WHERE + whereClause,
          whereArgs, null, accessContext);

      // There must be only one row in the db
      if (data.getNumberOfRows() != 1) {
        return false;
      }

      db.update(tableId, cvDataTableVal, whereClause, whereArgs);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
      return true;
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Delete any prior server conflict row.
   * Move the local row into the indicated local conflict state.
   * Insert a server row with the values specified in the cvValues array.
   *
   * @param db                   an open database connection to use
   * @param tableId              the table to update
   * @param orderedColumns       the columns of the table that has the conflict
   * @param serverValues         the values as the server reports them (ignoring client side changes)
   * @param rowId                which row in the table to update
   * @param localRowConflictType the type of conflict
   * @param activeUser           the currently logged in user
   * @param locale               the users locale
   */
  public static void privilegedPlaceRowIntoConflictWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, ContentValues serverValues, String rowId,
      int localRowConflictType, String activeUser, String locale) {

    // The rolesList of the activeUser does not impact the execution of this action.
    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      deleteServerConflictRowWithId(db, tableId, rowId);
      placeRowIntoConflict(db, tableId, rowId, localRowConflictType);
      privilegedInsertRowWithId(db, tableId, orderedColumns, serverValues, rowId, activeUser,
          locale, false);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * Delete any prior server conflict row.
   * Examine database and incoming server values to determine how to apply the
   * server values to the database. This might delete the existing row, update
   * it, or create a conflict row.
   *
   * @param db             an open database connection to use
   * @param tableId        the table to update
   * @param orderedColumns the columns of the row
   * @param serverValues   field values for this row coming from server.
   *                       All fields must have values. The SyncState field
   *                       (a local field that does not come from the server)
   *                       should be "changed" or "deleted"
   * @param rowId          which row in the table to update
   * @param activeUser     the currently logged in user
   * @param rolesList      passed in to determine if the current user is a privileged user
   * @param locale         the users selected locale
   */
  public static void privilegedPerhapsPlaceRowIntoConflictWithId(OdkConnectionInterface db, String
      tableId,
      OrderedColumns orderedColumns, ContentValues serverValues, String rowId, String activeUser,
      String rolesList, String locale) {

    AccessContext accessContext = getAccessContext(db, tableId, activeUser, rolesList);

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      // The rolesList of the activeUser does not impact the execution of this portion of
      // the action.

      // PLAN:
      // (1) delete any existing server-conflict row.
      // (2) if the local row was synced or synced pending changes, then either delete
      //     the row from the device (if server deleted row) or accept the
      //     server changes and place the row into synced_pending_changes status.
      // (3) if the local row was in the new_row state, move it into changed (prior
      //     to creating a conflict pair in step 5)
      // (4) if the local row was in conflict, restore it to its pre-conflict state
      //     (either deleted or changed).
      // (5) move the local row into conflict and insert the server row, placing it
      //     into conflict.
      // (6) enforce permissions on the change. This may immediately resolve conflict
      //     by taking the server changes or may overwrite the local row's permissions
      //     column values with those from the server.
      // (7) optimize the conflict -- perhaps immediately resolving it based upon
      //     whether the user actually has the privileges to do anything other than
      //     taking the server changes or if the changes only update the tracking
      //     and (perhaps) the metadata fields.

      // Do it...

      // (1) delete any existing server conflict row
      deleteServerConflictRowWithId(db, tableId, rowId);
      // fetch the current local (possibly-in-conflict) row
      BaseTable baseTable = privilegedGetRowsWithId(db, tableId, rowId, activeUser);
      // throws will abort the transaction, rolling back these changes
      if (baseTable.getNumberOfRows() == 0) {
        throw new IllegalArgumentException("no matching row found for server conflict");
      } else if (baseTable.getNumberOfRows() != 1) {
        throw new IllegalArgumentException("row has checkpoints or database is corrupt");
      }

      Row localRow = baseTable.getRowAtIndex(0);

      if (localRow.getDataByKey(DataTableColumns.SAVEPOINT_TYPE) == null) {
        throw new IllegalArgumentException("row has checkpoints");
      }

      boolean isServerRowDeleted = serverValues.getAsString(DataTableColumns.SYNC_STATE)
          .equals(SyncState.deleted.name());

      SyncState state;
      {
        String strSyncState = localRow.getDataByKey(DataTableColumns.SYNC_STATE);
        state = SyncState.valueOf(strSyncState);
      }
      SyncState initialLocalRowState = state;

      if (state == SyncState.synced || state == SyncState.synced_pending_files) {
        // (2) if the local row was synced or synced pending changes, then either delete
        //     the row from the device (if server deleted row) or accept the
        //     server changes and place the row into synced_pending_changes status.

        // the server's change should be applied locally.
        if (isServerRowDeleted) {
          privilegedDeleteRowWithId(db, tableId, rowId, activeUser);
        } else {
          // Local row needs to be updated with server values.
          //
          // detect and handle file attachment column changes
          if (state == SyncState.synced) {
            // determine whether there are any changes in the columns that hold file attachments.
            // if there are, then we need to transition into synced_pending_files. Otherwise, we
            // can remain in the synced state.

            for (ColumnDefinition cd : orderedColumns.getColumnDefinitions()) {
              // todo: does not handle array containing (types containing) rowpath elements
              if (cd.isUnitOfRetention() && cd.getType().getDataType()
                  .equals(ElementDataType.rowpath)) {
                String uriFragment = serverValues.getAsString(cd.getElementKey());
                String localUriFragment = localRow.getDataByKey(cd.getElementKey());
                if (uriFragment != null) {
                  if (!uriFragment.equals(localUriFragment)) {
                    state = SyncState.synced_pending_files;
                    WebLogger.getLogger(db.getAppName()).i(TAG,
                        "privilegedPerhapsPlaceRowIntoConflictWithId: revising from synced to "
                            + "synced_pending_files");
                    break;
                  }
                }
              }
            }
          }

          // update the row from the changes on the server
          serverValues.put(DataTableColumns.SYNC_STATE, state.name());
          serverValues.putNull(DataTableColumns.CONFLICT_TYPE);
          privilegedUpdateRowWithId(db, tableId, orderedColumns, serverValues, rowId, activeUser,
              locale, false);
        }

        // In either case (delete or sync outcome), be sure to commit our changes!!!!
        if (!dbWithinTransaction) {
          db.setTransactionSuccessful();
        }
        return;

      } else if (state == SyncState.new_row) {
        // (3) if the local row was in the new_row state, move it into changed (prior
        //     to creating a conflict pair in step 5)

        // update the row with all of the local columns as-is, except the sync state.
        ContentValues values = new ContentValues();
        for (int i = 0; i < baseTable.getWidth(); ++i) {
          String colName = baseTable.getElementKey(i);
          if (DataTableColumns.EFFECTIVE_ACCESS.equals(colName)) {
            continue;
          }
          if (localRow.getDataByIndex(i) == null) {
            values.putNull(colName);
          } else {
            values.put(colName, localRow.getDataByIndex(i));
          }
        }
        // move this into the changed state...
        state = SyncState.changed;
        values.put(DataTableColumns.SYNC_STATE, state.name());

        privilegedUpdateRowWithId(db, tableId, orderedColumns, values, rowId,
            accessContext.activeUser, locale, false);

      } else if (state == SyncState.in_conflict) {
        // (4) if the local row was in conflict, restore it to its pre-conflict state
        //     (either deleted or changed).

        // we need to remove the in_conflict records that refer to the
        // prior state of the server
        String localRowConflictTypeBeforeSyncStr = localRow
            .getDataByKey(DataTableColumns.CONFLICT_TYPE);
        if (localRowConflictTypeBeforeSyncStr == null) {
          // this row is in conflict. It MUST have a non-null conflict type.
          throw new IllegalStateException("conflict type is null on an in-conflict row");
        }

        int localRowConflictTypeBeforeSync = Integer.parseInt(localRowConflictTypeBeforeSyncStr);
        if (localRowConflictTypeBeforeSync == ConflictType.SERVER_DELETED_OLD_VALUES
            || localRowConflictTypeBeforeSync == ConflictType.SERVER_UPDATED_UPDATED_VALUES) {
          // should be impossible
          throw new IllegalStateException("only the local conflict record should remain");
        }

        // move the local conflict back into the normal non-conflict (null) state
        // set the sync state to "changed" temporarily (otherwise we can't update)

        state = localRowConflictTypeBeforeSync == ConflictType.LOCAL_DELETED_OLD_VALUES ?
            SyncState.deleted :
            SyncState.changed;

        restoreRowFromConflict(db, tableId, rowId, state, localRowConflictTypeBeforeSync);
      }
      // and drop through if SyncState is changed or deleted

      // (5) move the local row into conflict and insert the server row, placing it
      //     into conflict.
      int localRowConflictType = state == SyncState.deleted ?
          ConflictType.LOCAL_DELETED_OLD_VALUES :
          ConflictType.LOCAL_UPDATED_UPDATED_VALUES;

      placeRowIntoConflict(db, tableId, rowId, localRowConflictType);

      serverValues.put(DataTableColumns.SYNC_STATE, SyncState.in_conflict.name());
      serverValues.put(DataTableColumns.CONFLICT_TYPE, isServerRowDeleted ?
          ConflictType.SERVER_DELETED_OLD_VALUES :
          ConflictType.SERVER_UPDATED_UPDATED_VALUES);
      privilegedInsertRowWithId(db, tableId, orderedColumns, serverValues, rowId, activeUser,
          locale, false);

      // To get here, the original local row was in some state other than synced or
      // synced_pending_files. Therefore, any non-empty rowpath fields should drive
      // the row into the synced_pending_files state if we resolve the row early.

      // (6) enforce permissions on the change. This may immediately resolve conflict
      //     by taking the server changes or may overwrite the local row's permissions
      //     column values with those from the server.
      if (enforcePermissionsAndOptimizeConflictProcessing(db, tableId, orderedColumns, rowId,
          initialLocalRowState, accessContext, locale)) {
        // and...
        // (7) optimize the conflict -- perhaps immediately resolving it based upon
        //     whether the user actually has the privileges to do anything other than
        //     taking the server changes or if the changes only update the tracking
        //     and (perhaps) the metadata fields.
        optimizeConflictProcessing(db, tableId, orderedColumns, rowId, initialLocalRowState,
            accessContext, locale);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * If the latest row-level permissions from the server prevent the activeUser from
   * performing the modify or delete action on the row, immediately resolve the conflict
   * by taking the server's changes.
   * <p>
   * If the latest row-level permissions from the server prevent the activeUser from
   * altering the permissions on the row, reset all of those permissions to match
   * the server's latest values.
   * <p>
   * And, finally, optimize the conflict -- perhaps immediately resolving it based upon
   * whether the user actually has the privileges to do anything other than
   * taking the server changes or if the changes only update the tracking
   * and (perhaps) the metadata fields.
   *
   * @param db                   an open database connection to use
   * @param tableId              the table to update
   * @param orderedColumns       the columns of the
   * @param rowId                which row in the table to update
   * @param initialLocalRowState the state of the row on the server
   * @param accessContext        the users permissions
   * @param locale               the users selected locale
   * @return true if we are still in conflict
   */
  private static boolean enforcePermissionsAndOptimizeConflictProcessing(OdkConnectionInterface db,
      String tableId, OrderedColumns orderedColumns, String rowId, SyncState initialLocalRowState,
      AccessContext accessContext, String locale) {

    // we should have two in-conflict records, on is the local, one is the server
    BaseTable baseTable = privilegedGetRowsWithId(db, tableId, rowId, accessContext.activeUser);
    if (baseTable.getNumberOfRows() != 2) {
      throw new IllegalStateException(
          "we should have exactly two rows -- one local-conflict and " + "one server-conflict row");
    }
    Integer idxServerRow = null;
    Integer idxLocalRow = null;
    int localRowConflictType = -1;

    {
      for (int idx = 0; idx < 2; ++idx) {
        String rowConflictTypeStr = baseTable.getRowAtIndex(idx)
            .getDataByKey(DataTableColumns.CONFLICT_TYPE);
        if (rowConflictTypeStr == null) {
          // this row is in conflict. It MUST have a non-null conflict type.
          throw new IllegalStateException("conflict type is null on an in-conflict row");
        }
        int rowConflictType = Integer.parseInt(rowConflictTypeStr);
        if (rowConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES
            || rowConflictType == ConflictType.LOCAL_UPDATED_UPDATED_VALUES) {
          idxLocalRow = idx;
          localRowConflictType = rowConflictType;
        } else if (rowConflictType == ConflictType.SERVER_DELETED_OLD_VALUES
            || rowConflictType == ConflictType.SERVER_UPDATED_UPDATED_VALUES) {
          idxServerRow = idx;
        }
      }

      if (idxServerRow == null) {
        throw new IllegalStateException(
            "did not find server conflict row while optimizing " + "the conflict");
      }

      if (idxLocalRow == null) {
        throw new IllegalStateException(
            "did not find local conflict row while optimizing " + "the conflict");
      }
    }

    Row serverRow = baseTable.getRowAtIndex(idxServerRow);
    String serverDefaultAccess = serverRow.getDataByKey(DataTableColumns.DEFAULT_ACCESS);
    String serverOwner = serverRow.getDataByKey(DataTableColumns.ROW_OWNER);
    String serverGroupReadOnly = serverRow.getDataByKey(DataTableColumns.GROUP_READ_ONLY);
    String serverGroupModify = serverRow.getDataByKey(DataTableColumns.GROUP_MODIFY);
    String serverGroupPrivileged = serverRow.getDataByKey(DataTableColumns.GROUP_PRIVILEGED);

    // Part 1: verify the ability to modify or delete the row fields (excluding permissions)

    // if the server changed privileges in the row such that this user does not have privileges
    // to modify (if local changed) or delete (if local deleted), then we can immediately
    // resolve to take server changes.
    TableSecuritySettings tss = getTableSecuritySettings(db, tableId);

    try {
      String updatedSyncState = localRowConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES ?
          SyncState.deleted.name() :
          SyncState.changed.name();
      RowChange rowChange = localRowConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES ?
          RowChange.DELETE_ROW :
          RowChange.CHANGE_ROW;

      tss.allowRowChange(accessContext.activeUser, accessContext.rolesArray, updatedSyncState,
          serverDefaultAccess, serverOwner, serverGroupModify,
          serverGroupPrivileged, rowChange);

    } catch (ActionNotAuthorizedException ignored) {
      Row localRow = baseTable.getRowAtIndex(idxLocalRow);

      internalResolveServerConflictTakeServerRowWithId(db, tableId, rowId, orderedColumns,
          initialLocalRowState, serverRow, localRow, accessContext.activeUser, locale);
      return false;
    }

    // Part 2: Test if the permissions fields of the local row are different from any of
    // those from the server.
    //
    // If they are, and the local user is not able to modify permissions fields, silently
    // update the local in-conflict row to have the same permissions fields as the
    // server row.

    Row localRow = baseTable.getRowAtIndex(idxLocalRow);
    String localDefaultAccess = localRow.getDataByKey(DataTableColumns.DEFAULT_ACCESS);
    String localOwner = localRow.getDataByKey(DataTableColumns.ROW_OWNER);
    String localGroupReadOnly = localRow.getDataByKey(DataTableColumns.GROUP_READ_ONLY);
    String localGroupModify = localRow.getDataByKey(DataTableColumns.GROUP_MODIFY);
    String localGroupPrivileged = localRow.getDataByKey(DataTableColumns.GROUP_PRIVILEGED);

    if (!(sameValue(localDefaultAccess, serverDefaultAccess) && sameValue(localOwner, serverOwner)
        && sameValue(localGroupReadOnly, serverGroupReadOnly) && sameValue(localGroupModify,
        serverGroupModify) && sameValue(localGroupPrivileged, serverGroupPrivileged))) {

      // permissions columns have changed
      // test if we have permissions to make these changes
      try {
        tss.canModifyPermissions(accessContext.activeUser, accessContext.rolesArray,
            serverGroupPrivileged, serverOwner);
      } catch (ActionNotAuthorizedException ignored) {

        // don't have permission to alter permissions columns --
        // update the row with all of the local columns as-is, but override all the
        // permissions fields with the values from the server.
        ContentValues values = new ContentValues();
        for (int i = 0; i < baseTable.getWidth(); ++i) {
          String colName = baseTable.getElementKey(i);
          if (DataTableColumns.EFFECTIVE_ACCESS.equals(colName)) {
            continue;
          }
          if (localRow.getDataByIndex(i) == null) {
            values.putNull(colName);
          } else {
            values.put(colName, localRow.getDataByIndex(i));
          }
        }
        // take the server's permissions fields.
        if (serverDefaultAccess == null) {
          values.putNull(DataTableColumns.DEFAULT_ACCESS);
        } else {
          values.put(DataTableColumns.DEFAULT_ACCESS, serverDefaultAccess);
        }
        if (serverOwner == null) {
          values.putNull(DataTableColumns.ROW_OWNER);
        } else {
          values.put(DataTableColumns.ROW_OWNER, serverOwner);
        }
        if (serverGroupReadOnly == null) {
          values.putNull(DataTableColumns.GROUP_READ_ONLY);
        } else {
          values.put(DataTableColumns.GROUP_READ_ONLY, serverGroupReadOnly);
        }
        if (serverGroupModify == null) {
          values.putNull(DataTableColumns.GROUP_MODIFY);
        } else {
          values.put(DataTableColumns.GROUP_MODIFY, serverGroupModify);
        }
        if (serverGroupPrivileged == null) {
          values.putNull(DataTableColumns.GROUP_PRIVILEGED);
        } else {
          values.put(DataTableColumns.GROUP_PRIVILEGED, serverGroupPrivileged);
        }

        privilegedUpdateRowWithId(db, tableId, orderedColumns, values, rowId,
            accessContext.activeUser, locale, false);
      }
    }

    // at this point, all of the local row's changes are confirmed to be
    // able to be made by the active user. i.e.,
    //
    // If the local row is deleted, we could push a delete up to the server.
    //
    // If the local row is modified, we can push the modification up to the server.
    //
    // If the activeUser does not have permission to change the row's permissions,
    // all of those changes have been reverted to match the values on the server.
    return true;
  }

  /**
   * We have a valid, actionable conflict.
   * <p>
   * Silently resolve this conflict if it can be reasonably resolved.
   *
   * @param db                   an open database connection to use
   * @param tableId              the table to update
   * @param orderedColumns       the columns of the row
   * @param rowId                which row in the table to update
   * @param initialLocalRowState the initial data in the local row
   * @param accessContext        the permissions of the currently logged in row
   * @param locale               the users selected locale
   */
  private static void optimizeConflictProcessing(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, String rowId, SyncState initialLocalRowState,
      AccessContext accessContext, String locale) {

    // we should have two in-conflict records, on is the local, one is the server
    BaseTable baseTable = privilegedGetRowsWithId(db, tableId, rowId, accessContext.activeUser);
    if (baseTable.getNumberOfRows() != 2) {
      throw new IllegalStateException(
          "we should have exactly two rows -- one local-conflict and " + "one server-conflict row");
    }
    Integer idxServerRow = null;
    int serverRowConflictType = -1;
    Integer idxLocalRow = null;
    int localRowConflictType = -1;

    {
      for (int idx = 0; idx < 2; ++idx) {
        String rowConflictTypeStr = baseTable.getRowAtIndex(idx)
            .getDataByKey(DataTableColumns.CONFLICT_TYPE);
        if (rowConflictTypeStr == null) {
          // this row is in conflict. It MUST have a non-null conflict type.
          throw new IllegalStateException("conflict type is null on an in-conflict row");
        }
        int rowConflictType = Integer.parseInt(rowConflictTypeStr);
        if (rowConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES
            || rowConflictType == ConflictType.LOCAL_UPDATED_UPDATED_VALUES) {
          idxLocalRow = idx;
          localRowConflictType = rowConflictType;
        } else if (rowConflictType == ConflictType.SERVER_DELETED_OLD_VALUES
            || rowConflictType == ConflictType.SERVER_UPDATED_UPDATED_VALUES) {
          idxServerRow = idx;
          serverRowConflictType = rowConflictType;
        }
      }

      if (idxServerRow == null) {
        throw new IllegalStateException(
            "did not find server conflict row while optimizing " + "the conflict");
      }

      if (idxLocalRow == null) {
        throw new IllegalStateException(
            "did not find local conflict row while optimizing " + "the conflict");
      }
    }

    // if the server and device are both trying to delete the row, then silently delete it
    if (localRowConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES
        && serverRowConflictType == ConflictType.SERVER_DELETED_OLD_VALUES) {

      // simply apply the server's change locally.
      resolveServerConflictWithDeleteRowWithId(db, tableId, rowId, accessContext.activeUser);
      return;
    }

    // if the server and device are not both modifying the row, then we are done --
    // user reconciliation is always required when faced with a mix of delete and
    // modify actions to the same row.
    if (localRowConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES
        || serverRowConflictType == ConflictType.SERVER_DELETED_OLD_VALUES) {
      return;
    }

    // Both the server and device are trying to modify this row

    Row serverRow = baseTable.getRowAtIndex(idxServerRow);
    Row localRow = baseTable.getRowAtIndex(idxLocalRow);

    // Track whether:
    // (1) any of the user-specified columns are modified
    // (2) any of the metadata columns are modified
    // (3) any of the permissions columns are modified

    boolean userSpecifiedColumnsDiffer = false;
    for (int i = 0; i < baseTable.getWidth(); ++i) {
      String colName = baseTable.getElementKey(i);
      if (ADMIN_COLUMNS.contains(colName) || DataTableColumns.EFFECTIVE_ACCESS.equals(colName)) {
        // these values are ignored during comparisons
        continue;
      }
      String localValue = localRow.getDataByKey(colName);
      String serverValue = serverRow.getDataByKey(colName);

      ElementDataType dt = ElementDataType.string;
      try {
        ColumnDefinition cd = orderedColumns.find(colName);
        dt = cd.getType().getDataType();
      } catch (IllegalArgumentException ignored) {
        // ignore
      }
      if (!identicalValue(localValue, serverValue, dt)) {
        userSpecifiedColumnsDiffer = true;
        break;
      }
    }

    boolean nonPermissionsMetadataColumnsDiffer = false;
    boolean permissionsColumnsDiffer = false;

    for (int i = 0; i < baseTable.getWidth(); ++i) {
      String colName = baseTable.getElementKey(i);
      if (!ADMIN_COLUMNS.contains(colName) || DataTableColumns.ID.equals(colName)
          || DataTableColumns.CONFLICT_TYPE.equals(colName) || DataTableColumns.SYNC_STATE
          .equals(colName) || DataTableColumns.ROW_ETAG.equals(colName)) {
        // these values are ignored during comparisons
        continue;
      }
      String localValue = localRow.getDataByKey(colName);
      String serverValue = serverRow.getDataByKey(colName);

      if (DataTableColumns.DEFAULT_ACCESS.equals(colName) || DataTableColumns.ROW_OWNER
          .equals(colName) || DataTableColumns.GROUP_READ_ONLY.equals(colName)
          || DataTableColumns.GROUP_MODIFY.equals(colName) || DataTableColumns.GROUP_PRIVILEGED
          .equals(colName)) {

        permissionsColumnsDiffer = permissionsColumnsDiffer || !sameValue(localValue, serverValue);
      } else {
        nonPermissionsMetadataColumnsDiffer =
            nonPermissionsMetadataColumnsDiffer || !sameValue(localValue, serverValue);
      }
    }

    // if the user-specified fields do not differ and the permissions columns do not differ
    // then we can take the server's changes (updating the metadata fields with those from the
    // server)
    if (!userSpecifiedColumnsDiffer && !permissionsColumnsDiffer) {
      internalResolveServerConflictTakeServerRowWithId(db, tableId, rowId, orderedColumns,
          initialLocalRowState, serverRow, localRow, accessContext.activeUser, locale);
    }

    // otherwise, we have changes that require reconciliation
  }

  /**
   * Delete the specified rowId in this tableId. Deletion respects sync
   * semantics. If the row is in the SyncState.new_row state, then the row and
   * its associated file attachments are immediately deleted. Otherwise, the row
   * is placed into the SyncState.deleted state and will be retained until the
   * device can delete the record on the server.
   * <p/>
   * If you need to immediately delete a record that would otherwise sync to the
   * server, call updateRowETagAndSyncState(...) to set the row to
   * SyncState.new_row, and then call this method and it will be immediately
   * deleted (in this case, unless the record on the server was already deleted,
   * it will remain and not be deleted during any subsequent synchronizations).
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles the user has
   * @throws ActionNotAuthorizedException if the user isn't allowed to delete the row
   */
  public static void deleteRowWithId(OdkConnectionInterface db, String tableId, String rowId,
      String activeUser, String rolesList) throws ActionNotAuthorizedException {

    // TODO: rolesList of user may impact whether we can delete the record.
    // Particularly with sync'd records, is there anything special to do here?
    // consider sync path vs. tools path.
    // I.e., if the user is super-user or higher, we should take local FilterScope.
    // otherwise, we should take server FilterScope. Or should we allow user to select
    // which to take?

    boolean shouldPhysicallyDelete = false;

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      Object[] whereArgs = new Object[] { rowId };
      String whereClause = K_DATATABLE_ID_EQUALS_PARAM;

      // first need to test whether we can delete all the rows under this rowId.
      // If we can't, then throw an access violation
      Cursor c = null;
      try {
        c = db.query(tableId,
            new String[] { DataTableColumns.SYNC_STATE, DataTableColumns.DEFAULT_ACCESS,
                DataTableColumns.ROW_OWNER, DataTableColumns.GROUP_READ_ONLY,
                DataTableColumns.GROUP_MODIFY, DataTableColumns.GROUP_PRIVILEGED }, whereClause,
            whereArgs, null, null, DataTableColumns.SAVEPOINT_TIMESTAMP + " ASC", null);
        boolean hasFirst = c.moveToFirst();

        int idxSyncState = c.getColumnIndex(DataTableColumns.SYNC_STATE);
        int idxDefaultAccess = c.getColumnIndex(DataTableColumns.DEFAULT_ACCESS);
        int idxOwner = c.getColumnIndex(DataTableColumns.ROW_OWNER);
        int idxGroupModify = c.getColumnIndex(DataTableColumns.GROUP_MODIFY);
        int idxGroupPrivileged = c.getColumnIndex(DataTableColumns.GROUP_PRIVILEGED);

        List<String> rolesArray = getRolesArray(rolesList);

        TableSecuritySettings tss = getTableSecuritySettings(db, tableId);

        if (hasFirst) {
          do {
            // verify each row
            String priorSyncState = c.getString(idxSyncState);
            String priorDefaultAccess = c.isNull(idxDefaultAccess) ?
                null :
                c.getString(idxDefaultAccess);
            String priorOwner = c.isNull(idxOwner) ? null : c.getString(idxOwner);
            String priorGroupModify = c.isNull(idxGroupModify) ? null : c.getString(idxGroupModify);
            String priorGroupPrivileged = c.isNull(idxGroupPrivileged) ?
                null :
                c.getString(idxGroupPrivileged);

            tss.allowRowChange(activeUser, rolesArray, priorSyncState, priorDefaultAccess,
                priorOwner, priorGroupModify, priorGroupPrivileged,
                RowChange.DELETE_ROW);

          } while (c.moveToNext());
        }

      } finally {
        if (c != null && !c.isClosed()) {
          c.close();
        }
      }

      // delete any checkpoints
      whereClause =
          K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.SAVEPOINT_TYPE + S_IS_NULL;
      db.delete(tableId, whereClause, whereArgs);

      // this will return null if there are no rows.
      SyncState syncState = getSyncState(db, tableId, rowId);

      if (syncState == null) {
        // the rowId no longer exists (we deleted all checkpoints)
        shouldPhysicallyDelete = true;

      } else if (syncState == SyncState.new_row) {
        // we can safely remove this record from the database
        whereClause = K_DATATABLE_ID_EQUALS_PARAM;

        db.delete(tableId, whereClause, whereArgs);
        shouldPhysicallyDelete = true;

      } else if (syncState != SyncState.in_conflict) {

        Map<String, Object> values = new TreeMap<>();
        values.put(DataTableColumns.SYNC_STATE, SyncState.deleted.name());
        values.put(DataTableColumns.SAVEPOINT_TIMESTAMP,
            TableConstants.nanoSecondsFromMillis(System.currentTimeMillis()));

        db.update(tableId, values, K_DATATABLE_ID_EQUALS_PARAM, whereArgs);
      }
      // TODO: throw exception if in the SyncState.in_conflict state?

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }

    if (shouldPhysicallyDelete) {
      File instanceFolder = new File(
          ODKFileUtils.getInstanceFolder(db.getAppName(), tableId, rowId));
      try {
        ODKFileUtils.deleteDirectory(instanceFolder);
      } catch (Exception e) {
        WebLogger.getLogger(db.getAppName())
            .e(TAG, "Unable to delete this directory: " + instanceFolder.getAbsolutePath());
        WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      }
    }
  }

  /*
    * Internal method to execute a delete checkpoint statement with the given where clause
    *
    * @param db an open database connection to use
    * @param tableId the table to update
    * @param rowId which row in the table to update
    * @param whereClause
    * @param whereArgs
    * @param activeUser
    * @param rolesList
    * @throws ActionNotAuthorizedException
   */
  private static void rawCheckpointDeleteDataInTable(OdkConnectionInterface db, String tableId,
      String rowId, String whereClause, Object[] whereArgs, String activeUser, String rolesList)
      throws ActionNotAuthorizedException {

    boolean shouldPhysicallyDelete = false;

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      // first need to test whether we can delete all the rows that are selected
      // by the where clause. If we can't, then throw an access violation
      Cursor c = null;
      try {
        c = db.query(tableId,
            new String[] { DataTableColumns.SYNC_STATE, DataTableColumns.DEFAULT_ACCESS,
                DataTableColumns.ROW_OWNER, DataTableColumns.GROUP_READ_ONLY,
                DataTableColumns.GROUP_MODIFY, DataTableColumns.GROUP_PRIVILEGED }, whereClause,
            whereArgs, null, null, null, null);
        boolean hasRow = c.moveToFirst();

        int idxSyncState = c.getColumnIndex(DataTableColumns.SYNC_STATE);
        int idxDefaultAccess = c.getColumnIndex(DataTableColumns.DEFAULT_ACCESS);
        int idxOwner = c.getColumnIndex(DataTableColumns.ROW_OWNER);
        int idxGroupModify = c.getColumnIndex(DataTableColumns.GROUP_MODIFY);
        int idxGroupPrivileged = c.getColumnIndex(DataTableColumns.GROUP_PRIVILEGED);

        List<String> rolesArray = getRolesArray(rolesList);

        TableSecuritySettings tss = getTableSecuritySettings(db, tableId);

        if (hasRow) {
          do {
            // the row is entirely removed -- delete the attachments
            String priorSyncState = c.getString(idxSyncState);
            String priorDefaultAccess = c.isNull(idxDefaultAccess) ?
                null :
                c.getString(idxDefaultAccess);
            String priorOwner = c.isNull(idxOwner) ? null : c.getString(idxOwner);
            String priorGroupModify = c.isNull(idxGroupModify) ? null : c.getString(idxGroupModify);
            String priorGroupPrivileged = c.isNull(idxGroupPrivileged) ?
                null :
                c.getString(idxGroupPrivileged);

            tss.allowRowChange(activeUser, rolesArray, priorSyncState, priorDefaultAccess,
                priorOwner, priorGroupModify, priorGroupPrivileged,
                RowChange.DELETE_ROW);
          } while (c.moveToNext());
        }

      } finally {
        if (c != null && !c.isClosed()) {
          c.close();
        }
      }

      db.delete(tableId, whereClause, whereArgs);

      // see how many rows remain.
      // If there are none, then we should delete all the attachments for this row.
      c = null;
      try {
        c = db.query(tableId, new String[] { DataTableColumns.SYNC_STATE },
            K_DATATABLE_ID_EQUALS_PARAM, new Object[] { rowId }, null, null, null, null);
        c.moveToFirst();
        // the row is entirely removed -- delete the attachments
        shouldPhysicallyDelete = c.getCount() == 0;
      } finally {
        if (c != null && !c.isClosed()) {
          c.close();
        }
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }

    if (shouldPhysicallyDelete) {
      File instanceFolder = new File(
          ODKFileUtils.getInstanceFolder(db.getAppName(), tableId, rowId));
      try {
        ODKFileUtils.deleteDirectory(instanceFolder);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        WebLogger.getLogger(db.getAppName())
            .e(TAG, "Unable to delete this directory: " + instanceFolder.getAbsolutePath());
        WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      }
    }
  }

  /**
   * Delete any checkpoint rows for the given rowId in the tableId. Checkpoint
   * rows are created by ODK Survey to hold intermediate values during the
   * filling-in of the form. They act as restore points in the Survey, should
   * the application die.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles the user has
   * @throws ActionNotAuthorizedException if the user isn't allowed to perform the action
   */
  public static void deleteAllCheckpointRowsWithId(OdkConnectionInterface db, String tableId,
      String rowId,
      String activeUser, String rolesList) throws ActionNotAuthorizedException {
    String b = K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.SAVEPOINT_TYPE + S_IS_NULL;

    rawCheckpointDeleteDataInTable(db, tableId, rowId, b, new Object[] { rowId }, activeUser,
        rolesList);
  }

  /**
   * Delete any checkpoint rows for the given rowId in the tableId. Checkpoint
   * rows are created by ODK Survey to hold intermediate values during the
   * filling-in of the form. They act as restore points in the Survey, should
   * the application die.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles the user has
   * @throws ActionNotAuthorizedException if the user isn't allowed to perform the action
   */
  public static void deleteLastCheckpointRowWithId(OdkConnectionInterface db, String tableId,
      String rowId,
      String activeUser, String rolesList) throws ActionNotAuthorizedException {
    String b =
        K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.SAVEPOINT_TYPE + S_IS_NULL + S_AND
            + DataTableColumns.SAVEPOINT_TIMESTAMP + " IN (SELECT MAX("
            + DataTableColumns.SAVEPOINT_TIMESTAMP + ") FROM " + tableId + K_WHERE
            + K_DATATABLE_ID_EQUALS_PARAM + ")";

    rawCheckpointDeleteDataInTable(db, tableId, rowId, b, new Object[] { rowId, rowId }, activeUser,
        rolesList);
  }

  /**
   * Update the given rowId with the values in the cvValues. If certain metadata
   * values are not specified in the cvValues, then suitable default values may
   * be supplied for them. Furthermore, if the cvValues do not specify certain
   * metadata fields, then an exception may be thrown if there are more than one
   * row matching this rowId.
   *
   * @param db             an open database connection to use
   * @param tableId        the table to update
   * @param orderedColumns the columns of the table with the row to update
   * @param cvValues       the new values
   * @param rowId          which row in the table to update
   * @param activeUser     the currently logged in user
   * @param rolesList      the list of roles the user has
   * @param locale         the users currently selected locale
   * @throws ActionNotAuthorizedException if the user isn't allowed to perform the action
   */
  public static void updateRowWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, ContentValues cvValues, String rowId, String activeUser,
      String rolesList, String locale) throws ActionNotAuthorizedException {

    // TODO: make sure caller passes in the correct roleList for the use case.
    // TODO: for multi-step sync actions, we probably need an internal variant of this.

    if (cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>();
    cvDataTableVal.put(DataTableColumns.ID, rowId);
    for (String key : cvValues.keySet()) {
      cvDataTableVal.put(key, cvValues.get(key));
    }

    upsertDataIntoExistingTable(db, tableId, orderedColumns, cvDataTableVal, true, false,
        activeUser, rolesList, locale, false);
  }

  private static void updateRowWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, Map<String, Object> cvValues, String activeUser,
      String rolesList, String locale) throws ActionNotAuthorizedException {

    // TODO: make sure caller passes in the correct roleList for the use case.
    // TODO: for multi-step sync actions, we probably need an internal variant of this.

    if (cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    if (!cvValues.containsKey(DataTableColumns.ID)) {
      throw new IllegalArgumentException(TAG + ": No rowId in cvValues map " + tableId);
    }

    upsertDataIntoExistingTable(db, tableId, orderedColumns, cvValues, true, false, activeUser,
        rolesList, locale, false);
  }

  /**
   * Update the given rowId with the values in the cvValues. All field
   * values are specified in the cvValues. This is a server-induced update
   * of the row to match all fields from the server. An error is thrown if
   * there isn't a row matching this rowId or if there are checkpoint or
   * conflict entries for this rowId.
   *
   * @param db             an open database connection to use
   * @param tableId        the table to update
   * @param orderedColumns the columns of the table with the row to update
   * @param cvValues       the new values
   * @param rowId          which row in the table to update
   * @param activeUser     the currently logged in user
   * @param locale         the users currently selected locale
   */
  private static void privilegedUpdateRowWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, ContentValues cvValues, String rowId, String activeUser,
      String locale, boolean asCsvRequestedChange) {

    // TODO: make sure caller passes in the correct roleList for the use case.
    // TODO: for multi-step sync actions, we probably need an internal variant of this.

    String rolesList = RoleConsts.ADMIN_ROLES_LIST;

    if (cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>();
    cvDataTableVal.put(DataTableColumns.ID, rowId);
    for (String key : cvValues.keySet()) {
      cvDataTableVal.put(key, cvValues.get(key));
    }

    try {
      upsertDataIntoExistingTable(db, tableId, orderedColumns, cvDataTableVal, true, true,
          activeUser, rolesList, locale, asCsvRequestedChange);
    } catch (ActionNotAuthorizedException e) {
      WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      throw new IllegalStateException(e);
    }
  }

  /**
   * SYNC Only. ADMIN Privileges!
   * <p/>
   * Delete the local row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   */
  public static void privilegedDeleteRowWithId(OdkConnectionInterface db, String tableId, String
      rowId,
      String activeUser) {

    // TODO: make sure caller passes in the correct roleList for the use case.
    String rolesList = RoleConsts.ADMIN_ROLES_LIST;

    boolean inTransaction = false;
    try {

      inTransaction = db.inTransaction();
      if (!inTransaction) {
        db.beginTransactionNonExclusive();
      }

      // delete the record of the server row
      deleteServerConflictRowWithId(db, tableId, rowId);

      // move the local record into the 'new_row' sync state
      // so it can be physically deleted.

      if (privilegedUpdateRowETagAndSyncState(db, tableId, rowId, null, SyncState.new_row,
          activeUser)) {

        // delete what was the local conflict record
        deleteRowWithId(db, tableId, rowId, activeUser, rolesList);
      }

      if (!inTransaction) {
        db.setTransactionSuccessful();
      }
    } catch (ActionNotAuthorizedException e) {
      WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      throw new IllegalStateException(e);
    } finally {
      if (db != null) {
        if (!inTransaction) {
          db.endTransaction();
        }
      }
    }
  }

  /**
   * Delete the local and server conflict records to resolve a server conflict
   * <p/>
   * A combination of primitive actions, all performed in one transaction:
   * <p/>
   * // delete the record of the server row
   * deleteServerConflictRowWithId(appName, dbHandleName, tableId, rowId);
   * <p/>
   * // move the local record into the 'new_row' sync state
   * // so it can be physically deleted.
   * updateRowETagAndSyncState(appName, dbHandleName, tableId, rowId, null,
   * SyncState.new_row.name());
   * // move the local conflict back into the normal (null) state
   * deleteRowWithId(appName, dbHandleName, tableId, rowId);
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   */
  public static void resolveServerConflictWithDeleteRowWithId(OdkConnectionInterface db, String
      tableId,
      String rowId, String activeUser) {

    // TODO: make sure caller passes in the correct roleList for the use case.

    boolean inTransaction = false;
    try {

      inTransaction = db.inTransaction();
      if (!inTransaction) {
        db.beginTransactionNonExclusive();
      }

      // delete the record of the server row
      deleteServerConflictRowWithId(db, tableId, rowId);

      // move the local record into the 'new_row' sync state
      // so it can be physically deleted.

      if (!privilegedUpdateRowETagAndSyncState(db, tableId, rowId, null, SyncState.new_row,
          activeUser)) {
        throw new IllegalArgumentException(
            "row id " + rowId + " does not have exactly 1 row in table " + tableId);
      }

      // move the local conflict back into the normal (null) state
      try {
        deleteRowWithId(db, tableId, rowId, activeUser, RoleConsts.ADMIN_ROLES_LIST);
      } catch (ActionNotAuthorizedException e) {
        WebLogger.getLogger(db.getAppName()).e(TAG, "unexpected -- should always succeed");
        WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      }

      if (!inTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (db != null) {
        if (!inTransaction) {
          db.endTransaction();
        }
      }
    }
  }

  /**
   * Resolve the server conflict by taking the local changes.
   * If the local changes are to delete this record, the record will be deleted
   * upon the next successful sync.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles that the user has
   * @param locale     the users currently selected locale
   * @throws ActionNotAuthorizedException if the user can't make the change
   */
  public static void resolveServerConflictTakeLocalRowWithId(OdkConnectionInterface db, String
      tableId,
      String rowId, String activeUser, String rolesList, String locale)
      throws ActionNotAuthorizedException {

    // TODO: if rolesList contains RoleConsts.ROLE_ADMINISTRATOR or  RoleConsts.ROLE_SUPER_USER
    // TODO: then we should take the local rowFilterScope values. Otherwise use server values.

    // I.e., if the user is super-user or higher, we should take local FilterScope.
    // otherwise, we should take server FilterScope. Or should we allow user to select
    // which to take?

    boolean inTransaction = false;
    try {

      inTransaction = db.inTransaction();
      if (!inTransaction) {
        db.beginTransactionNonExclusive();
      }

      OrderedColumns orderedColumns = getUserDefinedColumns(db, tableId);

      AccessContext accessContext = getAccessContext(db, tableId, activeUser,
          RoleConsts.ADMIN_ROLES_LIST);

      // get both conflict records for this row.
      // the local record is always before the server record (due to conflict_type values)
      String b =
          K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.CONFLICT_TYPE + S_IS_NOT_NULL;
      BaseTable table = privilegedQuery(db, tableId, QueryUtil
              .buildSqlStatement(tableId, b, null, null,
                  new String[] { DataTableColumns.CONFLICT_TYPE }, new String[] { "ASC" }),
          new Object[] { rowId }, null, accessContext);

      if (table.getNumberOfRows() != 2) {
        throw new IllegalStateException(
            "Did not find a server and local row when resolving conflicts for rowId: " + rowId);
      }
      Row localRow = table.getRowAtIndex(0);
      Row serverRow = table.getRowAtIndex(1);

      int localConflictType = Integer
          .parseInt(localRow.getDataByKey(DataTableColumns.CONFLICT_TYPE));

      int serverConflictType = Integer
          .parseInt(serverRow.getDataByKey(DataTableColumns.CONFLICT_TYPE));

      if (localConflictType != ConflictType.LOCAL_UPDATED_UPDATED_VALUES
          && localConflictType != ConflictType.LOCAL_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Did not find local conflict row when resolving conflicts for rowId: " + rowId);
      }

      if (serverConflictType != ConflictType.SERVER_UPDATED_UPDATED_VALUES
          && serverConflictType != ConflictType.SERVER_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Did not find server conflict row when resolving conflicts for rowId: " + rowId);
      }

      // update what was the local conflict record with the local's changes
      // by the time we apply the update, the local conflict record will be
      // restored to the proper (conflict_type, sync_state) values.
      //
      // No need to specify them here.
      Map<String, Object> updateValues = new TreeMap<>();
      updateValues.put(DataTableColumns.ID, rowId);
      updateValues
          .put(DataTableColumns.ROW_ETAG, serverRow.getDataByKey(DataTableColumns.ROW_ETAG));

      // take the server's filter metadata values ...
      Map<String, Object> privilegedUpdateValues = new TreeMap<>();
      privilegedUpdateValues.put(DataTableColumns.ID, rowId);
      privilegedUpdateValues.put(DataTableColumns.DEFAULT_ACCESS,
          serverRow.getDataByKey(DataTableColumns.DEFAULT_ACCESS));
      privilegedUpdateValues
          .put(DataTableColumns.ROW_OWNER, serverRow.getDataByKey(DataTableColumns.ROW_OWNER));
      privilegedUpdateValues.put(DataTableColumns.GROUP_READ_ONLY,
          serverRow.getDataByKey(DataTableColumns.GROUP_READ_ONLY));
      privilegedUpdateValues.put(DataTableColumns.GROUP_MODIFY,
          serverRow.getDataByKey(DataTableColumns.GROUP_MODIFY));
      privilegedUpdateValues.put(DataTableColumns.GROUP_PRIVILEGED,
          serverRow.getDataByKey(DataTableColumns.GROUP_PRIVILEGED));
      privilegedUpdateValues.put(DataTableColumns.SAVEPOINT_TIMESTAMP,
          serverRow.getDataByKey(DataTableColumns.SAVEPOINT_TIMESTAMP));
      privilegedUpdateValues.put(DataTableColumns.SAVEPOINT_CREATOR,
          serverRow.getDataByKey(DataTableColumns.SAVEPOINT_CREATOR));

      // Figure out whether to take the server or local metadata fields.
      // and whether to take the server or local data fields.

      SyncState finalSyncState = SyncState.changed;

      if (localConflictType != ConflictType.LOCAL_UPDATED_UPDATED_VALUES) {
        finalSyncState = SyncState.deleted;

        // Deletion is really a "TakeServerChanges" action, but ending with 'deleted' as
        // the final sync state.

        // copy everything over from the server row
        updateValues
            .put(DataTableColumns.FORM_ID, serverRow.getDataByKey(DataTableColumns.FORM_ID));
        updateValues.put(DataTableColumns.LOCALE, serverRow.getDataByKey(DataTableColumns.LOCALE));
        updateValues.put(DataTableColumns.SAVEPOINT_TYPE,
            serverRow.getDataByKey(DataTableColumns.SAVEPOINT_TYPE));
        updateValues.put(DataTableColumns.SAVEPOINT_TIMESTAMP,
            serverRow.getDataByKey(DataTableColumns.SAVEPOINT_TIMESTAMP));
        updateValues.put(DataTableColumns.SAVEPOINT_CREATOR,
            serverRow.getDataByKey(DataTableColumns.SAVEPOINT_CREATOR));

        // including the values of the user fields on the server
        for (String elementKey : orderedColumns.getRetentionColumnNames()) {
          updateValues.put(elementKey, serverRow.getDataByKey(elementKey));
        }
        //} else {
        // We are updating -- preserve the local metadata and column values
        // this is a no-op, as we are updating the local record, so we don't
        // need to do anything special.
        //}
      }

      // delete the record of the server row
      deleteServerConflictRowWithId(db, tableId, rowId);

      // move the local conflict back into the normal non-conflict (null) state
      // set the sync state to "changed" temporarily (otherwise we can't update)

      restoreRowFromConflict(db, tableId, rowId, SyncState.changed, localConflictType);

      // update local with the changes
      updateRowWithId(db, tableId, orderedColumns, updateValues, activeUser, rolesList, locale);

      // update as if user has admin privileges.
      // do this so we can update the filter type and filter value
      updateRowWithId(db, tableId, orderedColumns, privilegedUpdateValues, activeUser,
          RoleConsts.ADMIN_ROLES_LIST, locale);

      // and if we are deleting, try to delete it.
      // this may throw an ActionNotAuthorizedException
      if (finalSyncState == SyncState.deleted) {
        deleteRowWithId(db, tableId, rowId, activeUser, rolesList);
      }

      if (!inTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (db != null) {
        if (!inTransaction) {
          db.endTransaction();
        }
      }
    }
  }

  /**
   * Resolve the server conflict by taking the local changes plus a value map
   * of select server field values.  This map should not update any metadata
   * fields -- it should just contain user data fields.
   * <p/>
   * It is an error to call this if the local change is to delete the row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param cvValues   key-value pairs from the server record that we should incorporate.
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param rolesList  the list of roles that the user has
   * @param locale     the users currently selected locale
   * @throws ActionNotAuthorizedException if the user can't make the change
   */
  public static void resolveServerConflictTakeLocalRowPlusServerDeltasWithId(OdkConnectionInterface
      db,
      String tableId, ContentValues cvValues, String rowId, String activeUser, String rolesList,
      String locale) throws ActionNotAuthorizedException {

    // TODO: if rolesList does not contain RoleConsts.ROLE_SUPER_USER or RoleConsts.ROLE_ADMINISTRATOR
    // TODO: then take the server's rowFilterScope rather than the user's values of those.
    // TODO: and apply the update only if the user roles support that update.

    // I.e., if the user is super-user or higher, we should take local FilterScope.
    // otherwise, we should take server FilterScope. Or should we allow user to select
    // which to take?

    boolean inTransaction = false;
    try {

      inTransaction = db.inTransaction();
      if (!inTransaction) {
        db.beginTransactionNonExclusive();
      }

      OrderedColumns orderedColumns = getUserDefinedColumns(db, tableId);

      AccessContext accessContext = getAccessContext(db, tableId, activeUser,
          RoleConsts.ADMIN_ROLES_LIST);

      // get both conflict records for this row.
      // the local record is always before the server record (due to conflict_type values)
      BaseTable table = privilegedQuery(db, tableId, QueryUtil.buildSqlStatement(tableId,
          K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.CONFLICT_TYPE + S_IS_NOT_NULL,
          null, null, new String[] { DataTableColumns.CONFLICT_TYPE }, new String[] { "ASC" }),
          new Object[] { rowId }, null, accessContext);

      if (table.getNumberOfRows() != 2) {
        throw new IllegalStateException(
            "Did not find a server and local row when resolving conflicts for rowId: " + rowId);
      }
      Row localRow = table.getRowAtIndex(0);
      Row serverRow = table.getRowAtIndex(1);

      int localConflictType = Integer
          .parseInt(localRow.getDataByKey(DataTableColumns.CONFLICT_TYPE));

      int serverConflictType = Integer
          .parseInt(serverRow.getDataByKey(DataTableColumns.CONFLICT_TYPE));

      if (localConflictType != ConflictType.LOCAL_UPDATED_UPDATED_VALUES
          && localConflictType != ConflictType.LOCAL_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Did not find local conflict row when resolving conflicts for rowId: " + rowId);
      }

      if (serverConflictType != ConflictType.SERVER_UPDATED_UPDATED_VALUES
          && serverConflictType != ConflictType.SERVER_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Did not find server conflict row when resolving conflicts for rowId: " + rowId);
      }

      if (localConflictType == ConflictType.LOCAL_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Local row is marked for deletion -- blending does not make sense for rowId: " + rowId);
      }

      Map<String, Object> updateValues = new HashMap<>();
      for (String key : cvValues.keySet()) {
        updateValues.put(key, cvValues.get(key));
      }

      // clean up the incoming map of server values to retain
      cleanUpValuesMap(orderedColumns, updateValues);
      updateValues.put(DataTableColumns.ID, rowId);
      updateValues
          .put(DataTableColumns.ROW_ETAG, serverRow.getDataByKey(DataTableColumns.ROW_ETAG));

      // update what was the local conflict record with the local's changes
      // by the time we apply the update, the local conflict record will be
      // restored to the proper (conflict_type, sync_state) values.
      //
      // No need to specify them here.

      // but take the local's metadata values (i.e., do not change these
      // during the update) ...
      updateValues.put(DataTableColumns.FORM_ID, localRow.getDataByKey(DataTableColumns.FORM_ID));
      updateValues.put(DataTableColumns.LOCALE, localRow.getDataByKey(DataTableColumns.LOCALE));
      updateValues.put(DataTableColumns.SAVEPOINT_TYPE,
          localRow.getDataByKey(DataTableColumns.SAVEPOINT_TYPE));
      updateValues.put(DataTableColumns.SAVEPOINT_TIMESTAMP,
          localRow.getDataByKey(DataTableColumns.SAVEPOINT_TIMESTAMP));
      updateValues.put(DataTableColumns.SAVEPOINT_CREATOR,
          localRow.getDataByKey(DataTableColumns.SAVEPOINT_CREATOR));

      // take the server's filter metadata values ...
      Map<String, Object> privilegedUpdateValues = new TreeMap<>();
      privilegedUpdateValues.put(DataTableColumns.ID, rowId);
      privilegedUpdateValues.put(DataTableColumns.DEFAULT_ACCESS,
          serverRow.getDataByKey(DataTableColumns.DEFAULT_ACCESS));
      privilegedUpdateValues
          .put(DataTableColumns.ROW_OWNER, serverRow.getDataByKey(DataTableColumns.ROW_OWNER));
      privilegedUpdateValues.put(DataTableColumns.GROUP_READ_ONLY,
          serverRow.getDataByKey(DataTableColumns.GROUP_READ_ONLY));
      privilegedUpdateValues.put(DataTableColumns.GROUP_MODIFY,
          serverRow.getDataByKey(DataTableColumns.GROUP_MODIFY));
      privilegedUpdateValues.put(DataTableColumns.GROUP_PRIVILEGED,
          serverRow.getDataByKey(DataTableColumns.GROUP_PRIVILEGED));
      privilegedUpdateValues.put(DataTableColumns.SAVEPOINT_TIMESTAMP,
          serverRow.getDataByKey(DataTableColumns.SAVEPOINT_TIMESTAMP));
      privilegedUpdateValues.put(DataTableColumns.SAVEPOINT_CREATOR,
          serverRow.getDataByKey(DataTableColumns.SAVEPOINT_CREATOR));

      // delete the record of the server row
      deleteServerConflictRowWithId(db, tableId, rowId);

      // move the local conflict back into the normal (null) state

      restoreRowFromConflict(db, tableId, rowId, SyncState.changed, localConflictType);

      // update local with server's changes
      updateRowWithId(db, tableId, orderedColumns, updateValues, activeUser, rolesList, locale);

      // update as if user has admin privileges.
      // do this so we can update the filter type and filter value
      updateRowWithId(db, tableId, orderedColumns, privilegedUpdateValues, activeUser,
          RoleConsts.ADMIN_ROLES_LIST, locale);

      if (!inTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (db != null) {
        if (!inTransaction) {
          db.endTransaction();
        }
      }
    }
  }

  /**
   * Resolve the server conflict by taking the server changes.  This may delete the local row.
   *
   * @param db         an open database connection to use
   * @param tableId    the table to update
   * @param rowId      which row in the table to update
   * @param activeUser the currently logged in user
   * @param locale     the users currently selected locale
   */
  public static void resolveServerConflictTakeServerRowWithId(OdkConnectionInterface db, String
      tableId,
      String rowId, String activeUser, String locale) {

    // TODO: incoming rolesList should be the privileged user roles because we are
    // TODO: overwriting our local row with everything from the server.

    // we have no way in the resolve conflicts screen to choose which filter scope
    // to take. Need to allow super-user and above to choose the local filter scope
    // vs just taking what the server has.

    boolean inTransaction = false;
    try {

      inTransaction = db.inTransaction();
      if (!inTransaction) {
        db.beginTransactionNonExclusive();
      }

      OrderedColumns orderedColumns = getUserDefinedColumns(db, tableId);

      AccessContext accessContext = getAccessContext(db, tableId, activeUser,
          RoleConsts.ADMIN_ROLES_LIST);

      // get both conflict records for this row.
      // the local record is always before the server record (due to conflict_type values)
      String b =
          K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.CONFLICT_TYPE + S_IS_NOT_NULL;
      BaseTable table = privilegedQuery(db, tableId, QueryUtil
              .buildSqlStatement(tableId, b, null, null,
                  new String[] { DataTableColumns.CONFLICT_TYPE }, new String[] { "ASC" }),
          new Object[] { rowId }, null, accessContext);

      if (table.getNumberOfRows() != 2) {
        throw new IllegalStateException(
            "Did not find a server and local row when resolving conflicts for rowId: " + rowId);
      }
      Row localRow = table.getRowAtIndex(0);
      Row serverRow = table.getRowAtIndex(1);

      internalResolveServerConflictTakeServerRowWithId(db, tableId, rowId, orderedColumns, null,
          serverRow, localRow, activeUser, locale);

      if (!inTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (db != null) {
        if (!inTransaction) {
          db.endTransaction();
        }
      }
    }
  }

  /**
   * Resolve the server conflict by taking the server changes.  This may delete the local row.
   *
   * @param db                   an open database connection to use
   * @param tableId              the table to update
   * @param rowId                which row in the table to update
   * @param orderedColumns       the columns in the row
   * @param initialLocalRowState -- if not null, what the row started as
   * @param serverRow            the server's version of the row
   * @param localRow             the local version of the row
   * @param activeUser           the currently logged in user
   * @param locale               the users currently selected locale
   */
  private static void internalResolveServerConflictTakeServerRowWithId(OdkConnectionInterface db,
      String tableId, String rowId, OrderedColumns orderedColumns, SyncState initialLocalRowState,
      Row serverRow, Row localRow, String activeUser, String locale) {

    // TODO: incoming rolesList should be the privileged user roles because we are
    // TODO: overwriting our local row with everything from the server.

    // we have no way in the resolve conflicts screen to choose which filter scope
    // to take. Need to allow super-user and above to choose the local filter scope
    // vs just taking what the server has.

    boolean inTransaction = false;
    try {

      inTransaction = db.inTransaction();
      if (!inTransaction) {
        db.beginTransactionNonExclusive();
      }

      int localConflictType = Integer
          .parseInt(localRow.getDataByKey(DataTableColumns.CONFLICT_TYPE));

      int serverConflictType = Integer
          .parseInt(serverRow.getDataByKey(DataTableColumns.CONFLICT_TYPE));

      if (localConflictType != ConflictType.LOCAL_UPDATED_UPDATED_VALUES
          && localConflictType != ConflictType.LOCAL_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Did not find local conflict row when resolving conflicts for rowId: " + rowId);
      }

      if (serverConflictType != ConflictType.SERVER_UPDATED_UPDATED_VALUES
          && serverConflictType != ConflictType.SERVER_DELETED_OLD_VALUES) {
        throw new IllegalStateException(
            "Did not find server conflict row when resolving conflicts for rowId: " + rowId);
      }

      if (serverConflictType == ConflictType.SERVER_DELETED_OLD_VALUES) {

        resolveServerConflictWithDeleteRowWithId(db, tableId, rowId, activeUser);

      } else {
        // construct an update map of all of the server row's values
        // except CONFLICT_TYPE, which should be null, and SYNC_STATE.
        Map<String, Object> updateValues = new HashMap<>();

        for (String adminColName : ADMIN_COLUMNS) {
          if (adminColName.equals(DataTableColumns.CONFLICT_TYPE) || adminColName
              .equals(DataTableColumns.SYNC_STATE)) {
            continue;
          }
          updateValues.put(adminColName, serverRow.getDataByKey(adminColName));
        }
        updateValues.put(DataTableColumns.CONFLICT_TYPE, null);

        // take all the data values from the server...
        for (String elementKey : orderedColumns.getRetentionColumnNames()) {
          updateValues.put(elementKey, serverRow.getDataByKey(elementKey));
        }

        // what the new sync state should be...
        SyncState newState;
        {
          boolean uriFragmentsChangedOrAppeared = false;
          boolean hasUriFragments = false;
          // we are collapsing to the server state. Examine the
          // server row. Look at all the columns that may contain file
          // attachments. If they do (non-null, non-empty), then
          // set the hasUriFragments flag to true and break out of the loop.
          //
          // Set the resolved row to synced_pending_files if there are
          // non-null, non-empty file attachments in the row. This
          // ensures that we will pull down those attachments at the next
          // sync.
          for (ColumnDefinition cd : orderedColumns.getColumnDefinitions()) {
            if (!cd.getType().getDataType().equals(ElementDataType.rowpath)) {
              // not a file attachment
              continue;
            }
            String v = serverRow.getDataByKey(cd.getElementKey());
            if (v != null && !v.isEmpty()) {
              // non-null file attachment specified on server row
              hasUriFragments = true;
              String lv = localRow.getDataByKey(cd.getElementKey());
              uriFragmentsChangedOrAppeared =
                  uriFragmentsChangedOrAppeared || lv == null || !lv.equals(v);
              if (initialLocalRowState != SyncState.synced) {
                break;
              }
            }
          }

          if (initialLocalRowState == SyncState.synced) {
            newState = uriFragmentsChangedOrAppeared ?
                SyncState.synced_pending_files :
                SyncState.synced;
          } else {
            newState = hasUriFragments ? SyncState.synced_pending_files : SyncState.synced;
          }
        }
        // save it...
        updateValues.put(DataTableColumns.SYNC_STATE, newState);

        // delete however many rows there are (might be 2 if in_conflict)
        privilegedDeleteRowWithId(db, tableId, rowId, activeUser);

        // and insert the server row, but as a local row
        upsertDataIntoExistingTable(db, tableId, orderedColumns, updateValues, false, true,
            activeUser, RoleConsts.ADMIN_ROLES_LIST, locale, false);
      }

      if (!inTransaction) {
        db.setTransactionSuccessful();
      }
    } catch (ActionNotAuthorizedException e) {
      WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      throw new IllegalStateException(e);
    } finally {
      if (db != null) {
        if (!inTransaction) {
          db.endTransaction();
        }
      }
    }
  }

  /**
   * Inserts a checkpoint row for the given rowId in the tableId. Checkpoint
   * rows are created by ODK Survey to hold intermediate values during the
   * filling-in of the form. They act as restore points in the Survey, should
   * the application die.
   *
   * @param db             an open database connection to use
   * @param tableId        the table to update
   * @param orderedColumns the columns in the row to insert
   * @param cvValues       the values of the row
   * @param rowId          which row in the table to update
   * @param activeUser     the currently logged in user
   * @param rolesList      the roles that the user has
   * @param locale         the users selected locale
   * @throws ActionNotAuthorizedException if the user can't insert the row
   */
  public static void insertCheckpointRowWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, ContentValues cvValues, String rowId, String activeUser,
      String rolesList, String locale) throws ActionNotAuthorizedException {

    if (cvValues.size() <= 0) {
      throw new IllegalArgumentException(
          TAG + ": No values to add into table for checkpoint" + tableId);
    }

    // these are all managed in the database layer...
    // the user should NOT set them...

    if (cvValues.containsKey(DataTableColumns.SAVEPOINT_TIMESTAMP)) {
      throw new IllegalArgumentException(
          TAG + ": No user supplied savepoint timestamp can be included for a checkpoint");
    }

    if (cvValues.containsKey(DataTableColumns.SAVEPOINT_TYPE)) {
      throw new IllegalArgumentException(
          TAG + ": No user supplied savepoint type can be included for a checkpoint");
    }

    if (cvValues.containsKey(DataTableColumns.ROW_ETAG)) {
      throw new IllegalArgumentException(
          TAG + ": No user supplied row ETag can be included for a checkpoint");
    }

    if (cvValues.containsKey(DataTableColumns.SYNC_STATE)) {
      throw new IllegalArgumentException(
          TAG + ": No user supplied sync state can be included for a checkpoint");
    }

    if (cvValues.containsKey(DataTableColumns.CONFLICT_TYPE)) {
      throw new IllegalArgumentException(
          TAG + ": No user supplied conflict type can be included for a checkpoint");
    }

    // If a rowId is specified, a cursor will be needed to
    // get the current row to create a checkpoint with the relevant data
    Cursor c = null;
    try {
      // Allow the user to pass in no rowId if this is the first
      // checkpoint row that the user is adding
      if (rowId == null) {

        // TODO: is this even valid any more? I think we disallow this in the AIDL flow.

        String rowIdToUse = LocalizationUtils.genUUID();
        HashMap<String, Object> currValues = new HashMap<>();
        for (String key : cvValues.keySet()) {
          currValues.put(key, cvValues.get(key));
        }
        currValues.put(BaseColumns._ID, rowIdToUse);
        currValues.put(DataTableColumns.SYNC_STATE, SyncState.new_row.name());
        insertCheckpointIntoExistingTable(db, tableId, orderedColumns, currValues, activeUser,
            rolesList, locale, true, null, null, null, null, null);
        return;
      }

      String b = K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.SAVEPOINT_TIMESTAMP
          + " IN (SELECT MAX(" + DataTableColumns.SAVEPOINT_TIMESTAMP + ") FROM " + tableId
          + K_WHERE + K_DATATABLE_ID_EQUALS_PARAM + ")";
      c = db.query(tableId, null, b, new Object[] { rowId, rowId }, null, null, null,
          null);
      c.moveToFirst();

      if (c.getCount() > 1) {
        throw new IllegalStateException(TAG + ": More than one checkpoint at a timestamp");
      }

      // Inserting a checkpoint for the first time
      if (c.getCount() <= 0) {
        HashMap<String, Object> currValues = new HashMap<>();
        for (String key : cvValues.keySet()) {
          currValues.put(key, cvValues.get(key));
        }
        currValues.put(BaseColumns._ID, rowId);
        currValues.put(DataTableColumns.SYNC_STATE, SyncState.new_row.name());
        insertCheckpointIntoExistingTable(db, tableId, orderedColumns, currValues, activeUser,
            rolesList, locale, true, null, null, null, null, null);
      } else {
        // Make sure that the conflict_type of any existing row
        // is null, otherwise throw an exception
        int conflictIndex = c.getColumnIndex(DataTableColumns.CONFLICT_TYPE);
        if (!c.isNull(conflictIndex)) {
          throw new IllegalStateException(
              TAG + ":  A checkpoint cannot be added for a row that is in conflict");
        }

        // these are all managed in the database layer...
        // the user should NOT set them...

        if (cvValues.containsKey(DataTableColumns.DEFAULT_ACCESS)) {
          throw new IllegalArgumentException(
              TAG + ": No user supplied default access can be included for a checkpoint");
        }

        if (cvValues.containsKey(DataTableColumns.ROW_OWNER)) {
          throw new IllegalArgumentException(
              TAG + ": No user supplied row owner can be included for a checkpoint");
        }

        if (cvValues.containsKey(DataTableColumns.GROUP_READ_ONLY)) {
          throw new IllegalArgumentException(
              TAG + ": No user supplied group read only can be included for a checkpoint");
        }

        if (cvValues.containsKey(DataTableColumns.GROUP_MODIFY)) {
          throw new IllegalArgumentException(
              TAG + ": No user supplied group modify can be included for a checkpoint");
        }

        if (cvValues.containsKey(DataTableColumns.GROUP_PRIVILEGED)) {
          throw new IllegalArgumentException(
              TAG + ": No user supplied group privileged can be included for a checkpoint");
        }

        HashMap<String, Object> currValues = new HashMap<>();
        for (String key : cvValues.keySet()) {
          currValues.put(key, cvValues.get(key));
        }

        // This is unnecessary
        // We should only have one row at this point
        //c.moveToFirst();

        String priorDefaultAccess = null;
        String priorOwner = null;
        String priorGroupReadOnly = null;
        String priorGroupModify = null;
        String priorGroupPrivileged = null;

        // Get the number of columns to iterate over and add
        // those values to the content values
        for (int i = 0; i < c.getColumnCount(); i++) {
          String name = c.getColumnName(i);

          if (name.equals(DataTableColumns.DEFAULT_ACCESS)) {
            priorDefaultAccess = c.getString(i);
          }

          if (name.equals(DataTableColumns.ROW_OWNER)) {
            priorOwner = c.getString(i);
          }

          if (name.equals(DataTableColumns.GROUP_READ_ONLY)) {
            priorGroupReadOnly = c.getString(i);
          }

          if (name.equals(DataTableColumns.GROUP_MODIFY)) {
            priorGroupModify = c.getString(i);
          }

          if (name.equals(DataTableColumns.GROUP_PRIVILEGED)) {
            priorGroupPrivileged = c.getString(i);
          }

          if (currValues.containsKey(name)) {
            continue;
          }

          // omitting savepoint timestamp will generate a new timestamp.
          if (name.equals(DataTableColumns.SAVEPOINT_TIMESTAMP)) {
            continue;
          }

          // set savepoint type to null to mark this as a checkpoint
          if (name.equals(DataTableColumns.SAVEPOINT_TYPE)) {
            currValues.put(name, null);
            continue;
          }

          // sync state (a non-null field) should either remain 'new_row'
          // or be set to 'changed' for all other existing values.
          if (name.equals(DataTableColumns.SYNC_STATE)) {
            String priorState = c.getString(i);
            if (priorState.equals(SyncState.new_row.name())) {
              currValues.put(name, SyncState.new_row.name());
            } else {
              currValues.put(name, SyncState.changed.name());
            }
            continue;
          }

          if (c.isNull(i)) {
            currValues.put(name, null);
            continue;
          }

          // otherwise, just copy the values over...
          Class<?> theClass = CursorUtils.getIndexDataType(c, i);
          Object object = CursorUtils.getIndexAsType(c, theClass, i);
          insertValueIntoContentValues(currValues, theClass, name, object);
        }

        insertCheckpointIntoExistingTable(db, tableId, orderedColumns, currValues, activeUser,
            rolesList, locale, false, priorDefaultAccess, priorOwner, priorGroupReadOnly,
            priorGroupModify, priorGroupPrivileged);
      }
    } finally {
      if (c != null && !c.isClosed()) {
        c.close();
      }
    }
  }

  /**
   * Insert the given rowId with the values in the cvValues. All metadata field
   * values must be specified in the cvValues. This is called from Sync for inserting
   * a row verbatim from the server.
   * <p/>
   * If a row with this rowId is present, then an exception is thrown.
   *
   * @param db                   an open database connection to use
   * @param tableId              the table to update
   * @param orderedColumns       the columns in the row to insert
   * @param cvValues             the cells of the row to insert
   * @param rowId                which row in the table to update
   * @param activeUser           the currently logged in user
   * @param locale               the users selected locale
   * @param asCsvRequestedChange whether it's from a csv import
   */
  public static void privilegedInsertRowWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, ContentValues cvValues, String rowId, String activeUser,
      String locale, boolean asCsvRequestedChange) {

    String rolesList = RoleConsts.ADMIN_ROLES_LIST;

    if (cvValues == null || cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>();
    cvDataTableVal.put(DataTableColumns.ID, rowId);
    for (String key : cvValues.keySet()) {
      cvDataTableVal.put(key, cvValues.get(key));
    }

    // TODO: verify that all fields are specified
    try {
      upsertDataIntoExistingTable(db, tableId, orderedColumns, cvDataTableVal, false, true,
          activeUser, rolesList, locale, asCsvRequestedChange);
    } catch (ActionNotAuthorizedException e) {
      WebLogger.getLogger(db.getAppName()).printStackTrace(e);
      throw new IllegalStateException(e);
    }
  }

  /**
   * Insert the given rowId with the values in the cvValues. If certain metadata
   * values are not specified in the cvValues, then suitable default values may
   * be supplied for them.
   * <p/>
   * If a row with this rowId and certain matching metadata fields is present,
   * then an exception is thrown.
   *
   * @param db             an open database connection to use
   * @param tableId        the table to update
   * @param orderedColumns the columns of the row to insert
   * @param cvValues       the data in the cells of the row to insert
   * @param rowId          which row in the table to update
   * @param activeUser     the currently logged in user
   * @param rolesList      the roles that the user has
   * @param locale         the users selected locale
   * @throws ActionNotAuthorizedException if the user can't insert the row
   */
  public static void insertRowWithId(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, ContentValues cvValues, String rowId, String activeUser,
      String rolesList, String locale) throws ActionNotAuthorizedException {

    if (cvValues == null || cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>();
    cvDataTableVal.put(DataTableColumns.ID, rowId);
    for (String key : cvValues.keySet()) {
      cvDataTableVal.put(key, cvValues.get(key));
    }

    upsertDataIntoExistingTable(db, tableId, orderedColumns, cvDataTableVal, false, false,
        activeUser, rolesList, locale, false);
  }

  /**
   * Write checkpoint into the database
   *
   * @param db                   an open database connection to use
   * @param tableId              the table to update
   * @param orderedColumns       the columns of the row to insert
   * @param cvValues             the data in the cells of the row to insert
   * @param activeUser           the currently logged in user
   * @param rolesList            the roles that the user has
   * @param locale               the users selected locale
   * @param isNewRow             Whether the row is a new row or not
   * @param priorGroupReadOnly   whether the group has read only permissions
   * @param priorGroupModify     whether the group has modify permissions
   * @param priorGroupPrivileged whether the group has full permissions
   */
  private static void insertCheckpointIntoExistingTable(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, HashMap<String, Object> cvValues, String activeUser,
      String rolesList, String locale, boolean isNewRow, String priorDefaultAccess,
      String priorOwner, String priorGroupReadOnly, String priorGroupModify,
      String priorGroupPrivileged) throws ActionNotAuthorizedException {

    String rowId;

    if (cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    Map<String, Object> cvDataTableVal = new HashMap<>(cvValues);

    if (cvDataTableVal.containsKey(DataTableColumns.ID)) {

      rowId = (String) cvDataTableVal.get(DataTableColumns.ID);
      if (rowId == null) {
        throw new IllegalArgumentException(DataTableColumns.ID + ", if specified, cannot be null");
      }
    } else {
      throw new IllegalArgumentException(TAG
          + ": rowId should not be null in insertCheckpointIntoExistingTable in the ContentValues");
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.ROW_ETAG)
        || cvDataTableVal.get(DataTableColumns.ROW_ETAG) == null) {
      cvDataTableVal.put(DataTableColumns.ROW_ETAG, DataTableColumns.DEFAULT_ROW_ETAG);
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.CONFLICT_TYPE)) {
      cvDataTableVal.put(DataTableColumns.CONFLICT_TYPE, null);
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.FORM_ID)) {
      cvDataTableVal.put(DataTableColumns.FORM_ID, null);
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.LOCALE)
        || cvDataTableVal.get(DataTableColumns.LOCALE) == null) {
      cvDataTableVal.put(DataTableColumns.LOCALE, locale);
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_TYPE)
        || cvDataTableVal.get(DataTableColumns.SAVEPOINT_TYPE) == null) {
      cvDataTableVal.put(DataTableColumns.SAVEPOINT_TYPE, null);
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_TIMESTAMP)
        || cvDataTableVal.get(DataTableColumns.SAVEPOINT_TIMESTAMP) == null) {
      String timeStamp = TableConstants.nanoSecondsFromMillis(System.currentTimeMillis());
      cvDataTableVal.put(DataTableColumns.SAVEPOINT_TIMESTAMP, timeStamp);
    }

    if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_CREATOR)
        || cvDataTableVal.get(DataTableColumns.SAVEPOINT_CREATOR) == null) {
      cvDataTableVal.put(DataTableColumns.SAVEPOINT_CREATOR, activeUser);
    }

    cleanUpValuesMap(orderedColumns, cvDataTableVal);

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      List<String> rolesArray = getRolesArray(rolesList);

      // get the security settings
      TableSecuritySettings tss = getTableSecuritySettings(db, tableId);

      if (isNewRow) {

        // ensure that filter type and value are defined. Use defaults if not.

        if (!cvDataTableVal.containsKey(DataTableColumns.DEFAULT_ACCESS)
            || cvDataTableVal.get(DataTableColumns.DEFAULT_ACCESS) == null) {
          cvDataTableVal.put(DataTableColumns.DEFAULT_ACCESS, tss.defaultAccessOnCreation);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.ROW_OWNER)) {
          cvDataTableVal.put(DataTableColumns.ROW_OWNER, activeUser);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.GROUP_READ_ONLY)
            || cvDataTableVal.get(DataTableColumns.GROUP_READ_ONLY) == null) {
          cvDataTableVal.put(DataTableColumns.GROUP_READ_ONLY, null);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.GROUP_MODIFY)
            || cvDataTableVal.get(DataTableColumns.GROUP_MODIFY) == null) {
          cvDataTableVal.put(DataTableColumns.GROUP_MODIFY, null);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.GROUP_PRIVILEGED)
            || cvDataTableVal.get(DataTableColumns.GROUP_PRIVILEGED) == null) {
          cvDataTableVal.put(DataTableColumns.GROUP_PRIVILEGED, null);
        }

        cvDataTableVal.put(DataTableColumns.SYNC_STATE, SyncState.new_row.name());

        tss.allowRowChange(activeUser, rolesArray, SyncState.new_row.name(), priorDefaultAccess,
            priorOwner, priorGroupModify, priorGroupPrivileged,
            RowChange.NEW_ROW);

      } else {

        // don't allow changes to default access or owner or syncState when inserting checkpoints
        cvDataTableVal.put(DataTableColumns.DEFAULT_ACCESS, priorDefaultAccess);

        cvDataTableVal.put(DataTableColumns.ROW_OWNER, priorOwner);

        cvDataTableVal.put(DataTableColumns.GROUP_READ_ONLY, priorGroupReadOnly);

        cvDataTableVal.put(DataTableColumns.GROUP_MODIFY, priorGroupModify);

        cvDataTableVal.put(DataTableColumns.GROUP_PRIVILEGED, priorGroupPrivileged);

        // for this call path, syncState is already updated by caller

        tss.allowRowChange(activeUser, rolesArray,
            (String) cvDataTableVal.get(DataTableColumns.SYNC_STATE), priorDefaultAccess,
            priorOwner, priorGroupModify, priorGroupPrivileged,
            RowChange.CHANGE_ROW);
      }

      db.insertOrThrow(tableId, null, cvDataTableVal);

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }

  }

  /**
   * Get the table's security settings.
   *
   * @param db      an open database connection to use
   * @param tableId the table to read from
   * @return the TableSecuritySettings object for the requested table
   */
  private static TableSecuritySettings getTableSecuritySettings(OdkConnectionInterface db,
      String tableId) {

    // get the security settings
    List<KeyValueStoreEntry> entries = getTableMetadata(db, tableId,
        KeyValueStoreConstants.PARTITION_TABLE, LocalKeyValueStoreConstants.TableSecurity.ASPECT,
        null).getEntries();

    KeyValueStoreEntry locked = null;
    KeyValueStoreEntry defaultAccessOnCreation = null;
    KeyValueStoreEntry unverifiedUserCanCreate = null;
    for (KeyValueStoreEntry entry : entries) {
      switch (entry.key) {
      case LocalKeyValueStoreConstants.TableSecurity.KEY_DEFAULT_ACCESS_ON_CREATION:
        defaultAccessOnCreation = entry;
        break;
      case LocalKeyValueStoreConstants.TableSecurity.KEY_UNVERIFIED_USER_CAN_CREATE:
        unverifiedUserCanCreate = entry;
        break;
      case LocalKeyValueStoreConstants.TableSecurity.KEY_LOCKED:
        locked = entry;
        break;
      default:
        break;
      }
    }

    Boolean isLocked = locked != null ? KeyValueStoreUtils.getBoolean(locked) : null;
    if (isLocked == null) {
      isLocked = false;
    }

    Boolean canUnverifiedUserCreateRow = unverifiedUserCanCreate != null ?
        KeyValueStoreUtils.getBoolean(unverifiedUserCanCreate) :
        null;
    if (canUnverifiedUserCreateRow == null) {
      canUnverifiedUserCreateRow = true;
    }

    String defaultAccess = defaultAccessOnCreation != null ? defaultAccessOnCreation.value : null;
    if (defaultAccess == null) {
      defaultAccess = DataTableColumns.DEFAULT_DEFAULT_ACCESS;
    }

    return new TableSecuritySettings(tableId, isLocked, canUnverifiedUserCreateRow, defaultAccess);
  }

  /*
   * Write data into a user defined database table
   *
   * TODO: This is broken w.r.t. updates of partial fields
   */
  private static void upsertDataIntoExistingTable(OdkConnectionInterface db, String tableId,
      OrderedColumns orderedColumns, Map<String, Object> cvValues, boolean shouldUpdate,
      boolean asServerRequestedChange, String activeUser, String rolesList, String locale,
      boolean asCsvRequestedChange) throws ActionNotAuthorizedException {

    String rowId;
    String whereClause = null;
    boolean specifiesConflictType = cvValues.containsKey(DataTableColumns.CONFLICT_TYPE);
    boolean nullConflictType =
        specifiesConflictType && cvValues.get(DataTableColumns.CONFLICT_TYPE) == null;
    Object[] whereArgs = new Object[specifiesConflictType ? 1 + (nullConflictType ? 0 : 1) : 1];
    boolean update = false;
    String updatedSyncState = SyncState.new_row.name();
    String priorDefaultAccess = DataTableColumns.DEFAULT_DEFAULT_ACCESS;
    String priorOwner = DataTableColumns.DEFAULT_ROW_OWNER;
    String priorGroupModify = DataTableColumns.DEFAULT_GROUP_MODDIFY;
    String priorGroupPrivileged = DataTableColumns.DEFAULT_GROUP_PRIVILEGED;

    if (cvValues.size() <= 0) {
      throw new IllegalArgumentException(TAG + ": No values to add into table " + tableId);
    }

    HashMap<String, Object> cvDataTableVal = new HashMap<>(cvValues);

    // if this is a server-requested change, all the user fields and admin columns should be specified.
    if (asServerRequestedChange && !asCsvRequestedChange) {
      for (String columnName : orderedColumns.getRetentionColumnNames()) {
        if (!cvDataTableVal.containsKey(columnName)) {
          throw new IllegalArgumentException(
              TAG + ": Not all user field values are set during server " + (shouldUpdate ?
                  "update" :
                  "insert") + " in table " + tableId + " missing: " + columnName);
        }
      }
      for (String columnName : ADMIN_COLUMNS) {
        if (!cvDataTableVal.containsKey(columnName)) {
          throw new IllegalArgumentException(
              TAG + ": Not all metadata field values are set during server " + (shouldUpdate ?
                  "update" :
                  "insert") + " in table " + tableId + " missing: " + columnName);
        }
      }
    }

    boolean dbWithinTransaction = db.inTransaction();
    try {
      if (!dbWithinTransaction) {
        db.beginTransactionNonExclusive();
      }

      if (cvDataTableVal.containsKey(DataTableColumns.ID)) {
        // The user specified a row id; we need to determine whether to
        // insert or update the record, or to reject the action because
        // there are either checkpoint records for this row id, or, if
        // a server conflict is associated with this row, that the
        // _conflict_type to update was not specified.
        //
        // i.e., the tuple (_id, _conflict_type) should be unique. If
        // we find that there are more than 0 or 1 records matching this
        // tuple, then we should reject the update request.
        //
        // TODO: perhaps we want to allow updates to the local conflict
        // row if there are no checkpoints on it? I.e., change the
        // tri-state conflict type to a pair of states (local / remote).
        // and all local changes are flagged local. Remote only exists
        // if the server is in conflict.

        rowId = (String) cvDataTableVal.get(DataTableColumns.ID);
        if (rowId == null) {
          throw new IllegalArgumentException(
              DataTableColumns.ID + ", if specified, cannot be null");
        }

        if (specifiesConflictType) {
          if (nullConflictType) {
            whereClause =
                K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.CONFLICT_TYPE + S_IS_NULL;
            whereArgs[0] = rowId;
          } else {
            whereClause = K_DATATABLE_ID_EQUALS_PARAM + S_AND + DataTableColumns.CONFLICT_TYPE
                + S_EQUALS_PARAM;
            whereArgs[0] = rowId;
            whereArgs[1] = cvValues.get(DataTableColumns.CONFLICT_TYPE);
          }
        } else {
          whereClause = K_DATATABLE_ID_EQUALS_PARAM;
          whereArgs[0] = rowId;
        }

        AccessContext accessContext = getAccessContext(db, tableId, activeUser,
            RoleConsts.ADMIN_ROLES_LIST);

        BaseTable data = privilegedQuery(db, tableId,
            K_SELECT_FROM + tableId + K_WHERE + whereClause, whereArgs, null, accessContext);

        // There must be only one row in the db for the update to work
        if (shouldUpdate) {
          if (data.getNumberOfRows() == 1) {
            int defaultAccessCursorIndex = data
                .getColumnIndexOfElementKey(DataTableColumns.DEFAULT_ACCESS);
            priorDefaultAccess = data.getRowAtIndex(0).getDataByIndex(defaultAccessCursorIndex);
            if (priorDefaultAccess == null) {
              priorDefaultAccess = DataTableColumns.DEFAULT_DEFAULT_ACCESS;
            }
            int ownerCursorIndex = data.getColumnIndexOfElementKey(DataTableColumns.ROW_OWNER);
            priorOwner = data.getRowAtIndex(0).getDataByIndex(ownerCursorIndex);
            int groupReadOnlyCursorIndex = data
                .getColumnIndexOfElementKey(DataTableColumns.GROUP_READ_ONLY);
            int groupModifyCursorIndex = data
                .getColumnIndexOfElementKey(DataTableColumns.GROUP_MODIFY);
            priorGroupModify = data.getRowAtIndex(0).getDataByIndex(groupModifyCursorIndex);
            int groupPrivilegedCursorIndex = data
                .getColumnIndexOfElementKey(DataTableColumns.GROUP_PRIVILEGED);
            priorGroupPrivileged = data.getRowAtIndex(0).getDataByIndex(groupPrivilegedCursorIndex);

            int syncStateCursorIndex = data.getColumnIndexOfElementKey(DataTableColumns.SYNC_STATE);
            updatedSyncState = data.getRowAtIndex(0).getDataByIndex(syncStateCursorIndex);

            // allow updates to in_conflict rows if they are initiated through privileged
            // code paths (e.g., enforcePermissionsDuringConflictProcessing )
            if (updatedSyncState.equals(SyncState.deleted.name())
                || !asServerRequestedChange && updatedSyncState
                .equals(SyncState.in_conflict.name())) {
              throw new IllegalStateException(TAG + ": Cannot update a deleted or in-conflict row");
            } else if (updatedSyncState.equals(SyncState.synced.name()) || updatedSyncState
                .equals(SyncState.synced_pending_files.name())) {
              updatedSyncState = SyncState.changed.name();
            }
            update = true;
          } else if (data.getNumberOfRows() > 1) {
            throw new IllegalArgumentException(
                TAG + ": row id " + rowId + " has more than 1 row in table " + tableId);
          }
        } else {
          if (data.getNumberOfRows() > 0) {
            throw new IllegalArgumentException(
                TAG + ": row id " + rowId + " is already present in table " + tableId);
          }
        }

      } else {
        rowId = "uuid:" + UUID.randomUUID();
      }

      // TODO: This is broken w.r.t. updates of partial fields
      // TODO: This is broken w.r.t. updates of partial fields
      // TODO: This is broken w.r.t. updates of partial fields
      // TODO: This is broken w.r.t. updates of partial fields

      if (!cvDataTableVal.containsKey(DataTableColumns.ID)) {
        cvDataTableVal.put(DataTableColumns.ID, rowId);
      }

      List<String> rolesArray = getRolesArray(rolesList);

      // get the security settings
      TableSecuritySettings tss = getTableSecuritySettings(db, tableId);

      if (!asServerRequestedChange) {
        // do not allow _default_access, _row_owner, _sync_state, _group_privileged
        // _group_modify, _group_read_only to be modified in normal workflow
        if (cvDataTableVal.containsKey(DataTableColumns.DEFAULT_ACCESS) || cvDataTableVal
            .containsKey(DataTableColumns.ROW_OWNER) || cvDataTableVal
            .containsKey(DataTableColumns.GROUP_PRIVILEGED) || cvDataTableVal
            .containsKey(DataTableColumns.GROUP_MODIFY) || cvDataTableVal
            .containsKey(DataTableColumns.GROUP_READ_ONLY)) {
          tss.canModifyPermissions(activeUser, rolesArray, priorGroupPrivileged, priorOwner);
        }
      }

      if (update) {

        // MODIFYING

        if (!cvDataTableVal.containsKey(DataTableColumns.SYNC_STATE)
            || cvDataTableVal.get(DataTableColumns.SYNC_STATE) == null) {
          cvDataTableVal.put(DataTableColumns.SYNC_STATE, updatedSyncState);
        }

        if (!asServerRequestedChange) {

          // apply row access restrictions
          // this will throw an IllegalArgumentException
          tss.allowRowChange(activeUser, rolesArray, updatedSyncState, priorDefaultAccess,
              priorOwner, priorGroupModify, priorGroupPrivileged,
              RowChange.CHANGE_ROW);

        }

        if (cvDataTableVal.containsKey(DataTableColumns.LOCALE)
            && cvDataTableVal.get(DataTableColumns.LOCALE) == null) {
          cvDataTableVal.put(DataTableColumns.LOCALE, locale);
        }

        if (cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_TYPE)
            && cvDataTableVal.get(DataTableColumns.SAVEPOINT_TYPE) == null) {
          cvDataTableVal.put(DataTableColumns.SAVEPOINT_TYPE, SavepointTypeManipulator.complete());
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_TIMESTAMP)
            || cvDataTableVal.get(DataTableColumns.SAVEPOINT_TIMESTAMP) == null) {
          String timeStamp = TableConstants.nanoSecondsFromMillis(System.currentTimeMillis());
          cvDataTableVal.put(DataTableColumns.SAVEPOINT_TIMESTAMP, timeStamp);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_CREATOR)
            || cvDataTableVal.get(DataTableColumns.SAVEPOINT_CREATOR) == null) {
          cvDataTableVal.put(DataTableColumns.SAVEPOINT_CREATOR, activeUser);
        }
      } else {

        // INSERTING

        if (!cvDataTableVal.containsKey(DataTableColumns.ROW_ETAG)
            || cvDataTableVal.get(DataTableColumns.ROW_ETAG) == null) {
          cvDataTableVal.put(DataTableColumns.ROW_ETAG, DataTableColumns.DEFAULT_ROW_ETAG);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.SYNC_STATE)
            || cvDataTableVal.get(DataTableColumns.SYNC_STATE) == null) {
          cvDataTableVal.put(DataTableColumns.SYNC_STATE, SyncState.new_row.name());
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.CONFLICT_TYPE)) {
          cvDataTableVal.put(DataTableColumns.CONFLICT_TYPE, null);
        }

        if (!asServerRequestedChange) {

          if (!cvDataTableVal.containsKey(DataTableColumns.DEFAULT_ACCESS)
              || cvDataTableVal.get(DataTableColumns.DEFAULT_ACCESS) == null) {
            cvDataTableVal.put(DataTableColumns.DEFAULT_ACCESS, tss.defaultAccessOnCreation);
          }

          // activeUser
          if (!cvDataTableVal.containsKey(DataTableColumns.ROW_OWNER)
              || cvDataTableVal.get(DataTableColumns.ROW_OWNER) == null) {
            cvDataTableVal.put(DataTableColumns.ROW_OWNER, activeUser);
          }

          tss.allowRowChange(activeUser, rolesArray, updatedSyncState, priorDefaultAccess,
              priorOwner, priorGroupModify, priorGroupPrivileged,
              RowChange.NEW_ROW);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.FORM_ID)) {
          cvDataTableVal.put(DataTableColumns.FORM_ID, null);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.LOCALE)
            || cvDataTableVal.get(DataTableColumns.LOCALE) == null) {
          cvDataTableVal.put(DataTableColumns.LOCALE, locale);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_TYPE)
            || cvDataTableVal.get(DataTableColumns.SAVEPOINT_TYPE) == null) {
          cvDataTableVal.put(DataTableColumns.SAVEPOINT_TYPE, SavepointTypeManipulator.complete());
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_TIMESTAMP)
            || cvDataTableVal.get(DataTableColumns.SAVEPOINT_TIMESTAMP) == null) {
          String timeStamp = TableConstants.nanoSecondsFromMillis(System.currentTimeMillis());
          cvDataTableVal.put(DataTableColumns.SAVEPOINT_TIMESTAMP, timeStamp);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.SAVEPOINT_CREATOR)
            || cvDataTableVal.get(DataTableColumns.SAVEPOINT_CREATOR) == null) {
          cvDataTableVal.put(DataTableColumns.SAVEPOINT_CREATOR, activeUser);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.GROUP_READ_ONLY)
            || cvDataTableVal.get(DataTableColumns.GROUP_READ_ONLY) == null) {
          cvDataTableVal.put(DataTableColumns.GROUP_READ_ONLY, null);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.GROUP_MODIFY)
            || cvDataTableVal.get(DataTableColumns.GROUP_MODIFY) == null) {
          cvDataTableVal.put(DataTableColumns.GROUP_MODIFY, null);
        }

        if (!cvDataTableVal.containsKey(DataTableColumns.GROUP_PRIVILEGED)
            || cvDataTableVal.get(DataTableColumns.GROUP_PRIVILEGED) == null) {
          cvDataTableVal.put(DataTableColumns.GROUP_PRIVILEGED, null);
        }
      }

      cleanUpValuesMap(orderedColumns, cvDataTableVal);

      if (update) {
        db.update(tableId, cvDataTableVal, whereClause, whereArgs);
      } else {
        db.insertOrThrow(tableId, null, cvDataTableVal);
      }

      if (!dbWithinTransaction) {
        db.setTransactionSuccessful();
      }
    } finally {
      if (!dbWithinTransaction) {
        db.endTransaction();
      }
    }
  }

  /**
   * If the caller specified a complex json value for a structured type, flush
   * the value through to the individual columns.
   *
   * @param orderedColumns TODO what?
   * @param values TODO what?
   */
  private static void cleanUpValuesMap(OrderedColumns orderedColumns, Map<String, Object> values) {

    Map<String, String> toBeResolved = new TreeMap<>();

    for (Map.Entry<String, Object> stringObjectEntry : values.entrySet()) {
      //@formatter:off
      if (stringObjectEntry.getKey().equals(DataTableColumns.CONFLICT_TYPE)
          || stringObjectEntry.getKey().equals(DataTableColumns.DEFAULT_ACCESS)
          || stringObjectEntry.getKey().equals(DataTableColumns.ROW_OWNER)
          || stringObjectEntry.getKey().equals(DataTableColumns.GROUP_READ_ONLY)
          || stringObjectEntry.getKey().equals(DataTableColumns.GROUP_MODIFY)
          || stringObjectEntry.getKey().equals(DataTableColumns.GROUP_PRIVILEGED)
          || stringObjectEntry.getKey().equals(DataTableColumns.FORM_ID)
          || stringObjectEntry.getKey().equals(DataTableColumns.ID)
          || stringObjectEntry.getKey().equals(DataTableColumns.LOCALE)
          || stringObjectEntry.getKey().equals(DataTableColumns.ROW_ETAG)
          || stringObjectEntry.getKey().equals(DataTableColumns.SAVEPOINT_CREATOR)
          || stringObjectEntry.getKey().equals(DataTableColumns.SAVEPOINT_TIMESTAMP)
          || stringObjectEntry.getKey().equals(DataTableColumns.SAVEPOINT_TYPE)
          || stringObjectEntry.getKey().equals(DataTableColumns.SYNC_STATE)
          || stringObjectEntry.getKey().equals(BaseColumns._ID)) {
        continue;
      }
      //@formatter:on
      // OK it is one of the data columns
      ColumnDefinition cp = orderedColumns.find(stringObjectEntry.getKey());
      if (!cp.isUnitOfRetention()) {
        toBeResolved.put(stringObjectEntry.getKey(), (String) stringObjectEntry.getValue());
      }
    }

    // remove these non-retained values from the values set...
    for (String key : toBeResolved.keySet()) {
      values.remove(key);
    }

    while (!toBeResolved.isEmpty()) {

      Map<String, String> moreToResolve = new TreeMap<>();

      for (Map.Entry<String, String> entry : toBeResolved.entrySet()) {
        String key = entry.getKey();
        String json = entry.getValue();
        if (json == null) {
          // don't need to do anything
          // since the value is null
          continue;
        }
        ColumnDefinition cp = orderedColumns.find(key);
        try {
          TypeReference<Map<String, Object>> reference = new TypeReference<Map<String, Object>>() {
          };
          Map<String, Object> struct = ODKFileUtils.mapper.readValue(json, reference);
          for (ColumnDefinition child : cp.getChildren()) {
            String subkey = child.getElementKey();
            ColumnDefinition subcp = orderedColumns.find(subkey);
            if (subcp.isUnitOfRetention()) {
              ElementType subtype = subcp.getType();
              values.put(subkey, struct.get(subcp.getElementName()));
            } else {
              // this must be a javascript structure... re-JSON it and save (for
              // next round).
              moreToResolve.put(subkey,
                  ODKFileUtils.mapper.writeValueAsString(struct.get(subcp.getElementName())));
            }
          }
        } catch (IOException e) {
          WebLogger.getLogger(null).printStackTrace(e);
          throw new IllegalStateException("should not be happening");
        }
      }

      toBeResolved = moreToResolve;
    }
  }

  /**
   * TODO What is this for?
   */
  @SuppressWarnings("JavaDoc")
  public enum AccessColumnType {
    NO_EFFECTIVE_ACCESS_COLUMN, LOCKED_EFFECTIVE_ACCESS_COLUMN, UNLOCKED_EFFECTIVE_ACCESS_COLUMN
  }

  private enum RowChange {
    NEW_ROW, CHANGE_ROW, DELETE_ROW
  }

  /**
   * A class that represents a users permissions
   */
  public static class AccessContext {
    /**
     * TODO what?
     */
    public final AccessColumnType accessColumnType;
    /**
     * Whether the user can create a row
     */
    public final boolean canCreateRow;
    /**
     * The username of the user
     */
    public final String activeUser;
    /** true if user is a super-user or administrator */
    public final boolean isPrivilegedUser;
    /**
     * Whether the user has verified the permissions against the server or not
     */
    public final boolean isUnverifiedUser;
    private final List<String> rolesArray;
    private final List<String> groupArray;

    /**
     * Constructor that stores its arguments and sets up some internal variables based on the
     * users roles
     * @param accessColumnType TODO what?
     * @param canCreateRow whether the user can create a row or not
     * @param activeUser the user's username
     * @param rolesArray a list of the user's roles
     */
    AccessContext(AccessColumnType accessColumnType, boolean canCreateRow, String activeUser,
        List<String> rolesArray) {
      if (activeUser == null) {
        throw new IllegalStateException("activeUser cannot be null!");
      }
      this.accessColumnType = accessColumnType;
      this.canCreateRow = canCreateRow;
      this.activeUser = activeUser;
      this.rolesArray = rolesArray;
      this.groupArray = new ArrayList<>();

      if (rolesArray == null) {
        this.isPrivilegedUser = false;
        this.isUnverifiedUser = true;
      } else {
        this.isPrivilegedUser = rolesArray.contains(RoleConsts.ROLE_SUPER_USER) || rolesArray
            .contains(RoleConsts.ROLE_ADMINISTRATOR);
        this.isUnverifiedUser = false;

        for (String role : rolesArray) {
          groupArray.add(role);
        }
      }
    }

    /**
     * Returns whether the user has the passed role
     * @param role The role to check against
     * @return whether the user has the passed role
     */
    public boolean hasRole(String role) {
      return rolesArray != null && rolesArray.contains(role);
    }

    /**
     * Returns a copy of this object, but as a privileged user
     * @return A copy of this object, but as a privilegeduser
     */
    public AccessContext copyAsPrivilegedUser() {

      // figure out whether we have a privileged user or not
      List<String> rolesArray = getRolesArray(RoleConsts.ADMIN_ROLES_LIST);

      return new AccessContext(accessColumnType, true, activeUser, rolesArray);
    }

    /**
     * Returns the groups
     * @return the roles that the user has
     */
    public List<String> getGroupsArray() {
      return groupArray;
    }
  }

  private static class TableSecuritySettings {
    final String tableId;
    final boolean isLocked;
    final boolean canUnverifiedUserCreateRow;
    final String defaultAccessOnCreation;

    public TableSecuritySettings(final String tableId, final boolean isLocked,
        final boolean canUnverifiedUserCreateRow, final String defaultAccessOnCreation) {
      this.tableId = tableId;
      this.isLocked = isLocked;
      this.canUnverifiedUserCreateRow = canUnverifiedUserCreateRow;
      this.defaultAccessOnCreation = defaultAccessOnCreation;
    }

    public void canModifyPermissions(String activeUser, Collection<String> rolesArray,
        String groupPrivileged, String priorOwner) throws ActionNotAuthorizedException {

      if (rolesArray == null) {
        // unverified user

        // throw an exception
        throw new ActionNotAuthorizedException(
            TAG + ": unverified users cannot modify defaultAccess, rowOwner, or group"
                + "permission fields in (any) table " + tableId);

      } else if (!(rolesArray.contains(RoleConsts.ROLE_SUPER_USER) || rolesArray
          .contains(RoleConsts.ROLE_ADMINISTRATOR) || groupPrivileged != null && rolesArray
          .contains(groupPrivileged))) {

        // not (super-user or administrator or groupPrivileged or (rowOwner in unlocked table))
        // NOTE: in a new row priorOwner will be defaultRowOwner
        if (DataTableColumns.DEFAULT_ROW_OWNER == null || priorOwner == null || activeUser ==
            null) {
          String f = "breakpoint";
        }
        if (!isLocked && (helperEquals(DataTableColumns.DEFAULT_ROW_OWNER, priorOwner)
            || helperEquals(priorOwner, activeUser))) {
          return;
        }

        // throw an exception
        throw new ActionNotAuthorizedException(TAG
            + ": user does not have the privileges (super-user or administrator or group_privileged"
            + " or (row_owner in unlocked table)) to modify defaultAccess, rowOwner, or group"
            + " permission fields in table " + tableId);
      }
    }
    private static boolean helperEquals(String a, String b) {
      if (a == null) {
        return b == null;
      }
      return a.equals(b);
    }

    public void allowRowChange(String activeUser, Collection<String> rolesArray, String updatedSyncState,
        String priorDefaultAccess, String priorOwner, String priorGroupModify, String priorGroupPrivileged,
        RowChange rowChange)
        throws ActionNotAuthorizedException {

      switch (rowChange) {
      case NEW_ROW:

        // enforce restrictions:
        // 1. if locked, only super-user, administrator, and group_privileged members can create rows.
        // 2. otherwise, if unverified user, allow creation based upon unverifedUserCanCreate flag
        if (isLocked) {
          // inserting into a LOCKED table

          if (rolesArray == null) {
            // unverified user

            // throw an exception
            throw new ActionNotAuthorizedException(
                TAG + ": unverified users cannot create a row in a locked table " + tableId);
          }

          if (!(rolesArray.contains(RoleConsts.ROLE_SUPER_USER) || rolesArray
              .contains(RoleConsts.ROLE_ADMINISTRATOR) || priorGroupPrivileged != null && rolesArray
              .contains(priorGroupPrivileged))) {
            // bad JSON
            // not a super-user and not an administrator

            // throw an exception
            throw new ActionNotAuthorizedException(TAG
                + ": user does not have the privileges (super-user or administrator or group_privileged) "
                + "to create a row in a locked table " + tableId);
          }

        } else if (rolesArray == null) {
          // inserting into an UNLOCKED table

          // unverified user
          if (!canUnverifiedUserCreateRow) {

            // throw an exception
            throw new ActionNotAuthorizedException(TAG
                + ": unverified users do not have the privileges to create a row in this unlocked table "
                + tableId);
          }
        }
        break;
      case CHANGE_ROW:

        // if SyncState is new_row then allow edits in both locked and unlocked tables
        if (!updatedSyncState.equals(SyncState.new_row.name())) {

          if (isLocked) {
            // modifying a LOCKED table

            // disallow edits if:
            // 1. user is unverified
            // 2. existing owner is null or does not match the activeUser AND
            //    the activeUser is neither a super-user nor an administrator nor a member of
            //    group_privileged.

            if (rolesArray == null || rolesArray.isEmpty()) {
              // unverified user

              // throw an exception
              throw new ActionNotAuthorizedException(
                  TAG + ": unverified users cannot modify rows in a locked table " + tableId);
            }

            // allow if prior owner matches activeUser
            if (!(priorOwner != null && activeUser.equals(priorOwner))) {
              // otherwise...
              // reject if the activeUser is not a super-user or administrator or member of
              // group_privileged

              if (!(rolesArray.contains(RoleConsts.ROLE_SUPER_USER) || rolesArray
                  .contains(RoleConsts.ROLE_ADMINISTRATOR)
                  || priorGroupPrivileged != null && rolesArray.contains(priorGroupPrivileged))) {
                // bad JSON or
                // not a super-user and not an administrator

                // throw an exception
                throw new ActionNotAuthorizedException(
                    TAG + ": user does not have the privileges (super-user or "
                        + "administrator or group_privileged) to modify rows in a locked table "
                        + tableId);
              }
            }

          } else {
            // modifying an UNLOCKED table
            boolean groupAuth = false;

            if (rolesArray != null) {
              if (priorGroupModify != null) {
                groupAuth = rolesArray.contains(priorGroupModify);
              }

              if (priorGroupPrivileged != null) {
                groupAuth |= rolesArray.contains(priorGroupPrivileged);
              }
            }
            // allow if group authorized
            if (!groupAuth) {
              // allow if defaultAccess is MODIFY or FULL
              if (priorDefaultAccess == null || !(
                  priorDefaultAccess.equals(RowFilterScope.Access.MODIFY.name())
                      || priorDefaultAccess.equals(RowFilterScope.Access.FULL.name()))) {
                // otherwise...

                // allow if prior owner matches activeUser
                if (priorOwner == null || !activeUser.equals(priorOwner)) {
                  // otherwise...
                  // reject if the activeUser is not a super-user or administrator

                  if (rolesArray == null || !(rolesArray.contains(RoleConsts.ROLE_SUPER_USER)
                      || rolesArray.contains(RoleConsts.ROLE_ADMINISTRATOR))) {
                    // bad JSON or
                    // not a super-user and not an administrator

                    // throw an exception
                    throw new ActionNotAuthorizedException(
                        TAG + ": user does not have the privileges (super-user or administrator) "
                            + "to modify hidden or read-only rows in an unlocked table " + tableId);
                  }
                }
              }
            }
          }
        }
        break;
      case DELETE_ROW:

        // if SyncState is new_row then allow deletes in both locked and unlocked tables
        if (!updatedSyncState.equals(SyncState.new_row.name())) {

          if (isLocked) {
            // deleting a LOCKED table

            // disallow deletes if:
            // 1. user is unverified
            // 2. user is not a super-user or an administrator or member of group_privileged

            if (rolesArray == null) {
              // unverified user

              // throw an exception
              throw new ActionNotAuthorizedException(
                  TAG + ": unverified users cannot delete rows in a locked table " + tableId);
            }

            // reject if the activeUser is not a super-user or administrator

            if (!(rolesArray.contains(RoleConsts.ROLE_SUPER_USER) || rolesArray
                .contains(RoleConsts.ROLE_ADMINISTRATOR)
                || priorGroupPrivileged != null && rolesArray.contains(priorGroupPrivileged))) {
              // bad JSON or
              // not a super-user and not an administrator

              // throw an exception
              throw new ActionNotAuthorizedException(TAG
                  + ": user does not have the privileges (super-user or administrator or group_privileged) "
                  + "to delete rows in a locked table " + tableId);
            }
          } else {
            // delete in an UNLOCKED table

            boolean groupAuth = false;

            if (rolesArray != null) {
              if (priorGroupPrivileged != null) {
                groupAuth = rolesArray.contains(priorGroupPrivileged);
              }
            }

            if (!groupAuth) {
              // allow if defaultAccess is FULL
              if (priorDefaultAccess == null || !priorDefaultAccess
                  .equals(RowFilterScope.Access.FULL.name())) {
                // otherwise...

                // allow if prior owner matches activeUser
                if (priorOwner == null || !activeUser.equals(priorOwner)) {
                  // otherwise...
                  // reject if the activeUser is not a super-user or administrator

                  if (rolesArray == null || !(rolesArray.contains(RoleConsts.ROLE_SUPER_USER)
                      || rolesArray.contains(RoleConsts.ROLE_ADMINISTRATOR))) {
                    // bad JSON or
                    // not a super-user and not an administrator

                    // throw an exception
                    throw new ActionNotAuthorizedException(TAG
                        + ": user does not have the privileges (super-user or administrator) to delete hidden or read-only rows in an unlocked table "
                        + tableId);
                  }
                }
              }
            }
          }
        }
        break;
      }
    }
  }
}
