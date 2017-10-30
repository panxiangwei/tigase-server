/*
 * DataRepository.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2017 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */

package tigase.db;

//~--- non-JDK imports --------------------------------------------------------

import tigase.xmpp.jid.BareJID;

import java.sql.*;
import java.util.Calendar;
import java.util.TimeZone;

//~--- JDK imports ------------------------------------------------------------

/**
 * The interface defines a generic data repository for storing arbitrary data in any application specific form. This
 * interface unifies database (repository) access allowing for easier way to create database connections pools or
 * database fail-over mechanisms.
 * <p>
 * Created: Jun 16, 2010 3:34:32 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public interface DataRepository
		extends DataSource {

	Calendar UTC_CALENDAR = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

	/**
	 * Helper enumeration with types of supported databases.
	 */
	public static enum dbTypes {
		derby,
		mysql,
		postgresql,
		jtds,
		sqlserver,
		other
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Methods acts as {@link #checkSchemaVersion(DataSourceAware, boolean)} executed with parameter {@code
	 * shutdownServer} set to {@code true}
	 *
	 * @param datasource implementation of {@link DataSourceAware} interface
	 */
	void checkSchemaVersion(DataSourceAware<? extends DataRepository> datasource);

	/**
	 * Method checks version of the particular DataSource stored in the defined source.
	 *
	 * @param datasource implementation of {@link DataSourceAware} interface
	 * @param shutdownServer specifies whether server should be shutdown automatically if the version in the database
	 * doesn't match required version.
	 *
	 * @return a {@code false} when the version doesn't match or there is no version information in the repository. if
	 * {@code shutdownServer} is set to {@code true} and the component version is final it would force shutting down of
	 * the server, otherwise (for non-final version) only a warning would be printed.
	 *
	 * @throws SQLException when there is a problem accessing the DataSource
	 */
	boolean checkSchemaVersion(DataSourceAware<? extends DataRepository> datasource, boolean shutdownServer)
			throws SQLException;

	/**
	 * The method checks whether a table for the given name exists in the database.
	 *
	 * @param tableName is a <code>String</code> value of the table name to check
	 *
	 * @return <code>true</code> <code>boolean</code> value if the table exist in the database and <code>false</code> if
	 * the table was not found.
	 *
	 * @throws SQLException if there was a problem accessing database.
	 */
	boolean checkTable(String tableName) throws SQLException;

	/**
	 * The method checks whether a table for the given name exists in the database and if it does not, it automatically
	 * creates it.
	 *
	 * @param tableName is a <code>String</code> value of the table name to check
	 * @param createTableQuery is a <code>String</code> with the query to create table
	 *
	 * @return <code>true</code> <code>boolean</code> value if the table exist in the database and <code>false</code> if
	 * the table was not found.
	 *
	 * @throws SQLException if there was a problem accessing database.
	 */
	boolean checkTable(String tableName, String createTableQuery) throws SQLException;

	/**
	 * Commits current transaction on the DataRepository connection. Please note that calling this method on the
	 * repository pool has no effect. You have to obtain particular repository handle first, before you can start
	 * transaction.
	 *
	 * @throws SQLException
	 */
	void commit() throws SQLException;

	/**
	 * Creates a SQL statement on which SQL queries can be executed later by the higher repository layer.
	 *
	 * @param user_id user id for which the statement has to be created. This is an optional parameter and null can be
	 * provided. It is used mainly to group queries for the same user on the same DB connection.
	 *
	 * @return a newly created <code>Statement</code>
	 *
	 * @throws SQLException if a JDBC error occurs.
	 */
	Statement createStatement(BareJID user_id) throws SQLException;

	/**
	 * Ends current transaction on the DataRepository connection. Please note that calling this method on the repository
	 * pool has no effect. You have to obtain particular repository handle first, before you can start transaction.
	 *
	 * @throws SQLException
	 */
	void endTransaction() throws SQLException;

	// ~--- methods --------------------------------------------------------------

	/**
	 * Initializes a prepared statement for a given query and stores it internally under the given id key. It can be
	 * retrieved later using <code>getPreparedStatement(stIdKey)</code> method.
	 *
	 * @param stIdKey is a statement identification key.
	 * @param query is a query for the prepared statement.
	 *
	 * @throws SQLException
	 */
	void initPreparedStatement(String stIdKey, String query) throws SQLException;

	/**
	 * Initializes a prepared statement for a given query and stores it internally under the given id key. It can be
	 * retrieved later using <code>getPreparedStatement(stIdKey)</code> method.
	 *
	 * @param stIdKey is a statement identification key.
	 * @param query is a query for the prepared statement.
	 * @param autoGeneratedKeys defines if statement should return auto generated keys
	 *
	 * @throws SQLException
	 */
	void initPreparedStatement(String stIdKey, String query, int autoGeneratedKeys) throws SQLException;

	/**
	 * A helper method to release resources from the statement and result set. This is most common operation for all
	 * database calls, therefore it does make sense to add such a utility method to the API.
	 *
	 * @param stmt a <code>Statement</code> variable to release resources for. Might be null.
	 * @param rs a <code>ResultSet</code> variable to release resources for. Might be null.
	 */
	void release(Statement stmt, ResultSet rs);

	void releaseRepoHandle(DataRepository repo);

	/**
	 * Rolls back started transaction on the DataRepository connection. Please note that calling this method on the
	 * repository pool has no effect. You have to obtain particular repository handle first, before you can start
	 * transaction.
	 *
	 * @throws SQLException
	 */
	void rollback() throws SQLException;

	/**
	 * Starts transaction on the DataRepository connection. Please note that calling this method on the repository pool
	 * has no effect. You have to obtain particular repository handle first, before you can start transaction.
	 *
	 * @throws SQLException
	 */
	void startTransaction() throws SQLException;

	/**
	 * Returns <code>DataRepository</code> instance. If this is a repository pool then it returns particular instance
	 * from the pool. It this is a real repository instance it returns itself. This is exclusive take, no other thread
	 * may use this handle until it is returned to the pool.
	 *
	 * @param user_id is user account ID for which we acquire the handle.
	 *
	 * @return DataRepository instance.
	 */
	DataRepository takeRepoHandle(BareJID user_id);

	//~--- get methods ----------------------------------------------------------

	/**
	 * Returns type of DataRepository database
	 *
	 * @return a value of <code>dbTypes</code>
	 */
	dbTypes getDatabaseType();

	int getPoolSize();

	// ~--- get methods ----------------------------------------------------------

	/**
	 * Returns a prepared statement for a given key.
	 *
	 * @param user_id user id for which the statement has to be created. This is an optional parameter and null can be
	 * provided. It is used mainly to group queries for the same user on the same DB connection.
	 * @param stIdKey is a statement identification key.
	 *
	 * @return a <code>PreparedStatement</code> for the given id key or null if such a statement does not exist.
	 *
	 * @throws SQLException
	 */
	PreparedStatement getPreparedStatement(BareJID user_id, String stIdKey) throws SQLException;

	/**
	 * Returns a prepared statement for a given key.
	 *
	 * @param hashCode user for selection of connection to use. It is used mainly to group queries for the same user on
	 * the same DB connection.
	 * @param stIdKey is a statement identification key.
	 *
	 * @return a <code>PreparedStatement</code> for the given id key or null if such a statement does not exist.
	 *
	 * @throws SQLException
	 */
	PreparedStatement getPreparedStatement(int hashCode, String stIdKey) throws SQLException;

	/**
	 * Returns a DB connection string or DB connection URI.
	 *
	 * @return a <code>String</code> value representing database connection string.
	 */
	String getResourceUri();

	/**
	 * Helper method to set timestamp into prepared statements. Provides proper calendar when needed to adjust
	 * timestamps so that they are stored in the database in proper time zone.
	 *
	 * @param stmt
	 * @param pos
	 * @param timestamp
	 *
	 * @throws SQLException
	 */
	default void setTimestamp(PreparedStatement stmt, int pos, java.sql.Timestamp timestamp) throws SQLException {
		switch (getDatabaseType()) {
			case jtds:
			case sqlserver:
				stmt.setTimestamp(pos, timestamp, UTC_CALENDAR);
				break;
			default:
				stmt.setTimestamp(pos, timestamp);
				break;
		}
	}

	/**
	 * Helper method to get timestamp from result set. Provides proper calendar when needed to adjust timestamps so that
	 * they are stored in the database in proper time zone.
	 *
	 * @param rs
	 * @param pos
	 *
	 * @return
	 *
	 * @throws SQLException
	 */
	default Timestamp getTimestamp(ResultSet rs, int pos) throws SQLException {
		switch (getDatabaseType()) {
			case jtds:
			case sqlserver:
				return rs.getTimestamp(pos, UTC_CALENDAR);
			default:
				return rs.getTimestamp(pos);
		}
	}

	/**
	 * Helper method to get timestamp from result set. Provides proper calendar when needed to adjust timestamps so that
	 * they are stored in the database in proper time zone.
	 *
	 * @param rs
	 * @param pos
	 *
	 * @return
	 *
	 * @throws SQLException
	 */
	default Timestamp getTimestamp(ResultSet rs, String field) throws SQLException {
		switch (getDatabaseType()) {
			case jtds:
			case sqlserver:
				return rs.getTimestamp(field, UTC_CALENDAR);
			default:
				return rs.getTimestamp(field);
		}
	}

}

//~ Formatted in Tigase Code Convention on 13/09/21
