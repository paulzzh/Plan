/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.storage.database.queries.objects.playertable;

import com.djrapitops.plan.delivery.domain.TablePlayer;
import com.djrapitops.plan.delivery.domain.mutators.ActivityIndex;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.SQLDB;
import com.djrapitops.plan.storage.database.queries.Query;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import com.djrapitops.plan.storage.database.queries.analysis.ActivityIndexQueries;
import com.djrapitops.plan.storage.database.sql.tables.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.djrapitops.plan.storage.database.sql.building.Sql.*;

/**
 * Query for displaying players on /server page players tab.
 *
 * @author AuroraLS3
 */
public class ServerTablePlayersQuery implements Query<List<TablePlayer>> {

    private final ServerUUID serverUUID;
    private final long date;
    private final long activeMsThreshold;
    private final int xMostRecentPlayers;

    /**
     * Create a new query.
     *
     * @param serverUUID         UUID of the Plan server.
     * @param date               Date used for Activity Index calculation
     * @param activeMsThreshold  Playtime threshold for Activity Index calculation
     * @param xMostRecentPlayers Limit query size
     */
    public ServerTablePlayersQuery(ServerUUID serverUUID, long date, long activeMsThreshold, int xMostRecentPlayers) {
        this.serverUUID = serverUUID;
        this.date = date;
        this.activeMsThreshold = activeMsThreshold;
        this.xMostRecentPlayers = xMostRecentPlayers;
    }

    @Override
    public List<TablePlayer> executeQuery(SQLDB db) {
        String selectLatestGeolocations = SELECT +
                "a." + GeoInfoTable.USER_ID + ',' +
                "a." + GeoInfoTable.GEOLOCATION +
                FROM + GeoInfoTable.TABLE_NAME + " a" +
                // Super smart optimization https://stackoverflow.com/a/28090544
                // Join the last_used column, but only if there's a bigger one.
                // That way the biggest a.last_used value will have NULL on the b.last_used column and MAX doesn't need to be used.
                LEFT_JOIN + GeoInfoTable.TABLE_NAME + " b ON a." + GeoInfoTable.USER_ID + "=b." + GeoInfoTable.USER_ID + AND + "a." + GeoInfoTable.LAST_USED + "<b." + GeoInfoTable.LAST_USED +
                WHERE + "b." + GeoInfoTable.LAST_USED + IS_NULL;

        String selectSessionData = SELECT + "s." + SessionsTable.USER_ID + ',' +
                "MAX(" + SessionsTable.SESSION_END + ") as last_seen," +
                "COUNT(1) as count," +
                "SUM(" + SessionsTable.SESSION_END + '-' + SessionsTable.SESSION_START + '-' + SessionsTable.AFK_TIME + ") as active_playtime" +
                FROM + SessionsTable.TABLE_NAME + " s" +
                WHERE + "s." + SessionsTable.SERVER_ID + "=" + ServerTable.SELECT_SERVER_ID +
                GROUP_BY + "s." + SessionsTable.USER_ID;

        String selectBaseUsers = SELECT +
                "u." + UsersTable.USER_UUID + ',' +
                "u." + UsersTable.USER_NAME + ',' +
                "u." + UsersTable.REGISTERED + ',' +
                UserInfoTable.BANNED + ',' +
                "geo." + GeoInfoTable.GEOLOCATION + ',' +
                "ses.last_seen," +
                "ses.count," +
                "ses.active_playtime," +
                "act.activity_index" +
                FROM + UsersTable.TABLE_NAME + " u" +
                INNER_JOIN + UserInfoTable.TABLE_NAME + " on u." + UsersTable.ID + "=" + UserInfoTable.TABLE_NAME + '.' + UserInfoTable.USER_ID +
                LEFT_JOIN + '(' + selectLatestGeolocations + ") geo on geo." + GeoInfoTable.USER_ID + "=u." + UsersTable.ID +
                LEFT_JOIN + '(' + selectSessionData + ") ses on ses." + SessionsTable.USER_ID + "=u." + UsersTable.ID +
                LEFT_JOIN + '(' + ActivityIndexQueries.selectActivityIndexSQL() + ") act on u." + UsersTable.ID + "=act." + UserInfoTable.USER_ID +
                WHERE + UserInfoTable.SERVER_ID + "=" + ServerTable.SELECT_SERVER_ID +
                ORDER_BY + "ses.last_seen DESC LIMIT ?";

        return db.query(new QueryStatement<List<TablePlayer>>(selectBaseUsers, 1000) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, serverUUID.toString()); // Session query
                ActivityIndexQueries.setSelectActivityIndexSQLParameters(statement, 2, activeMsThreshold, serverUUID, date);
                statement.setString(13, serverUUID.toString()); // Session query
                statement.setInt(14, xMostRecentPlayers);
            }

            @Override
            public List<TablePlayer> processResults(ResultSet set) throws SQLException {
                List<TablePlayer> players = new ArrayList<>();
                while (set.next()) {
                    TablePlayer.Builder player = TablePlayer.builder()
                            .uuid(UUID.fromString(set.getString(UsersTable.USER_UUID)))
                            .name(set.getString(UsersTable.USER_NAME))
                            .geolocation(set.getString(GeoInfoTable.GEOLOCATION))
                            .registered(set.getLong(UsersTable.REGISTERED))
                            .lastSeen(set.getLong("last_seen"))
                            .sessionCount(set.getInt("count"))
                            .activePlaytime(set.getLong("active_playtime"))
                            .activityIndex(new ActivityIndex(set.getDouble("activity_index"), date));
                    if (set.getBoolean(UserInfoTable.BANNED)) {
                        player.banned();
                    }
                    players.add(player.build());
                }
                return players;
            }
        });
    }
}