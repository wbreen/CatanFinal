/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file Copyright (C) 2005 Chadwick A McHenry <mchenryc@acm.org>
 * Portions of this file Copyright (C) 2007-2017 Jeremy D Monin <jeremy@nand.net>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net 
 **/
package soc.server;

import soc.debug.D;  // JM

import soc.game.*;
import soc.message.*;

import soc.robot.SOCRobotClient;
import soc.server.database.DBSettingMismatchException;
import soc.server.database.SOCDBHelper;

import soc.server.genericServer.LocalStringConnection;
import soc.server.genericServer.Server;
import soc.server.genericServer.StringConnection;

import soc.util.IntPair;
import soc.util.SOCGameBoardReset;
import soc.util.SOCGameList;  // used in javadoc
import soc.util.SOCRobotParameters;
import soc.util.SOCServerFeatures;
import soc.util.Version;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

/**
 * A server for Settlers of Catan
 *
 * @author  Robert S. Thomas
 *
 * Note: This is an attempt at being more modular. 5/13/99 RST
 * Note: Hopefully fixed all of the deadlock problems. 12/27/01 RST
 *<P>
 * For server command line options, use the --help option.
 *<P>
 * If the database is used (see {@link SOCDBHelper}), users can
 * be set up with a username & password in that database to log in and play.
 * Users without accounts can connect by leaving the password blank,
 * as long as they aren't using a nickname which has a password in the database.
 * There's a database setup script parameter {@link SOCDBHelper#PROP_JSETTLERS_DB_SCRIPT_SETUP}.
 * If the setup script is ran, the server exits afterward, so that the
 * script won't be part of the command line for normal server operation.
 *<P>
 *<b>Network traffic:</b>
 * The first message over the connection is the client's version
 * and the second is the server's response:
 * Either {@link SOCRejectConnection}, or the lists of
 * channels and games ({@link SOCChannels}, {@link SOCGames}).
 *<UL>
 *<LI> See {@link SOCMessage} for details of the client/server protocol.
 *<LI> See {@link Server} for details of the server threading and processing.
 *<LI> To get a player's connection, use {@link Server#getConnection(String) getConnection(plName)}.
 *<LI> To get a client's nickname, use <tt>(String)</tt> {@link StringConnection#getData() connection.getData()}.
 *<LI> To get the rest of a client's data, use ({@link SOCClientData})
 *       {@link StringConnection#getAppData() connection.getAppData()}.
 *<LI> To send a message to all players in a game, use {@link #messageToGame(String, SOCMessage)}
 *       and related methods.
 *</UL>
 *<P>
 * The server supports several <b>debug commands</b> when {@link #allowDebugUser enabled}, and
 * when sent as chat messages by a user named "debug".
 * (Or, by the only user in a practice game.)
 * See {@link #processDebugCommand(StringConnection, String, String)}
 * and {@link #handleGAMETEXTMSG(StringConnection, SOCGameTextMsg)}
 * for details.
 *<P>
 * The version check timer is set in {@link SOCClientData#setVersionTimer(SOCServer, StringConnection)}.
 * Before 1.1.06, the server's response was first message,
 * and client version was then sent in reply to server's version.
 *<P>
 * Java properties (starting with "jsettlers.") were added in 1.1.09, with constant names
 * starting with PROP_JSETTLERS_, and listed in {@link #PROPS_LIST}.
 */
public class SOCServer extends Server
{
    private static final long serialVersionUID = 1119L;  // Last structural change v1.1.19

    /**
     * Default tcp port number 8880 to listen, and for client to connect to remote server.
     * Should match SOCPlayerClient.SOC_PORT_DEFAULT.
     *<P>
     * 8880 is the default SOCPlayerClient port since jsettlers 1.0.4, per cvs history.
     * @since 1.1.09
     */
    public static final int SOC_PORT_DEFAULT = 8880;

    /**
     * Default number of bots to start (7; {@link #PROP_JSETTLERS_STARTROBOTS} property).
     * @since 1.1.19
     */
    public static final int SOC_STARTROBOTS_DEFAULT = 7;

    /**
     * Default maximum number of connected clients (40; {@link #maxConnections} field).
     * Always at least 20 more than {@link #SOC_STARTROBOTS_DEFAULT}.
     * @since 1.1.15
     */
    public static final int SOC_MAXCONN_DEFAULT = Math.max(40, 20 + SOC_STARTROBOTS_DEFAULT);

    /**
     * Filename <tt>"jsserver.properties"</tt> for the optional server startup properties file.
     * @since 1.1.20
     */
    public static final String SOC_SERVER_PROPS_FILENAME = "jsserver.properties";

    // If a new property is added, please add a PROP_JSETTLERS_ constant,
    // add it to PROPS_LIST, and update /src/bin/jsserver.properties.sample.

    /** Property <tt>jsettlers.port</tt> to specify the port the server binds to and listens on.
     * Default is {@link #SOC_PORT_DEFAULT}.
     * @since 1.1.09
     */
    public static final String PROP_JSETTLERS_PORT = "jsettlers.port";

    /** Property <tt>jsettlers.connections</tt> to specify the maximum number of connections allowed.
     * Remember that robots count against this limit.
     * Default is {@link #SOC_MAXCONN_DEFAULT}.
     * @since 1.1.09
     */
    public static final String PROP_JSETTLERS_CONNECTIONS = "jsettlers.connections";

    /**
     * String property <tt>jsettlers.bots.cookie</tt> to specify the robot connect cookie.
     * (By default a random one is generated.)
     * The value must pass {@link SOCMessage#isSingleLineAndSafe(String)}:
     * Must not contain the <tt>'|'</tt> or <tt>','</tt> characters.
     * @see #PROP_JSETTLERS_BOTS_SHOWCOOKIE
     * @since 1.1.19
     */
    public static final String PROP_JSETTLERS_BOTS_COOKIE = "jsettlers.bots.cookie";

    /**
     * Boolean property <tt>jsettlers.bots.showcookie</tt> to print the
     * {@link #PROP_JSETTLERS_BOTS_COOKIE robot connect cookie} to System.err during server startup.
     * (The default is N, the cookie is not printed.)<P>
     * Format is:<P><tt>Robot cookie: 03883269284ee140cb907ea203846333</tt>
     * @since 1.1.19
     */
    public static final String PROP_JSETTLERS_BOTS_SHOWCOOKIE = "jsettlers.bots.showcookie";

    /**
     * Property <tt>jsettlers.startrobots</tt> to start some robots when the server starts.
     * (The default is {@link #SOC_STARTROBOTS_DEFAULT}.)
     *<P>
     * 30% will be "smart" robots, the other 70% will be "fast" robots.
     * Remember that robots count against the {@link #PROP_JSETTLERS_CONNECTIONS max connections} limit.
     *<P>
     * Before v1.1.19 the default was 0, no robots were started by default.
     * @since 1.1.09
     */
    public static final String PROP_JSETTLERS_STARTROBOTS = "jsettlers.startrobots";

    /**
     * Open Registration Mode boolean property {@code jsettlers.accounts.open}.
     * If this property is Y, anyone can self-register to create their own user accounts.
     * Otherwise only users in {@link #PROP_JSETTLERS_ACCOUNTS_ADMINS} can
     * create new accounts after the first account.
     *<P>
     * The default is N in version 1.1.19 and newer; previously was Y by default.
     * To require that all players have accounts in the database, see {@link #PROP_JSETTLERS_ACCOUNTS_REQUIRED}.
     *<P>
     * If this field is Y when the server is initialized, the server calls
     * {@link SOCServerFeatures#add(String) features.add}({@link SOCServerFeatures#FEAT_OPEN_REG}).
     * @since 1.1.19
     */
    public static final String PROP_JSETTLERS_ACCOUNTS_OPEN = "jsettlers.accounts.open";

    /**
     * Boolean property <tt>jsettlers.accounts.required</tt> to require that all players have user accounts.
     * If this property is Y, a jdbc database is required and all users must have an account and password
     * in the database. If a client tries to join or create a game or channel without providing a password,
     * they will be sent {@link SOCStatusMessage#SV_PW_REQUIRED}.
     * This property implies {@link SOCServerFeatures#FEAT_ACCTS}.
     *<P>
     * The default is N.
     *<P>
     * If {@link #PROP_JSETTLERS_ACCOUNTS_OPEN} is used, anyone can create their own account (Open Registration).
     * Otherwise see {@link #PROP_JSETTLERS_ACCOUNTS_ADMINS} for the list of user admin accounts.
     * @since 1.1.19
     */
    public static final String PROP_JSETTLERS_ACCOUNTS_REQUIRED = "jsettlers.accounts.required";

    /**
     * Property {@code jsettlers.accounts.admins} to specify the Account Admin usernames
     * which can create accounts and run user-related commands. If this property is set,
     * it is a comma-separated list of usernames (nicknames), and a user must authenticate
     * and be on this list to create user accounts. If not set, no new accounts can be created
     * unless {@link #PROP_JSETTLERS_ACCOUNTS_OPEN} is true.
     *<P>
     * If any other user requests account creation, the server will reply with
     * {@link SOCStatusMessage#SV_ACCT_NOT_CREATED_DENIED}.
     *<P>
     * The server doesn't require or check at startup that the named accounts all already
     * exist, this is just a list of names.
     *<P>
     * This property can't be set at the same time as {@link #PROP_JSETTLERS_ACCOUNTS_OPEN},
     * they ask for opposing security policies.
     *<P>
     * Before v1.2.00, any authenticated user could create accounts.
     *
     * @see #isUserDBUserAdmin(String)
     * @since 1.1.19
     */
    public static final String PROP_JSETTLERS_ACCOUNTS_ADMINS = "jsettlers.accounts.admins";

    /**
     * Property <tt>jsettlers.allow.debug</tt> to permit debug commands over TCP.
     * (The default is N; to allow, set to Y)
     *<P>
     * Backported to 1.1.14 from 2.0.00.
     * @since 1.1.14
     */
    public static final String PROP_JSETTLERS_ALLOW_DEBUG = "jsettlers.allow.debug";

    /**
     * Property <tt>jsettlers.client.maxcreategames</tt> to limit the amount of
     * games that a client can create at once. (The default is 5.)
     * Once a game is completed and deleted (all players leave), they can create another.
     * Set this to -1 to disable it; 0 will disallow any game creation.
     * This limit is ignored for practice games.
     * @since 1.1.10
     * @see #CLIENT_MAX_CREATE_GAMES
     */
    public static final String PROP_JSETTLERS_CLI_MAXCREATEGAMES = "jsettlers.client.maxcreategames";

    /**
     * Property <tt>jsettlers.client.maxcreatechannels</tt> to limit the amount of
     * chat channels that a client can create at once. (The default is 2.)
     * Once a channel is deleted (all members leave), they can create another.
     * Set this to -1 to disable it; 0 will disallow any chat channel creation.
     * @since 1.1.10
     * @see #CLIENT_MAX_CREATE_CHANNELS
     * @see SOCServerFeatures#FEAT_CHANNELS
     */
    public static final String PROP_JSETTLERS_CLI_MAXCREATECHANNELS = "jsettlers.client.maxcreatechannels";

    /**
     * Property prefix <tt>jsettlers.gameopt.</tt> to specify game option defaults in a server properties file.
     * Option names are case-insensitive past this prefix. Syntax for default value is the same as on the
     * command line, for example:
     *<pre> jsettlers.gameopt.RD=y
     * jsettlers.gameopt.n7=t7</pre>
     *<P>
     * See {@link #parseCmdline_DashedArgs(String[])} for how game option properties are checked at startup.
     * @since 1.1.20
     */
    public static final String PROP_JSETTLERS_GAMEOPT_PREFIX = "jsettlers.gameopt.";

    /**
     * List and descriptions of all available JSettlers {@link Properties properties},
     * such as {@link #PROP_JSETTLERS_PORT} and {@link SOCDBHelper#PROP_JSETTLERS_DB_URL}.
     *<P>
     * Each property name is followed in the array by a brief description:
     * [0] is a property, [1] is its description, [2] is the next property, etc.
     * (This was added in 1.1.13 for {@link #printUsage(boolean)}}.
     *<P>
     * When you add or update any property, please also update <tt>/src/bin/jsserver.properties.sample</tt>.
     * @since 1.1.09
     */
    public static final String[] PROPS_LIST =
    {
        PROP_JSETTLERS_PORT,     "TCP port number for server to listen for client connections",
        PROP_JSETTLERS_CONNECTIONS,   "Maximum connection count, including robots (default " + SOC_MAXCONN_DEFAULT + ")",
        PROP_JSETTLERS_STARTROBOTS,   "Number of robots to create at startup (default " + SOC_STARTROBOTS_DEFAULT + ")",
        PROP_JSETTLERS_ACCOUNTS_OPEN, "Permit open self-registration of new user accounts? (if Y and using a DB)",
        PROP_JSETTLERS_ACCOUNTS_REQUIRED, "Require all players to have a user account? (if Y; requires a DB)",
        PROP_JSETTLERS_ACCOUNTS_ADMINS, "Permit only these usernames to create accounts (comma-separated)",
        PROP_JSETTLERS_ALLOW_DEBUG,   "Allow remote debug commands? (if Y)",
        PROP_JSETTLERS_CLI_MAXCREATECHANNELS,   "Maximum simultaneous channels that a client can create",
        PROP_JSETTLERS_CLI_MAXCREATEGAMES,      "Maximum simultaneous games that a client can create",
        PROP_JSETTLERS_GAMEOPT_PREFIX + "*",    "Game option defaults, case-insensitive: jsettlers.gameopt.RD=y",
        PROP_JSETTLERS_BOTS_COOKIE,             "Robot cookie value (default is random generated each startup)",
        PROP_JSETTLERS_BOTS_SHOWCOOKIE,         "Flag to show the robot cookie value at startup",
        SOCDBHelper.PROP_JSETTLERS_DB_BCRYPT_WORK__FACTOR, "For user accounts in DB, password encryption Work Factor (see README) (9 to "
            + soc.server.database.BCrypt.GENSALT_MAX_LOG2_ROUNDS + ')',
        SOCDBHelper.PROP_JSETTLERS_DB_SAVE_GAMES,  "Flag to save all games in DB (if 1 or Y)",
        SOCDBHelper.PROP_JSETTLERS_DB_USER,     "DB username",
        SOCDBHelper.PROP_JSETTLERS_DB_PASS,     "DB password",
        SOCDBHelper.PROP_JSETTLERS_DB_URL,      "DB connection URL",
        SOCDBHelper.PROP_JSETTLERS_DB_JAR,      "DB driver jar filename",
        SOCDBHelper.PROP_JSETTLERS_DB_DRIVER,   "DB driver class name",
        SOCDBHelper.PROP_JSETTLERS_DB_SETTINGS, "If set to \"write\", save DB settings properties values to the settings table and exit",
        SOCDBHelper.PROP_JSETTLERS_DB_SCRIPT_SETUP, "If set, full path or relative path to db setup sql script; will run and exit",
        SOCDBHelper.PROP_JSETTLERS_DB_UPGRADE__SCHEMA, "Flag: If set, server will upgrade the DB schema to latest version and exit (if 1 or Y)",
    };

    /**
     * Name used when sending messages from the server.
     */
    public static final String SERVERNAME = "Server";

    /**
     * Minimum required client version, to connect and play a game.
     * Same format as {@link soc.util.Version#versionNumber()}.
     * Currently there is no enforced minimum (0000).
     * @see #setClientVersSendGamesOrReject(StringConnection, int, boolean)
     */
    public static final int CLI_VERSION_MIN = 0000;

    /**
     * Minimum required client version, in "display" form, like "1.0.00".
     * Currently there is no minimum.
     * @see #setClientVersSendGamesOrReject(StringConnection, int, boolean)
     */
    public static final String CLI_VERSION_MIN_DISPLAY = "0.0.00";

    /**
     * If client never tells us their version, assume they are version 1.0.0 (1000).
     * @see #CLI_VERSION_TIMER_FIRE_MS
     * @see #handleJOINGAME(StringConnection, SOCJoinGame)
     * @since 1.1.06
     */
    public static final int CLI_VERSION_ASSUMED_GUESS = 1000;

    /**
     * Client version is guessed after this many milliseconds (1200) if the client
     * hasn't yet sent it to us.
     * @see #CLI_VERSION_ASSUMED_GUESS
     * @since 1.1.06
     */
    public static final int CLI_VERSION_TIMER_FIRE_MS = 1200;

    /**
     * If game will expire in this or fewer minutes, warn the players. Default is 10.
     * Must be at least twice the sleep-time in {@link SOCGameTimeoutChecker#run()}.
     * The game expiry time is set at game creation in {@link SOCGameListAtServer#createGame(String, String, Hashtable)}.
     *<P>
     * If you update this field, also update {@link #GAME_TIME_EXPIRE_CHECK_MINUTES}.
     *
     * @see #checkForExpiredGames(long)
     * @see SOCGameTimeoutChecker#run()
     * @see SOCGameListAtServer#GAME_EXPIRE_MINUTES
     * @see #GAME_TIME_EXPIRE_ADDTIME_MINUTES
     */
    public static int GAME_EXPIRE_WARN_MINUTES = 10;

    /**
     * Sleep time (minutes) between checks for expired games in {@link SOCGameTimeoutChecker#run()}.
     * Default is 5 minutes. Must be at most half of {@link #GAME_EXPIRE_WARN_MINUTES}
     * so the user has time to react after seeing the warning.
     * @since 1.2.00
     */
    public static int GAME_TIME_EXPIRE_CHECK_MINUTES = GAME_EXPIRE_WARN_MINUTES / 2;

    /**
     * Amount of time to add (30 minutes) when the <tt>*ADDTIME*</tt> command is used by a player.
     * @see #GAME_EXPIRE_WARN_MINUTES
     * @since 1.1.20
     */
    public static final int GAME_TIME_EXPIRE_ADDTIME_MINUTES = 30;
        // 30 minutes is hardcoded into some texts sent to players;
        // if you change it here, you will need to also search for those.

    /**
     * Force robot to end their turn after this many seconds
     * of inactivity. Default is 8.
     * @see #checkForExpiredTurns(long)
     * @since 1.1.11
     */
    public static int ROBOT_FORCE_ENDTURN_SECONDS = 8;

    /**
     * Force robot to end their turn after this much inactivity,
     * while they've made a trade offer. Default is 60 seconds.
     * @see #checkForExpiredTurns(long)
     * @since 1.1.11
     */
    public static int ROBOT_FORCE_ENDTURN_TRADEOFFER_SECONDS = 60;

    /**
     * Maximum permitted game name length, default 30 characters.
     * Before 1.1.13, the default maximum was 20 characters.
     *
     * @see #createOrJoinGameIfUserOK(StringConnection, String, String, String, Hashtable)
     * @since 1.1.07
     */
    public static int GAME_NAME_MAX_LENGTH = 30;

    /**
     * Maximum permitted player name length, default 20 characters.
     * The client already truncates to 20 characters in SOCPlayerClient.getValidNickname.
     *
     * @see #createOrJoinGameIfUserOK(StringConnection, String, String, String, Hashtable)
     * @since 1.1.07
     */
    public static int PLAYER_NAME_MAX_LENGTH = 20;

    /**
     * Maximum number of games that a client can create at the same time (default 5).
     * Once this limit is reached, the client must delete a game before creating a new one.
     * Set this to -1 to disable it; 0 will disallow any game creation.
     * This limit is ignored for practice games.
     * @since 1.1.10
     * @see #PROP_JSETTLERS_CLI_MAXCREATEGAMES
     */
    public static int CLIENT_MAX_CREATE_GAMES = 5;

    /**
     * Maximum number of chat channels that a client can create at the same time (default 2).
     * Once this limit is reached, the client must delete a channel before creating a new one.
     * Set this to -1 to disable it; 0 will disallow any chat channel creation.
     *<P>
     * If this field is nonzero when the server is initialized, the server calls
     * {@link SOCServerFeatures#add(String) features.add}({@link SOCServerFeatures#FEAT_CHANNELS}).
     * If the field value is changed afterwards, that affects new clients joining the server
     * but does not clear <tt>FEAT_CHANNELS</tt> from the <tt>features</tt> list.
     *
     * @since 1.1.10
     * @see #PROP_JSETTLERS_CLI_MAXCREATECHANNELS
     */
    public static int CLIENT_MAX_CREATE_CHANNELS = 2;

    /**
     * For local practice games (pipes, not TCP), the name of the pipe.
     * Used to distinguish practice vs "real" games.
     * 
     * @see soc.server.genericServer.LocalStringConnection
     */
    public static String PRACTICE_STRINGPORT = "SOCPRACTICE"; 

    /** {@link AuthSuccessRunnable#success(StringConnection, int)}
     *  result flag bit: Authentication succeeded.
     *  @see #AUTH_OR_REJECT__SET_USERNAME
     *  @see #AUTH_OR_REJECT__TAKING_OVER
     *  @since 1.1.19
     */
    private static final int AUTH_OR_REJECT__OK = 0x1;

    /** {@link AuthSuccessRunnable#success(StringConnection, int)}
     *  result flag bit: Authentication succeeded, is taking over another connection.
     *  @see #AUTH_OR_REJECT__OK
     *  @since 1.1.19
     */
    private static final int AUTH_OR_REJECT__TAKING_OVER = 0x2;

    /** {@link AuthSuccessRunnable#success(StringConnection, int)}
     *  result flag bit: Authentication succeeded, but nickname is not an exact case-sensitive match to DB username;
     *  client must be sent a status message with its exact nickname. See
     *  {@link #authOrRejectClientUser(StringConnection, String, String, int, boolean, boolean, AuthSuccessRunnable)}
     *  javadoc.
     *  @see #AUTH_OR_REJECT__OK
     *  @since 1.2.00
     */
    private static final int AUTH_OR_REJECT__SET_USERNAME = 0x4;

    /**
     * So we can get random numbers.
     */
    private Random rand = new Random();

    /**
     * Maximum number of connections allowed.
     * Remember that robots count against this limit.
     * Set with {@link #PROP_JSETTLERS_CONNECTIONS}.
     */
    protected int maxConnections;

    /**
     * Is a debug user allowed to run commands listed in {@link #DEBUG_COMMANDS_HELP}?
     * Default is false.  Set with {@link #PROP_JSETTLERS_ALLOW_DEBUG}.
     *<P>
     * Note that all practice games are debug mode, for ease of debugging;
     * to determine this, {@link #handleGAMETEXTMSG(StringConnection, SOCGameTextMsg)} checks if the
     * client is using {@link LocalStringConnection} to talk to the server.
     *<P>
     * Backported to 1.1.14 from 2.0.00.
     *
     * @see #processDebugCommand(StringConnection, String, String)
     * @since 1.1.14
     */
    private boolean allowDebugUser;

    /**
     * Properties for the server, or empty if that constructor wasn't used.
     * Property names are held in PROP_* and SOCDBHelper.PROP_* constants.
     * Some properties activate optional {@link #features}.
     * @see #SOCServer(int, Properties)
     * @see #PROPS_LIST
     * @since 1.1.09
     */
    private Properties props;

    /**
     * True if {@link #props} contains a property which is used to run the server in Utility Mode
     * instead of Server Mode.  In Utility Mode the server reads its properties, initializes its
     * database connection if any, and performs one task such as a password reset or table/index creation.
     * It won't start other threads and won't fail startup if TCP port binding fails.
     *<P>
     * For a list of Utility Mode properties, see {@link #hasUtilityModeProperty()}.
     *<P>
     * This flag is set early in {@link #initSocServer(String, String, Properties)};
     * if you add a property which sets Utility Mode, update that code.
     * @see #utilityModeMessage
     * @since 1.1.20
     */
    private boolean hasUtilityModeProp;

    /**
     * If {@link #hasUtilityModeProp}, an optional status message to print before exiting, or <tt>null</tt>.
     * @since 1.1.20
     */
    private String utilityModeMessage;

    /**
     * Active optional server features, if any; see {@link SOCServerFeatures} constants for currently defined features.
     * Features are activated through the command line or {@link #props}.
     * @since 1.1.19
     */
    private SOCServerFeatures features = new SOCServerFeatures(false);

    /**
     * Server internal flag to indicate that user accounts are active, and authentication
     * is required to create accounts, and there aren't any accounts in the database yet.
     * (Server's active features include {@link SOCServerFeatures#FEAT_ACCTS} but not
     * {@link SOCServerFeatures#FEAT_OPEN_REG}.) This flag is set at startup, instead of
     * querying {@link SOCDBHelper#countUsers()} every time a client connects.
     *<P>
     * Used for signaling to <tt>SOCAccountClient</tt> that it shouldn't ask for a
     * password when connecting to create the first account, by sending the client
     * {@link SOCServerFeatures#FEAT_OPEN_REG} along with the actually active features.
     *<P>
     * The first successful account creation will clear this flag.
     *<P>
     * {@link #handleCREATEACCOUNT(StringConnection, SOCCreateAccount)} does call <tt>countUsers()</tt>
     * and requires auth if any account exists, even if this flag is set.
     * @since 1.1.19
     */
    private boolean acctsNotOpenRegButNoUsers;

    /**
     * Randomly generated cookie string required for robot clients to connect
     * and identify as bots using {@link SOCImARobot}, or <tt>null</tt>.
     * It isn't sent encrypted and is a weak "shared secret".
     * Generated in {@link #generateRobotCookie()} unless the server is given
     * {@link #PROP_JSETTLERS_BOTS_COOKIE} at startup, which can set it to
     * any string or to <tt>null</tt> if the property is empty.
     *<P>
     * The value must pass {@link SOCMessage#isSingleLineAndSafe(String)}:
     * Must not contain the <tt>'|'</tt> or <tt>','</tt> characters.
     * @since 1.1.19
     */
    private String robotCookie;

    /**
     * A list of robot {@link StringConnection}s connected to this server.
     * @see SOCPlayerLocalRobotRunner#robotClients
     */
    protected Vector robots = new Vector();

    /**
     * Robot default parameters; copied for each newly connecting robot.
     * Changing this will not change parameters of any robots already connected.
     *
     * @see #handleIMAROBOT(StringConnection, soc.message.SOCImARobot)
     * @see soc.robot.SOCRobotDM
     */
    public static SOCRobotParameters ROBOT_PARAMS_DEFAULT
        = new SOCRobotParameters(120, 35, 0.13f, 1.0f, 1.0f, 3.0f, 1.0f, 1, 1);
        // Formerly a literal in handleIMAROBOT.
        // Strategy type 1 == SOCRobotDM.FAST_STRATEGY.
        // If you change values here, see handleIMAROBOT(..)
        // and SOCPlayerClient.startPracticeGame(..)
        // for assumptions which may also need to be changed.

    /**
     * Smarter robot default parameters. (For practice games; not referenced by server)
     * Same as ROBOT_PARAMS_DEFAULT but with SMART_STRATEGY, not FAST_STRATEGY.
     *
     * @see #ROBOT_PARAMS_DEFAULT
     * @see soc.robot.SOCRobotDM
     */
    public static SOCRobotParameters ROBOT_PARAMS_SMARTER
        = new SOCRobotParameters(120, 35, 0.13f, 1.0f, 1.0f, 3.0f, 1.0f, 0, 1);

    /**
     * Did the command line include an option that prints some information
     * (like --help or --version) and should exit, instead of starting the server?
     * Set in {@link #parseCmdline_DashedArgs(String[])}.
     * @since 1.1.15
     */
    private static boolean hasStartupPrintAndExit = false;

    /**
     * Did the properties or command line include --option / -o to set {@link SOCGameOption game option} values?
     * Checked in constructors for possible stderr option-values printout.
     * @since 1.1.07
     */
    public static boolean hasSetGameOptions = false;

    /** Status Message to send, nickname already logged into the system */
    public static final String MSG_NICKNAME_ALREADY_IN_USE
        = "Someone with that nickname is already logged into the system.";

    /**
     * Status Message to send, nickname already logged into the system.
     * Prepend to {@link #MSG_NICKNAME_ALREADY_IN_USE}.
     * The "take over" option is used for reconnect when a client loses
     * connection, and server doesn't realize it.
     * A new connection can "take over" the name after a minute's timeout.
     * @since 1.1.08
     */
    public static final String MSG_NICKNAME_ALREADY_IN_USE_WAIT_TRY_AGAIN
        = " and try again. ";

    /**
     * Part 1 of Status Message to send, nickname already logged into the system
     * with a newer client version.  Prepend to version number required.
     * The "take over" option is used for reconnect when a client loses
     * connection, and server doesn't realize it.
     * @see #MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P2
     * @since 1.1.08
     */
    public static final String MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P1
        = "You need client version ";

    /**
     * Part 2 of Status Message to send, nickname already logged into the system
     * with a newer client version.  Append to version number required.
     * @see #MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P1
     * @since 1.1.08
     */
    public static final String MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P2
        = " or newer to take over this connection.";

    /**
     * Number of seconds before a connection is considered disconnected, and
     * its nickname can be "taken over" by a new connection with the right password.
     * Used only when a password is given by the new connection.
     * @see #checkNickname(String, StringConnection, boolean, boolean)
     * @since 1.1.08
     */
    public static final int NICKNAME_TAKEOVER_SECONDS_SAME_PASSWORD = 15;

    /**
     * Number of seconds before a connection is considered disconnected, and
     * its nickname can be "taken over" by a new connection from the same IP.
     * Used when no password is given by the new connection.
     * @see #checkNickname(String, StringConnection, boolean, boolean)
     * @since 1.1.08
     */
    public static final int NICKNAME_TAKEOVER_SECONDS_SAME_IP = 30;

    /**
     * Number of seconds before a connection is considered disconnected, and
     * its nickname can be "taken over" by a new connection from a different IP.
     * Used when no password is given by the new connection.
     * @see #checkNickname(String, StringConnection, boolean, boolean)
     * @since 1.1.08
     */
    public static final int NICKNAME_TAKEOVER_SECONDS_DIFFERENT_IP = 150;

    /**
     * list of chat channels
     *<P>
     * Instead of calling {@link SOCChannelList#deleteChannel(String)},
     * call {@link #destroyChannel(String)} to also clean up related server data.
     */
    protected SOCChannelList channelList = new SOCChannelList();

    /**
     * list of soc games
     */
    protected SOCGameListAtServer gameList = new SOCGameListAtServer();

    /**
     * table of requests for robots to join games
     */
    protected Hashtable robotJoinRequests = new Hashtable();

    /**
     * table of requestst for robots to leave games
     */
    protected Hashtable robotDismissRequests = new Hashtable();

    /**
     * table of game data files
     */
    protected Hashtable gameDataFiles = new Hashtable();

    /**
     * the current game event record
     */

    //protected SOCGameEventRecord currentGameEventRecord;

    /**
     * the time that this server was started
     */
    protected long startTime;

    /**
     * The total number of games that have been started:
     * {@link GameHandler#startGame(SOCGame)} has been called
     * and game play has begun. Game state became {@link SOCGame#READY}
     * or higher from an earlier/lower state.
     */
    protected int numberOfGamesStarted;

    /**
     * The total number of games finished: Game state became {@link SOCGame#OVER} or higher
     * from an earlier/lower state. Incremented in {@link #gameOverIncrGamesFinishedCount()}.
     *<P>
     * Before v1.1.20 this was the number of games destroyed, and <tt>*STATS*</tt>
     * wouldn't reflect a newly finished game until all players had left that game.
     */
    protected int numberOfGamesFinished;

    /**
     * total number of users
     */
    protected int numberOfUsers;

    /**
     * Client version count stats since startup (includes bots).
     * Incremented from {@link #handleVERSION(StringConnection, SOCVersion)};
     * currently assumes single-threaded access to this map.
     *<P>
     * Key = <tt>Integer</tt> version number, Value = <tt>Integer</tt> client count.
     * @since 1.1.19
     */
    protected HashMap clientPastVersionStats;

    /**
     * Timer for delaying auth replies for consistency with {@code BCrypt} timing. Used when
     * {@code ! hadDelay} in {@link SOCDBHelper.AuthPasswordRunnable#authResult(String, boolean)} callbacks.
     * @since 1.2.00
     */
    private Timer replyAuthTimer = new Timer(true);  // use daemon thread

    /**
     * server robot pinger
     */
    SOCServerRobotPinger serverRobotPinger;

    /**
     * game timeout checker
     */
    SOCGameTimeoutChecker gameTimeoutChecker;

    String databaseUserName;
    String databasePassword;

    /**
     * User admins list, from {@link #PROP_JSETTLERS_ACCOUNTS_ADMINS}, or {@code null} if not specified.
     * Unless {@link SOCServerFeatures#FEAT_OPEN_REG} is active, only usernames on this list
     * can create user accounts in {@link #handleCREATEACCOUNT(StringConnection, SOCCreateAccount)}.
     *<P>
     * If DB schema &gt;= {@link SOCDBHelper#SCHEMA_VERSION_1200}, this list is
     * made lowercase for case-insensitive checks in {@link #isUserDBUserAdmin(String)}.
     *<P>
     * Before v1.2.00, if this was {@code null} any authenticated user could create other accounts.
     *
     * @since 1.1.19
     */
    private Set<String> databaseUserAdmins;

    /**
     * Create a Settlers of Catan server listening on TCP port <tt>p</tt>.
     * Most server threads are started here; you must start its main thread yourself.
     * Optionally connect to a database for user info and game stats.
     *<P>
     * No bots will be started here ({@link #PROP_JSETTLERS_STARTROBOTS} == 0),
     * call {@link #setupLocalRobots(int, int)} if bots are wanted.
     *<P>
     * In 1.1.07 and later, will also print game options to stderr if
     * any option defaults require a minimum client version, or if 
     * {@link #hasSetGameOptions} is set.
     *
     * @param p    the TCP port that the server listens on
     * @param mc   the maximum number of connections allowed;
     *            remember that robots count against this limit.
     * @param databaseUserName  the user name for accessing the database
     * @param databasePassword  the password for the user
     * @throws SocketException  If a network setup problem occurs
     * @throws EOFException   If db setup script ran successfully and server should exit now
     * @throws SQLException   If db setup script fails
     * @throws IllegalStateException  If {@link Version#versionNumber()} returns 0 (packaging error)
     */
    public SOCServer(int p, int mc, String databaseUserName, String databasePassword)
        throws SocketException, EOFException, SQLException, IllegalStateException
    {
        super(p);
        maxConnections = mc;
        initSocServer(databaseUserName, databasePassword, null);
    }

    /**
     * Create a Settlers of Catan server listening on TCP port <tt>p</tt>.
     * Most server threads are started here; you must start its main thread yourself.
     * Optionally connect to a database for user info and game stats.
     *<P>
     * The database properties are {@link SOCDBHelper#PROP_JSETTLERS_DB_USER}
     * and {@link SOCDBHelper#PROP_JSETTLERS_DB_PASS}.
     *<P>
     * To run a DB setup script to create database tables, send its filename
     * or relative path as {@link SOCDBHelper#PROP_JSETTLERS_DB_SCRIPT_SETUP}.
     *<P>
     * If a db URL or other DB properties are specified in <tt>props</tt>, but <tt>SOCServer</tt>
     * can't successfully connect to that database, this constructor throws <tt>SQLException</tt>;
     * for details see {@link #initSocServer(String, String, Properties)}.
     * Other constructors can't set those properties, and will instead
     * continue <tt>SOCServer</tt> startup and run without any database.
     *<P>
     * Will also print game options to stderr if
     * any option defaults require a minimum client version, or if 
     * {@link #hasSetGameOptions} is set.
     *
     *<H3>Utility Mode:</H3>
     * Some properties such as {@link SOCDBHelper#PROP_JSETTLERS_DB_SCRIPT_SETUP}
     * will initialize the server environment, connect to the database, perform
     * a single task, and exit.  This is called <B>Utility Mode</B>.  In Utility Mode
     * the caller should not start threads or continue normal startup (Server Mode).
     * See {@link #hasUtilityModeProperty()} for more details.
     *<P>
     * For the password reset property {@link SOCDBHelper#PROP_IMPL_JSETTLERS_PW_RESET}, the
     * caller will need to prompt for and change the password; this constructor will not do that.
     *
     * @param p    the TCP port that the server listens on
     * @param props  null, or properties containing {@link #PROP_JSETTLERS_CONNECTIONS}
     *       and any other desired properties. If <tt>props<tt> contains game option default values
     *       (see below) with non-uppercase gameopt names, cannot be read-only: Startup will
     *       replace keys such as <tt>"jsettlers.gameopt.vp"</tt> with their canonical
     *       uppercase equivalent: <tt>"jsettlers.gameopt.VP"</tt>
     *       <P>
     *       If <tt>props</tt> is null, the properties will be created empty
     *       and no bots will be started ({@link #PROP_JSETTLERS_STARTROBOTS} == 0).
     *       If <tt>props</tt> != null but doesn't contain {@link #PROP_JSETTLERS_STARTROBOTS},
     *       the default value {@link #SOC_STARTROBOTS_DEFAULT} will be used.
     *       <P>
     *       <tt>props</tt> may contain game option default values (property names starting
     *       with {@link #PROP_JSETTLERS_GAMEOPT_PREFIX}).  Calls {@link #parseCmdline_GameOption(String, HashMap)}
     *       for each one found to set its default (current) value.
     *       <P>
     *       If you provide <tt>props</tt>, consider checking for a <tt>jsserver.properties</tt> file
     *       ({@link #SOC_SERVER_PROPS_FILENAME}) and calling {@link Properties#load(java.io.InputStream)}
     *       with it before calling this constructor.
     * @since 1.1.09
     * @see #PROPS_LIST
     * @throws SocketException  If a network setup problem occurs
     * @throws EOFException   If db setup script ran successfully and server should exit now
     * @throws SQLException   If db setup script fails, or need db but can't connect,
     *       or if other problems with DB-related contents of {@code props}
     *       (exception's {@link Throwable#getCause()} will be an {@link IllegalArgumentException} or
     *       {@link DBSettingMismatchException}); see {@link SOCDBHelper#initialize(String, String, Properties)} javadoc.
     *       This constructor prints the SQLException details to {@link System#err},
     *       caller doesn't need to extract the cause and print those same details.
     * @throws IllegalArgumentException  If <tt>props</tt> contains game options (<tt>jsettlers.gameopt.*</tt>)
     *       with bad syntax. See {@link #PROP_JSETTLERS_GAMEOPT_PREFIX} for expected syntax.
     *       See {@link #parseCmdline_DashedArgs(String[])} for how game option properties are checked.
     *       {@link Throwable#getMessage()} will have problem details.
     * @throws IllegalStateException  If {@link Version#versionNumber()} returns 0 (packaging error)
     */
    public SOCServer(final int p, Properties props)
        throws SocketException, EOFException, SQLException, IllegalArgumentException, IllegalStateException
    {
        super(p);

        maxConnections = init_getIntProperty(props, PROP_JSETTLERS_CONNECTIONS, 15);
        allowDebugUser = init_getBoolProperty(props, PROP_JSETTLERS_ALLOW_DEBUG, false);
        CLIENT_MAX_CREATE_GAMES = init_getIntProperty(props, PROP_JSETTLERS_CLI_MAXCREATEGAMES, CLIENT_MAX_CREATE_GAMES);
        CLIENT_MAX_CREATE_CHANNELS = init_getIntProperty(props, PROP_JSETTLERS_CLI_MAXCREATECHANNELS, CLIENT_MAX_CREATE_CHANNELS);
        String dbuser = props.getProperty(SOCDBHelper.PROP_JSETTLERS_DB_USER, "dbuser");
        String dbpass = props.getProperty(SOCDBHelper.PROP_JSETTLERS_DB_PASS, "dbpass");
        initSocServer(dbuser, dbpass, props);
    }

    /**
     * Create a Settlers of Catan server listening on local stringport <tt>s</tt>.
     * Most server threads are started here; you must start its main thread yourself.
     *<P>
     * No bots will be started here ({@link #PROP_JSETTLERS_STARTROBOTS} == 0),
     * call {@link #setupLocalRobots(int, int)} if bots are wanted.
     *<P>
     * In 1.1.07 and later, will also print game options to stderr if
     * any option defaults require a minimum client version, or if 
     * {@link #hasSetGameOptions} is set.
     *
     * @param s    the stringport that the server listens on.
     *             If this is a "practice game" server on the user's local computer,
     *             please use {@link #PRACTICE_STRINGPORT}.
     * @param mc   the maximum number of connections allowed;
     *            remember that robots count against this limit.
     * @param databaseUserName  the user name for accessing the database
     * @param databasePassword  the password for the user
     * @throws SocketException  If a network setup problem occurs
     * @throws EOFException   If db setup script ran successfully and server should exit now
     * @throws SQLException   If db setup script fails
     * @throws IllegalStateException  If {@link Version#versionNumber()} returns 0 (packaging error)
     */
    public SOCServer(String s, int mc, String databaseUserName, String databasePassword)
        throws SocketException, EOFException, SQLException, IllegalStateException
    {
        super(s);

        maxConnections = mc;
        initSocServer(databaseUserName, databasePassword, null);
    }

    /**
     * Common init for all constructors.
     * Prints some progress messages to {@link System#err}.
     * Sets game option default values via {@link #init_propsSetGameopts(Properties)}.
     * Starts all server threads except the main thread, unless constructed in Utility Mode
     * ({@link #hasUtilityModeProp}).
     * If {@link #PROP_JSETTLERS_STARTROBOTS} is specified, those aren't started until {@link #serverUp()}.
     *<P>
     * If there are problems with the network setup ({@link #error} != null),
     * this method will throw {@link SocketException}.
     *<P>
     * If problems running a {@link SOCDBHelper#PROP_JSETTLERS_DB_SCRIPT_SETUP db setup script}
     * or {@link SOCDBHelper#PROP_JSETTLERS_DB_UPGRADE__SCHEMA schema upgrade},
     * this method will throw {@link SQLException}.
     *<P>
     *If we can't connect to a database, but it looks like we need one (because
     * {@link SOCDBHelper#PROP_JSETTLERS_DB_URL}, {@link SOCDBHelper#PROP_JSETTLERS_DB_DRIVER}
     * or {@link SOCDBHelper#PROP_JSETTLERS_DB_JAR} is specified in <tt>props</tt>),
     * or there are other problems with DB-related contents of {@code props}
     * (see {@link SOCDBHelper#initialize(String, String, Properties)}; exception's {@link Throwable#getCause()}
     * will be an {@link IllegalArgumentException} or {@link DBSettingMismatchException}) this method will
     * print details to {@link System#err} and throw {@link SQLException}.
     *
     *<H5>Utility Mode</H5>
     * If a db setup script runs successfully, or <tt>props</tt> contains the password reset parameter
     * {@link SOCDBHelper#PROP_IMPL_JSETTLERS_PW_RESET}, the server does not complete its startup;
     * this method will set {@link #hasUtilityModeProp} and (only for setup script) throw {@link EOFException}.
     *<P>
     * For the password reset parameter, the caller will need to prompt for and change the password;
     * this method will not do that.
     *
     * @param databaseUserName Used for DB connect - not retained
     * @param databasePassword Used for DB connect - not retained
     * @param props  null, or properties containing {@link #PROP_JSETTLERS_CONNECTIONS}
     *       and any other desired properties. If <tt>props</tt> contains game option default values
     *       (see below) with non-uppercase gameopt names, cannot be read-only: Startup will
     *       replace keys such as <tt>"jsettlers.gameopt.vp"</tt> with their canonical
     *       uppercase equivalent: <tt>"jsettlers.gameopt.VP"</tt>
     *       <P>
     *       If <code>props</code> is null, the properties will be created empty
     *       and no bots will be started ({@link #PROP_JSETTLERS_STARTROBOTS} == 0).
     *       If <code>props</code> != null but doesn't contain {@link #PROP_JSETTLERS_STARTROBOTS},
     *       the default value {@link #SOC_STARTROBOTS_DEFAULT} will be used.
     *       <P>
     *       <code>props</code> may contain game option default values (property names starting
     *       with {@link #PROP_JSETTLERS_GAMEOPT_PREFIX}).  Calls {@link #parseCmdline_GameOption(String, HashMap)}
     *       for each one found to set its default (current) value.
     * @throws SocketException  If a network setup problem occurs
     * @throws EOFException   If db setup script ran successfully and server should exit now;
     *       thrown in Utility Mode ({@link #hasUtilityModeProp}).
     * @throws SQLException   If db setup script fails, or need db but can't connect
     * @throws IllegalArgumentException  If <tt>props</tt> contains game options (<tt>jsettlers.gameopt.*</tt>)
     *       with bad syntax. See {@link #PROP_JSETTLERS_GAMEOPT_PREFIX} for expected syntax.
     *       See {@link #parseCmdline_DashedArgs(String[])} for how game option properties are checked.
     *       Also thrown if {@link SOCDBHelper#PROP_JSETTLERS_DB_UPGRADE__SCHEMA} flag
     *       is set, but {@link SOCDBHelper#isSchemaLatestVersion()}. {@link Throwable#getMessage()} will have
     *       problem details for any {@code IllegalArgumentException} thrown here.
     * @throws IllegalStateException  If {@link Version#versionNumber()} returns 0 (packaging error)
     */
    private void initSocServer(String databaseUserName, String databasePassword, Properties props)
        throws SocketException, EOFException, SQLException, IllegalArgumentException, IllegalStateException
    {
        Version.printVersionText(System.err, "Java Settlers Server ");
        if (Version.versionNumber() == 0)
        {
            throw new IllegalStateException("Packaging error: Cannot determine JSettlers version");
        }

        final boolean wants_upg_schema
            = init_getBoolProperty(props, SOCDBHelper.PROP_JSETTLERS_DB_UPGRADE__SCHEMA, false);
        boolean db_test_bcrypt_mode = false;
        if (props != null)
        {
            final String val = props.getProperty(SOCDBHelper.PROP_JSETTLERS_DB_BCRYPT_WORK__FACTOR);
            if (val != null)
            {
                db_test_bcrypt_mode = (val.equalsIgnoreCase("test"));
                if (db_test_bcrypt_mode)
                    // make sure DBH.initialize won't try to parse "test" as an integer
                    props.remove(SOCDBHelper.PROP_JSETTLERS_DB_BCRYPT_WORK__FACTOR);
            }
        }

        // Set this flag as early as possible
        hasUtilityModeProp = (props != null)
            && ((null != props.getProperty(SOCDBHelper.PROP_JSETTLERS_DB_SCRIPT_SETUP))
                || props.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_SETTINGS)
                || (null != props.getProperty(SOCDBHelper.PROP_IMPL_JSETTLERS_PW_RESET))
                || wants_upg_schema || db_test_bcrypt_mode);

        /* Check for problems during super setup (such as port already in use).
         * Ignore net errors if we're running a DB setup script and then exiting.
         */
        if ((error != null) && ! hasUtilityModeProp)
        {
            final String errMsg = "* Exiting due to network setup problem: " + error.toString();
            throw new SocketException(errMsg);
        }

        if (props == null)
        {
            props = new Properties();
        } else {
            // Add any default properties if not specified.

            if (! props.containsKey(PROP_JSETTLERS_STARTROBOTS))
                props.setProperty(PROP_JSETTLERS_STARTROBOTS, Integer.toString(SOC_STARTROBOTS_DEFAULT));

            // Set game option defaults from any jsettlers.gameopt.* properties found.
            // If problems found, throws IllegalArgumentException with details.
            init_propsSetGameopts(props);
        }

        this.props = props;

        if (allowDebugUser)
        {
            System.err.println("Warning: Remote debug commands are allowed.");
        }

        /**
         * See if the user specified a non-random robot cookie value.
         */
        if (props.containsKey(PROP_JSETTLERS_BOTS_COOKIE))
        {
            final String cook = props.getProperty(PROP_JSETTLERS_BOTS_COOKIE).trim();
            if (cook.length() > 0)
            {
                if (SOCMessage.isSingleLineAndSafe(cook))
                {
                    robotCookie = cook;
                } else {
                    final String errmsg = "Error: The robot cookie value (param " + PROP_JSETTLERS_BOTS_COOKIE
                        + ") can't contain comma or pipe characters.";
                    System.err.println(errmsg);
                    throw new IllegalArgumentException(errmsg);
                }
            }
            // else robotCookie remains null
        } else {
            robotCookie = generateRobotCookie();
        }

        final boolean accountsRequired = init_getBoolProperty(props, PROP_JSETTLERS_ACCOUNTS_REQUIRED, false);

        /**
         * Try to connect to the DB, if any.
         * Running SOCDBHelper.initialize(..) will handle some Utility Mode properties
         * like PROP_JSETTLERS_DB_SETTINGS if present.
         */
        boolean db_err_printed = false;
        try
        {
            SOCDBHelper.initialize(databaseUserName, databasePassword, props);
            features.add(SOCServerFeatures.FEAT_ACCTS);
            System.err.println("User database initialized.");

            if (props.getProperty(SOCDBHelper.PROP_JSETTLERS_DB_SCRIPT_SETUP) != null)
            {
                // the sql script was ran by initialize

                final String msg = "DB setup script successful";
                utilityModeMessage = msg;
                throw new EOFException(msg);
            }

            // set some DB-related SOCServer fields: acctsNotOpenRegButNoUsers, databaseUserAdmins
            initSocServer_dbParamFields(accountsRequired, wants_upg_schema);

            // check schema version, upgrade if requested:
            if (! SOCDBHelper.isSchemaLatestVersion())
            {
                if (wants_upg_schema)
                {
                    try
                    {
                        SOCDBHelper.upgradeSchema(databaseUserAdmins);

                        String msg = "DB schema upgrade was successful";
                        if (SOCDBHelper.doesSchemaUpgradeNeedBGTasks())
                            msg += "; some upgrade tasks will complete in the background during normal server operation";
                        utilityModeMessage = msg;

                        throw new EOFException(msg);
                    }
                    catch (EOFException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        db_err_printed = true;
                        if (e instanceof MissingResourceException)
                            System.err.println("* To begin schema upgrade, please fix and rerun: " + e.getMessage());
                        else
                            System.err.println(e);

                        if (e instanceof SQLException)
                        {
                            throw (SQLException) e;
                        } else {
                            SQLException sqle = new SQLException("Error during DB schema upgrade");
                            sqle.initCause(e);
                            throw sqle;
                        }
                    }
                } else {
                    System.err.println("\n* Database schema upgrade is recommended: To upgrade, use -D"
                        + SOCDBHelper.PROP_JSETTLERS_DB_UPGRADE__SCHEMA + "=Y command line flag.\n");
                }
            }
            else if (wants_upg_schema)
            {
                db_err_printed = true;
                final String errmsg = "* Cannot upgrade database schema: Already at latest version";
                System.err.println(errmsg);
                throw new IllegalArgumentException(errmsg);
            }

            // reminder: if props.getProperty(SOCDBHelper.PROP_IMPL_JSETTLERS_PW_RESET),
            // caller will need to prompt for and change the password
        }
        catch (SQLException sqle) // just a warning
        {
            if (wants_upg_schema && db_err_printed)
            {
                // the schema upgrade failed to complete; upgradeSchema() printed the exception.
                // don't continue server startup with just a warning

                throw sqle;
            }

            System.err.println("Warning: No user database available: " + sqle.getMessage());
            Throwable cause = sqle.getCause();
            while ((cause != null) && ! (cause instanceof ClassNotFoundException))
            {
                System.err.println("\t" + cause);
                cause = cause.getCause();
            }

            if (wants_upg_schema || (props.getProperty(SOCDBHelper.PROP_JSETTLERS_DB_SCRIPT_SETUP) != null))
            {
                // the sql script ran in initialize failed to complete;
                // now that we've printed the exception, don't continue server startup with just a warning

                throw sqle;
            }

            String propReqDB = null;
            if (accountsRequired)
                propReqDB = PROP_JSETTLERS_ACCOUNTS_REQUIRED;
            else if (props.containsKey(PROP_JSETTLERS_ACCOUNTS_ADMINS))
                propReqDB = PROP_JSETTLERS_ACCOUNTS_ADMINS;

            if (propReqDB != null)
            {
                final String errMsg = "* Property " + propReqDB + " requires a database.";
                System.err.println(errMsg);
                System.err.println("\n* Exiting because current startup properties specify a database.");
                throw new SQLException(errMsg);
            }

            if (props.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_URL)
                || props.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_JAR)
                || props.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_DRIVER))
            {
                // If other db props were asked for, the user is expecting a DB.
                // So, fail instead of silently continuing without it.
                System.err.println("* Exiting because current startup properties specify a database.");
                throw sqle;
            }

            if (props.containsKey(SOCDBHelper.PROP_IMPL_JSETTLERS_PW_RESET))
            {
                System.err.println("* Exiting because --pw-reset requires a database.");
                throw sqle;
            }

            System.err.println("Users will not be authenticated.");
        }
        catch (EOFException eox)  // successfully ran script or schema upgrade, signal to exit
        {
            throw eox;
        }
        catch (IOException iox) // error from requested script
        {
            System.err.println("\n* Could not run database setup script: " + iox.getMessage());
            Throwable cause = iox.getCause();
            while ((cause != null) && ! (cause instanceof ClassNotFoundException))
            {
                System.err.println("\t" + cause);
                cause = cause.getCause();
            }

            try
            {
                SOCDBHelper.cleanup(true);
            }
            catch (SQLException x) { }

            SQLException sqle = new SQLException("Error running DB setup script");
            sqle.initCause(iox);
            throw sqle;
        }
        catch (IllegalArgumentException iax)
        {
            System.err.println("\n* Error in specified database properties: " + iax.getMessage());
            SQLException sqle = new SQLException("Error with DB props");
            sqle.initCause(iax);
            throw sqle;
        }
        catch (DBSettingMismatchException dx)
        {
            // initialize(..) already printed details to System.err
            System.err.println("\n* Mismatch between database settings and specified properties");
            SQLException sqle = new SQLException("DB settings mismatch");
            sqle.initCause(dx);
            throw sqle;
        }

        // No errors; continue normal startup.

        if (db_test_bcrypt_mode)
            SOCDBHelper.testBCryptSpeed();

        if (hasUtilityModeProp)
        {
            return;  // <--- don't continue startup if Utility Mode ---
        }

        if (SOCDBHelper.isInitialized())
        {
            if (accountsRequired)
                System.err.println("User database accounts are required for all players.");

            // Note: This hook is not triggered under eclipse debugging.
            //    https://bugs.eclipse.org/bugs/show_bug.cgi?id=38016  "WONTFIX/README" since 2007-07-18
            try
            {
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    public void run() {
                        System.err.println("\n--\n-- shutdown; disconnecting from db --\n--\n");
                        System.err.flush();
                        try
                        {
                            // Before disconnect, do a final check for unexpected DB settings changes
                            try
                            {
                                SOCDBHelper.checkSettings(true, false);
                            } catch (Exception x) {}

                            SOCDBHelper.cleanup(true);
                        }
                        catch (SQLException x) { }
                    }
                });
            } catch (Throwable th)
            {
                // just a warning
                System.err.println("Warning: Could not register shutdown hook for database disconnect. Check java security settings.");            
            }
        }

        startTime = System.currentTimeMillis();
        numberOfGamesStarted = 0;
        numberOfGamesFinished = 0;
        numberOfUsers = 0;
        clientPastVersionStats = new HashMap();

        if (CLIENT_MAX_CREATE_CHANNELS != 0)
            features.add(SOCServerFeatures.FEAT_CHANNELS);

        /**
         *  Start various threads.
         */
        serverRobotPinger = new SOCServerRobotPinger(this, robots);
        serverRobotPinger.start();
        gameTimeoutChecker = new SOCGameTimeoutChecker(this);
        gameTimeoutChecker.start();
        this.databaseUserName = databaseUserName;
        this.databasePassword = databasePassword;

        /**
         * Print game options if we've set them on commandline, or if
         * any option defaults require a minimum client version.
         */
        if (hasSetGameOptions || (SOCGameOption.optionsMinimumVersion(SOCGameOption.getAllKnownOptions()) > -1))
        {
            Thread.yield();  // wait for other output to appear first
            try { Thread.sleep(200); } catch (InterruptedException ie) {}

            printGameOptions();
        }

        if (init_getBoolProperty(props, PROP_JSETTLERS_BOTS_SHOWCOOKIE, false))
            System.err.println("Robot cookie: " + robotCookie);

        System.err.print("The server is ready.");
        if (port > 0)
            System.err.print(" Listening on port " + port);
        System.err.println();

        if (SOCDBHelper.isInitialized() && SOCDBHelper.doesSchemaUpgradeNeedBGTasks())
            SOCDBHelper.startSchemaUpgradeBGTasks();  // includes 5-second sleep before conversions begin

        System.err.println();
    }

    /**
     * Set some DB-related SOCServer fields and features:
     * {@link #databaseUserAdmins} from {@link #PROP_JSETTLERS_ACCOUNTS_ADMINS},
     * {@link #features}({@link SOCServerFeatures#FEAT_OPEN_REG}) and {@link #acctsNotOpenRegButNoUsers}
     * from {@link #PROP_JSETTLERS_ACCOUNTS_OPEN}.
     *<P>
     * Prints some status messages and any problems to {@link System#err}.
     *<P>
     * Must not call this method until after {@link SOCDBHelper#initialize(String, String, Properties)}.
     *
     * @param accountsRequired  Are accounts required? Caller should check {@link #PROP_JSETTLERS_ACCOUNTS_REQUIRED}.
     * @param wantsUpgSchema  If true, server is preparing to try to upgrade the schema and exit.
     *     Certain hint messages here won't be printed, because the server is exiting afterwards.
     * @throws IllegalArgumentException if {@link #PROP_JSETTLERS_ACCOUNTS_ADMINS} is inconsistent or empty
     * @throws SQLException  if unexpected problem with DB when calling {@link SOCDBHelper#countUsers()}
     *     for {@link #acctsNotOpenRegButNoUsers}
     * @since 1.2.00
     */
    private void initSocServer_dbParamFields(final boolean accountsRequired, final boolean wantsUpgSchema)
        throws IllegalArgumentException, SQLException
    {
        // open reg for user accounts?  if not, see if we have any yet
        if (init_getBoolProperty(props, PROP_JSETTLERS_ACCOUNTS_OPEN, false))
        {
            features.add(SOCServerFeatures.FEAT_OPEN_REG);
            if (! hasUtilityModeProp)
                System.err.println("User database Open Registration is active, anyone can create accounts.");
        } else {
            if (SOCDBHelper.countUsers() == 0)
                acctsNotOpenRegButNoUsers = true;
        }

        if (props.containsKey(PROP_JSETTLERS_ACCOUNTS_ADMINS))
        {
            String errmsg = null;

            final String userAdmins = props.getProperty(PROP_JSETTLERS_ACCOUNTS_ADMINS);
            if (! SOCDBHelper.isInitialized())
            {
                errmsg = "* Property " + PROP_JSETTLERS_ACCOUNTS_ADMINS + " requires a database.";
            } else if (userAdmins.length() == 0) {
                errmsg = "* Property " + PROP_JSETTLERS_ACCOUNTS_ADMINS + " cannot be an empty string.";
            } else if (features.isActive(SOCServerFeatures.FEAT_OPEN_REG)) {
                errmsg = "* Cannot use Open Registration with User Account Admins List.";
            } else {
                final boolean downcase = (SOCDBHelper.getSchemaVersion() >= SOCDBHelper.SCHEMA_VERSION_1200);
                databaseUserAdmins = new HashSet<String>();
                String[] admins = userAdmins.split(SOCMessage.sep2);  // split on "," - sep2 will never be in a username
                for (int i = 0; i < admins.length; ++i)
                {
                    String na = admins[i].trim();
                    if (na.length() > 0)
                    {
                        if (downcase)
                            na = na.toLowerCase(Locale.US);
                        databaseUserAdmins.add(na);
                    }
                }
                if (databaseUserAdmins.isEmpty())  // was it commas only?
                    errmsg = "* Property " + PROP_JSETTLERS_ACCOUNTS_ADMINS + " cannot be an empty list.";
            }

            if (errmsg != null)
            {
                System.err.println(errmsg);
                throw new IllegalArgumentException(errmsg);
            }

            System.err.println("User account administrators limited to: " + userAdmins);
            if (acctsNotOpenRegButNoUsers && ! wantsUpgSchema)
                System.err.println
                    ("** User database is currently empty: Run SOCAccountClient to create the user admin account(s) named above.");
        }
        else if (acctsNotOpenRegButNoUsers && ! wantsUpgSchema)
        {
            System.err.println
                ("** To create users, you must list admin names in property " + PROP_JSETTLERS_ACCOUNTS_ADMINS + ".");
        }
    }

    /**
     * For initialization, get and parse an integer property, or use its default instead.
     * @param props  Properties to look in
     * @param pName  Property name
     * @param pDefault  Default value to use if not found or not parsable
     * @return The property's parsed integer value, or <tt>pDefault</tt>
     * @since 1.1.10
     */
    private static int init_getIntProperty(Properties props, final String pName, final int pDefault)
    {
        try
        {
            String mcs = props.getProperty(pName, Integer.toString(pDefault));
            if (mcs != null)
                return Integer.parseInt(mcs);
        }
        catch (NumberFormatException e) { }

        return pDefault;
    }

    /**
     * Get and parse a boolean property, or use its default instead.
     * True values are: T Y 1.
     * False values are: F N 0.
     * Not case-sensitive.
     * Any other value will be ignored and get <tt>pDefault</tt>.
     *<P>
     * Backported to 1.1.14 from 2.0.00's {@code getConfigBoolProperty(..)}.
     *
     * @param props  Properties to look in, such as {@link SOCServer#props}, or null for <tt>pDefault</tt>
     * @param pName  Property name
     * @param pDefault  Default value to use if not found or not parsable
     * @return The property's parsed value, or <tt>pDefault</tt>
     * @since 1.1.14
     */
    private static boolean init_getBoolProperty(Properties props, final String pName, final boolean pDefault)
    {
        if (props == null)
            return pDefault;

        try
        {
            String mcs = props.getProperty(pName);
            if (mcs == null)
                return pDefault;
            if (mcs.equalsIgnoreCase("Y") || mcs.equalsIgnoreCase("T"))
                return true;
            else if (mcs.equalsIgnoreCase("N") || mcs.equalsIgnoreCase("F"))
                return false;

            final int iv = Integer.parseInt(mcs);
            if (iv == 0)
                return false;
            else if (iv == 1)
                return true;
        }
        catch (NumberFormatException e) { }

        return pDefault;        
    }

    /**
     * Callback to take care of things when server comes up, after the server socket
     * is bound and listening, in the main thread.
     * If {@link #PROP_JSETTLERS_STARTROBOTS} is specified, start those {@link SOCRobotClient}s now.
     *<P>
     * Once this method completes, server begins its main loop of listening for incoming
     * client connections, and starting a Thread for each one to handle that client's messages.
     *
     * @throws IllegalStateException If server was constructed in Utility Mode and shouldn't continue
     *    normal startup; see {@link #hasUtilityModeProperty()} for details.
     * @since 1.1.09
     */
    public void serverUp()
        throws IllegalStateException
    {
        if (hasUtilityModeProp)
            throw new IllegalStateException();

        /**
         * If we have any STARTROBOTS, start them up now.
         * Each bot will have its own thread and {@link SOCRobotClient}.
         */
        if ((props != null) && (props.containsKey(PROP_JSETTLERS_STARTROBOTS)))
        {
            try
            {
                final int rcount = Integer.parseInt(props.getProperty(PROP_JSETTLERS_STARTROBOTS));
                final int hcount = maxConnections - rcount;  // max human client connection count
                int fast30 = (int) (0.30f * rcount);
                boolean loadSuccess = setupLocalRobots(fast30, rcount - fast30);  // each bot gets a thread
                if (! loadSuccess)
                {
                    System.err.println("** Cannot start robots with this JAR.");
                    System.err.println("** For robots, please use the Full JAR instead of the server-only JAR.");
                }
                else if ((hcount < 6) || (hcount < rcount))
                {
                    new Thread() {
                        public void run()
                        {
                            try {
                                Thread.sleep(1600);  // wait for bot-connect messages to print
                            } catch (InterruptedException e) {}
                            System.err.println("** Warning: Only " + hcount
                                + " player connections available, because of the robot connections.");
                        }
                    }.start();
                }
            }
            catch (NumberFormatException e)
            {
                System.err.println("Not starting robots: Bad number format, ignoring property " + PROP_JSETTLERS_STARTROBOTS);
            }
        }        
    }

    /**
     * The 16 hex characters to use in {@link #generateRobotCookie()}.
     * @since 1.1.19
     */
    private final static char[] GENERATEROBOTCOOKIE_HEX
        = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    /**
     * Generate and return a string to use for {@link #robotCookie}.
     * Currently a lowercase hex string; format or length does not have to be compatible
     * between versions.  The contents are randomly generated for each server run.
     * @return Robot connect cookie contents to use for this server
     * @since 1.1.19
     */
    private final String generateRobotCookie()
    {
        byte[] rnd = new byte[16];
        rand.nextBytes(rnd);
        char[] rndChars = new char[2 * 16];
        int ic = 0;  // index into rndChars
        for (int i = 0; i < 16; ++i)
        {
            final int byt = rnd[i] & 0xFF;
            rndChars[ic] = GENERATEROBOTCOOKIE_HEX[byt >>> 4];   ++ic;
            rndChars[ic] = GENERATEROBOTCOOKIE_HEX[byt & 0x0F];  ++ic;
        }

        return new String(rndChars);
    }

    /**
     * Adds a connection to a chat channel.
     *
     * WARNING: MUST HAVE THE channelList.takeMonitorForChannel(ch)
     * before calling this method
     *
     * @param c    the Connection to be added
     * @param ch   the name of the channel
     *
     */
    public void connectToChannel(StringConnection c, String ch)
    {
        if (c != null)
        {
            if (channelList.isChannel(ch))
            {
                if (!channelList.isMember(c, ch))
                {
                    c.put(SOCMembers.toCmd(ch, channelList.getMembers(ch)));
                    if (D.ebugOn)
                        D.ebugPrintln("*** " + c.getData() + " joined the channel " + ch + " at "
                            + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
                    channelList.addMember(c, ch);
                }
            }
        }
    }

    /**
     * Connection <tt>c</tt> leaves the channel <tt>ch</tt>.
     * If the channel becomes empty after removing <tt>c</tt>, this method can destroy it.
     *<P>
     * <B>Locks:</B> Must have {@link SOCChannelList#takeMonitorForChannel(String) channelList.takeMonitorForChannel(ch)}
     * when calling this method.
     * May or may not have {@link SOCChannelList#takeMonitor()}, see <tt>channelListLock</tt> parameter.
     *
     * @param c  the connection
     * @param ch the channel
     * @param destroyIfEmpty  if true, this method will destroy the channel if it's now empty.
     *           If false, the caller must call {@link #destroyChannel(String)}
     *           before calling {@link SOCChannelList#releaseMonitor()}.
     * @param channelListLock  true if we have the {@link SOCChannelList#takeMonitor()} lock
     *           when called; false if it must be acquired and released within this method
     * @return true if we destroyed the channel, or if it would have been destroyed but <tt>destroyIfEmpty</tt> is false.
     */
    public boolean leaveChannel
        (final StringConnection c, final String ch, final boolean destroyIfEmpty, boolean channelListLock)
    {
        D.ebugPrintln("leaveChannel: " + c.getData() + " " + ch + " " + channelListLock);

        if (c != null)
        {
            if (channelList.isMember(c, ch))
            {
                channelList.removeMember(c, ch);

                SOCLeave leaveMessage = new SOCLeave(c.getData(), c.host(), ch);
                messageToChannelWithMon(ch, leaveMessage);
                if (D.ebugOn)
                    D.ebugPrintln("*** " + c.getData() + " left the channel " + ch + " at "
                        + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            }

            final boolean isEmpty = channelList.isChannelEmpty(ch);
            if (isEmpty && destroyIfEmpty)
            {
                if (channelListLock)
                {
                    destroyChannel(ch);
                }
                else
                {
                    channelList.takeMonitor();

                    try
                    {
                        destroyChannel(ch);
                    }
                    catch (Exception e)
                    {
                        D.ebugPrintStackTrace(e, "Exception in leaveChannel");
                    }

                    channelList.releaseMonitor();
                }
            }

            return isEmpty;
        } else {
            return false;
        }
    }

    /**
     * Destroy a channel and then clean up related data, such as the owner's count of
     * {@link SOCClientData#getcurrentCreatedChannels()}.
     * Calls {@link SOCChannelList#deleteChannel(String)}.
     *<P>
     * <B>Locks:</B> Must have {@link #channelList}{@link SOCChannelList#takeMonitor() .takeMonitor()}
     * before calling this method.
     *
     * @param ch  Name of the channel to destroy
     * @see #leaveChannel(StringConnection, String, boolean, boolean)
     * @since 1.1.20
     */
    protected final void destroyChannel(final String ch)
    {
        channelList.deleteChannel(ch);

        // Reduce the owner's channels-active count
        StringConnection oConn = (StringConnection) conns.get(channelList.getOwner(ch));
        if (oConn != null)
            ((SOCClientData) oConn.getAppData()).deletedChannel();
    }

    /**
     * Adds a connection to a game, unless they're already a member.
     * If the game doesn't yet exist, create it,
     * and announce the new game to all clients.
     *<P>
     * After this, human players are free to join, until someone clicks "Start Game".
     * At that point, server will look for robots to fill empty seats. 
     *
     * @param c    the Connection to be added; its name and version should already be set.
     * @param gaName  the name of the game.  Not validated or trimmed, see
     *             {@link #createOrJoinGameIfUserOK(StringConnection, String, String, String, Map)} for that.
     * @param gaOpts  if creating a game with options, hashtable of {@link SOCGameOption}; otherwise null.
     *                Should already be validated, by calling
     *                {@link SOCGameOption#adjustOptionsToKnown(Hashtable, Hashtable, boolean)}
     *                with <tt>doServerPreadjust</tt> true.
     *
     * @return     true if c was not a member of ch before,
     *             false if c was already in this game
     *
     * @throws SOCGameOptionVersionException if asking to create a game (gaOpts != null),
     *           but client's version is too low to join because of a
     *           requested game option's minimum version in gaOpts. 
     *           Calculated via {@link SOCGameOption#optionsNewerThanVersion(int, boolean, boolean, Hashtable)}.
     *           (this exception was added in 1.1.07)
     * @throws IllegalArgumentException if client's version is too low to join for any
     *           other reason. (this exception was added in 1.1.06)
     * @see #joinGame(SOCGame, StringConnection, boolean, boolean)
     * @see #handleSTARTGAME(StringConnection, SOCStartGame)
     * @see #handleJOINGAME(StringConnection, SOCJoinGame)
     */
    public boolean connectToGame(StringConnection c, final String gaName, Hashtable gaOpts)
        throws SOCGameOptionVersionException, IllegalArgumentException
    {
        if (c == null)
        {
            return false;  // shouldn't happen
        }

        boolean result = false;

        final int cliVers = c.getVersion();
        boolean gameExists = false;
        gameList.takeMonitor();

        try
        {
            gameExists = gameList.isGame(gaName);
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in connectToGame");
        }

        gameList.releaseMonitor();

        if (gameExists)
        {
            boolean cliVersOld = false;
            gameList.takeMonitorForGame(gaName);
            SOCGame ga = gameList.getGameData(gaName);

            try
            {
                if (gameList.isMember(c, gaName))
                {
                    result = false;
                }
                else
                {
                    if (ga.getClientVersionMinRequired() <= cliVers)
                    {
                        gameList.addMember(c, gaName);
                        result = true;
                    } else {
                        cliVersOld = true;
                    }
                }
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in connectToGame (isMember)");
            }

            gameList.releaseMonitorForGame(gaName);
            if (cliVersOld)
                throw new IllegalArgumentException("Client version");

                // <---- Exception: Early return ----
        }
        else
        {
            /**
             * the game did not exist, create it after checking options
             */
            final int gVers;
            if (gaOpts == null)
            {
                gVers = -1;
            } else {
                gVers = SOCGameOption.optionsMinimumVersion(gaOpts);
                if (gVers > cliVers)
                {
                    // Which option(s) are too new for client?
                    Vector optsValuesTooNew =
                        SOCGameOption.optionsNewerThanVersion(cliVers, true, false, gaOpts);
                    throw new SOCGameOptionVersionException(gVers, cliVers, optsValuesTooNew);

                    // <---- Exception: Early return ----
                }
            }

            gameList.takeMonitor();
            boolean monitorReleased = false;

            try
            {
                // Create new game, expiring in SOCGameListAtServer.GAME_EXPIRE_MINUTES .
                gameList.createGame(gaName, c.getData(), gaOpts);
                if ((strSocketName != null) && (strSocketName.equals(PRACTICE_STRINGPORT)))
                {
                    gameList.getGameData(gaName).isPractice = true;  // flag if practice game (set since 1.1.09)
                }

                // Add this (creating) player to the game
                gameList.addMember(c, gaName);

                // must release monitor before we broadcast
                gameList.releaseMonitor();
                monitorReleased = true;

                result = true;
                ((SOCClientData) c.getAppData()).createdGame();

                // check required client version before we broadcast
                final int cversMin = getMinConnectedCliVersion();

                if ((gVers <= cversMin) && (gaOpts == null))
                {
                    // All clients can join it, and no game options: use simplest message
                    broadcast(SOCNewGame.toCmd(gaName));

                } else {
                    // Send messages, based on clients' version
                    // and whether there are game options.

                    // Client version variables:
                    // cversMax: maximum version connected to server
                    // cversMin: minimum version connected to server 
                    // VERSION_FOR_NEWGAMEWITHOPTIONS: minimum to understand game options

                    // Game version variables:
                    // gVersMinGameOptsNoChange: minimum to understand these game options
                    //           without backwards-compatibility changes to their values     
                    // gVers: minimum to play the game

                    final int gVersMinGameOptsNoChange;
                    if (cversMin < Version.versionNumber())
                        gVersMinGameOptsNoChange = SOCGameOption.optionsMinimumVersion(gaOpts, true);
                    else
                        gVersMinGameOptsNoChange = -1;  // all clients are our current version

                    if ((cversMin >= gVersMinGameOptsNoChange)
                        && (cversMin >= SOCNewGameWithOptions.VERSION_FOR_NEWGAMEWITHOPTIONS))
                    {
                        // All cli can understand msg with version/options included
                        broadcast
                            (SOCNewGameWithOptions.toCmd(gaName, gaOpts, gVers, -2));
                    } else {
                        // Only some can understand msg with version/options included;
                        // send at most 1 message to each connected client, split by client version.
                        // Send the old simple NEWGAME message to connected clients of version
                        // newgameSimpleMsgMaxCliVers and lower.  If no game options, send that
                        // message type to all clients.

                        final int cversMax = getMaxConnectedCliVersion();
                        final int newgameSimpleMsgMaxCliVers;  // max version to get simple no-opts newgame message

                        if ((gaOpts != null) && (cversMax >= SOCNewGameWithOptions.VERSION_FOR_NEWGAMEWITHOPTIONS))
                        {
                            // Announce to the connected clients with versions new enough for game options:

                            if ((cversMin < gVersMinGameOptsNoChange)  // client versions are connected
                                && (gVers < gVersMinGameOptsNoChange)) // able to play, but needs value changes
                            {
                                // Some clients' versions are too old to understand these game
                                // option values without change; send them an altered set for
                                // compatibility with those clients.

                                // Since cversMin < gVersMinGameOptsNoChange,
                                //   we know gVersMinGameOptsNoChange > -1 and thus >= 1107.
                                // cversMax and VERSION_FOR_NEWGAMEWITHOPTIONS are also 1107.
                                // So:
                                //  1107 <= cversMax
                                //  gVers < gVersMinGameOptsNoChange
                                //  1107 <= gVersMinGameOptsNoChange

                                // Loop through "joinable" client versions < gVersMinGameOptsNoChange.
                                // A separate message is sent below to clients < gVers.
                                int cv = cversMin;  // start loop with min cli version
                                if (gVers > cv)
                                    cv = gVers;  // game version is higher, start there

                                for ( ; cv < gVersMinGameOptsNoChange; ++cv)
                                {
                                    if (isCliVersionConnected(cv))
                                        broadcastToVers
                                          (SOCNewGameWithOptions.toCmd(gaName, gaOpts, gVers, cv),
                                           cv, cv);
                                }
                                // Now send to newer clients, no changes needed
                                broadcastToVers
                                  (SOCNewGameWithOptions.toCmd(gaName, gaOpts, gVers, -2),
                                   gVersMinGameOptsNoChange, Integer.MAX_VALUE);
                            } else {
                                // No clients need backwards-compatible option value changes.
                                broadcastToVers
                                  (SOCNewGameWithOptions.toCmd(gaName, gaOpts, gVers, -2),
                                   SOCNewGameWithOptions.VERSION_FOR_NEWGAMEWITHOPTIONS, Integer.MAX_VALUE);
                            }

                            // Simple announcement will go only to
                            // clients too old to understand NEWGAMEWITHOPTIONS
                            newgameSimpleMsgMaxCliVers = SOCNewGameWithOptions.VERSION_FOR_NEWGAMEWITHOPTIONS - 1;
                        } else {

                            // Game has no opts, or no clients are new enough for opts;
                            // simple announcement will go to all clients
                            newgameSimpleMsgMaxCliVers = Integer.MAX_VALUE;
                        }

                        // "Simple" announcement message without game options:
                        final int newgameSimpleMsgCantJoinVers;  // narrow down the versions for announcement
                        if (gVers <= newgameSimpleMsgMaxCliVers)
                        {
                            // To older clients who can join, announce game without its options/version
                            broadcastToVers(SOCNewGame.toCmd(gaName), gVers, newgameSimpleMsgMaxCliVers);
                            newgameSimpleMsgCantJoinVers = gVers - 1;
                        } else {
                            // No older clients can join.  This game's already been announced to                       
                            // some clients (new enough for NEWGAMEWITHOPTIONS).
                            newgameSimpleMsgCantJoinVers = newgameSimpleMsgMaxCliVers;
                        }

                        // To older clients who can't join, announce game with cant-join prefix
                        if (cversMin <= newgameSimpleMsgCantJoinVers)
                        {
                            StringBuffer sb = new StringBuffer();
                            sb.append(SOCGames.MARKER_THIS_GAME_UNJOINABLE);
                            sb.append(gaName);
                            broadcastToVers
                                (SOCNewGame.toCmd(sb.toString()),
                                 SOCGames.VERSION_FOR_UNJOINABLE, newgameSimpleMsgCantJoinVers);
                        }
                    }
                }
            }
            catch (IllegalArgumentException e)
            {
                if (! monitorReleased)
                    gameList.releaseMonitor();
                throw e;  // caller handles it
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in connectToGame");
            }

            if (! monitorReleased)
                gameList.releaseMonitor();
        }

        return result;
    }

    /**
     * the connection c leaves the game gm.  Clean up; if needed, call {@link #forceEndGameTurn(SOCGame, String)}.
     *<P>
     * If the game becomes empty after removing <tt>c</tt>, this method can destroy it if both of these
     * conditions are true:
     * <UL>
     *  <LI> <tt>c</tt> was the last non-robot player
     *  <LI> No one was watching/observing
     * </UL>
     *<P>
     * <B>Locks:</B> Has {@link SOCGameList#takeMonitorForGame(String) gameList.takeMonitorForGame(gm)}
     * when calling this method; should not have {@link SOCGame#takeMonitor()}.
     * May or may not have {@link SOCGameList#takeMonitor()}, see <tt>gameListLock</tt> parameter.
     *
     * @param c  the connection; if c is being dropped because of an error,
     *           this method assumes that {@link StringConnection#disconnect()}
     *           has already been called.  This method won't exclude c from
     *           any communication about leaving the game, in case they are
     *           still connected and in other games.
     * @param gm the game
     * @param destroyIfEmpty  if true, this method will destroy the game if it's now empty.
     *           If false, the caller must call {@link #destroyGame(String)}
     *           before calling {@link SOCGameList#releaseMonitor()}.
     * @param gameListLock  true if caller holds the {@link SOCGameList#takeMonitor()} lock when called;
     *           false if it must be acquired and released within this method
     * @return true if the game was destroyed, or if it would have been destroyed but <tt>destroyIfEmpty</tt> is false.
     */
    public boolean leaveGame
        (final StringConnection c, final String gm, final boolean destroyIfEmpty, final boolean gameListLock)
    {
        if (c == null)
        {
            return false;  // <---- Early return: no connection ----
        }

        boolean gameDestroyed = false;

        gameList.removeMember(c, gm);

        boolean isPlayer = false;
        int playerNumber = 0;    // removing this player number
        SOCGame ga = gameList.getGameData(gm);
        if (ga == null)
        {
            return false;  // <---- Early return: no game ----
        }

        boolean gameHasHumanPlayer = false;
        boolean gameHasObserver = false;
        boolean gameVotingActiveDuringStart = false;

        final int gameState = ga.getGameState();
        final String plName = c.getData();  // Retain name, since will become null within game obj.

        for (playerNumber = 0; playerNumber < ga.maxPlayers;
                playerNumber++)
        {
            SOCPlayer player = ga.getPlayer(playerNumber);

            if ((player != null) && (player.getName() != null)
                && (player.getName().equals(plName)))
            {
                isPlayer = true;

                /**
                 * About to remove this player from the game. Before doing so:
                 * If a board-reset vote is in progress, they cannot vote
                 * once they have left. So to keep the game moving,
                 * fabricate their response: vote No.
                 */
                if (ga.getResetVoteActive())
                {
                    if (gameState <= SOCGame.START2B)
                        gameVotingActiveDuringStart = true;

                    if (ga.getResetPlayerVote(playerNumber) == SOCGame.VOTE_NONE)
                    {
                        gameList.releaseMonitorForGame(gm);
                        ga.takeMonitor();
                        resetBoardVoteNotifyOne(ga, playerNumber, plName, false);                
                        ga.releaseMonitor();
                        gameList.takeMonitorForGame(gm);
                    }
                }

                /** 
                 * Remove the player.
                 */
                ga.removePlayer(plName);  // player obj name becomes null

                //broadcastGameStats(cg);
                break;
            }
        }

        SOCLeaveGame leaveMessage = new SOCLeaveGame(plName, "-", gm);
        messageToGameWithMon(gm, leaveMessage);
        recordGameEvent(gm, leaveMessage.toCmd());

        if (D.ebugOn)
            D.ebugPrintln("*** " + plName + " left the game " + gm + " at "
                + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
        messageToGameWithMon(gm, new SOCGameTextMsg(gm, SERVERNAME, plName + " left the game"));

        /**
         * check if there is at least one person playing the game
         */
        for (int pn = 0; pn < ga.maxPlayers; pn++)
        {
            SOCPlayer player = ga.getPlayer(pn);

            if ((player != null) && (player.getName() != null) && (!ga.isSeatVacant(pn)) && (!player.isRobot()))
            {
                gameHasHumanPlayer = true;
                break;
            }
        }

        //D.ebugPrintln("*** gameHasHumanPlayer = "+gameHasHumanPlayer+" for "+gm);

        /**
         * if no human players, check if there is at least one person watching the game
         */
        if (!gameHasHumanPlayer && !gameList.isGameEmpty(gm))
        {
            Enumeration membersEnum = gameList.getMembers(gm).elements();

            while (membersEnum.hasMoreElements())
            {
                StringConnection member = (StringConnection) membersEnum.nextElement();

                //D.ebugPrintln("*** "+member.data+" is a member of "+gm);
                boolean nameMatch = false;

                for (int pn = 0; pn < ga.maxPlayers; pn++)
                {
                    SOCPlayer player = ga.getPlayer(pn);

                    if ((player != null) && (player.getName() != null) && (player.getName().equals(member.getData())))
                    {
                        nameMatch = true;
                        break;
                    }
                }

                if (!nameMatch)
                {
                    gameHasObserver = true;
                    break;
                }
            }
        }
        //D.ebugPrintln("*** gameHasObserver = "+gameHasObserver+" for "+gm);

        /**
         * if the leaving member was playing the game, and
         * the game isn't over, then decide:
         * - Do we need to force-end the current turn?
         * - Do we need to cancel their initial settlement placement?
         * - Should we replace the leaving player with a robot?
         */
        if (isPlayer && (gameHasHumanPlayer || gameHasObserver)
                && ((ga.getPlayer(playerNumber).getPublicVP() > 0)
                    || (gameState == SOCGame.START1A)
                    || (gameState == SOCGame.START1B))
                && (gameState < SOCGame.OVER)
                && !(gameState < SOCGame.START1A))
        {
            boolean foundNoRobots;

            if (ga.getPlayer(playerNumber).isRobot())
            {
                /**
                 * don't replace bot with bot; force end-turn instead.
                 */
                foundNoRobots = true;
            }
            else
            {
                /**
                 * get a robot to replace this human player;
                 * just in case, check game-version vs robots-version,
                 * like at new-game (readyGameAskRobotsJoin).
                 */
                foundNoRobots = false;

                messageToGameWithMon(gm, new SOCGameTextMsg(gm, SERVERNAME, "Fetching a robot player..."));

                if (robots.isEmpty())
                {
                    messageToGameWithMon(gm, new SOCGameTextMsg(gm, SERVERNAME, "Sorry, no robots on this server."));
                    foundNoRobots = true;
                }
                else if (ga.getClientVersionMinRequired() > Version.versionNumber())
                {
                    messageToGameWithMon(gm, new SOCGameTextMsg
                            (gm, SERVERNAME,
                             "Sorry, the robots can't join this game; its version is somehow newer than server and robots, it's "
                             + ga.getClientVersionMinRequired()));
                    foundNoRobots = true;                        
                }
                else
                {
                    /**
                     * request a robot that isn't already playing this game or
                     * is not already requested to play in this game
                     */
                    boolean nameMatch = false;
                    StringConnection robotConn = null;
    
                    final int[] robotIndexes = robotShuffleForJoin();  // Shuffle to distribute load
    
                    Vector requests = (Vector) robotJoinRequests.get(gm);
    
                    for (int idx = 0; idx < robots.size(); idx++)
                    {
                        robotConn = (StringConnection) robots.get(robotIndexes[idx]);
                        nameMatch = false;
    
                        for (int i = 0; i < ga.maxPlayers; i++)
                        {
                            SOCPlayer pl = ga.getPlayer(i);
    
                            if (pl != null)
                            {
                                String pname = pl.getName();
    
                                // D.ebugPrintln("CHECKING " + (String) robotConn.getData() + " == " + pname);
    
                                if ((pname != null) && (pname.equals(robotConn.getData())))
                                {
                                    nameMatch = true;
    
                                    break;
                                }
                            }
                        }
    
                        if ((!nameMatch) && (requests != null))
                        {
                            Enumeration requestsEnum = requests.elements();
    
                            while (requestsEnum.hasMoreElements())
                            {
                                StringConnection tempCon = (StringConnection) requestsEnum.nextElement();
    
                                // D.ebugPrintln("CHECKING " + robotConn + " == " + tempCon);
    
                                if (tempCon == robotConn)
                                {
                                    nameMatch = true;
                                }
    
                                break;
                            }
                        }
    
                        if (!nameMatch)
                        {
                            break;
                        }
                    }
    
                    if (!nameMatch)
                    {
                        /**
                         * make the request
                         */
                        D.ebugPrintln("@@@ JOIN GAME REQUEST for " + robotConn.getData());

                        if (ga.isSeatLocked(playerNumber))
                        {
                            // make sure bot can sit
                            ga.unlockSeat(playerNumber);
                            messageToGameWithMon(gm, new SOCSetSeatLock(gm, playerNumber, false));
                        }
                        robotConn.put(SOCJoinGameRequest.toCmd(gm, playerNumber, ga.getGameOptions()));
    
                        /**
                         * record the request
                         */
                        if (requests == null)
                        {
                            requests = new Vector();
                            requests.addElement(robotConn);
                            robotJoinRequests.put(gm, requests);
                        }
                        else
                        {
                            requests.addElement(robotConn);
                        }
                    }
                    else
                    {
                        messageToGameWithMon(gm, new SOCGameTextMsg(gm, SERVERNAME, "*** Can't find a robot! ***"));
                        foundNoRobots = true;
                    }
                }
            }  // if (should try to find a robot)

            /**
             * What to do if no robot was found to fill their spot?
             * Must keep the game going, might need to force-end current turn.
             */
            if (foundNoRobots)
            {
                final boolean stillActive = endGameTurnOrForce(ga, playerNumber, plName, c, true);
                if (! stillActive)
                {
                    // force game destruction below
                    gameHasHumanPlayer = false;
                    gameHasObserver = false;
                }
            }
        }

        /**
         * if the game has no players, or if they're all
         * robots, then end the game and update stats.
         */
        final boolean emptyGame = gameList.isGameEmpty(gm);

        gameDestroyed = (emptyGame || ! (gameHasHumanPlayer || gameHasObserver));
        if (gameDestroyed && destroyIfEmpty)
        {
            if (gameListLock)
            {
                destroyGame(gm);
            }
            else
            {
                gameList.takeMonitor();

                try
                {
                    destroyGame(gm);
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception in leaveGame (destroyGame)");
                }

                gameList.releaseMonitor();
            }
        }

        //D.ebugPrintln("*** gameDestroyed = "+gameDestroyed+" for "+gm);
        return gameDestroyed;
    }

    /**
     * End this player's turn cleanly, or force-end if needed.
     *<P>
     * Can be called for a player still in the game, or for a player
     * who has left ({@link SOCGame#removePlayer(String)} has been called).
     * Can be called for a player who isn't current player; in that case
     * it takes action if the game was waiting for the player (picking random
     * resources for discard) but won't end the current turn.
     *<P>
     * <b>Locks:</b> Must not have ga.takeMonitor() when calling this method.
     * May or may not have <tt>gameList.takeMonitorForGame(ga)</tt>;
     * use <tt>hasMonitorFromGameList</tt> to indicate.
     *<P>
     * Not public, but package visibility, for use by {@link SOCGameTimeoutChecker}. 
     *
     * @param ga   The game to end turn if called for current player, or to otherwise stop waiting for a player
     * @param plNumber  player.getNumber; may or may not be current player
     * @param plName    player.getName
     * @param plConn    player's client connection
     * @param hasMonitorFromGameList  if false, have not yet called
     *          {@link SOCGameList#takeMonitorForGame(String) gameList.takeMonitorForGame(ga)};
     *          if false, this method will take this monitor at its start,
     *          and release it before returning.
     * @return true if the turn was ended and game is still active;
     *          false if we find that all players have left and
     *          the gamestate has been changed here to {@link SOCGame#OVER OVER}.
     */
    boolean endGameTurnOrForce
        (SOCGame ga, final int plNumber, final String plName, StringConnection plConn,
         final boolean hasMonitorFromGameList)
    {
        boolean gameStillActive = true;

        final String gm = ga.getName();
        if (! hasMonitorFromGameList)
        {
            gameList.takeMonitorForGame(gm);
        }
        final int cpn = ga.getCurrentPlayerNumber();
        final int gameState = ga.getGameState();

        /**
         * Is a board-reset vote is in progress?
         * If they're still a sitting player, to keep the game
         * moving, fabricate their response: vote No.
         */
        boolean gameVotingActiveDuringStart = false;

        if (ga.getResetVoteActive())
        {
            if (gameState <= SOCGame.START2B)
                gameVotingActiveDuringStart = true;

            if ((! ga.isSeatVacant(plNumber))
                && (ga.getResetPlayerVote(plNumber) == SOCGame.VOTE_NONE))
            {
                gameList.releaseMonitorForGame(gm);
                ga.takeMonitor();
                resetBoardVoteNotifyOne(ga, plNumber, plName, false);                
                ga.releaseMonitor();
                gameList.takeMonitorForGame(gm);
            }
        }

        /**
         * Now end their turn, or handle any needed responses if not current player.
         */
        if (plNumber == cpn)
        {
            /**
             * End their turn just to keep the game limping along.
             * To prevent deadlock, we must release gamelist's monitor for
             * this game before calling endGameTurn.
             */

            if ((gameState == SOCGame.START1B) || (gameState == SOCGame.START2B))
            {
                /**
                 * Leaving during 1st or 2nd initial road placement.
                 * Cancel the settlement they just placed,
                 * and send that cancel to the other players.
                 * Don't change gameState yet.
                 * Note that their 2nd settlement is removed in START2B,
                 * but not their 1st settlement. (This would impact the robots much more.)
                 */
                SOCPlayer pl = ga.getPlayer(plNumber);
                SOCSettlement pp = new SOCSettlement(pl, pl.getLastSettlementCoord(), null);
                ga.undoPutInitSettlement(pp);
                ga.setGameState(gameState);  // state was changed by undoPutInitSettlement
                messageToGameWithMon
                  (gm, new SOCCancelBuildRequest(gm, SOCSettlement.SETTLEMENT));
            }

            if (ga.canEndTurn(plNumber))
            {
                gameList.releaseMonitorForGame(gm);
                ga.takeMonitor();
                endGameTurn(ga, null);
                ga.releaseMonitor();
                gameList.takeMonitorForGame(gm);
            } else {
                /**
                 * Cannot easily end turn.
                 * Must back out something in progress.
                 * May or may not end turn; see javadocs
                 * of forceEndGameTurn and game.forceEndTurn.
                 * All start phases are covered here (START1A..START2B)
                 * because canEndTurn returns false in those gameStates.
                 */
                gameList.releaseMonitorForGame(gm);
                ga.takeMonitor();
                if (gameVotingActiveDuringStart)
                {
                    /**
                     * If anyone has requested a board-reset vote during
                     * game-start phases, we have to tell clients to cancel
                     * the vote request, because {@link soc.message.SOCTurn}
                     * isn't always sent during start phases.  (Voting must
                     * end when the turn ends.)
                     */
                    messageToGame(gm, new SOCResetBoardReject(gm));
                    ga.resetVoteClear();
                }

                /**
                 * Force turn to end
                 */
                gameStillActive = forceEndGameTurn(ga, plName);
                ga.releaseMonitor();
                if (gameStillActive)
                {
                    gameList.takeMonitorForGame(gm);
                }
            }
        }
        else
        {
            /**
             * Check if game is waiting for input from the player who
             * is leaving, but who isn't current player.
             * To keep the game moving, fabricate their response.
             * - Board-reset voting: Handled above.
             * - Waiting for discard: Handle here.
             */
            if ((gameState == SOCGame.WAITING_FOR_DISCARDS)
                 && (ga.getPlayer(plNumber).getNeedToDiscard()))
            {
                /**
                 * For discard, tell the discarding player's client that they discarded the resources,
                 * tell everyone else that the player discarded unknown resources.
                 */
                gameList.releaseMonitorForGame(gm);
                ga.takeMonitor();
                forceGamePlayerDiscard(ga, cpn, plConn, plName, plNumber);
                sendGameState(ga, false);  // WAITING_FOR_DISCARDS or MOVING_ROBBER
                ga.releaseMonitor();
                gameList.takeMonitorForGame(gm);
            }

        }  // current player?

        if (! hasMonitorFromGameList)
        {
            gameList.releaseMonitorForGame(gm);
        }

        return gameStillActive;
    }

    /**
     * shuffle the indexes to distribute load among {@link #robots}
     * @return a shuffled array of robot indexes, from 0 to ({#link {@link #robots}}.size() - 1
     * @since 1.1.06
     */
    private int[] robotShuffleForJoin()
    {
        int[] robotIndexes = new int[robots.size()];

        for (int i = 0; i < robots.size(); i++)
        {
            robotIndexes[i] = i;
        }

        for (int j = 0; j < 3; j++)
        {
            for (int i = 0; i < robotIndexes.length; i++)
            {
                // Swap a random robot, below the ith robot, with the ith robot
                int idx = Math.abs(rand.nextInt() % (robotIndexes.length - i));
                int tmp = robotIndexes[idx];
                robotIndexes[idx] = robotIndexes[i];
                robotIndexes[i] = tmp;
            }
        }
        return robotIndexes;
    }

    /**
     * Set up some robot opponents, running in our JVM for operator convenience.
     * Set up more than needed; when a game is started, game setup will
     * randomize whether its humans will play against smart or fast ones.
     * (Some will be SOCRobotDM.FAST_STRATEGY, some SMART_STRATEGY).
     *<P>
     * The bots will start up and connect in separate threads, then be given their
     * <tt>FAST</tt> or <tt>SMART</tt> strategy params in {@link #handleIMAROBOT(StringConnection, SOCImARobot)}
     * based on their name prefixes ("droid " or "robot " respectively).
     *<P>
     * In v1.2.00 and newer, human players can't use names with bot prefixes "droid " or "robot ":
     * see {@link #checkNickname(String, StringConnection, boolean, boolean)}.
     *<P>
     * Before 1.1.09, this method was part of SOCPlayerClient.
     *
     * @param numFast number of fast robots, with {@link soc.robot.SOCRobotDM#FAST_STRATEGY FAST_STRATEGY}
     * @param numSmart number of smart robots, with {@link soc.robot.SOCRobotDM#SMART_STRATEGY SMART_STRATEGY}
     * @return True if robots were set up, false if an exception occurred.
     *     This typically happens if a robot class, or SOCDisplaylessClient,
     *     can't be loaded, due to packaging of the server-only JAR.
     * @see soc.client.SOCPlayerClient#startPracticeGame()
     * @see soc.client.SOCPlayerClient#startLocalTCPServer(int)
     * @since 1.1.00
     */
    public boolean setupLocalRobots(final int numFast, final int numSmart)
    {
        try
        {
            // Make some faster ones first.
            for (int i = 0; i < numFast; ++i)
            {
                String rname = "droid " + (i+1);
                SOCPlayerLocalRobotRunner.createAndStartRobotClientThread(rname, strSocketName, port, robotCookie);
                    // includes yield() and sleep(75 ms) this thread.
            }

            // Make a few smarter ones now:
            // handleIMAROBOT will give them SOCServer.ROBOT_PARAMS_SMARTER
            // based on their name prefixes being "robot " not "droid ".

            for (int i = 0; i < numSmart; ++i)
            {
                String rname = "robot " + (i+1+numFast);
                SOCPlayerLocalRobotRunner.createAndStartRobotClientThread(rname, strSocketName, port, robotCookie);
                    // includes yield() and sleep(75 ms) this thread.
            }
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
        catch (LinkageError e)
        {
            return false;
        }

        return true;
    }

    /**
     * Force this player (not current player) to discard, and report resources to all players.
     * Does not send gameState, which may have changed when this method called
     * {@link SOCGame#playerDiscardRandom(int)}.
     *<P>
     * Assumes, as {@link #endGameTurn(SOCGame, SOCPlayer)} does:
     * <UL>
     * <LI> ga.takeMonitor already called (not the same as {@link SOCGameList#takeMonitorForGame(String)})
     * <LI> gamelist.takeMonitorForGame is NOT called, we do NOT have that monitor
     * </UL>
     *
     * @param cg  Game object
     * @param cpn Game's current player number
     * @param c   Connection of discarding player
     * @param plName Discarding player <tt>pn</tt>'s name, for GameTextMsg
     * @param pn  Player number who must discard resources
     * @throws IllegalStateException if <tt>pn</tt> is current player, or if incorrect game state or incorrect
     *     player status; see {@link SOCGame#playerDiscardRandom(int)} for details
     */
    private void forceGamePlayerDiscard(SOCGame cg, final int cpn, StringConnection c, String plName, final int pn)
        throws IllegalStateException
    {
        final SOCResourceSet rset = cg.playerDiscardRandom(pn);

        // Report resources lost; see also forceEndGameTurn for same reporting code.
        final String gaName = cg.getName();
        if ((c != null) && c.isConnected())
            reportRsrcGainLoss(gaName, rset, true, true, pn, -1, null, c);
        int totalRes = rset.getTotal();
        messageToGameExcept
            (gaName, c, new SOCPlayerElement
                (gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.UNKNOWN, totalRes, true), true);
        messageToGame(gaName, plName + " discarded " + totalRes + " resources.");

        System.err.println("Forced discard: " + totalRes + " from " + plName + " in game " + gaName);
    }

    /**
     * Destroy a game and clean up related data, such as the owner's count of
     * {@link SOCClientData#getCurrentCreatedGames()}.
     *<P>
     * <B>Locks:</B> Must have {@link #gameList}{@link SOCGameList#takeMonitor() .takeMonitor()}
     * before calling this method.
     *
     * @param gm  Name of the game to destroy
     * @see #leaveGame(StringConnection, String, boolean, boolean)
     */
    public void destroyGame(String gm)
    {
        //D.ebugPrintln("***** destroyGame("+gm+")");
        SOCGame cg = null;

        cg = gameList.getGameData(gm);

        if (cg != null)
        {
            ///
            /// write out game data
            ///

            /*
               currentGameEventRecord.setSnapshot(cg);
               saveCurrentGameEventRecord(gm);
               SOCGameRecord gr = (SOCGameRecord)gameRecords.get(gm);
               writeGameRecord(gm, gr);
             */

            //storeGameScores(cg);

            ///
            /// delete the game from gamelist,
            /// tell all robots to leave
            ///
            Vector members = null;
            members = gameList.getMembers(gm);

            gameList.deleteGame(gm);  // also calls SOCGame.destroyGame

            if (members != null)
            {
                Enumeration conEnum = members.elements();

                while (conEnum.hasMoreElements())
                {
                    StringConnection con = (StringConnection) conEnum.nextElement();
                    con.put(SOCRobotDismiss.toCmd(gm));
                }
            }

            // Reduce the owner's games-active count
            final String gaOwner = cg.getOwner();
            if (gaOwner != null)
            {
                StringConnection oConn = (StringConnection) conns.get(gaOwner);
                if (oConn != null)
                    ((SOCClientData) oConn.getAppData()).deletedGame();
            }
        }
    }

    /**
     * Used when SOCPlayerClient is also hosting games.
     * @return The names (Strings) of games on this server
     */
    public Enumeration getGameNames()
    {
        return gameList.getGames();
    }

    /**
     * Given a game name on this server, return its state.
     *
     * @param gm Game name
     * @return Game's state, or -1 if no game with that name on this server
     * @since 1.1.00
     */
    public int getGameState(String gm)
    {
        SOCGame g = gameList.getGameData(gm);
        if (g != null)
            return g.getGameState();
        else
            return -1;
    }

    /**
     * Given a game name on this server, return its game options.
     *
     * @param gm Game name
     * @return the game options (hashtable of {@link SOCGameOption}), or
     *       null if the game doesn't exist or has no options
     * @since 1.1.07
     */
    public Hashtable getGameOptions(String gm)
    {
        return gameList.getGameOptions(gm);
    }

    /**
     * True if the server was constructed with a property or command line argument which is used
     * to run the server in Utility Mode instead of Server Mode.  In Utility Mode the server reads
     * its properties, initializes its database connection if any, and performs one task such as a
     * password reset or table/index creation. It won't start other threads and won't fail startup
     * if TCP port binding fails.
     *<P>
     * Utility Mode may also set a status message, see {@link #getUtilityModeMessage()}.
     *<P>
     * The current Utility Mode properties/arguments are:
     *<UL>
     * <LI> <tt>{@link SOCDBHelper#PROP_JSETTLERS_DB_BCRYPT_WORK__FACTOR}=test</tt> prop value
     * <LI> {@link SOCDBHelper#PROP_JSETTLERS_DB_SCRIPT_SETUP} property
     * <LI> {@link SOCDBHelper#PROP_JSETTLERS_DB_UPGRADE__SCHEMA} flag property
     * <LI> <tt>{@link SOCDBHelper#PROP_JSETTLERS_DB_SETTINGS}=write</tt> prop value
     * <LI> <tt>--pw-reset=username</tt> argument
     *</UL>
     *
     * @return  True if server was constructed with a Utility Mode property or command line argument
     * @since 1.1.20
     */
    public final boolean hasUtilityModeProperty()
    {
        return hasUtilityModeProp;
    }

    /**
     * If {@link #hasUtilityModeProperty()}, get the optional status message to print before exiting.
     * @return  Optional status message, or <tt>null</tt>
     * @since 1.1.20
     */
    public final String getUtilityModeMessage()
    {
         return utilityModeMessage;
    }

    /**
     * Connection <tt>c</tt> is leaving the server; remove from all channels it was in.
     * In channels where <tt>c</tt> was the last connection, calls {@link #destroyChannel(String)}.
     *
     * @param c  the connection
     * @return   the channels it was in
     */
    public void leaveAllChannels(StringConnection c)
    {
        if (c != null)
        {
            List toDestroy = new ArrayList();  // channels where c was the last member

            channelList.takeMonitor();

            try
            {
                for (Enumeration k = channelList.getChannels();
                        k.hasMoreElements();)
                {
                    String ch = (String) k.nextElement();

                    if (channelList.isMember(c, ch))
                    {
                        boolean thisChannelDestroyed = false;
                        channelList.takeMonitorForChannel(ch);

                        try
                        {
                            thisChannelDestroyed = leaveChannel(c, ch, false, true);
                        }
                        catch (Exception e)
                        {
                            D.ebugPrintStackTrace(e, "Exception in leaveAllChannels (leaveChannel)");
                        }

                        channelList.releaseMonitorForChannel(ch);

                        if (thisChannelDestroyed)
                            toDestroy.add(ch);
                    }
                }
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in leaveAllChannels");
            }

            /** After iterating through all channels, destroy newly empty ones */
            for (Iterator de = toDestroy.iterator(); de.hasNext(); )
                destroyChannel((String) de.next());

            channelList.releaseMonitor();

            /**
             * let everyone know about the destroyed channels
             */
            for (Iterator de = toDestroy.iterator(); de.hasNext(); )
            {
                broadcast(SOCDeleteChannel.toCmd((String) de.next()));
            }
        }
    }

    /**
     * Connection <tt>c</tt> is leaving the server; remove from all games it was in.
     * In games where <tt>c</tt> was the last human player, calls {@link #destroyGame(String)}.
     *
     * @param c  the connection
     */
    public void leaveAllGames(StringConnection c)
    {
        if (c != null)
        {
            List toDestroy = new ArrayList();  // games where c was the last human player

            gameList.takeMonitor();

            try
            {
                for (Enumeration k = gameList.getGames(); k.hasMoreElements();)
                {
                    String ga = (String) k.nextElement();
                    Vector v = (Vector) gameList.getMembers(ga);

                    if (v.contains(c))
                    {
                        boolean thisGameDestroyed = false;
                        gameList.takeMonitorForGame(ga);

                        try
                        {
                            thisGameDestroyed = leaveGame(c, ga, false, true);
                        }
                        catch (Exception e)
                        {
                            D.ebugPrintStackTrace(e, "Exception in leaveAllGames (leaveGame)");
                        }

                        gameList.releaseMonitorForGame(ga);

                        if (thisGameDestroyed)
                            toDestroy.add(ga);
                    }
                }
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in leaveAllGames");
            }

            /** After iterating through all games, destroy newly empty ones */
            for (Iterator de = toDestroy.iterator(); de.hasNext(); )
                destroyGame((String) de.next());

            gameList.releaseMonitor();

            /**
             * let everyone know about the destroyed games
             */
            for (Iterator de = toDestroy.iterator(); de.hasNext(); )
            {
                final String ga = (String) de.next();
                D.ebugPrintln("** Broadcasting SOCDeleteGame " + ga);
                broadcast(SOCDeleteGame.toCmd(ga));
            }
        }
    }

    /**
     * Send a message to the given channel
     *
     * @param ch  the name of the channel
     * @param mes the message to send
     */
    public void messageToChannel(String ch, SOCMessage mes)
    {
        channelList.takeMonitorForChannel(ch);

        try
        {
            Vector v = channelList.getMembers(ch);

            if (v != null)
            {
                Enumeration menum = v.elements();

                while (menum.hasMoreElements())
                {
                    StringConnection c = (StringConnection) menum.nextElement();

                    if (c != null)
                    {
                        c.put(mes.toCmd());
                    }
                }
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in messageToChannel");
        }

        channelList.releaseMonitorForChannel(ch);
    }

    /**
     * Send a message to the given channel
     *
     * WARNING: MUST HAVE THE gameList.takeMonitorForChannel(ch) before
     * calling this method
     *
     * @param ch  the name of the channel
     * @param mes the message to send
     */
    public void messageToChannelWithMon(String ch, SOCMessage mes)
    {
        Vector v = channelList.getMembers(ch);

        if (v != null)
        {
            Enumeration menum = v.elements();

            while (menum.hasMoreElements())
            {
                StringConnection c = (StringConnection) menum.nextElement();

                if (c != null)
                {
                    c.put(mes.toCmd());
                }
            }
        }
    }

    /**
     * Send a message to a player and record it
     *
     * @param c   the player connection
     * @param mes the message to send
     */
    public void messageToPlayer(StringConnection c, SOCMessage mes)
    {
        if ((c != null) && (mes != null))
        {
            //currentGameEventRecord.addMessageOut(new SOCMessageRecord(mes, "SERVER", c.getData()));
            c.put(mes.toCmd());
        }
    }

    /**
     * Send a {@link SOCGameTextMsg} game text message to a player.
     * Equivalent to: messageToPlayer(conn, new {@link SOCGameTextMsg}(ga, {@link #SERVERNAME}, txt));
     *
     * @param c   the player connection
     * @param ga  game name
     * @param txt the message text to send
     * @since 1.1.08
     */
    public void messageToPlayer(StringConnection c, final String ga, final String txt)
    {
        if (c == null)
            return;
        c.put(SOCGameTextMsg.toCmd(ga, SERVERNAME, txt));
    }

    /**
     * Send a message to the given game.
     * <b>Locks:</b> Takes, releases {@link SOCGameList#takeMonitorForGame(String)}.
     *
     * @param ga  the name of the game
     * @param mes the message to send. If mes is a SOCGameTextMsg whose
     *            text begins with ">>>", the client should consider this
     *            an urgent message, and draw the user's attention in some way.
     *            (See {@link #messageToGameUrgent(String, String)})
     * @see #messageToGame(String, String)
     * @see #messageToGameWithMon(String, SOCMessage)
     * @see #messageToGameForVersions(SOCGame, int, int, SOCMessage, boolean)
     */
    public void messageToGame(String ga, SOCMessage mes)
    {
        gameList.takeMonitorForGame(ga);

        try
        {
            Vector v = gameList.getMembers(ga);

            if (v != null)
            {
                //D.ebugPrintln("M2G - "+mes);
                Enumeration menum = v.elements();

                while (menum.hasMoreElements())
                {
                    StringConnection c = (StringConnection) menum.nextElement();

                    if (c != null)
                    {
                        //currentGameEventRecord.addMessageOut(new SOCMessageRecord(mes, "SERVER", c.getData()));
                        c.put(mes.toCmd());
                    }
                }
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in messageToGame");
        }

        gameList.releaseMonitorForGame(ga);
    }

    /**
     * Send a server text message to the given game.
     * Equivalent to: messageToGame(ga, new SOCGameTextMsg(ga, {@link #SERVERNAME}, txt));
     *<P>
     * Do not pass SOCSomeMessage.toCmd() into this method; the message type number
     * will be GAMETEXTMSG, not the desired SOMEMESSAGE.
     *<P>
     * <b>Locks:</b> Takes, releases {@link SOCGameList#takeMonitorForGame(String)}.
     *
     * @param ga  the name of the game
     * @param txt the message text to send. If
     *            text begins with ">>>", the client should consider this
     *            an urgent message, and draw the user's attention in some way.
     *            (See {@link #messageToGameUrgent(String, String)})
     * @see #messageToGame(String, SOCMessage)
     * @see #messageToGameWithMon(String, SOCMessage)
     * @since 1.1.08
     */
    public void messageToGame(final String ga, final String txt)
    {
        gameList.takeMonitorForGame(ga);

        try
        {
            Vector v = gameList.getMembers(ga);

            if (v != null)
            {
                final String gameTextMsg = SOCGameTextMsg.toCmd(ga, SERVERNAME, txt);
                Enumeration menum = v.elements();

                while (menum.hasMoreElements())
                {
                    StringConnection c = (StringConnection) menum.nextElement();
                    if (c != null)
                        c.put(gameTextMsg);
                }
            }
        }
        catch (Throwable e)
        {
            D.ebugPrintStackTrace(e, "Exception in messageToGame");
        }

        gameList.releaseMonitorForGame(ga);
    }

    /**
     * Send a message to the given game.
     *<P>
     *<b>Locks:</b> MUST HAVE THE
     * {@link SOCGameList#takeMonitorForGame(String) gameList.takeMonitorForGame(ga)}
     * before calling this method.
     *
     * @param ga  the name of the game
     * @param mes the message to send
     * @see #messageToGame(String, SOCMessage)
     * @see #messageToGameForVersions(SOCGame, int, int, SOCMessage, boolean)
     */
    public void messageToGameWithMon(String ga, SOCMessage mes)
    {
        Vector v = gameList.getMembers(ga);

        if (v != null)
        {
            //D.ebugPrintln("M2G - "+mes);
            Enumeration menum = v.elements();

            while (menum.hasMoreElements())
            {
                StringConnection c = (StringConnection) menum.nextElement();

                if (c != null)
                {
                    //currentGameEventRecord.addMessageOut(new SOCMessageRecord(mes, "SERVER", c.getData()));
                    c.put(mes.toCmd());
                }
            }
        }
    }

    /**
     * Send a message to all the connections in a game
     * excluding some.
     *
     * @param gn  the name of the game
     * @param ex  the list of exceptions
     * @param mes the message
     * @param takeMon Should this method take and release
     *                game's monitor via {@link SOCGameList#takeMonitorForGame(String)} ?
     * @see #messageToGameExcept(String, StringConnection, SOCMessage, boolean)
     */
    public void messageToGameExcept(String gn, Vector ex, SOCMessage mes, boolean takeMon)
    {
        if (takeMon)
            gameList.takeMonitorForGame(gn);

        try
        {
            Vector v = gameList.getMembers(gn);

            if (v != null)
            {
                //D.ebugPrintln("M2GE - "+mes);
                Enumeration menum = v.elements();

                while (menum.hasMoreElements())
                {
                    StringConnection con = (StringConnection) menum.nextElement();

                    if ((con != null) && (!ex.contains(con)))
                    {
                        //currentGameEventRecord.addMessageOut(new SOCMessageRecord(mes, "SERVER", con.getData()));
                        con.put(mes.toCmd());
                    }
                }
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in messageToGameExcept");
        }

        if (takeMon)
            gameList.releaseMonitorForGame(gn);
    }

    /**
     * Send a message to all the connections in a game
     * excluding one.
     *
     * @param gn  the name of the game
     * @param ex  the excluded connection, or null
     * @param mes the message
     * @param takeMon Should this method take and release
     *                game's monitor via {@link SOCGameList#takeMonitorForGame(String)} ?
     * @see #messageToGameExcept(String, Vector, SOCMessage, boolean)
     * @see #messageToGameForVersionsExcept(SOCGame, int, int, StringConnection, SOCMessage, boolean)
     */
    public void messageToGameExcept(String gn, StringConnection ex, SOCMessage mes, boolean takeMon)
    {
        if (takeMon)
            gameList.takeMonitorForGame(gn);

        try
        {
            Vector v = gameList.getMembers(gn);

            if (v != null)
            {
                //D.ebugPrintln("M2GE - "+mes);
                Enumeration menum = v.elements();

                while (menum.hasMoreElements())
                {
                    StringConnection con = (StringConnection) menum.nextElement();
                    if ((con != null) && (con != ex))
                    {
                        //currentGameEventRecord.addMessageOut(new SOCMessageRecord(mes, "SERVER", con.getData()));
                        con.put(mes.toCmd());
                    }
                }
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in messageToGameExcept");
        }

        if (takeMon)
            gameList.releaseMonitorForGame(gn);
    }

    /**
     * Send a message to all the connections in a game in a certain version range.
     * Used for backwards compatibility.
     *
     * @param ga  the game
     * @param vmin  Minimum version to send to, or -1. Same format as
     *              {@link Version#versionNumber()} and {@link StringConnection#getVersion()}.
     * @param vmax  Maximum version to send to, or {@link Integer#MAX_VALUE}
     * @param mes  the message
     * @param takeMon  Should this method take and release game's monitor
     *                 via {@link SOCGameList#takeMonitorForGame(String)} ?
     *                 If the game's clients are all older than <tt>vmin</tt> or
     *                 newer than <tt>vmax</tt>, nothing happens and the monitor isn't taken.
     * @since 1.1.19
     */
    public final void messageToGameForVersions
        (final SOCGame ga, final int vmin, final int vmax, final SOCMessage mes, final boolean takeMon)
    {
        messageToGameForVersionsExcept(ga, vmin, vmax, null, mes, takeMon);
    }

    /**
     * Send a message to all the connections in a game in a certain version range, excluding one.
     * Used for backwards compatibility.
     *
     * @param ga  the game
     * @param vmin  Minimum version to send to, or -1. Same format as
     *              {@link Version#versionNumber()} and {@link StringConnection#getVersion()}.
     * @param vmax  Maximum version to send to, or {@link Integer#MAX_VALUE}
     * @param ex  the excluded connection, or null
     * @param mes  the message
     * @param takeMon  Should this method take and release game's monitor
     *                 via {@link SOCGameList#takeMonitorForGame(String)} ?
     *                 If the game's clients are all older than <tt>vmin</tt> or
     *                 newer than <tt>vmax</tt>, nothing happens and the monitor isn't taken.
     * @since 1.1.19
     * @see #messageToGameExcept(String, StringConnection, SOCMessage, boolean)
     */
    public final void messageToGameForVersionsExcept
        (final SOCGame ga, final int vmin, final int vmax, final StringConnection ex,
         final SOCMessage mes, final boolean takeMon)
    {
        if ((ga.clientVersionLowest > vmax) || (ga.clientVersionHighest < vmin))
            return;  // <--- All clients too old or too new ---

        final String gn = ga.getName();

        if (takeMon)
            gameList.takeMonitorForGame(gn);

        try
        {
            Vector v = gameList.getMembers(gn);
            if (v != null)
            {
                String mesCmd = null;  // lazy init, will be mes.toCmd()
                Enumeration menum = v.elements();

                while (menum.hasMoreElements())
                {
                    StringConnection con = (StringConnection) menum.nextElement();
                    if ((con == null) || (con == ex))
                        continue;

                    final int cv = con.getVersion();
                    if ((cv < vmin) || (cv > vmax))
                        continue;

                    if (mesCmd == null)
                        mesCmd = mes.toCmd();
                    con.put(mesCmd);
                }
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in messageToGameForVersions");
        }

        if (takeMon)
            gameList.releaseMonitorForGame(gn);
    }

    /**
     * Send an urgent SOCGameTextMsg to the given game.
     * An "urgent" message is a SOCGameTextMsg whose text
     * begins with ">>>"; the client should draw the user's
     * attention in some way.
     *<P>
     * <b>Locks:</b> Like {@link #messageToGame(String, String)}, will take and release the game's monitor.
     *
     * @param ga  the name of the game
     * @param mes the message to send. If mes does not begin with ">>>",
     *            will prepend ">>> " before sending mes.
     */
    public void messageToGameUrgent(String ga, String mes)
    {
        if (! mes.startsWith(">>>"))
            mes = ">>> " + mes;
        messageToGame(ga, mes);
    }

    /**
     * things to do when the connection c leaves
     *<P>
     * This method is called within a per-client thread,
     * after connection is removed from conns collection
     * and version collection, and after c.disconnect() has been called.
     *
     * @param c  the connection
     */
    public void leaveConnection(StringConnection c)
    {
        if ((c != null) && (c.getData() != null))
        {
            leaveAllChannels(c);
            leaveAllGames(c);

            /**
             * if it is a robot, remove it from the list
             */
            robots.removeElement(c);
        }
    }

    /**
     * Things to do when a new connection comes.
     *<P>
     * If we already have {@link #maxConnections} named clients, reject this new one
     * by sending {@link SOCRejectConnection}.
     *<P>
     * If the connection is accepted, it's added to {@link #unnamedConns} until the
     * player "names" it by joining or creating a game under their player name.
     * Other communication is then done, in {@link #newConnection2(StringConnection)}.
     *<P>
     * Also set client's "assumed version" to -1, until we have sent and
     * received a VERSION message.
     *<P>
     * This method is called within a per-client thread.
     * You can send to client, but can't yet receive messages from them.
     *<P>
     *  SYNCHRONIZATION NOTE: During the call to newConnection1, the monitor lock of
     *  {@link #unnamedConns} is held.  Thus, defer as much as possible until
     *  {@link #newConnection2(StringConnection)} (after the connection is accepted).
     *
     * @param c  the new Connection
     * @return true to accept and continue, false if you have rejected this connection;
     *         if false, addConnection will call {@link StringConnection#disconnectSoft()}.
     *
     * @see #addConnection(StringConnection)
     * @see #newConnection2(StringConnection)
     * @see #nameConnection(StringConnection, boolean)
     */
    public boolean newConnection1(StringConnection c)
    {
        if (c != null)
        {
            /**
             * see if we are under the connection limit
             */
            try
            {
                if (getNamedConnectionCount() >= maxConnections)
                {
                    SOCRejectConnection rcCommand = new SOCRejectConnection("Too many connections, please try another server.");
                    c.put(rcCommand.toCmd());
                }
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Caught exception in SOCServer.newConnection(Connection)");
            }

            try
            {
                /**
                 * prevent someone from connecting twice from
                 * the same machine
                 * (Commented out: This is a bad idea due to proxies, NAT, etc.)
                 */
                boolean hostMatch = false;
                /*
                Enumeration allConnections = this.getConnections();

                   while(allConnections.hasMoreElements()) {
                   StringConnection tempCon = (StringConnection)allConnections.nextElement();
                   if (!(c.host().equals("pippen")) && (tempCon.host().equals(c.host()))) {
                   hostMatch = true;
                   break;
                   }
                   }
                 */
                if (hostMatch)
                {
                    SOCRejectConnection rcCommand = new SOCRejectConnection("Can't connect to the server more than once from one machine.");
                    c.put(rcCommand.toCmd());
                }
                else
                {
                    /**
                     * Accept this connection.
                     * Once it's added to the list,
                     * {@link #newConnection2(StringConnection)} will
                     * try to wait for client version, and
                     * will send the list of channels and games.
                     */
                    c.setVersion(-1);
                    return true;
                }
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Caught exception in SOCServer.newConnection(Connection)");
            }
        }

        return false;  // Not accepted
    }

    /**
     * Send welcome messages (server version and features, and the lists of
     * channels and games ({@link SOCChannels}, {@link SOCGames})) when a new
     * connection comes, part 2 - c has been accepted and added to a connection list.
     * Unlike {@link #newConnection1(StringConnection)},
     * no connection-list locks are held when this method is called.
     *<P>
     * Client's {@link SOCClientData} appdata is set here.
     *<P>
     * This method is called within a per-client thread.
     * You can send to client, but can't yet receive messages from them.
     */
    protected void newConnection2(StringConnection c)
    {
        SOCClientData cdata = new SOCClientData();
        c.setAppData(cdata);

        // VERSION of server
        SOCServerFeatures feats = features;
        if (acctsNotOpenRegButNoUsers)
        {
            feats = new SOCServerFeatures(features);
            feats.add(SOCServerFeatures.FEAT_OPEN_REG);  // no accounts: don't require a password from SOCAccountClient
        }
        c.put(SOCVersion.toCmd
            (Version.versionNumber(), Version.version(), Version.buildnum(), feats.getEncodedList()));

        // CHANNELS
        Vector cl = new Vector();
        channelList.takeMonitor();

        try
        {
            Enumeration clEnum = channelList.getChannels();

            while (clEnum.hasMoreElements())
            {
                cl.addElement(clEnum.nextElement());
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in newConnection (channelList)");
        }

        channelList.releaseMonitor();

        c.put(SOCChannels.toCmd(cl));

        // GAMES

        /**
         * Has the client sent us its VERSION message, as the first inbound message?
         * Games will be sent once we know the client's version, or have guessed
         * that it's too old (if the client doesn't tell us soon enough).
         * So: Check if input is waiting for us. If it turns out
         * the waiting message is something other than VERSION,
         * server callback {@link #processFirstCommand} will set up the version TimerTask
         * using {@link SOCClientData#setVersionTimer}.
         * The version timer will call {@link #sendGameList} when it expires.
         * If no input awaits us right now, set up the timer here.
         */
        if (! c.isInputAvailable())
        {
            cdata.setVersionTimer(this, c);
        } 

    }  // newConnection2

    /**
     * Name a current connection to the system, which may replace an older connection.
     * Call c.setData(name) just before calling this method.
     * Calls {@link Server#nameConnection(StringConnection, boolean)} to move the connection
     * from the unnamed to the named connection list.  Increments {@link #numberOfUsers}.
     *<P>
     * If <tt>isReplacing</tt>:
     *</UL>
     * <LI> Replaces the old connection with the new one in all its games and channels
     * <LI> Calls {@link SOCClientData#copyClientPlayerStats(SOCClientData)}
     *      for win/loss record and current game and channel count
     * <LI> Sends the old connection an informational disconnect {@link SOCServerPing SOCServerPing(-1)}
     *</UL>
     *
     * @param c  Connected client; its name key ({@link StringConnection#getData()}) must not be null
     * @param isReplacing  Are we replacing / taking over a current connection?
     * @throws IllegalArgumentException If c isn't already connected, if c.getData() returns null,
     *          or if nameConnection has previously been called for this connection.
     * @since 1.1.08
     */
    @Override
    public void nameConnection(StringConnection c, boolean isReplacing)
        throws IllegalArgumentException
    {
        StringConnection oldConn = null;
        if (isReplacing)
        {
            String cKey = c.getData();
            if (cKey == null)
                throw new IllegalArgumentException("null c.getData");
            oldConn = (StringConnection) conns.get(cKey);
            if (oldConn == null)
                isReplacing = false;  // shouldn't happen, but fail gracefully
        }

        super.nameConnection(c, isReplacing);

        if (isReplacing)
        {
            gameList.replaceMemberAllGames(oldConn, c);
            channelList.replaceMemberAllChannels(oldConn, c);

            SOCClientData scdNew = (SOCClientData) (c.getAppData());
            SOCClientData scdOld = (SOCClientData) (oldConn.getAppData());
            if ((scdNew != null) && (scdOld != null))
                scdNew.copyClientPlayerStats(scdOld);

            // Let the old one know it's disconnected now,
            // in case it ever does get its connection back.
            if (oldConn.getVersion() >= 1108)
                oldConn.put(SOCServerPing.toCmd(-1));
        }

        numberOfUsers++;
    }

    /**
     * Send the entire list of games to this client; this is sent once per connecting client.
     * Or, send the set of changed games, if the client's guessed version was wrong.
     * The list includes a flag on games which can't be joined by this client version
     * ({@link SOCGames#MARKER_THIS_GAME_UNJOINABLE}).
     *<P>
     * If <b>entire list</b>, then depending on client's version, the message sent will be
     * either {@link SOCGames GAMES} or {@link SOCGamesWithOptions GAMESWITHOPTIONS}.
     * If <b>set of changed games</b>, sent as matching pairs of {@link SOCDeleteGame DELETEGAME}
     * and either {@link SOCNewGame NEWGAME} or {@link SOCNewGameWithOptions NEWGAMEWITHOPTIONS}.
     *<P>
     * There are 2 possible scenarios for when this method is called:
     *<P>
     * - (A) Sending game list to client, for the first time:
     *    Iterate through all games, looking for ones the client's version
     *    is capable of joining.  If not capable, mark the game name as such
     *    before sending it to the client.  (As a special case, very old
     *    client versions "can't know" about the game they can't join, because
     *    they don't recognize the marker.)
     *    Also set the client data's hasSentGameList flag.
     *<P>
     * - (B) The client didn't give its version, and was thus
     *    identified as an old version.  Now we know its newer true version,
     *    so we must tell it about games that it can now join,
     *    which couldn't have been joined by the older assumed version.
     *    So:  Look for games with those criteria.
     *<P>
     * Sending the list is done here, and not in newConnection2, because we must first
     * know the client's version.
     *<P>
     * The minimum version which recognizes the "can't join" marker is
     * 1.1.06 ({@link SOCGames#VERSION_FOR_UNJOINABLE}).  Older clients won't be sent
     * the game names they can't join.
     *<P>
     * <b>Locks:</b> Calls {@link SOCGameListAtServer#takeMonitor()} / releaseMonitor
     *
     * @param c Client's connection; will call getVersion() on it
     * @param prevVers  Previously assumed version of this client;
     *                  if re-sending the list, should be less than c.getVersion.
     * @since 1.1.06
     */
    public void sendGameList(StringConnection c, int prevVers)
    {
        final int cliVers = c.getVersion();   // Need to know this before sending

        // Before send list of games, try for a client version.
        // Give client 1.2 seconds to send it, before we assume it's old
        // (too old to know VERSION).
        // This waiting is done from SOCClientData.setVersionTimer;
        // time to wait is SOCServer.CLI_VERSION_TIMER_FIRE_MS.

        // GAMES / GAMESWITHOPTIONS

        // Based on version:
        // If client is too old (< 1.1.06), it can't be told names of games
        // that it isn't capable of joining.

        boolean cliCanKnow = (cliVers >= SOCGames.VERSION_FOR_UNJOINABLE);
        final boolean cliCouldKnow = (prevVers >= SOCGames.VERSION_FOR_UNJOINABLE);

        Vector gl = new Vector();  // contains Strings and/or SOCGames;
                                   // strings are names of unjoinable games,
                                   // with the UNJOINABLE prefix.
        gameList.takeMonitor();
        final boolean alreadySent =
            ((SOCClientData) c.getAppData()).hasSentGameList();  // Check while gamelist monitor is held
        boolean cliVersionChange = alreadySent && (cliVers > prevVers);

        if (alreadySent && ! cliVersionChange)
        {
            gameList.releaseMonitor();

            return;  // <---- Early return: Nothing to do ----
        }

        if (! alreadySent)
        {
            ((SOCClientData) c.getAppData()).setSentGameList();  // Set while gamelist monitor is held
        }

        /**
         * We release the monitor as soon as we can, even though we haven't yet
         * sent the list to the client.  It's theoretically possible the client will get
         * a NEWGAME message, which is OK, or a DELETEGAME message, before it receives the list
         * we're building.  
         * NEWGAME is OK because the GAMES message won't clear the list contents at client.
         * DELETEGAME is less OK, but it's not very likely.
         * If the game is deleted, and then they see it in the list, trying to join that game
         * will create a new empty game with that name.
         */
        Enumeration gaEnum = gameList.getGamesData();
        gameList.releaseMonitor();

        if (cliVersionChange && cliCouldKnow)
        {
            // If they already have the names of games they can't join,
            // no need to re-send those names.
            cliCanKnow = false;
        }

        try
        {
            SOCGame g;

            // Build the list of game names.  This loop is used for the
            // initial list, or for sending just the delta after the version fix.

            while (gaEnum.hasMoreElements())
            {
                g = (SOCGame) gaEnum.nextElement();
                int gameVers = g.getClientVersionMinRequired();

                if (cliVersionChange && (prevVers >= gameVers))
                {
                    continue;  // No need to re-announce, they already
                               // could join it with lower (prev-assumed) version
                }

                if (cliVers >= gameVers)
                {
                    gl.addElement(g);  // Can join
                } else if (cliCanKnow)
                {
                    //  Cannot join, but can see it
                    StringBuffer sb = new StringBuffer();
                    sb.append(SOCGames.MARKER_THIS_GAME_UNJOINABLE);
                    sb.append(g.getName());
                    gl.addElement(sb.toString());
                }
                // else
                //   can't join, and won't see it

            }

            // We now have the list of game names / socgame objs.

            if (! alreadySent)
            {
                // send the full list as 1 message
                if (cliVers >= SOCNewGameWithOptions.VERSION_FOR_NEWGAMEWITHOPTIONS)
                    c.put(SOCGamesWithOptions.toCmd(gl, cliVers));
                else
                    c.put(SOCGames.toCmd(gl));
            } else {
                // send deltas only
                for (int i = 0; i < gl.size(); ++i)
                {
                    Object ob = gl.elementAt(i);
                    String gaName;
                    if (ob instanceof SOCGame)
                        gaName = ((SOCGame) ob).getName();
                    else
                        gaName = (String) ob;

                    if (cliCouldKnow)
                    {
                        // first send delete, if it's on their list already
                        c.put(SOCDeleteGame.toCmd(gaName));
                    }
                    // announce as 'new game' to client
                    if ((ob instanceof SOCGame) && (cliVers >= SOCNewGameWithOptions.VERSION_FOR_NEWGAMEWITHOPTIONS))
                        c.put(SOCNewGameWithOptions.toCmd((SOCGame) ob, cliVers));
                    else
                        c.put(SOCNewGame.toCmd(gaName));
                }
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in newConnection(sendgamelist)");
        }

        /*
           gaEnum = gameList.getGames();
           int scores[] = new int[SOCGame.MAXPLAYERS];
           boolean robots[] = new boolean[SOCGame.MAXPLAYERS];
           while (gaEnum.hasMoreElements()) {
           String gameName = (String)gaEnum.nextElement();
           SOCGame theGame = gameList.getGameData(gameName);
           for (int i = 0; i < SOCGame.MAXPLAYERS; i++) {
           SOCPlayer player = theGame.getPlayer(i);
           if (player != null) {
           if (theGame.isSeatVacant(i)) {
           scores[i] = -1;
           robots[i] = false;
           } else {
           scores[i] = player.getPublicVP();
           robots[i] = player.isRobot();
           }
           } else {
           scores[i] = 0;
           }
           }
           c.put(SOCGameStats.toCmd(gameName, scores, robots));
           }
         */        

    }  // sendGameList

    /**
     * Check if a nickname is okay, and, if they're already logged in, whether a
     * new replacement connection can "take over" the existing one.
     *<P>
     * a name is ok if it hasn't been used yet, isn't {@link #SERVERNAME the server's name},
     * and (since 1.1.07) passes {@link SOCMessage#isSingleLineAndSafe(String)}.
     *<P>
     * The "take over" option is used for reconnect when a client loses
     * connection, and server doesn't realize it.
     * A new connection can "take over" the name after a timeout; check
     * the return value.
     * (After {@link #NICKNAME_TAKEOVER_SECONDS_SAME_IP} or
     *  {@link #NICKNAME_TAKEOVER_SECONDS_DIFFERENT_IP} seconds)
     * When taking over, the new connection's client version must be able
     * to join all games that the old connection is playing, as returned
     * by {@link SOCGameListAtServer#playerGamesMinVersion(StringConnection) gameList.playerGamesMinVersion}.
     *
     * @param n  the name
     * @param newc  A new incoming connection, asking for this name
     * @param withPassword  Did the connection supply a password?
     * @param isBot  True if authenticating as robot, false if human
     * @return   0 if the name is okay; <BR>
     *          -1 if OK <strong>and you are taking over a connection;</strong> <BR>
     *          -2 if not OK by rules (fails isSingleLineAndSafe,
     *             named "debug" or {@link #SERVERNAME},
     *             human with bot name prefix, etc); <BR>
     *          -vers if not OK by version (for takeover; will be -1000 lower); <BR>
     *          or, the number of seconds after which <tt>newc</tt> can
     *             take over this name's games.
     * @see #checkNickname_getRetryText(int)
     */
    private int checkNickname
        (String n, StringConnection newc, final boolean withPassword, final boolean isBot)
    {
        if (n.equals(SERVERNAME))
        {
            return -2;
        }

        if (! SOCMessage.isSingleLineAndSafe(n))
        {
            return -2;
        }

        // check "debug" and bot name prefixes used in setupLocalRobots
        final String nLower = n.toLowerCase(Locale.US);
        if ((nLower.equals("debug") && ! allowDebugUser)
            || ((! isBot)
                && (nLower.startsWith("droid ") || nLower.startsWith("robot "))))
        {
            return -2;
        }

        // check conns hashtable
        StringConnection oldc = getConnection(n, false);
        if (oldc == null)
        {
            return 0;  // OK: no player by that name already
        }

        // Can we take over this one?
        SOCClientData scd = (SOCClientData) oldc.getAppData();
        if (scd == null)
        {
            return -2;  // Shouldn't happen; name and SCD are assigned at same time
        }

        final int timeoutNeeded;
        if (withPassword)
            timeoutNeeded = NICKNAME_TAKEOVER_SECONDS_SAME_PASSWORD;
        else if (newc.host().equals(oldc.host()))
            // same IP address or hostname
            timeoutNeeded = NICKNAME_TAKEOVER_SECONDS_SAME_IP;
        else
            timeoutNeeded = NICKNAME_TAKEOVER_SECONDS_DIFFERENT_IP;

        final long now = System.currentTimeMillis();
        if (scd.disconnectLastPingMillis != 0)
        {
            int secondsSincePing = (int) (((now - scd.disconnectLastPingMillis)) / 1000L);
            if (secondsSincePing >= timeoutNeeded)
            {
                // Already sent ping, timeout has expired.
                // Re-check version just in case.
                int minVersForGames = gameList.playerGamesMinVersion(oldc);
                if (minVersForGames > newc.getVersion())
                {
                    if (minVersForGames < 1000)
                        minVersForGames = 1000;
                    return -minVersForGames;  // too old to play
                }
                // it's OK to take over this nickname.  A call made soon
                // to nameConnection(c,true) will transfer data from old conn, to new conn.
                return -1;
            } else {
                // Already sent ping, timeout not yet expired.
                return timeoutNeeded - secondsSincePing;
            }
        }

        // Have not yet sent a ping.
        int minVersForGames = gameList.playerGamesMinVersion(oldc);
        if (minVersForGames > newc.getVersion())
        {
            if (minVersForGames < 1000)
                minVersForGames = 1000;
            return -minVersForGames;  // too old to play
        }
        scd.disconnectLastPingMillis = now;
        if (oldc.getVersion() >= 1108)
        {
            // Already-connected client should respond to ping.
            // If not, consider them disconnected.
            oldc.put(SOCServerPing.toCmd(timeoutNeeded));
        }

        return timeoutNeeded;
    }

    /**
     * For a nickname that seems to be in use, build a text message with the
     * time remaining before someone can attempt to take over that nickname.
     * Used for reconnect when a client loses connection, and server doesn't realize it. 
     * A new connection can "take over" the name after a timeout.
     * ({@link #NICKNAME_TAKEOVER_SECONDS_SAME_IP},
     *  {@link #NICKNAME_TAKEOVER_SECONDS_DIFFERENT_IP})
     *
     * @param nameTimeout  Number of seconds before trying to reconnect
     * @return message starting with "Please wait x seconds" or "Please wait x minute(s)"
     * @since 1.1.08
     */
    private static final String checkNickname_getRetryText(final int nameTimeout)
    {
        StringBuffer sb = new StringBuffer("Please wait ");
        if (nameTimeout <= 90)
        {
            sb.append(nameTimeout);
            sb.append(" seconds");
        } else {
            sb.append((int) ((nameTimeout + 20) / 60));
            sb.append(" minute(s)");
        }
        sb.append(MSG_NICKNAME_ALREADY_IN_USE_WAIT_TRY_AGAIN);
        sb.append(MSG_NICKNAME_ALREADY_IN_USE);
        return sb.toString();
    }

    /**
     * For a nickname that seems to be in use, build a text message with the
     * minimum version number needed to take over that nickname.
     * Used for reconnect when a client loses connection, and server doesn't realize it. 
     * A new connection can "take over" the name after a timeout.
     *
     * @param needsVersion Version number required to take it over;
     *         a positive integer in the same format as {@link SOCGame#getClientVersionMinRequired()}
     * @return string containing the version,
     *         starting with {@link #MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P1}.
     * @since 1.1.08
     */
    private static final String checkNickname_getVersionText(final int needsVersion)
    {
        StringBuffer sb = new StringBuffer(MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P1);
        sb.append(needsVersion);
        sb.append(MSG_NICKNAME_ALREADY_IN_USE_NEWER_VERSION_P2);
        return sb.toString();
    }

    /**
     * Callback to process the client's first message command specially.
     * Look for VERSION message; if none is received, set up a timer to wait
     * for version and (if never received) send out the game list soon.
     *
     * @param str Contents of first message from the client.
     *         Will be parsed with {@link SOCMessage#toMsg(String)}.
     * @param con Connection (client) sending this message.
     * @return true if processed here (VERSION), false if this message should be
     *         queued up and processed by the normal {@link #processCommand(String, StringConnection)}.
     */
    public boolean processFirstCommand(String str, StringConnection con)
    {
        try
        {
            SOCMessage mes = SOCMessage.toMsg(str);
            if ((mes != null) && (mes.getType() == SOCMessage.VERSION))
            {
                handleVERSION(con, (SOCVersion) mes);

                return true;  // <--- Early return: Version was handled ---
            }
        }
        catch (Throwable e)
        {
            D.ebugPrintStackTrace(e, "ERROR -> processFirstCommand");
        }

        // It wasn't version, it was something else.  Set the
        // timer to wait for version, and return false for normal
        // processing of the message.

        ((SOCClientData) con.getAppData()).setVersionTimer(this, con);
        return false;
    }

    /**
     * Treat the incoming messages.  Messages of unknown type are ignored.
     *<P>
     * Called from the single 'treater' thread.
     * <em>Do not block or sleep</em> because this is single-threaded.
     *<P>
     * The first message from a client is treated by
     * {@link #processFirstCommand(String, StringConnection)} instead.
     *<P>
     * Note: When there is a choice, always use local information
     *       over information from the message.  For example, use
     *       the nickname from the connection to get the player
     *       information rather than the player information from
     *       the message.  This makes it harder to send false
     *       messages making players do things they didn't want
     *       to do.
     *
     * @param s    Contents of message from the client.
     *       Will be parsed with {@link SOCMessage#toMsg(String)}.
     * @param c    Connection (client) sending this message.
     */
    public void processCommand(String s, StringConnection c)
    {
        try
        {
            SOCMessage mes = (SOCMessage) SOCMessage.toMsg(s);

            // D.ebugPrintln(c.getData()+" - "+mes);
            if (mes != null)
            {
                switch (mes.getType())
                {

                /**
                 * client's echo of a server ping
                 */
                case SOCMessage.SERVERPING:
                    handleSERVERPING(c, (SOCServerPing) mes);
                    break;

                /**
                 * client's optional authentication request before creating a game (v1.1.19+)
                 */
                case SOCMessage.AUTHREQUEST:
                    handleAUTHREQUEST(c, (SOCAuthRequest) mes);
                    break;

                /**
                 * client's "version" message
                 */
                case SOCMessage.VERSION:
                    handleVERSION(c, (SOCVersion) mes);

                    break;
                
                /**
                 * "join a channel" message
                 */
                case SOCMessage.JOIN:
                    handleJOIN(c, (SOCJoin) mes);

                    break;

                /**
                 * "leave a channel" message
                 */
                case SOCMessage.LEAVE:
                    handleLEAVE(c, (SOCLeave) mes);

                    break;

                /**
                 * "leave all channels" message
                 */
                case SOCMessage.LEAVEALL:
                    removeConnection(c);
                    removeConnectionCleanup(c);

                    break;

                /**
                 * text message to a channel
                 */
                case SOCMessage.TEXTMSG:
                    handleTEXTMSG(c, (SOCTextMsg) mes);
                    break;

                /**
                 * a robot has connected to this server
                 */
                case SOCMessage.IMAROBOT:
                    handleIMAROBOT(c, (SOCImARobot) mes);

                    break;

                /**
                 * text message from a game (includes debug commands)
                 */
                case SOCMessage.GAMETEXTMSG:
                    handleGAMETEXTMSG(c, (SOCGameTextMsg) mes);
                    break;

                /**
                 * "join a game" message
                 */
                case SOCMessage.JOINGAME:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleJOINGAME(c, (SOCJoinGame) mes);

                    //ga = (SOCGame)gamesData.get(((SOCJoinGame)mes).getGame());
                    //if (ga != null) {
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCJoinGame)mes).getGame());
                    //}
                    break;

                /**
                 * "leave a game" message
                 */
                case SOCMessage.LEAVEGAME:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleLEAVEGAME(c, (SOCLeaveGame) mes);

                    //ga = (SOCGame)gamesData.get(((SOCLeaveGame)mes).getGame());
                    //if (ga != null) {
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCLeaveGame)mes).getGame());
                    //}
                    break;

                /**
                 * someone wants to sit down
                 */
                case SOCMessage.SITDOWN:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleSITDOWN(c, (SOCSitDown) mes);

                    //ga = (SOCGame)gamesData.get(((SOCSitDown)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCSitDown)mes).getGame());
                    break;

                /**
                 * someone put a piece on the board
                 */
                case SOCMessage.PUTPIECE:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handlePUTPIECE(c, (SOCPutPiece) mes);

                    //ga = (SOCGame)gamesData.get(((SOCPutPiece)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCPutPiece)mes).getGame());
                    break;

                /**
                 * a player is moving the robber
                 */
                case SOCMessage.MOVEROBBER:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleMOVEROBBER(c, (SOCMoveRobber) mes);

                    //ga = (SOCGame)gamesData.get(((SOCMoveRobber)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCMoveRobber)mes).getGame());
                    break;

                /**
                 * someone is starting a game
                 */
                case SOCMessage.STARTGAME:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleSTARTGAME(c, (SOCStartGame) mes);

                    //ga = (SOCGame)gamesData.get(((SOCStartGame)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCStartGame)mes).getGame());
                    break;

                case SOCMessage.ROLLDICE:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleROLLDICE(c, (SOCRollDice) mes);

                    //ga = (SOCGame)gamesData.get(((SOCRollDice)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCRollDice)mes).getGame());
                    break;

                case SOCMessage.DISCARD:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleDISCARD(c, (SOCDiscard) mes);

                    //ga = (SOCGame)gamesData.get(((SOCDiscard)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCDiscard)mes).getGame());
                    break;

                case SOCMessage.ENDTURN:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleENDTURN(c, (SOCEndTurn) mes);

                    //ga = (SOCGame)gamesData.get(((SOCEndTurn)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCEndTurn)mes).getGame());
                    break;

                case SOCMessage.CHOOSEPLAYER:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleCHOOSEPLAYER(c, (SOCChoosePlayer) mes);

                    //ga = (SOCGame)gamesData.get(((SOCChoosePlayer)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCChoosePlayer)mes).getGame());
                    break;

                case SOCMessage.MAKEOFFER:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleMAKEOFFER(c, (SOCMakeOffer) mes);

                    //ga = (SOCGame)gamesData.get(((SOCMakeOffer)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCMakeOffer)mes).getGame());
                    break;

                case SOCMessage.CLEAROFFER:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleCLEAROFFER(c, (SOCClearOffer) mes);

                    //ga = (SOCGame)gamesData.get(((SOCClearOffer)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCClearOffer)mes).getGame());
                    break;

                case SOCMessage.REJECTOFFER:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleREJECTOFFER(c, (SOCRejectOffer) mes);

                    //ga = (SOCGame)gamesData.get(((SOCRejectOffer)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCRejectOffer)mes).getGame());
                    break;

                case SOCMessage.ACCEPTOFFER:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleACCEPTOFFER(c, (SOCAcceptOffer) mes);

                    //ga = (SOCGame)gamesData.get(((SOCAcceptOffer)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCAcceptOffer)mes).getGame());
                    break;

                case SOCMessage.BANKTRADE:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleBANKTRADE(c, (SOCBankTrade) mes);

                    //ga = (SOCGame)gamesData.get(((SOCBankTrade)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCBankTrade)mes).getGame());
                    break;

                case SOCMessage.BUILDREQUEST:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleBUILDREQUEST(c, (SOCBuildRequest) mes);

                    //ga = (SOCGame)gamesData.get(((SOCBuildRequest)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCBuildRequest)mes).getGame());
                    break;

                case SOCMessage.CANCELBUILDREQUEST:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleCANCELBUILDREQUEST(c, (SOCCancelBuildRequest) mes);

                    //ga = (SOCGame)gamesData.get(((SOCCancelBuildRequest)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCCancelBuildRequest)mes).getGame());
                    break;

                case SOCMessage.BUYCARDREQUEST:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleBUYCARDREQUEST(c, (SOCBuyCardRequest) mes);

                    //ga = (SOCGame)gamesData.get(((SOCBuyCardRequest)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCBuyCardRequest)mes).getGame());
                    break;

                case SOCMessage.PLAYDEVCARDREQUEST:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handlePLAYDEVCARDREQUEST(c, (SOCPlayDevCardRequest) mes);

                    //ga = (SOCGame)gamesData.get(((SOCPlayDevCardRequest)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCPlayDevCardRequest)mes).getGame());
                    break;

                case SOCMessage.DISCOVERYPICK:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleDISCOVERYPICK(c, (SOCDiscoveryPick) mes);

                    //ga = (SOCGame)gamesData.get(((SOCDiscoveryPick)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCDiscoveryPick)mes).getGame());
                    break;

                case SOCMessage.MONOPOLYPICK:

                    //createNewGameEventRecord();
                    //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
                    handleMONOPOLYPICK(c, (SOCMonopolyPick) mes);

                    //ga = (SOCGame)gamesData.get(((SOCMonopolyPick)mes).getGame());
                    //currentGameEventRecord.setSnapshot(ga);
                    //saveCurrentGameEventRecord(((SOCMonopolyPick)mes).getGame());
                    break;

                case SOCMessage.CHANGEFACE:
                    handleCHANGEFACE(c, (SOCChangeFace) mes);

                    break;

                case SOCMessage.SETSEATLOCK:
                    handleSETSEATLOCK(c, (SOCSetSeatLock) mes);

                    break;

                case SOCMessage.RESETBOARDREQUEST:
                    handleRESETBOARDREQUEST(c, (SOCResetBoardRequest) mes);

                    break;

                case SOCMessage.RESETBOARDVOTE:
                    handleRESETBOARDVOTE(c, (SOCResetBoardVote) mes);

                    break;

                case SOCMessage.CREATEACCOUNT:
                    handleCREATEACCOUNT(c, (SOCCreateAccount) mes);

                    break;

                /**
                 * Game option messages. For the best writeup of these messages' interaction with
                 * the client, see {@link soc.client.SOCPlayerClient.GameOptionServerSet}'s javadoc.
                 */

                case SOCMessage.GAMEOPTIONGETDEFAULTS:
                    handleGAMEOPTIONGETDEFAULTS(c, (SOCGameOptionGetDefaults) mes);
                    break;

                case SOCMessage.GAMEOPTIONGETINFOS:
                    handleGAMEOPTIONGETINFOS(c, (SOCGameOptionGetInfos) mes);
                    break;

                case SOCMessage.NEWGAMEWITHOPTIONSREQUEST:
                    handleNEWGAMEWITHOPTIONSREQUEST(c, (SOCNewGameWithOptionsRequest) mes);
                    break;

                /**
                 * debug piece Free Placement (as of 20110104 (v 1.1.12))
                 */
                case SOCMessage.DEBUGFREEPLACE:
                    handleDEBUGFREEPLACE(c, (SOCDebugFreePlace) mes);
                    break;

                /**
                 * Generic simple request from a player.
                 * Added 2013-02-17 for v1.1.18.
                 */
                case SOCMessage.SIMPLEREQUEST:
                    handleSIMPLEREQUEST(c, (SOCSimpleRequest) mes);
                    break;

                }  // switch (mes.getType)
            }  // if (mes != null)
        }
        catch (Throwable e)
        {
            D.ebugPrintStackTrace(e, "ERROR -> processCommand");
        }

    }  // processCommand

    /**
     * Used by {@link #DEBUG_COMMANDS_HELP}, etc.
     * @see #DEBUG_COMMANDS_HELP_PLAYER
     */
    private static final String DEBUG_COMMANDS_HELP_RSRCS
        = "rsrcs: #cl #or #sh #wh #wo player";

    /**
     * Used by {@link #DEBUG_COMMANDS_HELP}, etc.
     * @see #DEBUG_COMMANDS_HELP_PLAYER
     */
    private static final String DEBUG_COMMANDS_HELP_DEV
        = "dev: #typ player";

    /**
     * Debug help: player name or number. Used by {@link #SOC_DEBUG_COMMANDS_HELP}, etc.
     * @since 1.1.20
     */
    private static final String DEBUG_COMMANDS_HELP_PLAYER
        = "'Player' is a player name or #number (upper-left is #0, increasing clockwise)";

    /**
     * Used by {@link #DEBUG_COMMANDS_HELP}, etc. Used with {@link SOCGame#debugFreePlacement}.
     */
    private static final String DEBUG_CMD_FREEPLACEMENT = "*FREEPLACE*";

    /**
     * List and description of general commands that any game member can run.
     * Used by {@link #processDebugCommand(StringConnection, String, String)}}
     * when <tt>*HELP*</tt> is requested.
     * @see #ADMIN_USER_COMMANDS_HELP
     * @see #DEBUG_COMMANDS_HELP
     * @since 1.1.20
     */
    public static final String[] GENERAL_COMMANDS_HELP =
        {
        "--- General Commands ---",
        "*ADDTIME*  add 30 minutes before game expiration",
        "*CHECKTIME*  print time remaining before expiration",
        "*HELP*   info on available commands",
        "*STATS*   server stats and current-game stats",
        "*VERSION*  show version and build information",
        "*WHO*   show players and observers of this game",
        };

    /**
     * Heading to show above any admin commands the user is authorized to run.  Declared separately
     * from {@link #ADMIN_USER_COMMANDS_HELP} for use when other admin types are added.
     *<BR>
     * <tt>--- Admin Commands ---</tt>
     *
     * @since 1.1.20
     */
    private static final String ADMIN_COMMANDS_HEADING = "--- Admin Commands ---";

    /**
     * List and description of user-admin commands. Along with
     * {@link #GENERAL_COMMANDS_HELP} and {@link #DEBUG_COMMANDS_HELP}, used by
     * {@link #processDebugCommand(StringConnection, String, String)}
     * when <tt>*HELP*</tt> is requested by a debug/admin user who passes
     * {@link #isUserDBUserAdmin(String) isUserDBUserAdmin(username)}.
     * Preceded by {@link #ADMIN_COMMANDS_HEADING}.
     * @since 1.1.20
     * @see #GENERAL_COMMANDS_HELP
     * @see #DEBUG_COMMANDS_HELP
     */
    public static final String[] ADMIN_USER_COMMANDS_HELP =
        {
        "*WHO* gameName   show players and observers of gameName",
        "*WHO* *  show all connected clients",
        "*DBSETTINGS*  show current database settings, if any",
        };

    /**
     * List and description of debug/admin commands. Along with
     * {@link #GENERAL_COMMANDS_HELP} and {@link #ADMIN_USER_COMMANDS_HELP},
     * used by {@link #processDebugCommand(StringConnection, String, String)}
     * when <tt>*HELP*</tt> is requested by a debug/admin user.
     * @since 1.1.07
     * @see #GENERAL_COMMANDS_HELP
     * @see #ADMIN_USER_COMMANDS_HELP
     * @see #DEBUG_COMMANDS_HELP_DEV_TYPES
     */
    public static final String[] DEBUG_COMMANDS_HELP =
        {
        "--- Debug Commands ---",
        "*BCAST*  broadcast msg to all games/channels",
        "*GC*    trigger the java garbage-collect",
        "*KILLBOT*  botname  End a bot's connection",
        "*KILLGAME*  end the current game",
        DEBUG_CMD_FREEPLACEMENT + " 1 or 0  Start or end 'Free Placement' mode",
        "*RESETBOT* botname  End a bot's connection",
        "*STOP*  kill the server",
        "--- Debug Resources ---",
        DEBUG_COMMANDS_HELP_RSRCS,
        "Example  rsrcs: 0 3 0 2 0 Myname  or  rsrcs: 0 3 0 2 0 #3",
        DEBUG_COMMANDS_HELP_DEV,
        "Example  dev: 2 Myname  or  dev: 2 #3",
        DEBUG_COMMANDS_HELP_PLAYER,
        "Development card types are:",  // see SOCDevCardConstants
        "0 robber",
        "1 road-building",
        "2 year of plenty",
        "3 monopoly",
        "4 governors house",
        "5 market",
        "6 university",
        "7 temple",
        "8 chapel"
        };

    /**
     * Debug help: 1-line summary of dev card types, from {@link SOCDevCardConstants}.
     * @see #DEBUG_COMMANDS_HELP
     * @since 1.1.17
     */
    private static final String DEBUG_COMMANDS_HELP_DEV_TYPES =
        "### 0:soldier  1:road  2:year of plenty  3:mono  4:gov  5:market  6:univ  7:temple  8:chapel";

    /**
     * Process a debug command, sent by the "debug" client/player.
     * Check {@link #allowDebugUser} before calling this method.
     * For list of commands see {@link #GENERAL_COMMANDS_HELP},
     * {@link #DEBUG_COMMANDS_HELP}, and {@link #ADMIN_USER_COMMANDS_HELP}.
     * "Unprivileged" general commands are handled by
     * {@link #handleGAMETEXTMSG(StringConnection, SOCGameTextMsg)}.
     * @return true if <tt>dcmd</tt> is a recognized debug command, false otherwise
     */
    public boolean processDebugCommand(StringConnection debugCli, String ga, String dcmd)
    {
        final String dcmdU = dcmd.toUpperCase();

        boolean isCmd = true;  // eventual return value; will set false if unrecognized

        if (dcmdU.startsWith("*KILLGAME*"))
        {
            messageToGameUrgent(ga, ">>> ********** " + debugCli.getData() + " KILLED THE GAME!!! ********** <<<");
            gameList.takeMonitor();

            try
            {
                destroyGame(ga);
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in KILLGAME");
            }

            gameList.releaseMonitor();
            broadcast(SOCDeleteGame.toCmd(ga));
        }
        else if (dcmdU.startsWith("*GC*"))
        {
            Runtime rt = Runtime.getRuntime();
            rt.gc();
            messageToGame(ga, "> GARBAGE COLLECTING DONE");
            messageToGame(ga, "> Free Memory: " + rt.freeMemory());
        }
        else if (dcmd.startsWith("*STOP*"))  // dcmd to force case-sensitivity
        {
            String stopMsg = ">>> ********** " + debugCli.getData() + " KILLED THE SERVER!!! ********** <<<";
            stopServer(stopMsg);
            System.exit(0);
        }
        else if (dcmdU.startsWith("*BCAST* "))
        {
            ///
            /// broadcast to all chat channels and games
            ///
            broadcast(SOCBCastTextMsg.toCmd(dcmd.substring(8)));
        }
        else if (dcmdU.startsWith("*BOTLIST*"))
        {
            Enumeration robotsEnum = robots.elements();

            while (robotsEnum.hasMoreElements())
            {
                StringConnection robotConn = (StringConnection) robotsEnum.nextElement();
                messageToGame(ga, "> Robot: " + robotConn.getData());
                robotConn.put(SOCAdminPing.toCmd((ga)));
            }
        }
        else if (dcmdU.startsWith("*RESETBOT* "))
        {
            String botName = dcmd.substring(11).trim();
            messageToGame(ga, "> botName = '" + botName + "'");

            Enumeration robotsEnum = robots.elements();

            boolean botFound = false;
            while (robotsEnum.hasMoreElements())
            {
                StringConnection robotConn = (StringConnection) robotsEnum.nextElement();
                if (botName.equals(robotConn.getData()))
                {
                    botFound = true;
                    messageToGame(ga, "> SENDING RESET COMMAND TO " + botName);

                    SOCAdminReset resetCmd = new SOCAdminReset();
                    robotConn.put(resetCmd.toCmd());

                    break;
                }
            }
            if (! botFound)
                D.ebugPrintln("L2590 Bot not found to reset: " + botName);
        }
        else if (dcmdU.startsWith("*KILLBOT* "))
        {
            String botName = dcmd.substring(10).trim();
            messageToGame(ga, "> botName = '" + botName + "'");

            Enumeration robotsEnum = robots.elements();

            boolean botFound = false;
            while (robotsEnum.hasMoreElements())
            {
                StringConnection robotConn = (StringConnection) robotsEnum.nextElement();

                if (botName.equals(robotConn.getData()))
                {
                    botFound = true;
                    messageToGame(ga, "> DISCONNECTING " + botName);
                    removeConnection(robotConn);
                    removeConnectionCleanup(robotConn);

                    break;
                }
            }
            if (! botFound)
                D.ebugPrintln("L2614 Bot not found to disconnect: " + botName);
        }
        else if (dcmdU.startsWith(DEBUG_CMD_FREEPLACEMENT))
        {
            processDebugCommand_freePlace
                (debugCli, ga, dcmd.substring(DEBUG_CMD_FREEPLACEMENT.length()).trim());
        }
        else
        {
            isCmd = false;
        }

        return isCmd;
    }

    /**
     * The server is being cleanly stopped.
     * Shut down with a final message "The game server is shutting down".
     */
    public synchronized void stopServer()
    {
        stopServer(">>> The game server is shutting down. <<<");
    }

    /**
     * The server is being cleanly stopped.  Send a final message, disconnect all
     * the connections, disconnect from database if connected.
     * Currently called only by the debug command "*STOP*",
     * and by SOCPlayerClient's locally hosted TCP server.
     *
     * @param stopMsg Final text message to send to all connected clients, or null.
     *         Will be sent as a {@link SOCBCastTextMsg}.
     *         As always, if message starts with ">>" it will be considered urgent.
     */
    public synchronized void stopServer(String stopMsg)
    {
        if (stopMsg != null)
        {
            System.out.println("stopServer: " + stopMsg);
            System.out.println();
            broadcast(SOCBCastTextMsg.toCmd(stopMsg));
        }

        /// give time for messages to drain (such as urgent text messages
        /// about stopping the server)
        try
        {
            Thread.sleep(500);
        }
        catch (InterruptedException ie)
        {
            Thread.yield();
        }

        /// now continue with shutdown
        try
        {
            SOCDBHelper.cleanup(true);
        }
        catch (SQLException x) { }
        
        super.stopServer();

        System.out.println("Server shutdown completed.");
    }

    /**
     * Check that the username and password (if any) is okay: Length versus {@link #PLAYER_NAME_MAX_LENGTH}, name
     * in use but not timed out versus takeover, etc. Checks password if using the optional database.
     * Calls {@link #checkNickname(String, StringConnection, boolean, boolean)} and
     * {@link SOCDBHelper#authenticateUserPassword(String, String, soc.server.database.SOCDBHelper.AuthPasswordRunnable)}.
     *<P>
     * If not okay, sends client a {@link SOCStatusMessage} with an appropriate status code.
     *<P>
     * If this connection is already logged on and named ({@link StringConnection#getData() c.getData()} != {@code null}),
     * does nothing: Won't check username or password, just calls {@code authCallback} with {@link #AUTH_OR_REJECT__OK}.
     *<P>
     * Otherwise:
     *<UL>
     * <LI> If this user is already logged into another connection, checks whether this new
     *     replacement connection can "take over" the existing one according to a timeout calculation
     *     in {@link #checkNickname(String, StringConnection, boolean, boolean)}.
     * <LI> Checks username format, password if using DB, etc. If any check fails,
     *     send client a rejection {@code SOCStatusMessage} and return.
     * <LI> If {@code doNameConnection}, calls {@link StringConnection#setData(String) c.setData(nickname)} and
     *     {@link #nameConnection(StringConnection, boolean) nameConnection(c, isTakingOver)}.
     *     If username was found in the optional database, those calls use the exact-case name found by
     *     querying there case-insensitively (see below).
     * <LI> Calls {@code authCallback} with the {@link #AUTH_OR_REJECT__OK} flag, and possibly also the
     *     {@link #AUTH_OR_REJECT__SET_USERNAME} and/or {@link #AUTH_OR_REJECT__TAKING_OVER} flags.
     *</UL>
     * If the password is correct but the username is only a case-insensitive match with the database,
     * the client must update its internal nickname field to the exact-case username:
     *<UL>
     * <LI> If client's version is new enough to do that (v1.2.00+), caller's {@code authCallback} must send
     *     {@link SOCStatusMessage}({@link SOCStatusMessage#SV_OK_SET_NICKNAME SV_OK_SET_NICKNAME}):
     *     Calls {@code authCallback} with {@link #AUTH_OR_REJECT__OK} | {@link #AUTH_OR_REJECT__SET_USERNAME}.
     *     If {@code doNameConnection}, caller can get the exact-case username from {@link StringConnection#getData()};
     *     otherwise {@link SOCDBHelper#getUser(String)} must be called.
     * <LI> If client is too old, this method sends
     *     {@link SOCStatusMessage}({@link SOCStatusMessage#SV_NAME_NOT_FOUND SV_NAME_NOT_FOUND})
     *     and does not call {@code authCallback}.
     *</UL>
     *<P>
     * If this connection is already logged on and named ({@link StringConnection#getData() c.getData()} != <tt>null</tt>),
     * does nothing: Won't check username or password, just calls {@code authCallback} with {@link #AUTH_OR_REJECT__OK}.
     *<P>
     * Before v1.2.00, this method had fewer possible status combinations and returned a single result instead
     * of passing a set of flag bits into {@code authCallback}. v1.2.00 also inlines {@code authenticateUser(..)} into
     * this method, its only caller.
     *
     * @param c  Client's connection
     * @param msgUser  Client username (nickname) to validate and authenticate; will be {@link String#trim() trim()med}.
     *     Ignored if connection is already authenticated
     *     ({@link StringConnection#getData() c.getData()} != <tt>null</tt>).
     * @param msgPass  Password to supply to {@code SOCDBHelper.authenticateUserPassword(..), or "";
     *     will be {@link String#trim() trim()med}. If {@code msgUser} is in the optional DB, the trimmed
     *     {@code msgPass} must match their password there. If {@code msgPass != ""} but {@code msgUser} isn't found
     *     in the DB or there is no DB, rejects authentication.
     * @param cliVers  Client version, from {@link StringConnection#getVersion()}
     * @param doNameConnection  True if successful auth of an unnamed connection should have this method call
     *     {@link StringConnection#setData(String) c.setData(nickname)} and
     *     {@link #nameConnection(StringConnection, boolean) nameConnection(c, isTakingOver)}.
     *     <P>
     *     If using the optional user DB, {@code nickname} is queried from the database by case-insensitive search; see
     *     {@link SOCDBHelper#authenticateUserPassword(String, String, soc.server.database.SOCDBHelper.AuthPasswordRunnable)}.
     *     Otherwise {@code nickname} is {@code msgUser}.
     *     <P>
     *     For the usual connect sequence, callers will want <tt>true</tt>.  Some callers might want to check
     *     other things after this method and possibly reject the connection at that point; they will want
     *     <tt>false</tt>. Those callers must remember to call <tt>c.setData(nickname)</tt> and
     *     <tt>nameConnection(c, (0 != (result &amp; {@link #AUTH_OR_REJECT__TAKING_OVER})))</tt> themselves to finish
     *     authenticating a connection. They will also need to get the originally-cased nickname by
     *     calling {@link SOCDBHelper#getUser(String)}.
     * @param allowTakeover  True if the new connection can "take over" an older connection in response to the
     *     message it sent.  If true, the caller must be prepared to send all game info/channel info that the
     *     old connection had joined, so the new connection has full info to participate in them.
     * @param authCallback  Callback to make if authentication succeeds, or if {@code c} was already authenticated.
     *     Calls {@link AuthSuccessRunnable#success(StringConnection, int)} with the {@link #AUTH_OR_REJECT__OK}
     *     flag bit set, and possibly also {@link #AUTH_OR_REJECT__SET_USERNAME} and/or (only if
     *     {@code allowTakeover}) {@link #AUTH_OR_REJECT__TAKING_OVER}.
     *     <BR>
     *     <B>Threads:</B> This callback will always run on the {@link InboundMessageQueue}'s Treater thread.
     * @throws IllegalArgumentException if {@code authCallback} is null
     * @since 1.1.19
     */
    private void authOrRejectClientUser
        (final StringConnection c, String msgUser, String msgPass, final int cliVers,
         final boolean doNameConnection, final boolean allowTakeover,
         final AuthSuccessRunnable authCallback)
        throws IllegalArgumentException
    {
        if (authCallback == null)
            throw new IllegalArgumentException("authCallback");

        if (c.getData() != null)
        {
            authCallback.success(c, AUTH_OR_REJECT__OK);

            return;  // <---- Early return: Already authenticated ----
        }

        boolean isTakingOver = false;  // will set true if a human player is replacing another player in the game

        msgUser = msgUser.trim();
        msgPass = msgPass.trim();

        /**
         * If connection doesn't already have a nickname, check that the nickname is ok
         */
        if (msgUser.length() > PLAYER_NAME_MAX_LENGTH)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWGAME_NAME_TOO_LONG, cliVers,
                     SOCStatusMessage.MSG_SV_NEWGAME_NAME_TOO_LONG + Integer.toString(PLAYER_NAME_MAX_LENGTH)));
            return;
        }

        /**
         * check if a nickname is okay, and, if they're already logged in,
         * whether a new replacement connection can "take over" the existing one.
         */
        final int nameTimeout = checkNickname(msgUser, c, (msgPass != null) && (msgPass.length() > 0), false);
        System.err.println
            ("L4910 past checkNickname at " + System.currentTimeMillis()
             + (((nameTimeout == 0) || (nameTimeout == -1))
                ? (" for " + msgUser)
                : ""));

        if (nameTimeout == -1)
        {
            if (allowTakeover)
            {
                isTakingOver = true;
            } else {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_NAME_IN_USE, cliVers,
                         MSG_NICKNAME_ALREADY_IN_USE));
                return;
            }
        } else if (nameTimeout == -2)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NAME_NOT_ALLOWED, cliVers,
                     "This nickname is not allowed."));
            return;
        } else if (nameTimeout <= -1000)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NAME_IN_USE, cliVers,
                     checkNickname_getVersionText(-nameTimeout)));
            return;
        } else if (nameTimeout > 0)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NAME_IN_USE, cliVers,
                     (allowTakeover) ? checkNickname_getRetryText(nameTimeout) : MSG_NICKNAME_ALREADY_IN_USE));
            return;
        }

        /**
         * account and password required?
         */
        if (init_getBoolProperty(props, PROP_JSETTLERS_ACCOUNTS_REQUIRED, false))
        {
            if (msgPass.length() == 0)
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_PW_REQUIRED, cliVers,
                         "This server requires user accounts and passwords."));
                return;
            }

            // Assert: msgPass isn't "".
            // authenticateUserPassword queries db and requires an account there when msgPass is not "".
        }

        if (msgPass.length() > SOCAuthRequest.PASSWORD_LEN_MAX)
        {
            final String txt = "Incorrect password for '" + msgUser + "'.";
            c.put(SOCStatusMessage.toCmd(SOCStatusMessage.SV_PW_WRONG, c.getVersion(), txt));
            return;
        }

        /**
         * password check new connection from optional database, if not done already and if possible
         */
        try
        {
            final String msgUserName = msgUser;
            final boolean takingOver = isTakingOver;
            SOCDBHelper.authenticateUserPassword
                (msgUser, msgPass, new SOCDBHelper.AuthPasswordRunnable()
                {
                    public void authResult(final String dbUserName, final boolean hadDelay)
                    {
                        // If no DB: If msgPass is "" returns msgUser, else returns null

                        if (isCurrentThreadTreater())
                            authOrRejectClientUser_postDBAuth
                                (c, msgUserName, dbUserName, cliVers,
                                 doNameConnection, takingOver, authCallback, hadDelay);
                        else
                            postToTreater(new Runnable()
                            {
                                public void run()
                                {
                                    authOrRejectClientUser_postDBAuth
                                        (c, msgUserName, dbUserName, cliVers,
                                         doNameConnection, takingOver, authCallback, hadDelay);
                                }
                            });
                    }
                });
        }
        catch (SQLException sqle)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_PROBLEM_WITH_DB, c.getVersion(),
                     "Problem connecting to database, please try again later."));

            return;  // <---- Early return: DB problem ----
        }
    }

    /**
     * After client user/password auth succeeds or fails, take care of the rest of
     * {@link #authOrRejectClientUser(StringConnection, String, String, int, boolean, boolean, AuthSuccessRunnable)}.
     * See that method's javadoc for most parameters.
     *<P>
     * That method also ensures this method and {@code authCallback} run in the Treater thread; see
     * {@link Server#isCurrentThreadTreater() isCurrentThreadTreater()}.
     *
     * @param hadDelay  If true, this callback has been delayed by {@code BCrypt} calculations;
     *     otherwise it's an immediate callback (user not found, password didn't use BCrypt hashing)
     * @since 1.2.00
     */
    private void authOrRejectClientUser_postDBAuth
        (final StringConnection c, final String msgUser, final String authUsername,
         final int cliVers, final boolean doNameConnection, final boolean isTakingOver,
         final AuthSuccessRunnable authCallback, final boolean hadDelay)
    {
        if (authUsername == null)
        {
            // Password too long, or user found in database but password incorrect

            int replySV = SOCStatusMessage.SV_PW_WRONG;
            final String txt = "Incorrect password for '" + msgUser + "'.";
            final String msg = SOCStatusMessage.toCmd(replySV, c.getVersion(), txt);
            if (hadDelay)
                c.put(msg);
            else
                // TODO consider timing actual delay of BCrypt calcs & use that
                replyAuthTimer.schedule
                    (new TimerTask()
                     {
                        public void run() { c.put(msg); }
                     }, 350 + rand.nextInt(250));  // roughly same range as DBH.testBCryptSpeed

            return;  // <---- Early return: Password auth failed ----
        }

        final boolean mustSetUsername = ! authUsername.equals(msgUser);
        if (mustSetUsername && (cliVers < 1200))
        {
            // Case differs: must reject if client too old for SOCStatusMessage.SV_OK_SET_NICKNAME
            final String msg = SOCStatusMessage.toCmd
                (SOCStatusMessage.SV_NAME_NOT_FOUND, cliVers,
                 "Nickname is case-sensitive: Use " + authUsername);
            if (hadDelay)
                c.put(msg);
            else
                replyAuthTimer.schedule
                    (new TimerTask()
                     {
                        public void run() { c.put(msg); }
                     }, 350 + rand.nextInt(250));

            return;  // <---- Early return: Client can't change nickname case ----
        }

        /**
         * Now that everything's validated, name this connection/user/player.
         * If isTakingOver, also copies their current game/channel count.
         */
        if (doNameConnection)
        {
            c.setData(authUsername);
            nameConnection(c, isTakingOver);
        }

        int ret = AUTH_OR_REJECT__OK;
        if (isTakingOver)
            ret |= AUTH_OR_REJECT__TAKING_OVER;
        if (mustSetUsername)
            ret |= AUTH_OR_REJECT__SET_USERNAME;

        authCallback.success(c, ret);
    }

    /**
     * Is this username on the {@link #databaseUserAdmins} list?
     * @param uname  Username to check; if null, returns false.
     *     If supported by DB schema version, this check is case-insensitive.
     * @return  True only if list != {@code null} and the user is on the list
     * @since 1.1.20
     */
    private boolean isUserDBUserAdmin(String uname)
    {
        if ((uname == null) || (databaseUserAdmins == null))
            return false;

        // Check if uname's on the user admins list; this check is also in handleCREATEACCOUNT.

        if (SOCDBHelper.getSchemaVersion() >= SOCDBHelper.SCHEMA_VERSION_1200)
            uname = uname.toLowerCase(Locale.US);

        return databaseUserAdmins.contains(uname);
    }

    /**
     * Handle the client's echo of a {@link SOCMessage#SERVERPING}.
     * Resets its {@link SOCClientData#disconnectLastPingMillis} to 0
     * to indicate client is actively responsive to server.
     * @since 1.1.08
     */
    private void handleSERVERPING(StringConnection c, SOCServerPing mes)
    {
        SOCClientData cd = (SOCClientData) c.getAppData();
        if (cd == null)
            return;
        cd.disconnectLastPingMillis = 0;

        // TODO any other reaction or flags?
    }

    /**
     * Handle the "version" message, client's version report.
     * May ask to disconnect, if version is too old.
     * Otherwise send the game list.
     * If we've already sent the game list, send changes based on true version.
     * If they send another VERSION later, with a different version, disconnect the client.
     *<P>
     * Along with the game list, the client will need to know the game option info.
     * This is sent when the client asks (after VERSION) for {@link SOCGameOptionGetInfos GAMEOPTIONGETINFOS}.
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleVERSION(StringConnection c, SOCVersion mes)
    {
        if (c == null)
            return;

        setClientVersSendGamesOrReject(c, mes.getVersionNumber(), true);
    }

    /**
     * Set client's version, and check against minimum required version {@link #CLI_VERSION_MIN}.
     * If version is too low, send {@link SOCRejectConnection REJECTCONNECTION}.
     * If we haven't yet sent the game list, send now.
     * If we've already sent the game list, send changes based on true version.
     *<P>
     * Along with the game list, the client will need to know the game option info.
     * This is sent when the client asks (after VERSION) for {@link SOCGameOptionGetInfos GAMEOPTIONGETINFOS}.
     * Game options are sent after client version is known, so the list of
     * sent options is based on client version.
     *<P>
     *<b>Locks:</b> To set the version, will synchronize briefly on {@link Server#unnamedConns unnamedConns}.
     * If {@link StringConnection#getVersion() c.getVersion()} is already == cvers,
     * don't bother to lock and set it.
     *<P>
     * Package access (not private) is strictly for use of {@link SOCClientData.SOCCDCliVersionTask#run()}.
     *
     * @param c     Client's connection
     * @param cvers Version reported by client, or assumed version if no report
     * @param isKnown Is this the client's definite version, or just an assumed one?
     *                Affects {@link StringConnection#isVersionKnown() c.isVersionKnown}.
     *                Can set the client's known version only once; a second "known" call with
     *                a different cvers will be rejected.
     * @return True if OK, false if rejected
     */
    boolean setClientVersSendGamesOrReject(StringConnection c, final int cvers, final boolean isKnown)
    {
        final int prevVers = c.getVersion();
        final boolean wasKnown = c.isVersionKnown();

        if (prevVers == -1)
            ((SOCClientData) c.getAppData()).clearVersionTimer();

        if (prevVers != cvers)
        {
            synchronized (unnamedConns)
            {
                c.setVersion(cvers, isKnown);
            }
        } else if (wasKnown)
        {
            return true;  // <--- Early return: Already knew it ----
        }

        String rejectMsg = null;
        String rejectLogMsg = null;

        if (cvers < CLI_VERSION_MIN)
        {
            if (cvers > 0)
                rejectMsg = "Sorry, your client version number " + cvers + " is too old, version ";
            else
                rejectMsg = "Sorry, your client version is too old, version number ";
            rejectMsg += Integer.toString(CLI_VERSION_MIN)
                + " (" + CLI_VERSION_MIN_DISPLAY + ") or above is required.";
            rejectLogMsg = "Rejected client: Version " + cvers + " too old";
        }
        if (wasKnown && isKnown && (cvers != prevVers))
        {
            // can't change the version once known
            rejectMsg = "Sorry, cannot report two different versions.";
            rejectLogMsg = "Rejected client: Already gave VERSION(" + prevVers
                + "), now says VERSION(" + cvers + ")";
        }

        if (rejectMsg != null)
        {
            c.put(new SOCRejectConnection(rejectMsg).toCmd());
            c.disconnectSoft();
            System.out.println(rejectLogMsg);
            return false;
        }

        // Send game list?
        // Will check c.getAppData().hasSentGameList() flag.
        // prevVers is ignored unless already sent game list.
        sendGameList(c, prevVers);

        // Warn if debug commands are allowed.
        // This will be displayed in the client's status line (v1.1.17 and newer).
        if (allowDebugUser)
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK, "Debugging is On.  Welcome to Java Settlers of Catan!"));

        // Increment version stats; currently assumes single-threaded access to the map.
        // We don't know yet if client is a bot, so bots are included in the stats.
        // (If this is not wanted, the bot could be subtracted at handleIMAROBOT.)
        final Integer cversObj = new Integer(cvers);
        final int prevCount;
        Integer prevCObj = (Integer) clientPastVersionStats.get(cversObj);
        prevCount = (prevCObj != null) ? prevCObj.intValue() : 0;
        clientPastVersionStats.put(cversObj, new Integer(1 + prevCount));

        // This client version is OK to connect
        return true;
    }

    /**
     * Handle the "join a channel" message.
     * If client hasn't yet sent its version, assume is
     * version 1.0.00 ({@link #CLI_VERSION_ASSUMED_GUESS}), disconnect if too low.
     *<P>
     * Requested channel name must pass {@link SOCMessage#isSingleLineAndSafe(String)}.
     * Channel name <tt>"*"</tt> is also rejected to avoid conflicts with admin commands.
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleJOIN(StringConnection c, SOCJoin mes)
    {
        if (c != null)
        {
            D.ebugPrintln("handleJOIN: " + mes);

            int cliVers = c.getVersion();
            final String chName = mes.getChannel().trim();
            final String msgUser = mes.getNickname().trim();  // trim before db query calls
            final String msgPass = mes.getPassword();

            /**
             * Check the reported version; if none, assume 1000 (1.0.00)
             */
            if (cliVers == -1)
            {
                if (! setClientVersSendGamesOrReject(c, CLI_VERSION_ASSUMED_GUESS, false))
                    return;  // <--- Discon and Early return: Client too old ---
                cliVers = c.getVersion();
            }

            if (c.getData() != null)
            {
                handleJOIN_postAuth(c, msgUser, chName, cliVers, AUTH_OR_REJECT__OK);
            } else {
                /**
                 * Check that the nickname is ok, check password if supplied; if not ok, sends a SOCStatusMessage.
                 */
                final int cv = cliVers;
                authOrRejectClientUser
                    (c, msgUser, msgPass, cv, true, false,
                     new AuthSuccessRunnable()
                     {
                         public void success(final StringConnection c, final int authResult)
                         {
                             handleJOIN_postAuth(c, msgUser, chName, cv, authResult);
                         }
                     });
            }
        }
    }

    /**
     * After successful client user auth, take care of the rest of
     * {@link #handleJOIN(StringConnection, SOCJoin)}.
     * @since 1.2.00
     */
    private void handleJOIN_postAuth
        (final StringConnection c, String msgUser, final String ch, final int cliVers, final int authResult)
    {
        final boolean mustSetUsername = (0 != (authResult & SOCServer.AUTH_OR_REJECT__SET_USERNAME));
        if (mustSetUsername)
            msgUser = c.getData();  // set to original case, from db case-insensitive search

        /**
         * Check that the channel name is ok
         */

        /*
           if (!checkChannelName(mes.getChannel())) {
           return;
           }
         */
        if ( (! SOCMessage.isSingleLineAndSafe(ch))
             || "*".equals(ch))
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWGAME_NAME_REJECTED, cliVers,
                     SOCStatusMessage.MSG_SV_NEWGAME_NAME_REJECTED));
              // "This game name is not permitted, please choose a different name."

              return;  // <---- Early return ----
        }

        /**
         * If creating a new channel, ensure they are below their max channel count.
         */
        if ((! channelList.isChannel(ch))
            && (CLIENT_MAX_CREATE_CHANNELS >= 0)
            && (CLIENT_MAX_CREATE_CHANNELS <= ((SOCClientData) c.getAppData()).getcurrentCreatedChannels()))
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWCHANNEL_TOO_MANY_CREATED, cliVers,
                     SOCStatusMessage.MSG_SV_NEWCHANNEL_TOO_MANY_CREATED + Integer.toString(CLIENT_MAX_CREATE_CHANNELS)));
            // Too many of your chat channels still active; maximum: 2

            return;  // <---- Early return ----
        }

        /**
         * Tell the client that everything is good to go
         */
        c.put(SOCJoinAuth.toCmd(msgUser, ch));
        final String txt = "Welcome to Java Settlers of Catan!";
        if (! mustSetUsername)
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK, txt));
        else
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK_SET_NICKNAME, c.getData() + SOCMessage.sep2_char + txt));

        /**
         * Add the StringConnection to the channel
         */

        if (channelList.takeMonitorForChannel(ch))
        {
            try
            {
                connectToChannel(c, ch);
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in handleJOIN (connectToChannel)");
            }

            channelList.releaseMonitorForChannel(ch);
        }
        else
        {
            /**
             * the channel did not exist, create it
             */
            channelList.takeMonitor();

            try
            {
                channelList.createChannel(ch, c.getData());
                ((SOCClientData) c.getAppData()).createdChannel();
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in handleJOIN (createChannel)");
            }

            channelList.releaseMonitor();
            broadcast(SOCNewChannel.toCmd(ch));
            c.put(SOCMembers.toCmd(ch, channelList.getMembers(ch)));
            if (D.ebugOn)
                D.ebugPrintln("*** " + c.getData() + " joined the channel " + ch + " at "
                    + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            channelList.takeMonitorForChannel(ch);

            try
            {
                channelList.addMember(c, ch);
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in handleJOIN (addMember)");
            }

            channelList.releaseMonitorForChannel(ch);
        }

        /**
         * let everyone know about the change
         */
        messageToChannel(ch, new SOCJoin(msgUser, "", "dummyhost", ch));
    }

    /**
     * Handle the "leave a channel" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleLEAVE(StringConnection c, SOCLeave mes)
    {
        D.ebugPrintln("handleLEAVE: " + mes);

        if (c != null)
        {
            boolean destroyedChannel = false;
            channelList.takeMonitorForChannel(mes.getChannel());

            try
            {
                destroyedChannel = leaveChannel(c, mes.getChannel(), true, false);
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in handleLEAVE");
            }

            channelList.releaseMonitorForChannel(mes.getChannel());

            if (destroyedChannel)
            {
                broadcast(SOCDeleteChannel.toCmd(mes.getChannel()));
            }
        }
    }

    /**
     * Handle the "I'm a robot" message.
     * Robots send their {@link SOCVersion} before sending this message.
     * Their version is checked here, must equal server's version.
     * For stability and control, the cookie in this message must
     * match this server's {@link #robotCookie}.
     *<P>
     * Bot tuning parameters are sent here to the bot.
     * Its {@link SOCClientData#isRobot} flag is set.
     *<P>
     * Before connecting here, bot clients are named and started in {@link #setupLocalRobots(int, int)}.
     * Default bot params are {@link #ROBOT_PARAMS_SMARTER} if the robot name starts with "robot "
     * or {@link #ROBOT_PARAMS_DEFAULT} otherwise (starts with "droid ").
     *
     * @param c  the connection that sent the message; not null
     *     but {@link StringConnection#getData() c.getData()} should be null
     * @param mes  the message
     */
    private void handleIMAROBOT(StringConnection c, SOCImARobot mes)
    {
        if (c != null)
        {
            final String botName = mes.getNickname();

            /**
             * Check that client hasn't already auth'd, as a human or bot
             */
            if (c.getData() != null)
            {
                c.put(new SOCRejectConnection("Client has already authorized.").toCmd());
                c.disconnectSoft();
                System.out.println("Rejected robot " + botName + ": Client sent authorize already");

                return;  // <--- Early return: Already authenticated ---
            }

            /**
             * Check the cookie given by this bot.
             */
            if ((robotCookie != null) && ! robotCookie.equals(mes.getCookie()))
            {
                final String rejectMsg = "Cookie contents do not match the running server.";
                c.put(new SOCRejectConnection(rejectMsg).toCmd());
                c.disconnectSoft();
                System.out.println("Rejected robot " + botName + ": Wrong cookie");

                return;  // <--- Early return: Robot client didn't send our cookie value ---
            }

            /**
             * Check the reported version; if none, assume 1000 (1.0.00)
             */
            final int srvVers = Version.versionNumber();
            int cliVers = c.getVersion();
            final String rbc = mes.getRBClass();
            final boolean isBuiltIn = (rbc == null)
                || (rbc.equals(SOCImARobot.RBCLASS_BUILTIN));
            if (isBuiltIn)
            {
                if (cliVers != srvVers)
                {
                    String rejectMsg = "Sorry, robot client version does not match, version number "
                        + Version.version(srvVers) + " is required.";
                    c.put(new SOCRejectConnection(rejectMsg).toCmd());
                    c.disconnectSoft();
                    System.out.println("Rejected robot " + botName + ": Version "
                        + cliVers + " does not match server version");

                    return;  // <--- Early return: Robot client too old ---
                } else {
                    System.out.println("Robot arrived: " + botName + ": built-in type");
                }
            } else {
                System.out.println("Robot arrived: " + botName + ": type " + rbc);
            }

            /**
             * Check that the nickname is ok
             */
            if (0 != checkNickname(botName, c, false, true))
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_NAME_IN_USE, cliVers,
                         MSG_NICKNAME_ALREADY_IN_USE));
                SOCRejectConnection rcCommand = new SOCRejectConnection(MSG_NICKNAME_ALREADY_IN_USE);
                c.put(rcCommand.toCmd());
                printAuditMessage
                    (null, "Robot login attempt, name already in use or bad", botName, null, c.host());
                // c.disconnect();
                c.disconnectSoft();

                return;
            }

            // Idle robots disconnect and reconnect every so often (socket timeout).
            // In case of disconnect-reconnect, don't print the error or re-arrival debug announcements.
            // The robot's nickname is used as the key for the disconnect announcement.
            {
                ConnExcepDelayedPrintTask depart
                    = (ConnExcepDelayedPrintTask) cliConnDisconPrintsPending.get(botName);
                if (depart != null)
                {
                    depart.cancel();
                    cliConnDisconPrintsPending.remove(botName);
                    ConnExcepDelayedPrintTask arrive
                        = (ConnExcepDelayedPrintTask) cliConnDisconPrintsPending.get(c);
                    if (arrive != null)
                    {
                        arrive.cancel();
                        cliConnDisconPrintsPending.remove(c);
                    }
                }
            }

            SOCRobotParameters params = null;
            //
            // send the current robot parameters
            //
            try
            {
                params = SOCDBHelper.retrieveRobotParams(botName);
                if ((params != null) && D.ebugIsEnabled())
                    D.ebugPrintln("*** Robot Parameters for " + botName + " = " + params);
            }
            catch (SQLException sqle)
            {
                System.err.println("Error retrieving robot parameters from db: Using defaults.");
            }

            if (params == null)
                if (botName.startsWith("robot "))
                    params = ROBOT_PARAMS_SMARTER;  // uses SOCRobotDM.SMART_STRATEGY
                else  // startsWith("droid ")
                    params = ROBOT_PARAMS_DEFAULT;  // uses SOCRobotDM.FAST_STRATEGY

            c.put(SOCUpdateRobotParams.toCmd(params));

            //
            // add this connection to the robot list
            //
            c.setData(botName);
            c.setHideTimeoutMessage(true);
            robots.addElement(c);
            SOCClientData scd = (SOCClientData) c.getAppData();
            scd.isRobot = true;
            scd.isBuiltInRobot = isBuiltIn;
            if (! isBuiltIn)
                scd.robot3rdPartyBrainClass = rbc;
            super.nameConnection(c, false);
        }
    }

    /**
     * Handle text message to a channel, including {@code *KILLCHANNEL*} channel debug command.
     * Was part of {@code processCommand(..)} before 1.2.00.
     *
     * @param c  the connection
     * @param mes  the message
     * @since 1.2.00
     */
    private void handleTEXTMSG(final StringConnection c, final SOCTextMsg mes)
    {
        final String chName = mes.getChannel();

        if (allowDebugUser && c.getData().equals("debug"))
        {
            if (mes.getText().startsWith("*KILLCHANNEL*"))
            {
                messageToChannel(chName, new SOCTextMsg
                    (chName, SERVERNAME,
                     "********** " + c.getData() + " KILLED THE CHANNEL **********"));

                channelList.takeMonitor();
                try
                {
                    destroyChannel(chName);
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception in KILLCHANNEL");
                }
                finally
                {
                    channelList.releaseMonitor();
                }

                broadcast(SOCDeleteChannel.toCmd(chName));

                return;
            }
        }

        /**
         * Send the message to the members of the channel
         */
        if (channelList.isMember(c, chName))
            messageToChannel(chName, mes);
    }

    /**
     * Handle game text messages, including debug commands.
     * Was part of processCommand before 1.1.07.
     * @since 1.1.07
     */
    private void handleGAMETEXTMSG(StringConnection c, SOCGameTextMsg gameTextMsgMes)
    {
        //createNewGameEventRecord();
        //currentGameEventRecord.setMessageIn(new SOCMessageRecord(mes, c.getData(), "SERVER"));
        final String gaName = gameTextMsgMes.getGame();
        recordGameEvent(gaName, gameTextMsgMes.toCmd());

        SOCGame ga = gameList.getGameData(gaName);
        if (ga == null)
            return;  // <---- early return: no game by that name ----

        final String plName = c.getData();
        if (null == ga.getPlayer(plName))
        {
            // c isn't a seated player in that game; have they joined it?
            // To avoid disruptions by game observers, only players can chat after initial placement.
            // To help form the game, non-seated members can also participate in the chat until then.

            final boolean canChat = (ga.getGameState() < SOCGame.PLAY) && gameList.isMember(c, gaName);
            if (! canChat)
            {
                messageToPlayer(c, gaName, "Observers can't chat during the game.");

                return;  // <---- early return: not a player in that game ----
            }
        }

        //currentGameEventRecord.setSnapshot(ga);

        ///
        /// command to add time to a game
        /// If the command text changes from '*ADDTIME*' to something else,
        /// please update the warning text sent in checkForExpiredGames().
        ///
        final String cmdText = gameTextMsgMes.getText();
        final String cmdTxtUC = cmdText.toUpperCase();
        if (cmdTxtUC.startsWith("*ADDTIME*") || cmdTxtUC.startsWith("ADDTIME"))
        {
            // Unless this is a practice game, if reasonable
            // add 30 minutes to the expiration time.  If this
            // changes to another timespan, please update the
            // warning text sent in checkForExpiredGames().
            // Use ">>>" in message text to mark as urgent.

            if (ga.isPractice)
            {
                messageToPlayer(c, gaName, ">>> Practice games never expire.");
            } else if (ga.getGameState() >= SOCGame.OVER) {
                messageToPlayer(c, gaName, "This game is over, cannot extend its time.");
            } else {
                // check game time currently remaining: if already more than
                // the original GAME_TIME_EXPIRE_MINUTES, don't add more now.
                final long now = System.currentTimeMillis();
                long exp = ga.getExpiration();
                int minRemain = (int) ((exp - now) / (60 * 1000));

                if (minRemain > SOCGameListAtServer.GAME_EXPIRE_MINUTES)
                {
                    messageToPlayer(c, gaName,
                        "Ask again later: This game does not expire soon, it has " + minRemain + " minutes remaining.");
                } else {
                    exp += (GAME_TIME_EXPIRE_ADDTIME_MINUTES * 60 * 1000);
                    minRemain += GAME_TIME_EXPIRE_ADDTIME_MINUTES;

                    ga.setExpiration(exp);
                    messageToGameUrgent(gaName, ">>> Game time has been extended.");
                    messageToGameUrgent(gaName, ">>> This game will expire in " + minRemain + " minutes.");
                }
            }
        }

        ///
        /// Check the time remaining for this game
        ///
        else if (cmdTxtUC.startsWith("*CHECKTIME*"))
        {
            processDebugCommand_gameStats(c, gaName, ga, true);
        }
        else if (cmdTxtUC.startsWith("*VERSION*"))
        {
            messageToPlayer(c, gaName,
                "Java Settlers Server " +Version.versionNumber() + " (" + Version.version() + ") build " + Version.buildnum());
        }
        else if (cmdTxtUC.startsWith("*STATS*"))
        {
            final long diff = System.currentTimeMillis() - startTime;
            final long hours = diff / (60 * 60 * 1000),
                  minutes = (diff - (hours * 60 * 60 * 1000)) / (60 * 1000),
                  seconds = (diff - (hours * 60 * 60 * 1000) - (minutes * 60 * 1000)) / 1000;
            Runtime rt = Runtime.getRuntime();
            if (hours < 24)
            {
                messageToPlayer(c, gaName, "> Uptime: " + hours + ":" + minutes + ":" + seconds);
            } else {
                final int days = (int) (hours / 24),
                          hr   = (int) (hours - (days * 24L));
                messageToPlayer(c, gaName, "> Uptime: " + days + "d " + hr + ":" + minutes + ":" + seconds);
            }
            messageToPlayer(c, gaName, "> Connections since startup: " + numberOfConnections);
            messageToPlayer(c, gaName, "> Current named connections: " + getNamedConnectionCount());
            messageToPlayer(c, gaName, "> Current connections including unnamed: " + getCurrentConnectionCount());
            messageToPlayer(c, gaName, "> Total Users: " + numberOfUsers);
            messageToPlayer(c, gaName, "> Games started: " + numberOfGamesStarted);
            messageToPlayer(c, gaName, "> Games finished: " + numberOfGamesFinished);
            messageToPlayer(c, gaName, "> Total Memory: " + rt.totalMemory());
            messageToPlayer(c, gaName, "> Free Memory: " + rt.freeMemory());
            final int vers = Version.versionNumber();
            messageToPlayer(c, gaName, "> Version: "
                + vers + " (" + Version.version() + ") build " + Version.buildnum());

            if (! clientPastVersionStats.isEmpty())
            {
                if (clientPastVersionStats.size() == 1)
                {
                    messageToPlayer(c, gaName, "> Client versions since startup: all "
                            + Version.version( ((Integer)(clientPastVersionStats.keySet().iterator().next())).intValue() ));
                } else {
                    // TODO sort it
                    messageToPlayer(c, gaName, "> Client versions since startup: (includes bots)");
                    Iterator it = clientPastVersionStats.keySet().iterator();
                    while (it.hasNext())
                    {
                        final Integer vobj = (Integer) it.next();
                        final int v = vobj.intValue();
                        messageToPlayer(c, gaName, ">   " + Version.version(v) + ": " + clientPastVersionStats.get(vobj));
                    }
                }
            }

            // show range of current game's member client versions if not server version (added to *STATS* in 1.1.19)
            if ((ga.clientVersionLowest != vers) || (ga.clientVersionLowest != ga.clientVersionHighest))
                messageToPlayer(c, gaName, "> This game's client versions: "
                    + Version.version(ga.clientVersionLowest) + " - " + Version.version(ga.clientVersionHighest));

            processDebugCommand_gameStats(c, gaName, ga, false);
        }
        else if (cmdTxtUC.startsWith("*WHO*"))
        {
            processDebugCommand_who(c, ga, cmdText);
        }
        else if (cmdTxtUC.startsWith("*DBSETTINGS*"))
        {
            processDebugCommand_dbSettings(c, ga);
        }

        //
        // check for admin/debugging commands
        //
        // 1.1.07: all practice games are debug mode, for ease of debugging;
        //         not much use for a chat window in a practice game anyway.
        //
        else
        {
            final boolean userIsDebug =
                (allowDebugUser && plName.equals("debug"))
                || (c instanceof LocalStringConnection);

            if (cmdTxtUC.startsWith("*HELP"))
            {
                for (int i = 0; i < GENERAL_COMMANDS_HELP.length; ++i)
                    messageToPlayer(c, gaName, GENERAL_COMMANDS_HELP[i]);

                if ((userIsDebug && ! (c instanceof LocalStringConnection))  // no user admins in practice games
                    || isUserDBUserAdmin(plName))
                {
                    messageToPlayer(c, gaName, ADMIN_COMMANDS_HEADING);
                    for (int i = 0; i < ADMIN_USER_COMMANDS_HELP.length; ++i)
                        messageToPlayer(c, gaName, ADMIN_USER_COMMANDS_HELP[i]);
                }

                if (userIsDebug)
                    for (int i = 0; i < DEBUG_COMMANDS_HELP.length; ++i)
                        messageToPlayer(c, gaName, DEBUG_COMMANDS_HELP[i]);

                return;
            }

            if (userIsDebug)
            {
                if (cmdTxtUC.startsWith("RSRCS:"))
                {
                    giveResources(c, cmdText, ga);
                }
                else if (cmdTxtUC.startsWith("DEV:"))
                {
                    giveDevCard(c, cmdText, ga);
                }
                else if (! ((cmdText.charAt(0) == '*')
                            && processDebugCommand(c, ga.getName(), cmdText)))
                {
                    //
                    // Send the message to the members of the game
                    //
                    messageToGame(gaName, new SOCGameTextMsg(gaName, plName, cmdText));
                }
            }
            else
            {
                //
                // Send the message to the members of the game
                //
                messageToGame(gaName, new SOCGameTextMsg(gaName, plName, cmdText));
            }
        }

        //saveCurrentGameEventRecord(gameTextMsgMes.getGame());
    }

    /**
     * Process the {@code *DBSETTINGS*} privileged admin command:
     * Check {@link #isUserDBUserAdmin(String)} and if OK and {@link SOCDBHelper#isInitialized()},
     * send the client a formatted list of server DB settings from {@link SOCDBHelper#getSettingsFormatted()}.
     * @param c  Client sending the admin command
     * @param gaName  Game in which to reply
     * @since 1.2.00
     */
    private void processDebugCommand_dbSettings(final StringConnection c, final SOCGame ga)
    {
        final String msgUser = c.getData();
        if (! (isUserDBUserAdmin(msgUser)
               || (allowDebugUser && msgUser.equals("debug"))))
        {
            return;
        }

        final String gaName = ga.getName();

        if (! SOCDBHelper.isInitialized())
        {
            messageToPlayer(c, gaName, "Not using a database.");
            return;
        }

        messageToPlayer(c, gaName, "Database settings:");
        Iterator<String> it = SOCDBHelper.getSettingsFormatted().iterator();
        while (it.hasNext())
            messageToPlayer(c, gaName, "> " + it.next() + ": " + it.next());
    }

    /**
     * Print time-remaining and other game stats.
     * Includes more detail beyond the end-game stats sent in {@link #sendGameStateOVER(SOCGame)}.
     *<P>
     * Before v1.1.20, this method was <tt>processDebugCommand_checktime(..)</tt>.
     *
     * @param c  Client requesting the stats
     * @param gaName  <tt>gameData.getName()</tt>
     * @param gameData  Game to print stats
     * @param isCheckTime  True if called from *CHECKTIME* server command, false for *STATS*.
     *     If true, mark text as urgent when sending remaining time before game expires.
     * @since 1.1.07
     */
    private void processDebugCommand_gameStats
        (StringConnection c, final String gaName, SOCGame gameData, final boolean isCheckTime)
    {
        if (gameData == null)
            return;

        messageToPlayer(c, gaName, "-- Game statistics: --");
        messageToPlayer(c, gaName, "Rounds played: " + gameData.getRoundCount());

        // player's stats
        if (c.getVersion() >= SOCPlayerStats.VERSION_FOR_RES_ROLL)
        {
            SOCPlayer cp = gameData.getPlayer(c.getData());
            if (cp != null)
                messageToPlayer(c, new SOCPlayerStats(cp, SOCPlayerStats.STYPE_RES_ROLL));
        }

        // time
        Date gstart = gameData.getStartTime();
        if (gstart != null)
        {                
            long gameSeconds = ((new Date().getTime() - gstart.getTime())+500L) / 1000L;
            long gameMinutes = (gameSeconds+29L)/60L;
            String gLengthMsg = "This game started " + gameMinutes + " minutes ago.";
            messageToPlayer(c, gaName, gLengthMsg);
            // Ignore possible "1 minutes"; that game is too short to worry about.
        }

        if (! gameData.isPractice)   // practice games don't expire
        {
            // If isCheckTime, use ">>>" in message text to mark as urgent:
            // ">>> This game will expire in 15 minutes."

            final String urg = (isCheckTime) ? ">>> " : "";
            String expireMsg = urg + "This game will expire in " + ((gameData.getExpiration() - System.currentTimeMillis()) / 60000) + " minutes.";
            messageToPlayer(c, gaName, expireMsg);
        }
    }

    /**
     * Process the <tt>*FREEPLACE*</tt> Free Placement debug command.
     * Can turn it off at any time, but can only turn it on during
     * your own turn after rolling (during game state {@link SOCGame#PLAY1}).
     * @param c   Connection (client) sending this message
     * @param gaName  Game to which this applies
     * @param arg  1 or 0, to turn on or off, or empty string or
     *    null to print current value
     * @since 1.1.12
     */
    private final void processDebugCommand_freePlace
        (StringConnection c, final String gaName, final String arg)
    {
        SOCGame ga = gameList.getGameData(gaName);
        if (ga == null)
            return;
        final boolean wasInitial = ga.isInitialPlacement();
        final boolean ppValue = ga.isDebugFreePlacement();
        final boolean ppWanted;
        if ((arg == null) || (arg.length() == 0))
            ppWanted = ppValue;
        else
            ppWanted = arg.equals("1");

        if (ppValue != ppWanted)
        {
            if (! ppWanted)
            {
                try
                {
                    ga.setDebugFreePlacement(false);
                }
                catch (IllegalStateException e)
                {
                    if (wasInitial)
                    {
                        messageToPlayer
                          (c, gaName, "* To exit this debug mode, all players must have either");
                        messageToPlayer
                          (c, gaName, "  1 settlement and 1 road, or all must have at least 2 of each.");
                    } else {
                        messageToPlayer
                          (c, gaName, "* Could not exit this debug mode: " + e.getMessage());
                    }
                    return;  // <--- early return ---
                }
            } else {
                if (c.getVersion() < SOCDebugFreePlace.VERSION_FOR_DEBUGFREEPLACE)
                {
                    messageToPlayer
                        (c, gaName, "* Requires client version "
                         + Version.version(SOCDebugFreePlace.VERSION_FOR_DEBUGFREEPLACE)
                         + " or newer.");
                    return;  // <--- early return ---
                }
                SOCPlayer cliPl = ga.getPlayer(c.getData());
                if (cliPl == null)
                    return;  // <--- early return ---
                if (ga.getCurrentPlayerNumber() != cliPl.getPlayerNumber())
                {
                    messageToPlayer
                        (c, gaName, "* Can do this only on your own turn.");
                    return;  // <--- early return ---
                }
                if ((ga.getGameState() != SOCGame.PLAY1)
                    && ! ga.isInitialPlacement())
                {
                    messageToPlayer
                        (c, gaName, "* Can do this only after rolling the dice.");
                    return;  // <--- early return ---
                }

                ga.setDebugFreePlacement(true);
            }
        }

        messageToPlayer
            (c, gaName, "- Free Placement mode is "
             + (ppWanted ? "ON -" : "off -" ));

        if (ppValue != ppWanted)
        {
            messageToPlayer(c, new SOCDebugFreePlace(gaName, ga.getCurrentPlayerNumber(), ppWanted));
            if (wasInitial && ! ppWanted)
            {
                boolean toldRoll = sendGameState(ga, false);
                if (!checkTurn(c, ga))
                {
                    // Player changed (or play started), announce new player.
                    sendTurn(ga, toldRoll);
                }
            }
        }
    }

    /**
     * Process unprivileged command <tt>*WHO*</tt> to show members of current game,
     * or  privileged <tt>*WHO* gameName|all</tt> to show all connected clients or some other game's members.
     *<P>
     * <B>Locks:</B> Takes/releases {@link SOCGameList#takeMonitorForGame(String) gameList.takeMonitorForGame(gaName)}
     * to call {@link SOCGameListAtServer#getMembers(String)}.
     *
     * @param c  Client sending the *WHO* command
     * @param ga  Game in which the command was sent
     * @param cmdText   Text of *WHO* command
     * @since 1.1.20
     */
    private void processDebugCommand_who
        (final StringConnection c, final SOCGame ga, final String cmdText)
    {
        final String gaName = ga.getName();  // name of game where c is connected and sent *WHO* command
        String gaNameWho = gaName;  // name of game to find members; if sendToCli, not equal to gaName
        boolean sendToCli = false;  // if true, send member list only to c instead of whole game

        int i = cmdText.indexOf(' ');
        if (i != -1)
        {
            // look for a game name or */all
            String gname = cmdText.substring(i+1).trim();

            if (gname.length() > 0)
            {
                // Check if using user admins; if not, if using debug user
                // Then: look for game name or */all

                final String uname = c.getData();
                boolean isAdmin = isUserDBUserAdmin(uname);
                if (! isAdmin)
                    isAdmin = (allowDebugUser && uname.equals("debug"));
                if (! isAdmin)
                {
                    messageToPlayer(c, gaName, "Must be an administrator to view that.");
                    return;
                }

                sendToCli = true;

                if (gname.equals("*") || gname.toUpperCase(Locale.US).equals("ALL"))
                {
                    // Instead of listing the game's members, list all connected clients.
                    // Do as little as possible inside synchronization block.

                    final ArrayList sbs = new ArrayList();  // list of StringBuilders
                    StringBuilder sb = new StringBuilder("Currently connected to server:");
                    sbs.add(sb);
                    sb = new StringBuilder("- ");
                    sbs.add(sb);

                    int nUnnamed;
                    synchronized (unnamedConns)
                    {
                        nUnnamed = unnamedConns.size();

                        Enumeration ec = getConnections();  // the named StringConnections
                        while (ec.hasMoreElements())
                        {
                            String cname = ((StringConnection) ec.nextElement()).getData();

                            int L = sb.length();
                            if (L + cname.length() > 50)
                            {
                                sb.append(',');
                                sb = new StringBuilder("- ");
                                sbs.add(sb);
                                L = 2;
                            }

                            if (L > 2)
                                sb.append(", ");
                            sb.append(cname);
                        }
                    }

                    if (nUnnamed != 0)
                    {
                        final String unnamed = (nUnnamed != 1)
                            ? (" and " + nUnnamed + " unnamed connections")
                            : "and 1 unnamed connection";
                        if (sb.length() + unnamed.length() + 2 > 50)
                        {
                            sb.append(',');
                            sb = new StringBuilder("- ");
                            sb.append(unnamed);
                            sbs.add(sb);
                        } else {
                            sb.append(", ");
                            sb.append(unnamed);
                        }
                    }

                    for (Iterator sbi = sbs.iterator(); sbi.hasNext(); )
                        messageToPlayer(c, gaName, sbi.next().toString());

                    return;  // <--- Early return; Not listing a game's members ---
                }

                if (gameList.isGame(gname))
                {
                    gaNameWho = gname;
                } else {
                    messageToPlayer(c, gaName, "Game not found.");
                    return;
                }
            }
        }

        Vector gameMembers = null;

        gameList.takeMonitorForGame(gaNameWho);

        try
        {
            gameMembers = gameList.getMembers(gaNameWho);
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in *WHO* (gameMembers)");
        }

        gameList.releaseMonitorForGame(gaNameWho);

        if (gameMembers == null)
        {
            return;  // unlikely since empty games are destroyed
        }

        if (sendToCli)
            messageToPlayer(c, gaName, "Members of game " + gaNameWho + ":");
        else
            messageToGame(gaName, "This game's members:");

        Enumeration membersEnum = gameMembers.elements();
        while (membersEnum.hasMoreElements())
        {
            StringConnection conn = (StringConnection) membersEnum.nextElement();
            String mNameStr = "> " + conn.getData();

            if (sendToCli)
                messageToPlayer(c, gaName, mNameStr);
            else
                messageToGame(gaName, mNameStr);
        }
    }

    /**
     * Handle the optional {@link SOCAuthRequest "authentication request"} message.
     *<P>
     * If {@link StringConnection#getData() c.getData()} != <tt>null</tt>, the client already authenticated and
     * this method replies with {@link SOCStatusMessage#SV_OK} without checking the password in this message.
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     * @see #isUserDBUserAdmin(String)
     * @since 1.1.19
     */
    private void handleAUTHREQUEST(StringConnection c, final SOCAuthRequest mes)
    {
        if (c == null)
            return;

        final String mesUser = mes.nickname.trim();  // trim before db query calls
        final String mesRole = mes.role;
        final boolean isPlayerRole = mesRole.equals(SOCAuthRequest.ROLE_GAME_PLAYER);
        final int cliVersion = c.getVersion();

        if (c.getData() != null)
        {
            handleAUTHREQUEST_postAuth(c, mesUser, mesRole, isPlayerRole, cliVersion, SOCServer.AUTH_OR_REJECT__OK);
        } else {
            if (cliVersion <= 0)
            {
                // unlikely: AUTHREQUEST was added in 1.1.19, version message timing was stable years earlier
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_NOT_OK_GENERIC, "AUTHREQUEST: Send version first"));
                return;
            }

            if (mes.authScheme != SOCAuthRequest.SCHEME_CLIENT_PLAINTEXT)
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_NOT_OK_GENERIC, "AUTHREQUEST: Auth scheme unknown: " + mes.authScheme));
                return;
            }

            // Check user authentication.  Don't call setData or nameConnection yet if there
            // are role-specific things to check and reject during this initial connection.
            authOrRejectClientUser
                (c, mesUser, mes.password, cliVersion, isPlayerRole, false,
                 new SOCServer.AuthSuccessRunnable()
                 {
                    public void success(final StringConnection c, final int authResult)
                    {
                        handleAUTHREQUEST_postAuth(c, mesUser, mesRole, isPlayerRole, cliVersion, authResult);
                    }
                 });
        }
    }

    /**
     * After successful client user auth, take care of the rest of
     * {@link #handleAUTHREQUEST(StringConnection, SOCAuthRequest)}.
     * @since 1.2.00
     */
    private void handleAUTHREQUEST_postAuth
        (final StringConnection c, final String mesUser, final String mesRole, final boolean isPlayerRole,
         final int cliVersion, int authResult)
    {
        if (c.getData() == null)
        {
            if (! isPlayerRole)
            {
                if (mesRole.equals(SOCAuthRequest.ROLE_USER_ADMIN))
                {
                    if (! isUserDBUserAdmin(mesUser))
                    {
                        c.put(SOCStatusMessage.toCmd
                                (SOCStatusMessage.SV_ACCT_NOT_CREATED_DENIED, cliVersion,
                                 "Your account is not authorized to create accounts."));

                        printAuditMessage
                            (mesUser,
                             "Requested jsettlers account creation, this requester not on account admins list",
                             null, null, c.host());

                        return;
                    }
                }

                // no role-specific problems: complete the authentication
                try
                {
                    c.setData(SOCDBHelper.getUser(mesUser));  // case-insensitive db search on mesUser
                    nameConnection(c, false);
                } catch (SQLException e) {
                    // unlikely, we've just queried db in authOrRejectClientUser
                    c.put(SOCStatusMessage.toCmd
                            (SOCStatusMessage.SV_PROBLEM_WITH_DB, c.getVersion(),
                             "Problem connecting to database, please try again later."));
                    return;
                }
            }
        }

        final String txt = "Welcome to Java Settlers of Catan!";
        if (0 == (authResult & AUTH_OR_REJECT__SET_USERNAME))
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK, txt));
        else
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK_SET_NICKNAME, c.getData() + SOCMessage.sep2_char + txt));
    }

    /**
     * Handle the "join a game" message: Join or create a game.
     * Will join the game, or return a STATUSMESSAGE if nickname is not OK.
     * Clients can join game as an observer, if they don't SITDOWN after joining.
     *<P>
     * If client hasn't yet sent its version, assume is version 1.0.00 ({@link #CLI_VERSION_ASSUMED_GUESS}), disconnect if too low.
     * If the client is too old to join a specific game, return a STATUSMESSAGE. (since 1.1.06)
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleJOINGAME(StringConnection c, SOCJoinGame mes)
    {
        if (c != null)
        {
            D.ebugPrintln("handleJOINGAME: " + mes);

            /**
             * Check the client's reported version; if none, assume 1000 (1.0.00)
             */
            if (c.getVersion() == -1)
            {
                if (! setClientVersSendGamesOrReject(c, CLI_VERSION_ASSUMED_GUESS, false))
                    return;  // <--- Early return: Client too old ---
            }

            createOrJoinGameIfUserOK
                (c, mes.getNickname(), mes.getPassword(), mes.getGame(), null);

        }
    }

    /**
     * Check username/password and create new game, or join game.
     * Called by handleJOINGAME and handleNEWGAMEWITHOPTIONSREQUEST.
     * JOINGAME or NEWGAMEWITHOPTIONSREQUEST may be the first message with the
     * client's username and password, so c.getData() may be null.
     * Assumes client's version is already received or guessed.
     *<P>
     * Game name and player name have a maximum length and some disallowed characters; see parameters.
     * Check the client's {@link SOCClientData#getCurrentCreatedGames()} vs {@link #CLIENT_MAX_CREATE_GAMES}.
     *<P>
     * If client is replacing/taking over their own lost connection,
     * first tell them they're rejoining all their other games.
     * That way, the requested game's window will appear last,
     * not hidden behind the others.
     *<P>
     *<b>Process if gameOpts != null:</b>
     *<UL>
     *  <LI> if game with this name already exists, respond with
     *      STATUSMESSAGE({@link SOCStatusMessage#SV_NEWGAME_ALREADY_EXISTS SV_NEWGAME_ALREADY_EXISTS})
     *  <LI> compare cli's param name-value pairs, with srv's known values. <br>
     *      - if any are above/below max/min, clip to the max/min value <br>
     *      - if any are unknown, resp with
     *        STATUSMESSAGE({@link SOCStatusMessage#SV_NEWGAME_OPTION_UNKNOWN SV_NEWGAME_OPTION_UNKNOWN}) <br>
     *      - if any are too new for client's version, resp with
     *        STATUSMESSAGE({@link SOCStatusMessage#SV_NEWGAME_OPTION_VALUE_TOONEW SV_NEWGAME_OPTION_VALUE_TOONEW}) <br>
     *      Comparison is done by {@link SOCGameOption#adjustOptionsToKnown(Hashtable, Hashtable, boolean)}.
     *  <LI> if ok: create new game with params;
     *      socgame will calc game's minCliVersion,
     *      and this method will check that against cli's version.
     *  <LI> announce to all players using NEWGAMEWITHOPTIONS;
     *       older clients get NEWGAME, won't see the options
     *  <LI> send JOINGAMEAUTH to requesting client, via {@link #joinGame(SOCGame, StringConnection, boolean, boolean)}
     *  <LI> send game status details to requesting client, via {@link #joinGame(SOCGame, StringConnection, boolean, boolean)}
     *</UL>
     *
     * @param c connection requesting the game, must not be null
     * @param msgUser username of client in message. Must pass {@link SOCMessage#isSingleLineAndSafe(String)}
     *                  and be at most {@link #PLAYER_NAME_MAX_LENGTH} characters.
     *                  Calls {@link String#trim() msgUser.trim()} before checking length.
     * @param msgPass password of client in message; will be {@link String#trim() trim()med}.
     * @param gameName  name of game to create/join. Must pass {@link SOCMessage#isSingleLineAndSafe(String)}
     *                  and be at most {@link #GAME_NAME_MAX_LENGTH} characters.
     *                  Calls {@link String#trim() gameName.trim()} before checking length.
     *                  Game name <tt>"*"</tt> is also rejected to avoid conflicts with admin commands.
     * @param gameOpts  if game has options, contains {@link SOCGameOption} to create new game; if not null, will not join an existing game.
     *                  Will validate and adjust by calling
     *                  {@link SOCGameOption#adjustOptionsToKnown(Hashtable, Hashtable, boolean)}
     *                  with <tt>doServerPreadjust</tt> true.
     *
     * @since 1.1.07
     */
    private void createOrJoinGameIfUserOK
        (StringConnection c, String msgUser, String msgPass, String gameName, final Hashtable gameOpts)
    {
        if (msgUser != null)
            msgUser = msgUser.trim();
        if (msgPass != null)
            msgPass = msgPass.trim();
        if (gameName != null)
            gameName = gameName.trim();
        final int cliVers = c.getVersion();

        if (c.getData() != null)
        {
            createOrJoinGameIfUserOK_postAuth(c, cliVers, gameName, gameOpts, AUTH_OR_REJECT__OK);
        } else {
            /**
             * Check that the nickname is ok, check password if supplied; if not ok, sends a SOCStatusMessage.
             */
            final String gName = gameName;
            authOrRejectClientUser
                (c, msgUser, msgPass, cliVers, true, true,
                 new AuthSuccessRunnable()
                 {
                    public void success(StringConnection c, int authResult)
                    {
                        createOrJoinGameIfUserOK_postAuth(c, cliVers, gName, gameOpts, authResult);
                    }
                 });
        }
    }

    /**
     * After successful client user auth, take care of the rest of
     * {@link #createOrJoinGameIfUserOK(StringConnection, String, String, String, Hashtable)}.
     * @since 1.2.00
     */
    private void createOrJoinGameIfUserOK_postAuth
        (final StringConnection c, final int cliVers, final String gameName,
         final Hashtable gameOpts, final int authResult)
    {
        final boolean isTakingOver = (0 != (authResult & AUTH_OR_REJECT__TAKING_OVER));

        /**
         * Check that the game name is ok
         */
        if ( (! SOCMessage.isSingleLineAndSafe(gameName))
             || "*".equals(gameName))
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWGAME_NAME_REJECTED, cliVers,
                     SOCStatusMessage.MSG_SV_NEWGAME_NAME_REJECTED));
              // "This game name is not permitted, please choose a different name."

              return;  // <---- Early return ----
        }
        if (gameName.length() > GAME_NAME_MAX_LENGTH)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWGAME_NAME_TOO_LONG, cliVers,
                     SOCStatusMessage.MSG_SV_NEWGAME_NAME_TOO_LONG + Integer.toString(GAME_NAME_MAX_LENGTH)));
            // Please choose a shorter name; maximum length: 30

            return;  // <---- Early return ----
        }

        /**
         * If creating a new game, ensure they are below their max game count.
         * (Don't limit max games on the practice server.)
         */
        if ((! gameList.isGame(gameName))
            && ((strSocketName == null) || ! strSocketName.equals(PRACTICE_STRINGPORT))
            && (CLIENT_MAX_CREATE_GAMES >= 0)
            && (CLIENT_MAX_CREATE_GAMES <= ((SOCClientData) c.getAppData()).getCurrentCreatedGames()))
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWGAME_TOO_MANY_CREATED, cliVers,
                     SOCStatusMessage.MSG_SV_NEWGAME_TOO_MANY_CREATED + Integer.toString(CLIENT_MAX_CREATE_GAMES)));
            // Too many of your games still active; maximum: 5

            return;  // <---- Early return ----            
        }

        /**
         * If we have game options, we're being asked to create a new game.
         * Validate them and ensure the game doesn't already exist.
         */
        if (gameOpts != null)
        {
            if (gameList.isGame(gameName))
            {
                c.put(SOCStatusMessage.toCmd
                      (SOCStatusMessage.SV_NEWGAME_ALREADY_EXISTS, cliVers,
                       SOCStatusMessage.MSG_SV_NEWGAME_ALREADY_EXISTS));
                // "A game with this name already exists, please choose a different name."

                return;  // <---- Early return ----
            }

            final StringBuffer optProblems = SOCGameOption.adjustOptionsToKnown(gameOpts, null, true);
            if (optProblems != null)
            {
                c.put(SOCStatusMessage.toCmd
                      (SOCStatusMessage.SV_NEWGAME_OPTION_UNKNOWN, cliVers,
                       "Unknown game option(s) were requested, cannot create this game. " + optProblems));

                return;  // <---- Early return ----
            }
        }

        /**
         * Try to create or add player to game, and tell the client that everything is ready;
         * if game doesn't yet exist, it's created in connectToGame, and announced
         * there to all clients.
         *<P>
         * If client's version is too low (based on game options, etc),
         * connectToGame will throw an exception; tell the client if that happens.
         *<P>
         * If rejoining after a lost connection, first rejoin all their other games.
         */
        try
        {
            if (0 != (authResult & SOCServer.AUTH_OR_REJECT__SET_USERNAME))
                c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK_SET_NICKNAME,
                     c.getData() + SOCMessage.sep2_char +
                     "Welcome to Java Settlers of Catan!"));

            if (isTakingOver)
            {
                /**
                 * Rejoin the requested game.
                 * First, rejoin all other games of this client.
                 * That way, the requested game's window will
                 * appear last, not hidden behind the others.
                 * For each game, calls joinGame to send JOINGAMEAUTH
                 * and the entire state of the game to client.
                 */
                Vector allConnGames = gameList.memberGames(c, gameName);
                if (allConnGames.size() == 0)
                {
                    c.put(SOCStatusMessage.toCmd(SOCStatusMessage.SV_OK,
                            "You've taken over the connection, but aren't in any games."));
                } else {
                    // Send list backwards: requested game will be sent last.
                    for (int i = allConnGames.size() - 1; i >= 0; --i)
                        joinGame((SOCGame) allConnGames.elementAt(i), c, false, true);
                }
            }
            else if (connectToGame(c, gameName, gameOpts))  // join or create the game
            {
                /**
                 * send JOINGAMEAUTH to client,
                 * send the entire state of the game to client,
                 * send client join event to other players of game
                 */
                SOCGame gameData = gameList.getGameData(gameName);

                if (gameData != null)
                {
                    joinGame(gameData, c, false, false);
                }
            }
        } catch (SOCGameOptionVersionException e)
        {
            // Let them know they can't join; include the game's version.
            // This cli asked to created it, otherwise gameOpts would be null.
            c.put(SOCStatusMessage.toCmd
              (SOCStatusMessage.SV_NEWGAME_OPTION_VALUE_TOONEW, cliVers,
                "Cannot create game with these options; requires version "
                + Integer.toString(e.gameOptsVersion)
                + SOCMessage.sep2_char + gameName
                + SOCMessage.sep2_char + e.problemOptionsList()));
        } catch (IllegalArgumentException e)
        {
            SOCGame game = gameList.getGameData(gameName);
            if (game == null)
            {
                D.ebugPrintStackTrace(e, "Exception in createOrJoinGameIfUserOK");   
            } else {
                // Let them know they can't join; include the game's version.
                c.put(SOCStatusMessage.toCmd
                  (SOCStatusMessage.SV_CANT_JOIN_GAME_VERSION, cliVers,
                    "Cannot join game; requires version "
                    + Integer.toString(game.getClientVersionMinRequired())
                    + ": " + gameName));
            }
        }

    }  //  createOrJoinGameIfUserOK

    /**
     * Handle the "leave game" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleLEAVEGAME(StringConnection c, SOCLeaveGame mes)
    {
        if (c != null)
        {
            boolean isMember = false;
            final String gaName = mes.getGame();
            if (! gameList.takeMonitorForGame(gaName))
            {
                return;  // <--- Early return: game not in gamelist ---
            }

            try
            {
                isMember = gameList.isMember(c, gaName);
            }
            catch (Exception e)
            {
                D.ebugPrintStackTrace(e, "Exception in handleLEAVEGAME (isMember)");
            }

            gameList.releaseMonitorForGame(gaName);

            if (isMember)
            {
                handleLEAVEGAME_member(c, gaName);
            }
            else if (((SOCClientData) c.getAppData()).isRobot)
            {
                handleLEAVEGAME_maybeGameReset_oldRobot(gaName);
                // During a game reset, this robot player
                // will not be found among cg's players
                // (isMember is false), because it's
                // attached to the old game object
                // instead of the new one.
                // So, check game state and update game's reset data.
            }
        }
    }

    /**
     * Handle a member leaving the game, from {@link #handleLEAVEGAME(StringConnection, SOCLeaveGame)}.
     * @since 1.1.07
     */
    private void handleLEAVEGAME_member(StringConnection c, final String gaName)
    {
        boolean gameDestroyed = false;
        if (! gameList.takeMonitorForGame(gaName))
        {
            return;  // <--- Early return: game not in gamelist ---
        }

        try
        {
            gameDestroyed = leaveGame(c, gaName, true, false);
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception in handleLEAVEGAME (leaveGame)");
        }

        gameList.releaseMonitorForGame(gaName);

        if (gameDestroyed)
        {
            broadcast(SOCDeleteGame.toCmd(gaName));
        }
        else
        {
            /*
               SOCLeaveGame leaveMessage = new SOCLeaveGame((String)c.getData(), c.host(), mes.getGame());
               messageToGame(mes.getGame(), leaveMessage);
               recordGameEvent(mes.getGame(), leaveMessage.toCmd());
             */
        }

        /**
         * if it's a robot, remove it from the request list
         */
        Vector requests = (Vector) robotDismissRequests.get(gaName);

        if (requests != null)
        {
            Enumeration reqEnum = requests.elements();
            SOCReplaceRequest req = null;

            while (reqEnum.hasMoreElements())
            {
                SOCReplaceRequest tempReq = (SOCReplaceRequest) reqEnum.nextElement();

                if (tempReq.getLeaving() == c)
                {
                    req = tempReq;
                    break;
                }
            }

            if (req != null)
            {
                requests.removeElement(req);

                /**
                 * Taking over a robot spot: let the person replacing the robot sit down
                 */
                SOCGame ga = gameList.getGameData(gaName);
                final int pn = req.getSitDownMessage().getPlayerNumber();
                final boolean isRobot = req.getSitDownMessage().isRobot();
                if (! isRobot)
                {
                    ga.getPlayer(pn).setFaceId(1);  // Don't keep the robot face icon
                }
                sitDown(ga, req.getArriving(), pn, isRobot, false);
            }
        }
    }

    /**
     * Handle an unattached robot saying it is leaving the game,
     * from {@link #handleLEAVEGAME(StringConnection, SOCLeaveGame)}.
     * Ignore the robot (since it's not a member of the game) unless
     * gamestate is {@link SOCGame#READY_RESET_WAIT_ROBOT_DISMISS}.
     *
     * @since 1.1.07
     */
    private void handleLEAVEGAME_maybeGameReset_oldRobot(final String gaName)
    {
        SOCGame cg = gameList.getGameData(gaName);
        if (cg.getGameState() != SOCGame.READY_RESET_WAIT_ROBOT_DISMISS)
            return;

        boolean gameResetRobotsAllDismissed = false;

        // TODO locks
        SOCGameBoardReset gr = cg.boardResetOngoingInfo;
        if (gr != null)
        {
            --gr.oldRobotCount;
            if (0 == gr.oldRobotCount)
                gameResetRobotsAllDismissed = true;
        }

        if (gameResetRobotsAllDismissed)
            resetBoardAndNotify_finish(gr, cg);  // TODO locks?
    }

    /**
     * handle "sit down" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleSITDOWN(StringConnection c, SOCSitDown mes)
    {
        if (c != null)
        {
            final String gaName = mes.getGame();
            SOCGame ga = gameList.getGameData(gaName);

            if (ga != null)
            {
                /**
                 * make sure this player isn't already sitting
                 */
                boolean canSit = true;
                boolean gameIsFull = false, gameAlreadyStarted = false;

                /*
                   for (int i = 0; i < SOCGame.MAXPLAYERS; i++) {
                   if (ga.getPlayer(i).getName() == (String)c.getData()) {
                   canSit = false;
                   break;
                   }
                   }
                 */
                //D.ebugPrintln("ga.isSeatVacant(mes.getPlayerNumber()) = "+ga.isSeatVacant(mes.getPlayerNumber()));

                /**
                 * if this is a robot, remove it from the request list
                 */
                boolean isBotJoinRequest = false;
                {
                    Vector joinRequests = (Vector) robotJoinRequests.get(gaName);
                    if (joinRequests != null)
                        isBotJoinRequest = joinRequests.removeElement(c);
                }

                /**
                 * make sure a person isn't sitting here already;
                 * if a robot is sitting there, dismiss the robot.
                 * Can't sit at a vacant seat after everyone has
                 * placed 1st settlement+road (state >= START2A).
                 *
                 * If a human leaves after game is started, seat will
                 * appear vacant when the requested bot sits to replace
                 * them, so let the bot sit at that vacant seat.
                 */
                ga.takeMonitor();

                try
                {
                    if (ga.isSeatVacant(mes.getPlayerNumber()))
                    {
                        gameAlreadyStarted = (ga.getGameState() >= SOCGame.START2A);
                        if (! gameAlreadyStarted)
                            gameIsFull = (1 > ga.getAvailableSeatCount());

                        if (gameIsFull || (gameAlreadyStarted && ! isBotJoinRequest))
                            canSit = false;
                    } else {
                        SOCPlayer seatedPlayer = ga.getPlayer(mes.getPlayerNumber());

                        if (seatedPlayer.isRobot() && (! ga.isSeatLocked(mes.getPlayerNumber()))
                            && (ga.getCurrentPlayerNumber() != mes.getPlayerNumber()))
                        {
                            /**
                             * boot the robot out of the game
                             */
                            StringConnection robotCon = getConnection(seatedPlayer.getName());
                            robotCon.put(SOCRobotDismiss.toCmd(gaName));

                            /**
                             * this connection has to wait for the robot to leave
                             * and then it can sit down
                             */
                            Vector disRequests = (Vector) robotDismissRequests.get(gaName);
                            SOCReplaceRequest req = new SOCReplaceRequest(c, robotCon, mes);

                            if (disRequests == null)
                            {
                                disRequests = new Vector();
                                disRequests.addElement(req);
                                robotDismissRequests.put(gaName, disRequests);
                            }
                            else
                            {
                                disRequests.addElement(req);
                            }
                        }

                        canSit = false;
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught at handleSITDOWN");
                }

                ga.releaseMonitor();

                //D.ebugPrintln("canSit 2 = "+canSit);
                if (canSit)
                {
                    sitDown(ga, c, mes.getPlayerNumber(), mes.isRobot(), false);
                }
                else
                {
                    /**
                     * if the robot can't sit, tell it to go away.
                     * otherwise if game is full, tell the player.
                     */
                    if (mes.isRobot())
                    {
                        c.put(SOCRobotDismiss.toCmd(gaName));
                    } else if (gameAlreadyStarted) {
                        messageToPlayer(c, gaName, "This game has already started, to play you must take over a robot.");
                    } else if (gameIsFull) {
                        messageToPlayer(c, gaName, "This game is full, you cannot sit down.");
                    }
                }
            }
        }
    }

    /**
     * handle "put piece" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handlePUTPIECE(StringConnection c, SOCPutPiece mes)
    {
        if (c == null)
            return;
        SOCGame ga = gameList.getGameData(mes.getGame());
        if (ga == null)
            return;

        ga.takeMonitor();

        try
        {
            final String gaName = ga.getName();
            final String plName = c.getData();
            SOCPlayer player = ga.getPlayer(plName);

            /**
             * make sure the player can do it
             */
            if (checkTurn(c, ga))
            {
                boolean sendDenyReply = false;
                /*
                   if (D.ebugOn) {
                   D.ebugPrintln("BEFORE");
                   for (int pn = 0; pn < SOCGame.MAXPLAYERS; pn++) {
                   SOCPlayer tmpPlayer = ga.getPlayer(pn);
                   D.ebugPrintln("Player # "+pn);
                   for (int i = 0x22; i < 0xCC; i++) {
                   if (tmpPlayer.isPotentialRoad(i))
                   D.ebugPrintln("### POTENTIAL ROAD AT "+Integer.toHexString(i));
                   }
                   }
                   }
                 */

                final int gameState = ga.getGameState();
                final int coord = mes.getCoordinates();
                switch (mes.getPieceType())
                {
                case SOCPlayingPiece.ROAD:

                    SOCRoad rd = new SOCRoad(player, coord, null);

                    if ((gameState == SOCGame.START1B) || (gameState == SOCGame.START2B) || (gameState == SOCGame.PLACING_ROAD) || (gameState == SOCGame.PLACING_FREE_ROAD1) || (gameState == SOCGame.PLACING_FREE_ROAD2))
                    {
                        if (player.isPotentialRoad(coord) && (player.getNumPieces(SOCPlayingPiece.ROAD) >= 1))
                        {
                            ga.putPiece(rd);  // Changes state and sometimes player (initial placement)

                            /*
                               if (D.ebugOn) {
                               D.ebugPrintln("AFTER");
                               for (int pn = 0; pn < SOCGame.MAXPLAYERS; pn++) {
                               SOCPlayer tmpPlayer = ga.getPlayer(pn);
                               D.ebugPrintln("Player # "+pn);
                               for (int i = 0x22; i < 0xCC; i++) {
                               if (tmpPlayer.isPotentialRoad(i))
                               D.ebugPrintln("### POTENTIAL ROAD AT "+Integer.toHexString(i));
                               }
                               }
                               }
                             */
                            gameList.takeMonitorForGame(gaName);
                            messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, plName + " built a road."));
                            messageToGameWithMon(gaName, new SOCPutPiece(gaName, player.getPlayerNumber(), SOCPlayingPiece.ROAD, coord));
                            gameList.releaseMonitorForGame(gaName);
                            boolean toldRoll = sendGameState(ga, false);
                            broadcastGameStats(ga);

                            if (!checkTurn(c, ga))
                            {
                                // Player changed (or play started), announce new player.
                                sendTurn(ga, true);
                            }
                            else if (toldRoll)
                            {
                                // When play starts, or after placing 2nd free road,
                                // announce even though player unchanged,
                                // to trigger auto-roll for the player.
                                // If the client is too old (1.0.6), it will ignore the prompt.
                                messageToGame(gaName, new SOCRollDicePrompt (gaName, player.getPlayerNumber()));
                            }
                        }
                        else
                        {
                            D.ebugPrintln("ILLEGAL ROAD: 0x" + Integer.toHexString(coord)
                                + ": player " + player.getPlayerNumber());
                            messageToPlayer(c, gaName, "You can't build a road there.");
                            sendDenyReply = true;
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "You can't build a road right now.");
                    }

                    break;

                case SOCPlayingPiece.SETTLEMENT:

                    SOCSettlement se = new SOCSettlement(player, coord, null);

                    if ((gameState == SOCGame.START1A) || (gameState == SOCGame.START2A) || (gameState == SOCGame.PLACING_SETTLEMENT))
                    {
                        if (player.isPotentialSettlement(coord) && (player.getNumPieces(SOCPlayingPiece.SETTLEMENT) >= 1))
                        {
                            ga.putPiece(se);   // Changes game state and (if game start) player
                            gameList.takeMonitorForGame(gaName);
                            messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, plName + " built a settlement."));
                            messageToGameWithMon(gaName, new SOCPutPiece(gaName, player.getPlayerNumber(), SOCPlayingPiece.SETTLEMENT, coord));
                            gameList.releaseMonitorForGame(gaName);
                            broadcastGameStats(ga);
                            sendGameState(ga);

                            if (!checkTurn(c, ga))
                            {
                                sendTurn(ga, false);  // Announce new current player.
                            }
                        }
                        else
                        {
                            D.ebugPrintln("ILLEGAL SETTLEMENT: 0x" + Integer.toHexString(coord)
                                + ": player " + player.getPlayerNumber());
                            messageToPlayer(c, gaName, "You can't build a settlement there.");
                            sendDenyReply = true;
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "You can't build a settlement right now.");
                    }

                    break;

                case SOCPlayingPiece.CITY:

                    SOCCity ci = new SOCCity(player, coord, null);

                    if (gameState == SOCGame.PLACING_CITY)
                    {
                        boolean houseRuleFirstCity = ga.isGameOptionSet("N7C") && ! ga.hasBuiltCity();
                        if (houseRuleFirstCity && ga.isGameOptionSet("N7")
                            && (ga.getRoundCount() < ga.getGameOptionIntValue("N7")))
                        {
                            // If "No 7s for first # rounds" is active, and this isn't its last round, 7s won't
                            // be rolled soon: Don't announce "Starting next turn, dice rolls of 7 may occur"
                            houseRuleFirstCity = false;
                        }

                        if (player.isPotentialCity(coord) && (player.getNumPieces(SOCPlayingPiece.CITY) >= 1))
                        {
                            ga.putPiece(ci);  // changes game state and maybe player
                            gameList.takeMonitorForGame(gaName);
                            messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, plName + " built a city."));
                            messageToGameWithMon(gaName, new SOCPutPiece(gaName, player.getPlayerNumber(), SOCPlayingPiece.CITY, coord));
                            if (houseRuleFirstCity)
                                messageToGameWithMon
                                    (gaName, new SOCGameTextMsg(gaName, SERVERNAME, "Starting next turn, dice rolls of 7 may occur (house rule)."));
                            gameList.releaseMonitorForGame(gaName);
                            broadcastGameStats(ga);
                            sendGameState(ga);

                            if (!checkTurn(c, ga))
                            {
                                sendTurn(ga, false);  // announce new current player
                            }
                        }
                        else
                        {
                            D.ebugPrintln("ILLEGAL CITY: 0x" + Integer.toHexString(coord)
                                + ": player " + player.getPlayerNumber());
                            messageToPlayer(c, gaName, "You can't build a city there.");
                            sendDenyReply = true;
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "You can't build a city right now.");
                    }

                    break;

                }  // switch (mes.getPieceType())

                if (sendDenyReply)
                {
                    messageToPlayer(c, new SOCCancelBuildRequest(gaName, mes.getPieceType()));
                    if (player.isRobot())
                    {
                        // Set the "force end turn soon" field
                        ga.lastActionTime = 0L;
                    }
                }                       
            }
            else
            {
                messageToPlayer(c, gaName, "It's not your turn.");
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception caught in handlePUTPIECE");
        }

        ga.releaseMonitor();
    }

    /**
     * handle "move robber" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleMOVEROBBER(StringConnection c, SOCMoveRobber mes)
    {
        if (c != null)
        {
            String gn = mes.getGame();
            SOCGame ga = gameList.getGameData(gn);

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    SOCPlayer player = ga.getPlayer(c.getData());

                    /**
                     * make sure the player can do it
                     */
                    final String gaName = ga.getName();
                    if (ga.canMoveRobber(player.getPlayerNumber(), mes.getCoordinates()))
                    {
                        SOCMoveRobberResult result = ga.moveRobber(player.getPlayerNumber(), mes.getCoordinates());
                        messageToGame(gaName, new SOCMoveRobber(gaName, player.getPlayerNumber(), mes.getCoordinates()));

                        Vector victims = result.getVictims();

                        /** only one possible victim */
                        if (victims.size() == 1)
                        {
                            /**
                             * report what was stolen
                             */
                            SOCPlayer victim = (SOCPlayer) victims.firstElement();
                            reportRobbery(ga, player, victim, result.getLoot());
                        }
                        /** no victim */
                        else if (victims.size() == 0)
                        {
                            /**
                             * just say it was moved; nothing is stolen
                             */
                            messageToGame(gaName, c.getData() + " moved the robber.");
                        }
                        else
                        {
                            /**
                             * else, the player needs to choose a victim
                             */
                            messageToGame(gaName, c.getData() + " moved the robber, must choose a victim.");
                        }

                        sendGameState(ga);
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "You can't move the robber.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "start game" message.  Game state must be NEW, or this message is ignored.
     * {@link #readyGameAskRobotsJoin(SOCGame, StringConnection[]) Ask some robots} to fill
     * empty seats, or {@link #startGame(SOCGame) begin the game} if no robots needed.
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleSTARTGAME(StringConnection c, SOCStartGame mes)
    {
        if (c != null)
        {
            String gn = mes.getGame();
            SOCGame ga = gameList.getGameData(gn);

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    if (ga.getGameState() == SOCGame.NEW)
                    {
                        boolean allowStart = true;
                        boolean seatsFull = true;
                        boolean anyLocked = false;
                        int numEmpty = 0;
                        int numPlayers = 0;

                        //
                        // count the number of unlocked empty seats
                        //
                        for (int i = 0; i < ga.maxPlayers; i++)
                        {
                            if (ga.isSeatVacant(i))
                            {
                                if (ga.isSeatLocked(i))
                                {
                                    anyLocked = true;
                                }
                                else
                                {
                                    seatsFull = false;
                                    ++numEmpty;
                                }
                            }
                            else
                            {
                                ++numPlayers;
                            }
                        }

                        // Check vs max-players allowed in game (option "PL").
                        // Like seat locks, this can cause robots to be unwanted
                        // in otherwise-empty seats.
                        {
                            final int numAvail = ga.getAvailableSeatCount();
                            if (numAvail < numEmpty)
                            {
                                numEmpty = numAvail;
                                if (numEmpty == 0)
                                    seatsFull = true;
                            }
                        }

                        if (numPlayers == 0)
                        {
                            // No one has sat, human client who requested STARTGAME is an observer.

                            allowStart = false;
                            messageToGame(gn, "To start the game, at least one player must sit down.");
                        }

                        if (seatsFull && (numPlayers < 2))
                        {
                            allowStart = false;
                            numEmpty = 3;
                            String m = "The only player cannot lock all seats. To start the game, other players or robots must join.";
                            messageToGame(gn, m);
                        }
                        else if (allowStart && ! seatsFull)
                        {
                            if (robots.isEmpty()) 
                            {                                
                                if (numPlayers < SOCGame.MINPLAYERS)
                                {
                                    messageToGame(gn,
                                        "No robots on this server, please fill at least "
                                        + SOCGame.MINPLAYERS + " seats before starting." );
                                }
                                else
                                {
                                    seatsFull = true;  // Enough players to start game.
                                }
                            }
                            else
                            {
                                //
                                // make sure there are enough robots connected,
                                // then set gamestate READY and ask them to connect.
                                //
                                if (numEmpty > robots.size())
                                {
                                    String m;
                                    if (anyLocked)
                                        m = "Sorry, not enough robots to fill all the seats.  Only " + robots.size() + " robots are available.";
                                    else
                                        m = "Sorry, not enough robots to fill all the seats.  Lock some seats.  Only " + robots.size() + " robots are available.";
                                    messageToGame(gn, m);
                                }
                                else
                                {
                                    ga.setGameState(SOCGame.READY);

                                    /**
                                     * Fill all the unlocked empty seats with robots.
                                     * Build a Vector of StringConnections of robots asked
                                     * to join, and add it to the robotJoinRequests table.
                                     */
                                    try
                                    {
                                        readyGameAskRobotsJoin(ga, null);
                                    }
                                    catch (IllegalStateException e)
                                    {
                                        System.err.println("Robot-join problem in game " + gn + ": " + e);

                                        // recover, so that human players can still start a game
                                        ga.setGameState(SOCGame.NEW);
                                        allowStart = false;

                                        messageToGame(gn, "Sorry, robots cannot join this game: " + e.getMessage());
                                        messageToGame(gn, "To start the game without robots, lock all empty seats.");
                                    }
                                }
                            }
                        }

                        /**
                         * If this doesn't need robots, then start the game.
                         * Otherwise wait for them to sit before starting the game.
                         */
                        if (seatsFull && allowStart)
                        {
                            startGame(ga);
                        }
                    }
                }
                catch (Throwable e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * Fill all the unlocked empty seats with robots, by asking them to join.
     * Builds a Vector of StringConnections of robots asked to join,
     * and adds it to the robotJoinRequests table.
     * Game state should be READY.
     * At most {@link SOCGame#getAvailableSeatCount()} robots will
     * be asked.
     *<P>
     * Called by {@link #handleSTARTGAME(StringConnection, SOCStartGame) handleSTARTGAME},
     * {@link #resetBoardAndNotify(String, int) resetBoardAndNotify}.
     *<P>
     * Once the robots have all responded (from their own threads/clients)
     * and joined up, the game can begin.
     *
     * @param ga  Game to ask robots to join
     * @param robotSeats If robotSeats is null, robots are randomly selected.
     *                   If non-null, a MAXPLAYERS-sized array of StringConnections.
     *                   Any vacant non-locked seat, with index i,
     *                   is filled with the robot whose connection is robotSeats[i].
     *                   Other indexes should be null, and won't be used.
     *
     * @throws IllegalStateException if {@link SOCGame#getGameState() ga.gamestate} is not READY,
     *         or if {@link SOCGame#getClientVersionMinRequired() ga.version} is
     *         somehow newer than server's version (which is assumed to be robots' version).
     * @throws IllegalArgumentException if robotSeats is not null but wrong length,
     *           or if a robotSeat element is null but that seat wants a robot (vacant non-locked).
     */
    private void readyGameAskRobotsJoin(SOCGame ga, StringConnection[] robotSeats)
        throws IllegalStateException, IllegalArgumentException
    {
        if (ga.getGameState() != SOCGame.READY)
            throw new IllegalStateException("SOCGame state not READY: " + ga.getGameState());

        if (ga.getClientVersionMinRequired() > Version.versionNumber())
            throw new IllegalStateException("SOCGame version somehow newer than server and robots, it's "
                    + ga.getClientVersionMinRequired());

        Vector robotRequests = null;

        int[] robotIndexes = null;
        if (robotSeats == null)
        {
            // shuffle the indexes to distribute load
            robotIndexes = robotShuffleForJoin();
        }
        else
        {
            // robotSeats not null: check length
            if (robotSeats.length != ga.maxPlayers)
                throw new IllegalArgumentException("robotSeats Length must be MAXPLAYERS");
        }

        final String gname = ga.getName();
        final Hashtable gopts = ga.getGameOptions();
        int seatsOpen = ga.getAvailableSeatCount();
        int idx = 0;
        StringConnection[] robotSeatsConns = new StringConnection[ga.maxPlayers];

        for (int i = 0; (i < ga.maxPlayers) && (seatsOpen > 0);
                i++)
        {
            if (ga.isSeatVacant(i) && ! ga.isSeatLocked(i))
            {
                /**
                 * fetch a robot player; game will start when all bots have arrived.
                 * Similar to SOCGameHandler.leaveGame, where a player has left and must be replaced by a bot.
                 */
                if (idx < robots.size())
                {
                    messageToGame(gname, "Fetching a robot player...");

                    StringConnection robotConn;
                    if (robotSeats != null)
                    {
                        robotConn = robotSeats[i];
                        if (robotConn == null)
                            throw new IllegalArgumentException("robotSeats[" + i + "] was needed but null");
                    }
                    else
                    {
                        robotConn = (StringConnection) robots.get(robotIndexes[idx]);
                    }
                    idx++;
                    --seatsOpen;
                    robotSeatsConns[i] = robotConn;

                    /**
                     * record the request
                     */
                    D.ebugPrintln("@@@ JOIN GAME REQUEST for " + robotConn.getData());
                    if (robotRequests == null)
                        robotRequests = new Vector();
                    robotRequests.addElement(robotConn);
                }
            }
        }

        if (robotRequests != null)
        {
            // we know it isn't empty,
            // so add to the request table
            robotJoinRequests.put(gname, robotRequests);

            // now, make the requests
            for (int i = 0; i < ga.maxPlayers; ++i)
                if (robotSeatsConns[i] != null)
                    robotSeatsConns[i].put(SOCJoinGameRequest.toCmd(gname, i, gopts));
        }
    }

    /**
     * handle "roll dice" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleROLLDICE(StringConnection c, SOCRollDice mes)
    {
        if (c != null)
        {
            final String gn = mes.getGame();
            SOCGame ga = gameList.getGameData(gn);

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    final String plName = c.getData();
                    final SOCPlayer pl = ga.getPlayer(plName);
                    if ((pl != null) && ga.canRollDice(pl.getPlayerNumber()))
                    {
                        /**
                         * Roll dice, distribute resources in game
                         */
                        IntPair dice = ga.rollDice();

                        /**
                         * Send roll results and then text to client.
                         * Client expects to see DiceResult first, then text message;
                         * to reduce visual clutter, SOCPlayerInterface.print
                         * expects text message to follow a certain format.
                         * If a 7 is rolled, sendGameState will also say who must discard
                         * (in a GAMETEXTMSG).
                         */
                        messageToGame(gn, new SOCDiceResult(gn, ga.getCurrentDice()));
                        messageToGame(gn, plName + " rolled a " + dice.getA() + " and a " + dice.getB() + ".");
                        sendGameState(ga);  // For 7, give visual feedback before sending discard request

                        /**
                         * if the roll is not 7, tell players what they got
                         */
                        if (ga.getCurrentDice() != 7)
                        {
                            boolean noPlayersGained = true;
                            StringBuffer gainsText = new StringBuffer();

                            for (int i = 0; i < ga.maxPlayers; i++)
                            {
                                if (! ga.isSeatVacant(i))
                                {
                                    SOCPlayer pli = ga.getPlayer(i);
                                    SOCResourceSet rsrcs = ga.getResourcesGainedFromRoll(pli, ga.getCurrentDice());

                                    if (rsrcs.getTotal() != 0)
                                    {
                                        if (noPlayersGained)
                                        {
                                            noPlayersGained = false;
                                        }
                                        else
                                        {
                                            gainsText.append(" ");
                                        }

                                        gainsText.append(pli.getName());
                                        gainsText.append(" gets ");
                                        // Send SOCPlayerElement messages,
                                        // build resource-text in gainsText.
                                        reportRsrcGainLoss(gn, rsrcs, false, false, i, -1, gainsText, null);
                                        gainsText.append(".");
                                    }

                                    //
                                    //  send all resource info for accuracy
                                    //
                                    StringConnection playerCon = getConnection(pli.getName());
                                    if (playerCon != null)
                                    {
                                        // CLAY, ORE, SHEEP, WHEAT, WOOD
                                        SOCResourceSet resources = pli.getResources();
                                        for (int res = SOCPlayerElement.CLAY; res <= SOCPlayerElement.WOOD; ++res)
                                            messageToPlayer(playerCon, new SOCPlayerElement(ga.getName(), i, SOCPlayerElement.SET, res, resources.getAmount(res)));
                                        messageToGame(ga.getName(), new SOCResourceCount(ga.getName(), i, resources.getTotal()));
                                    }
                                }  // if (! ga.isSeatVacant(i))
                            }  // for (i)

                            String message;
                            if (noPlayersGained)
                            {
                                message = "No player gets anything.";
                            }
                            else
                            {
                                message = gainsText.toString();
                            }
                            messageToGame(gn, message);

                            /*
                               if (D.ebugOn) {
                               for (int i=0; i < SOCGame.MAXPLAYERS; i++) {
                               SOCResourceSet rsrcs = ga.getPlayer(i).getResources();
                               String resourceMessage = "PLAYER "+i+" RESOURCES: ";
                               resourceMessage += rsrcs.getAmount(SOCResourceConstants.CLAY)+" ";
                               resourceMessage += rsrcs.getAmount(SOCResourceConstants.ORE)+" ";
                               resourceMessage += rsrcs.getAmount(SOCResourceConstants.SHEEP)+" ";
                               resourceMessage += rsrcs.getAmount(SOCResourceConstants.WHEAT)+" ";
                               resourceMessage += rsrcs.getAmount(SOCResourceConstants.WOOD)+" ";
                               resourceMessage += rsrcs.getAmount(SOCResourceConstants.UNKNOWN)+" ";
                               messageToGame(gn, new SOCGameTextMsg(gn, SERVERNAME, resourceMessage));
                               }
                               }
                             */
                        }
                        else
                        {
                            /**
                             * player rolled 7
                             */
                            for (int i = 0; i < ga.maxPlayers; i++)
                            {
                                if (( ! ga.isSeatVacant(i))
                                    && (ga.getPlayer(i).getResources().getTotal() > 7))
                                {
                                    // Request to discard half (round down)
                                    StringConnection con = getConnection(ga.getPlayer(i).getName());
                                    if (con != null)
                                    {
                                        con.put(SOCDiscardRequest.toCmd(ga.getName(), ga.getPlayer(i).getResources().getTotal() / 2));
                                    }
                                }
                            }
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gn, "You can't roll right now.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught at handleROLLDICE" + e);
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "discard" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleDISCARD(StringConnection c, SOCDiscard mes)
    {
        if (c != null)
        {
            final String gn = mes.getGame();
            SOCGame ga = gameList.getGameData(gn);

            if (ga != null)
            {
                final SOCPlayer player = ga.getPlayer(c.getData());
                final int pn;
                if (player != null)
                    pn = player.getPlayerNumber();
                else
                    pn = -1;  // c's client no longer in the game

                ga.takeMonitor();
                try
                {
                    if (player == null)
                    {
                        // The catch block will print this out semi-nicely
                        throw new IllegalArgumentException("player not found in game");
                    }

                    if (ga.canDiscard(pn, mes.getResources()))
                    {
                        ga.discard(pn, mes.getResources());

                        /**
                         * tell the player client that the player discarded the resources
                         */
                        reportRsrcGainLoss(gn, mes.getResources(), true, false, pn, -1, null, c);

                        /**
                         * tell everyone else that the player discarded unknown resources
                         */
                        messageToGameExcept
                            (gn, c, new SOCPlayerElement
                                (gn, pn, SOCPlayerElement.LOSE, SOCPlayerElement.UNKNOWN, mes.getResources().getTotal(), true),
                             true);
                        messageToGame(gn, c.getData() + " discarded " + mes.getResources().getTotal() + " resources.");

                        /**
                         * send the new state, or end turn if was marked earlier as forced
                         */
                        if ((ga.getGameState() != SOCGame.PLAY1) || ! ga.isForcingEndTurn())
                        {
                            sendGameState(ga);
                        } else {
                            endGameTurn(ga, player);  // already did ga.takeMonitor()
                        }
                    }
                    else
                    {
                        /**
                         * (TODO) there could be a better feedback message here
                         */
                        messageToPlayer(c, gn, "You can't discard that many cards.");
                    }
                }
                catch (Throwable e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "end turn" message.
     * This normally ends a player's normal turn (phase {@link SOCGame#PLAY1}).
     * On the 6-player board, it ends their placements during the 
     * {@link SOCGame#SPECIAL_BUILDING Special Building Phase}.
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleENDTURN(StringConnection c, SOCEndTurn mes)
    {
        if (c == null)
            return;
        SOCGame ga = gameList.getGameData(mes.getGame());
        if (ga == null)
            return;

        final String gname = ga.getName();               

        if (ga.isDebugFreePlacement())
        {
            // turn that off before ending current turn
            processDebugCommand_freePlace(c, gname, "0");
        }

        ga.takeMonitor();

        try
        {
            final String plName = c.getData();
            if (ga.getGameState() == SOCGame.OVER)
            {
                // Should not happen; is here just in case.
                SOCPlayer pl = ga.getPlayer(plName);
                if (pl != null)
                {
                    String msg = ga.gameOverMessageToPlayer(pl);
                        // msg = "The game is over; you are the winner!";
                        // msg = "The game is over; <someone> won.";
                        // msg = "The game is over; no one won.";
                    messageToPlayer(c, gname, msg);
                }
            }
            else if (checkTurn(c, ga))
            {
                SOCPlayer pl = ga.getPlayer(plName);
                if ((pl != null) && ga.canEndTurn(pl.getPlayerNumber()))
                {
                    endGameTurn(ga, pl);
                }
                else
                {
                    messageToPlayer(c, gname, "You can't end your turn yet.");
                }
            }
            else
            {
                messageToPlayer(c, gname, "It's not your turn.");
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception caught at handleENDTURN");
        }

        ga.releaseMonitor();
    }

    /**
     * Pre-checking already done, end the current player's turn in this game.
     * Alter game state and send messages to players.
     * Calls {@link SOCGame#endTurn()}, which may also end the game.
     * On the 6-player board, this may begin the {@link SOCGame#SPECIAL_BUILDING Special Building Phase},
     * or end a player's placements during that phase.
     * Otherwise, calls {@link #sendTurn(SOCGame, boolean)} and begins
     * the next player's turn.
     *<P>
     * Assumes:
     * <UL>
     * <LI> ga.canEndTurn already called, to validate player
     * <LI> ga.takeMonitor already called (not the same as {@link SOCGameList#takeMonitorForGame(String)})
     * <LI> gamelist.takeMonitorForGame is NOT called, we do NOT have that monitor
     * </UL>
     *<P>
     * As a special case, endTurn is used to begin the Special Building Phase during the
     * start of a player's own turn, if permitted.  (Added in 1.1.09)
     *
     * @param ga Game to end turn
     * @param pl Current player in <tt>ga</tt>, or null. Not needed except in SPECIAL_BUILDING.
     *           If null, will be determined within this method.
     */
    private void endGameTurn(SOCGame ga, SOCPlayer pl)
    {
        final String gname = ga.getName();

        if (ga.getGameState() == SOCGame.SPECIAL_BUILDING)
        {
            final int cpn = ga.getCurrentPlayerNumber();
            if (pl == null)
                pl = ga.getPlayer(cpn);
            pl.setAskedSpecialBuild(false);
            messageToGame(gname, new SOCPlayerElement(gname, cpn, SOCPlayerElement.SET, SOCPlayerElement.ASK_SPECIAL_BUILD, 0));
        }

        boolean hadBoardResetRequest = (-1 != ga.getResetVoteRequester());

        /**
         * End the Turn:
         */

        ga.endTurn();  // May set state to OVER, if new player has enough points to win.
                       // May begin or continue the Special Building Phase.

        /**
         * Send the results out:
         */

        if (hadBoardResetRequest)
        {
            // Cancel voting at end of turn
            messageToGame(gname, new SOCResetBoardReject(gname));
        }

        /**
         * send new state number; if game is now OVER,
         * also send end-of-game messages.
         */
        boolean wantsRollPrompt = sendGameState(ga, false);

        /**
         * clear any trade offers
         */
        gameList.takeMonitorForGame(gname);
        if (ga.clientVersionLowest >= SOCClearOffer.VERSION_FOR_CLEAR_ALL)
        {
            messageToGameWithMon(gname, new SOCClearOffer(gname, -1));            
        } else {
            for (int i = 0; i < ga.maxPlayers; i++)
                messageToGameWithMon(gname, new SOCClearOffer(gname, i));
        }
        gameList.releaseMonitorForGame(gname);

        /**
         * send whose turn it is
         */
        sendTurn(ga, wantsRollPrompt);
        if (ga.getGameState() == SOCGame.SPECIAL_BUILDING)
            messageToGame(gname,
                "Special building phase: "
                  + ga.getPlayer(ga.getCurrentPlayerNumber()).getName()
                  + "'s turn to place.");
    }

    /**
     * Try to force-end the current player's turn in this game.
     * Alter game state and send messages to players.
     * Will call {@link #endGameTurn(SOCGame, SOCPlayer)} if appropriate.
     * Will send gameState and current player (turn) to clients.
     *<P>
     * If the current player has lost connection, send the {@link SOCLeaveGame LEAVEGAME}
     * message out <b>before</b> calling this method.
     *<P>
     * Assumes, as {@link #endGameTurn(SOCGame, SOCPlayer)} does:
     * <UL>
     * <LI> ga.canEndTurn already called, returned false
     * <LI> ga.takeMonitor already called (not the same as {@link SOCGameList#takeMonitorForGame(String)})
     * <LI> gamelist.takeMonitorForGame is NOT called, we do NOT have that monitor
     * </UL>
     * @param ga Game to force end turn
     * @param plName Current player's name. Needed because if they have been disconnected by
     *               {@link #leaveGame(StringConnection, String, boolean)},
     *               their name within game object is already null.
     * @return true if the turn was ended and game is still active;
     *          false if we find that all players have left and
     *          the gamestate has been changed here to {@link SOCGame#OVER OVER}.
     *
     * @see #endGameTurnOrForce(SOCGame, int, String, StringConnection, boolean)
     * @see SOCGame#forceEndTurn()
     */
    private boolean forceEndGameTurn(SOCGame ga, final String plName)
    {
        final String gaName = ga.getName();
        final int cpn = ga.getCurrentPlayerNumber();

        SOCPlayer cp = ga.getPlayer(cpn);
        if (cp.hasAskedSpecialBuild())
        {
            cp.setAskedSpecialBuild(false);
            messageToGame(gaName, new SOCPlayerElement(gaName, cpn, SOCPlayerElement.SET, SOCPlayerElement.ASK_SPECIAL_BUILD, 0));
        }

        final SOCForceEndTurnResult res = ga.forceEndTurn();
            // State now hopefully PLAY1, or SPECIAL_BUILDING;
            // also could be initial placement (START1A or START2A).
        if (SOCGame.OVER == ga.getGameState())
            return false;  // <--- Early return: All players have left ---

        /**
         * Report any resources lost or gained.
         * See also forceGamePlayerDiscard for same reporting code.
         */
        SOCResourceSet resGainLoss = res.getResourcesGainedLost();
        if (resGainLoss != null)
        {
            /**
             * If returning resources to player (not discarding), report actual types/amounts.
             * For discard, tell the discarding player's client that they discarded the resources,
             * tell everyone else that the player discarded unknown resources.
             */
            if (! res.isLoss())
                reportRsrcGainLoss(gaName, resGainLoss, false, true, cpn, -1, null, null);
            else
            {
                StringConnection c = getConnection(plName);
                if ((c != null) && c.isConnected())
                    reportRsrcGainLoss(gaName, resGainLoss, true, true, cpn, -1, null, c);
                int totalRes = resGainLoss.getTotal();
                messageToGameExcept
                    (gaName, c, new SOCPlayerElement
                        (gaName, cpn, SOCPlayerElement.LOSE, SOCPlayerElement.UNKNOWN, totalRes, true), true);
                messageToGame(gaName, plName + " discarded " + totalRes + " resources.");
            }
        }

        /**
         * report any dev-card returned to player's hand
         */
        int card = res.getDevCardType();
        if (card != -1)
        {
            StringConnection c = getConnection(plName);
            if ((c != null) && c.isConnected())
                messageToPlayer(c, new SOCDevCard(gaName, cpn, SOCDevCard.ADDOLD, card));
            messageToGameExcept(gaName, c, new SOCDevCard(gaName, cpn, SOCDevCard.ADDOLD, SOCDevCardConstants.UNKNOWN), true);                       
            messageToGame(gaName, plName + "'s just-played development card was returned.");            
        }

        /**
         * For initial placements, we don't end turns as normal.
         * (Player number may go forward or backwards, new state isn't PLAY, etc.)
         * Update clients' gamestate, but don't call endGameTurn.
         */
        final int forceRes = res.getResult(); 
        if ((forceRes == SOCForceEndTurnResult.FORCE_ENDTURN_SKIP_START_ADV)
            || (forceRes == SOCForceEndTurnResult.FORCE_ENDTURN_SKIP_START_ADVBACK))
        {
            if (res.didUpdateFP() || res.didUpdateLP())
            {
                // will cause clients to recalculate lastPlayer too
                messageToGame(gaName, new SOCFirstPlayer(gaName, ga.getFirstPlayer()));
            }
            sendGameState(ga, false);
            sendTurn(ga, false);
            return true;  // <--- Early return ---
        }

        /**
         * If the turn can now end, proceed as if player requested it.
         * Otherwise, send current gamestate.  We'll all wait for other
         * players to send discard messages, and afterwards this turn can end.
         */
        if (ga.canEndTurn(cpn))
            endGameTurn(ga, null);  // could force gamestate to OVER, if a client leaves
        else
            sendGameState(ga, false);

        return (ga.getGameState() != SOCGame.OVER);
    }

    /**
     * handle "choose player" message during robbery
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleCHOOSEPLAYER(StringConnection c, SOCChoosePlayer mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    if (checkTurn(c, ga))
                    {
                        if (ga.canChoosePlayer(mes.getChoice()))
                        {
                            int rsrc = ga.stealFromPlayer(mes.getChoice());
                            reportRobbery(ga, ga.getPlayer(c.getData()), ga.getPlayer(mes.getChoice()), rsrc);
                            sendGameState(ga);
                        }
                        else
                        {
                            messageToPlayer(c, ga.getName(), "You can't steal from that player.");
                        }
                    }
                    else
                    {
                        messageToPlayer(c, ga.getName(), "It's not your turn.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "make offer" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleMAKEOFFER(StringConnection c, SOCMakeOffer mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                final String gaName = ga.getName();
                if (ga.isGameOptionSet("NT"))
                {
                    messageToPlayer(c, gaName, "Trading is not allowed in this game.");
                    return;  // <---- Early return: No Trading ----
                }

                ga.takeMonitor();

                try
                {
                    SOCTradeOffer offer = mes.getOffer();

                    /**
                     * remake the offer with data that we know is accurate,
                     * namely the 'from' datum
                     */
                    SOCPlayer player = ga.getPlayer(c.getData());

                    /**
                     * announce the offer, including text message similar to bank/port trade.
                     */
                    if (player != null)
                    {
                        SOCTradeOffer remadeOffer;
                        {
                            SOCResourceSet offGive = offer.getGiveSet(),
                                           offGet  = offer.getGetSet();
                            remadeOffer = new SOCTradeOffer(gaName, player.getPlayerNumber(), offer.getTo(), offGive, offGet);
                            player.setCurrentOffer(remadeOffer);
                            StringBuffer offMsgText = new StringBuffer(c.getData());
                            offMsgText.append(" offered to give ");
                            offGive.toFriendlyString(offMsgText);
                            offMsgText.append(" for ");
                            offGet.toFriendlyString(offMsgText);
                            offMsgText.append('.');
                            messageToGame(gaName, offMsgText.toString() );
                        }

                        SOCMakeOffer makeOfferMessage = new SOCMakeOffer(gaName, remadeOffer);
                        messageToGame(gaName, makeOfferMessage);

                        recordGameEvent(gaName, makeOfferMessage.toCmd());

                        /**
                         * clear all the trade messages because a new offer has been made
                         */
                        gameList.takeMonitorForGame(gaName);
                        if (ga.clientVersionLowest >= SOCClearTradeMsg.VERSION_FOR_CLEAR_ALL)
                        {
                            messageToGameWithMon(gaName, new SOCClearTradeMsg(gaName, -1));            
                        } else {
                            for (int i = 0; i < ga.maxPlayers; i++)
                                messageToGameWithMon(gaName, new SOCClearTradeMsg(gaName, i));
                        }
                        gameList.releaseMonitorForGame(gaName);
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "clear offer" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleCLEAROFFER(StringConnection c, SOCClearOffer mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    final String gaName = ga.getName();
                    ga.getPlayer(c.getData()).setCurrentOffer(null);
                    messageToGame(gaName, new SOCClearOffer(gaName, ga.getPlayer(c.getData()).getPlayerNumber()));
                    recordGameEvent(mes.getGame(), mes.toCmd());

                    /**
                     * clear all the trade messages
                     */
                    gameList.takeMonitorForGame(gaName);
                    if (ga.clientVersionLowest >= SOCClearTradeMsg.VERSION_FOR_CLEAR_ALL)
                    {
                        messageToGameWithMon(gaName, new SOCClearTradeMsg(gaName, -1));            
                    } else {
                        for (int i = 0; i < ga.maxPlayers; i++)
                            messageToGameWithMon(gaName, new SOCClearTradeMsg(gaName, i));
                    }
                    gameList.releaseMonitorForGame(gaName);
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "reject offer" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleREJECTOFFER(StringConnection c, SOCRejectOffer mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                SOCPlayer player = ga.getPlayer(c.getData());

                if (player != null)
                {
                    final String gaName = ga.getName();
                    SOCRejectOffer rejectMessage = new SOCRejectOffer(gaName, player.getPlayerNumber());
                    messageToGame(gaName, rejectMessage);

                    recordGameEvent(gaName, rejectMessage.toCmd());
                }
            }
        }
    }

    /**
     * handle "accept offer" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleACCEPTOFFER(StringConnection c, SOCAcceptOffer mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    SOCPlayer player = ga.getPlayer(c.getData());

                    if (player != null)
                    {
                        final int acceptingNumber = player.getPlayerNumber();
                        final int offeringNumber = mes.getOfferingNumber();
                        final String gaName = ga.getName();

                        if (ga.canMakeTrade(offeringNumber, acceptingNumber))
                        {
                            ga.makeTrade(offeringNumber, acceptingNumber);
                            reportTrade(ga, offeringNumber, acceptingNumber);

                            recordGameEvent(mes.getGame(), mes.toCmd());

                            /**
                             * clear all offers
                             */
                            for (int i = 0; i < ga.maxPlayers; i++)
                            {
                                ga.getPlayer(i).setCurrentOffer(null);
                            }
                            gameList.takeMonitorForGame(gaName);
                            if (ga.clientVersionLowest >= SOCClearOffer.VERSION_FOR_CLEAR_ALL)
                            {
                                messageToGameWithMon(gaName, new SOCClearOffer(gaName, -1));            
                            } else {
                                for (int i = 0; i < ga.maxPlayers; i++)
                                    messageToGameWithMon(gaName, new SOCClearOffer(gaName, i));
                            }
                            gameList.releaseMonitorForGame(gaName);

                            /**
                             * send a message to the bots that the offer was accepted
                             */
                            messageToGame(gaName, mes);
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "You can't make that trade.");
                        }
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "bank trade" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleBANKTRADE(StringConnection c, SOCBankTrade mes)
    {
        if (c != null)
        {
            final String gaName = mes.getGame();
            SOCGame ga = gameList.getGameData(gaName);

            if (ga != null)
            {
                final SOCResourceSet give = mes.getGiveSet(),
                    get = mes.getGetSet();

                ga.takeMonitor();

                try
                {
                    if (checkTurn(c, ga))
                    {
                        if (ga.canMakeBankTrade(give, get))
                        {
                            ga.makeBankTrade(give, get);
                            reportBankTrade(ga, give, get);

                            final int cpn = ga.getCurrentPlayerNumber();
                            final SOCPlayer cpl = ga.getPlayer(cpn);
                            if (cpl.isRobot())
                                c.put(SOCSimpleAction.toCmd(gaName, cpn, SOCSimpleAction.TRADE_SUCCESSFUL, 0, 0));
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "You can't make that trade.");
                            SOCClientData scd = (SOCClientData) c.getAppData();
                            if ((scd != null) && scd.isRobot)
                                D.ebugPrintln("ILLEGAL BANK TRADE: " + c.getData()
                                  + ": give " + give + ", get " + get);
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "It's not your turn.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "build request" message.
     * If client is current player, they want to buy a {@link SOCPlayingPiece}.
     * Otherwise, if 6-player board, they want to build during the
     * {@link SOCGame#SPECIAL_BUILDING Special Building Phase}.
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleBUILDREQUEST(StringConnection c, SOCBuildRequest mes)
    {
        if (c == null)
            return;
        SOCGame ga = gameList.getGameData(mes.getGame());
        if (ga == null)
            return;

        final String gaName = ga.getName();
        ga.takeMonitor();

        try
        {
            final boolean isCurrent = checkTurn(c, ga);
            SOCPlayer player = ga.getPlayer(c.getData());
            final int pn = player.getPlayerNumber();
            final int pieceType = mes.getPieceType();
            boolean sendDenyReply = false;  // for robots' benefit

            if (isCurrent)
            {
                if ((ga.getGameState() == SOCGame.PLAY1) || (ga.getGameState() == SOCGame.SPECIAL_BUILDING))
                {
                    switch (pieceType)
                    {
                    case SOCPlayingPiece.ROAD:

                        if (ga.couldBuildRoad(pn))
                        {
                            ga.buyRoad(pn);
                            messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.CLAY, 1));
                            messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.WOOD, 1));
                            sendGameState(ga);
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "You can't build a road.");
                            sendDenyReply = true;
                        }

                        break;

                    case SOCPlayingPiece.SETTLEMENT:

                        if (ga.couldBuildSettlement(pn))
                        {
                            ga.buySettlement(pn);
                            gameList.takeMonitorForGame(gaName);
                            messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.CLAY, 1));
                            messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.SHEEP, 1));
                            messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.WHEAT, 1));
                            messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.WOOD, 1));
                            gameList.releaseMonitorForGame(gaName);
                            sendGameState(ga);
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "You can't build a settlement.");
                            sendDenyReply = true;
                        }

                        break;

                    case SOCPlayingPiece.CITY:

                        if (ga.couldBuildCity(pn))
                        {
                            ga.buyCity(pn);
                            messageToGame(ga.getName(), new SOCPlayerElement(ga.getName(), pn, SOCPlayerElement.LOSE, SOCPlayerElement.ORE, 3));
                            messageToGame(ga.getName(), new SOCPlayerElement(ga.getName(), pn, SOCPlayerElement.LOSE, SOCPlayerElement.WHEAT, 2));
                            sendGameState(ga);
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "You can't build a city.");
                            sendDenyReply = true;
                        }

                        break;
                    }
                }
                else if (pieceType == -1)
                {
                    // 6-player board: Special Building Phase
                    // during start of own turn
                    try
                    {
                        ga.askSpecialBuild(pn, true);
                        messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.SET, SOCPlayerElement.ASK_SPECIAL_BUILD, 1));
                        endGameTurn(ga, player);  // triggers start of SBP
                    } catch (IllegalStateException e) {
                        messageToPlayer(c, gaName, "You can't ask to build now.");
                        sendDenyReply = true;
                    }
                }
                else
                {
                    messageToPlayer(c, gaName, "You can't build now.");
                    sendDenyReply = true;
                }
            }
            else
            {
                if (ga.maxPlayers <= 4)
                {
                    messageToPlayer(c, gaName, "It's not your turn.");
                    sendDenyReply = true;
                } else {
                    // 6-player board: Special Building Phase
                    // during other player's turn
                    try
                    {
                        ga.askSpecialBuild(pn, true);  // will validate that they can build now
                        messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.SET, SOCPlayerElement.ASK_SPECIAL_BUILD, 1));
                    } catch (IllegalStateException e) {
                        messageToPlayer(c, gaName, "You can't ask to build now.");
                        sendDenyReply = true;
                    }
                }
            }

            if (sendDenyReply && ga.getPlayer(pn).isRobot())
            {
                messageToPlayer(c, new SOCCancelBuildRequest(gaName, pieceType));
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception caught at handleBUILDREQUEST");
        }

        ga.releaseMonitor();
    }

    /**
     * handle "cancel build request" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleCANCELBUILDREQUEST(StringConnection c, SOCCancelBuildRequest mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    final String gaName = ga.getName();
                    if (checkTurn(c, ga))
                    {
                        final SOCPlayer player = ga.getPlayer(c.getData());
                        final int pn = player.getPlayerNumber();
                        final int gstate = ga.getGameState();

                        switch (mes.getPieceType())
                        {
                        case SOCPlayingPiece.ROAD:

                            if ((gstate == SOCGame.PLACING_ROAD) || (gstate == SOCGame.PLACING_FREE_ROAD2))
                            {
                                ga.cancelBuildRoad(pn);
                                if (gstate == SOCGame.PLACING_ROAD)
                                {
                                    messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.CLAY, 1));
                                    messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.WOOD, 1));
                                } else {
                                    messageToGame(gaName, player.getName() + " skipped placing the second road.");
                                }
                                sendGameState(ga);
                            }
                            else
                            {
                                messageToPlayer(c, gaName, "You didn't buy a road.");
                            }

                            break;

                        case SOCPlayingPiece.SETTLEMENT:

                            if (gstate == SOCGame.PLACING_SETTLEMENT)
                            {
                                ga.cancelBuildSettlement(pn);
                                gameList.takeMonitorForGame(gaName);
                                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.CLAY, 1));
                                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.SHEEP, 1));
                                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.WHEAT, 1));
                                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.WOOD, 1));
                                gameList.releaseMonitorForGame(gaName);
                                sendGameState(ga);
                            }
                            else if ((gstate == SOCGame.START1B) || (gstate == SOCGame.START2B))
                            {
                                SOCSettlement pp = new SOCSettlement(player, player.getLastSettlementCoord(), null);
                                ga.undoPutInitSettlement(pp);
                                messageToGame(gaName, mes);  // Re-send to all clients to announce it
                                    // (Safe since we've validated all message parameters)
                                messageToGame(gaName, player.getName() + " cancelled this settlement placement.");
                                sendGameState(ga);  // This send is redundant, if client reaction changes game state
                            }
                            else
                            {
                                messageToPlayer(c, gaName, "You didn't buy a settlement.");
                            }

                            break;

                        case SOCPlayingPiece.CITY:

                            if (gstate == SOCGame.PLACING_CITY)
                            {
                                ga.cancelBuildCity(pn);
                                messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.ORE, 3));
                                messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.GAIN, SOCPlayerElement.WHEAT, 2));
                                sendGameState(ga);
                            }
                            else
                            {
                                messageToPlayer(c, gaName, "You didn't buy a city.");
                            }

                            break;

                        default:
                            throw new IllegalArgumentException("Unknown piece type " + mes.getPieceType());
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "It's not your turn.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "buy card request" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleBUYCARDREQUEST(StringConnection c, SOCBuyCardRequest mes)
    {
        if (c == null)
            return;
        SOCGame ga = gameList.getGameData(mes.getGame());
        if (ga == null)
            return;

        ga.takeMonitor();

        try
        {
            final String gaName = ga.getName();
            SOCPlayer player = ga.getPlayer(c.getData());
            final int pn = player.getPlayerNumber();
            boolean sendDenyReply = false;  // for robots' benefit

            if (checkTurn(c, ga))
            {
                if (((ga.getGameState() == SOCGame.PLAY1) || (ga.getGameState() == SOCGame.SPECIAL_BUILDING))
                    && (ga.couldBuyDevCard(pn)))
                {
                    int card = ga.buyDevCard();
                    gameList.takeMonitorForGame(gaName);
                    messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.ORE, 1));
                    messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.SHEEP, 1));
                    messageToGameWithMon(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.LOSE, SOCPlayerElement.WHEAT, 1));
                    messageToGameWithMon(gaName, new SOCDevCardCount(gaName, ga.getNumDevCards()));
                    gameList.releaseMonitorForGame(gaName);
                    messageToPlayer(c, new SOCDevCard(gaName, pn, SOCDevCard.DRAW, card));

                    messageToGameExcept(gaName, c, new SOCDevCard(gaName, pn, SOCDevCard.DRAW, SOCDevCardConstants.UNKNOWN), true);

                    final int remain = ga.getNumDevCards();
                    final SOCSimpleAction actmsg = new SOCSimpleAction
                        (gaName, pn, SOCSimpleAction.DEVCARD_BOUGHT, remain, 0);

                    if (ga.clientVersionLowest >= SOCSimpleAction.VERSION_FOR_SIMPLEACTION)
                    {
                        messageToGame(gaName, actmsg);
                    } else {
                        gameList.takeMonitorForGame(gaName);

                        messageToGameForVersions
                            (ga, SOCSimpleAction.VERSION_FOR_SIMPLEACTION, Integer.MAX_VALUE, actmsg, false);

                        // Only pre-1.1.19 clients will see the game text messages
                        messageToGameForVersions(ga, -1, SOCSimpleAction.VERSION_FOR_SIMPLEACTION - 1,
                            new SOCGameTextMsg(gaName, SERVERNAME, c.getData() + " bought a development card."),
                            false);

                        final String remainTxt;
                        switch (ga.getNumDevCards())
                        {
                        case 1:  remainTxt = "There is 1 card left.";  break;
                        case 0:  remainTxt = "There are no more Development cards.";  break;
                        default: remainTxt = "There are " + ga.getNumDevCards() + " cards left.";
                        }
                        messageToGameForVersions(ga, -1, SOCSimpleAction.VERSION_FOR_SIMPLEACTION - 1,
                            new SOCGameTextMsg(gaName, SERVERNAME, remainTxt), false);

                        gameList.releaseMonitorForGame(gaName);
                    }

                    sendGameState(ga);
                }
                else
                {
                    if (ga.getNumDevCards() == 0)
                    {
                        messageToPlayer(c, gaName, "There are no more Development cards.");
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "You can't buy a development card now.");
                    }
                    sendDenyReply = true;
                }
            }
            else
            {
                if (ga.maxPlayers <= 4)
                {
                    messageToPlayer(c, gaName, "It's not your turn.");
                } else {
                    // 6-player board: Special Building Phase
                    try
                    {
                        ga.askSpecialBuild(pn, true);
                        messageToGame(gaName, new SOCPlayerElement(gaName, pn, SOCPlayerElement.SET, SOCPlayerElement.ASK_SPECIAL_BUILD, 1));
                    } catch (IllegalStateException e) {
                        messageToPlayer(c, gaName, "You can't ask to buy a card now.");
                    }
                }
                sendDenyReply = true;
            }

            if (sendDenyReply && ga.getPlayer(pn).isRobot())
            {
                messageToPlayer(c, new SOCCancelBuildRequest(gaName, -2));
            }
        }
        catch (Exception e)
        {
            D.ebugPrintStackTrace(e, "Exception caught");
        }

        ga.releaseMonitor();
    }

    /**
     * handle "play development card request" message
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handlePLAYDEVCARDREQUEST(StringConnection c, SOCPlayDevCardRequest mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    final String gaName = ga.getName();
                    String denyText = null;  // if player can't play right now, send this

                    if (checkTurn(c, ga))
                    {
                        SOCPlayer player = ga.getPlayer(c.getData());

                        switch (mes.getDevCard())
                        {
                        case SOCDevCardConstants.KNIGHT:

                            if (ga.canPlayKnight(player.getPlayerNumber()))
                            {
                                ga.playKnight();
                                gameList.takeMonitorForGame(gaName);
                                messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, player.getName() + " played a Soldier card."));
                                messageToGameWithMon(gaName, new SOCDevCard(gaName, player.getPlayerNumber(), SOCDevCard.PLAY, SOCDevCardConstants.KNIGHT));
                                messageToGameWithMon(gaName, new SOCSetPlayedDevCard(gaName, player.getPlayerNumber(), true));
                                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, player.getPlayerNumber(), SOCPlayerElement.GAIN, SOCPlayerElement.NUMKNIGHTS, 1));
                                gameList.releaseMonitorForGame(gaName);
                                broadcastGameStats(ga);
                                sendGameState(ga);
                            }
                            else
                            {
                                denyText = "You can't play a Soldier card now.";
                            }

                            break;

                        case SOCDevCardConstants.ROADS:

                            if (ga.canPlayRoadBuilding(player.getPlayerNumber()))
                            {
                                ga.playRoadBuilding();
                                gameList.takeMonitorForGame(gaName);
                                messageToGameWithMon(gaName, new SOCDevCard(gaName, player.getPlayerNumber(), SOCDevCard.PLAY, SOCDevCardConstants.ROADS));
                                messageToGameWithMon(gaName, new SOCSetPlayedDevCard(gaName, player.getPlayerNumber(), true));
                                messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, player.getName() + " played a Road Building card."));
                                gameList.releaseMonitorForGame(gaName);
                                sendGameState(ga);
                                if (ga.getGameState() == SOCGame.PLACING_FREE_ROAD1)
                                {
                                    messageToPlayer(c, gaName, "You may place 2 roads.");
                                }
                                else
                                {
                                    messageToPlayer(c, gaName, "You may place your 1 remaining road.");
                                }
                            }
                            else
                            {
                                denyText = "You can't play a Road Building card now.";
                            }

                            break;

                        case SOCDevCardConstants.DISC:

                            if (ga.canPlayDiscovery(player.getPlayerNumber()))
                            {
                                ga.playDiscovery();
                                gameList.takeMonitorForGame(gaName);
                                messageToGameWithMon(gaName, new SOCDevCard(gaName, player.getPlayerNumber(), SOCDevCard.PLAY, SOCDevCardConstants.DISC));
                                messageToGameWithMon(gaName, new SOCSetPlayedDevCard(gaName, player.getPlayerNumber(), true));
                                messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, player.getName() + " played a Year of Plenty card."));
                                gameList.releaseMonitorForGame(gaName);
                                sendGameState(ga);
                            }
                            else
                            {
                                denyText = "You can't play a Year of Plenty card now.";
                            }

                            break;

                        case SOCDevCardConstants.MONO:

                            if (ga.canPlayMonopoly(player.getPlayerNumber()))
                            {
                                ga.playMonopoly();
                                gameList.takeMonitorForGame(gaName);
                                messageToGameWithMon(gaName, new SOCDevCard(gaName, player.getPlayerNumber(), SOCDevCard.PLAY, SOCDevCardConstants.MONO));
                                messageToGameWithMon(gaName, new SOCSetPlayedDevCard(gaName, player.getPlayerNumber(), true));
                                messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, player.getName() + " played a Monopoly card."));
                                gameList.releaseMonitorForGame(gaName);
                                sendGameState(ga);
                            }
                            else
                            {
                                denyText = "You can't play a Monopoly card now.";
                            }

                            break;

                        // VP cards are secretly played when bought.
                        // (case SOCDevCardConstants.CAP, LIB, UNIV, TEMP, TOW):
                        // If player clicks "Play Card" the message is handled at the
                        // client, in SOCHandPanel.actionPerformed case CARD.
                        //  "You secretly played this VP card when you bought it."
                        //  break;

                        default:
                            denyText = "That card type is unknown.";
                            D.ebugPrintln("* SOCServer.handlePLAYDEVCARDREQUEST: asked to play unhandled type " + mes.getDevCard());

                        }
                    }
                    else
                    {
                        denyText = "It's not your turn.";
                    }

                    if (denyText != null)
                    {
                        final SOCClientData scd = (SOCClientData) c.getAppData();
                        if ((scd == null) || ! scd.isRobot)
                            messageToPlayer(c, gaName, denyText);
                        else
                            messageToPlayer(c, new SOCDevCard(gaName, -1, SOCDevCard.CANNOT_PLAY, mes.getDevCard()));
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "discovery pick" message (while playing Discovery card)
     *
     * @param c  the connection that sent the message
     * @param mes  the message
     */
    private void handleDISCOVERYPICK(StringConnection c, SOCDiscoveryPick mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    final String gaName = ga.getName();
                    if (checkTurn(c, ga))
                    {
                        SOCPlayer player = ga.getPlayer(c.getData());

                        if (ga.canDoDiscoveryAction(mes.getResources()))
                        {
                            ga.doDiscoveryAction(mes.getResources());

                            StringBuffer message = new StringBuffer(c.getData());
                            message.append(" received ");
                            reportRsrcGainLoss
                                (gaName, mes.getResources(), false, false, player.getPlayerNumber(), -1, message, null);
                            message.append(" from the bank.");
                            messageToGame(gaName, message.toString());
                            sendGameState(ga);
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "That is not a legal Year of Plenty pick.");
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "It's not your turn.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * handle "monopoly pick" message
     *
     * @param c     the connection that sent the message
     * @param mes   the message
     */
    private void handleMONOPOLYPICK(StringConnection c, SOCMonopolyPick mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                ga.takeMonitor();

                try
                {
                    final String gaName = ga.getName();
                    if (checkTurn(c, ga))
                    {
                        if (ga.canDoMonopolyAction())
                        {
                            int[] monoPicks = ga.doMonopolyAction(mes.getResource());
                            final boolean[] isVictim = new boolean[ga.maxPlayers];
                            final String monoPlayerName = c.getData();
                            int monoTotal = 0;
                            for (int pn = 0; pn < ga.maxPlayers; ++pn)
                            {
                                final int n = monoPicks[pn];
                                if (n > 0)
                                {
                                    monoTotal += n;
                                    isVictim[pn] = true;
                                }
                            }
                            final String resName
                                = " " + SOCResourceConstants.resName(mes.getResource()) + ".";
                            String message = monoPlayerName + " monopolized " + monoTotal + resName;

                            gameList.takeMonitorForGame(gaName);
                            messageToGameExcept(gaName, c, new SOCGameTextMsg(gaName, SERVERNAME, message), false);

                            /**
                             * just send all the player's resource counts for the monopolized resource;
                             * set isBad flag for each victim player's count
                             */
                            for (int pn = 0; pn < ga.maxPlayers; ++pn)
                            {
                                /**
                                 * Note: This works because SOCPlayerElement.CLAY == SOCResourceConstants.CLAY
                                 */
                                messageToGameWithMon
                                    (gaName, new SOCPlayerElement
                                        (gaName, pn, SOCPlayerElement.SET,
                                         mes.getResource(), ga.getPlayer(pn).getResources().getAmount(mes.getResource()), isVictim[pn]));
                            }
                            gameList.releaseMonitorForGame(gaName);

                            /**
                             * now that monitor is released, notify the
                             * victim(s) of resource amounts taken,
                             * and tell the player how many they won.
                             */
                            for (int pn = 0; pn < ga.maxPlayers; ++pn)
                            {
                                if (! isVictim[pn])
                                    continue;
                                int picked = monoPicks[pn];
                                String viName = ga.getPlayer(pn).getName();
                                StringConnection viCon = getConnection(viName);
                                if (viCon != null)
                                    messageToPlayer(viCon, gaName,
                                        monoPlayerName + "'s Monopoly took your " + picked + resName);
                            }

                            messageToPlayer(c, gaName, "You monopolized " + monoTotal + resName);
                            sendGameState(ga);
                        }
                        else
                        {
                            messageToPlayer(c, gaName, "You can't do a Monopoly pick now.");
                        }
                    }
                    else
                    {
                        messageToPlayer(c, gaName, "It's not your turn.");
                    }
                }
                catch (Exception e)
                {
                    D.ebugPrintStackTrace(e, "Exception caught");
                }

                ga.releaseMonitor();
            }
        }
    }

    /**
     * Handle the "simple request" message.
     * @param c  the connection
     * @param mes  the message
     * @since 1.1.18
     */
    private void handleSIMPLEREQUEST(StringConnection c, SOCSimpleRequest mes)
    {
        if (c == null)
            return;

        final String gaName = mes.getGame();
        SOCGame ga = gameList.getGameData(gaName);
        if (ga == null)
            return;
        SOCPlayer clientPl = ga.getPlayer(c.getData());
        if (clientPl == null)
            return;

        final int pn = mes.getPlayerNumber();
        final boolean clientIsPN = (pn == clientPl.getPlayerNumber());  // probably required for most request types
        final int reqtype = mes.getRequestType();

        switch (reqtype)
        {
        // None used in v1.1.18, so no cases
        //    (case SOCSimpleRequest.SC_PIRI_FORT_ATTACK, etc)

        default:
            // deny unknown types
            c.put(SOCSimpleRequest.toCmd(gaName, -1, reqtype, 0, 0));
            System.err.println
                ("handleSIMPLEREQUEST: Unknown type " + reqtype + " from " + c.getData() + " in game " + ga);
        }
    }

    /**
     * handle "change face" message
     *
     * @param c  the connection
     * @param mes  the message
     */
    private void handleCHANGEFACE(StringConnection c, SOCChangeFace mes)
    {
        if (c != null)
        {
            SOCGame ga = gameList.getGameData(mes.getGame());

            if (ga != null)
            {
                SOCPlayer player = ga.getPlayer(c.getData());

                if (player != null)
                {
                    final String gaName = mes.getGame();
                    final int id = mes.getFaceId();
                    if ((id <= 0) && ! player.isRobot())
                        return;  // only bots should use bot icons

                    player.setFaceId(id);
                    messageToGame(gaName, new SOCChangeFace(gaName, player.getPlayerNumber(), id));
                }
            }
        }
    }

    /**
     * handle "set seat lock" message
     *
     * @param c  the connection
     * @param mes  the message
     */
    private void handleSETSEATLOCK(StringConnection c, SOCSetSeatLock mes)
    {
        if (c != null)
        {
            final String gaName = mes.getGame();
            SOCGame ga = gameList.getGameData(gaName);

            if (ga != null)
            {
                SOCPlayer player = ga.getPlayer(c.getData());
                if (player == null)
                    return;

                try
                {
                    if (mes.getLockState() == true)
                    {
                        ga.lockSeat(mes.getPlayerNumber());
                    }
                    else
                    {
                        ga.unlockSeat(mes.getPlayerNumber());
                    }

                    messageToGame(mes.getGame(), mes);
                }
                catch (IllegalStateException e) {
                    messageToPlayer(c, gaName, "Cannot set that lock right now." );
                }
            }
        }
    }

    /**
     * handle "reset-board request" message.
     * If multiple human players, start a vote.
     * Otherwise, reset the game to a copy with
     * same name and (copy of) same players, new layout.
     *<P>
     * The requesting player doesn't vote, but server still
     * sends the vote-request-message, to tell that client their
     * request was accepted and voting has begun.
     *<P>
     * If only one player remains (all other humans have left at end),
     * ask them to start a new game instead. This is a rare occurrence
     * and we shouldn't bring in new robots and all,
     * since we already have an interface to set up a game.
     *<P>
     * If any human player's client is too old to vote for reset,
     * assume they vote yes.
     *
     * @see #resetBoardAndNotify(String, int)
     *
     * @param c  the connection
     * @param mes  the message
     */
    private void handleRESETBOARDREQUEST(StringConnection c, SOCResetBoardRequest mes)
    {
        if (c == null)
            return;
        String gaName = mes.getGame();
        SOCGame ga = gameList.getGameData(gaName);
        if (ga == null)
            return;        
        SOCPlayer reqPlayer = ga.getPlayer(c.getData());
        if (reqPlayer == null)
        {
            return;  // Not playing in that game (Security)
        }
        
        /**
         * Is voting already active from another player?
         * Or, has this player already asked for voting this turn?
         */
        if (ga.getResetVoteActive() || reqPlayer.hasAskedBoardReset())
        {
            // Ignore this second request. Can't send REJECT because
            // that would end the already-active round of voting.
            return;
        }
        
        /**
         * Is there more than one human player?
         * Grab connection information for humans and robots.
         */
        StringConnection[] humanConns = new StringConnection[ga.maxPlayers];
        StringConnection[] robotConns = new StringConnection[ga.maxPlayers];
        int numHuman = SOCGameBoardReset.sortPlayerConnections
            (ga, null, gameList.getMembers(gaName), humanConns, robotConns);

        final int reqPN = reqPlayer.getPlayerNumber();
        if (numHuman < 2)
        {
            // Are there robots? Go ahead and reset if so.
            boolean hadRobot = false, hadUnlockedRobot = false;
            for (int i = robotConns.length-1; i>=0; --i)
            {
                if (robotConns[i] != null)
                {
                    hadRobot = true;
                    if (! ga.isSeatLocked(i))
                    {
                        hadUnlockedRobot = true;
                        break;
                    }
                }
            }
            if (hadUnlockedRobot)
            {
                resetBoardAndNotify(gaName, reqPN);
            } else if (hadRobot) {
                messageToPlayer(c, gaName, "Please unlock at least one bot, so you will have an opponent.");
            } else {
                messageToGameUrgent(gaName, "Everyone has left this game. Please start a new game with players or bots.");
            }
        }
        else
        {
            // Probably put it to a vote.
            gameList.takeMonitorForGame(gaName);

            // First, Count number of other players who can vote (connected, version chk)
            int votingPlayers = 0;
            for (int i = ga.maxPlayers - 1; i>=0; --i)
            {
                if ((i != reqPN) && ! ga.isSeatVacant(i))
                {
                    StringConnection pc = getConnection(ga.getPlayer(i).getName());
                    if ((pc != null) && pc.isConnected() && pc.getVersion() >= 1100)                        
                    {
                         ++votingPlayers;                        
                    }
                }
            }

            if (votingPlayers == 0)
            {
                // No one else is capable of voting.
                // Reset the game immediately.
                messageToGameWithMon(gaName, new SOCGameTextMsg
                    (gaName, SERVERNAME, ">>> " + c.getData()
                     + " is resetting the game - other connected players are unable to vote (client too old)."));
                gameList.releaseMonitorForGame(gaName);
                resetBoardAndNotify(gaName, reqPN);
            }
            else
            {
                // Put it to a vote
                messageToGameWithMon(gaName, new SOCGameTextMsg
                    (gaName, SERVERNAME, c.getData() + " requests a board reset - other players please vote."));
                String vrCmd = SOCResetBoardVoteRequest.toCmd(gaName, reqPN);
                ga.resetVoteBegin(reqPN);
                gameList.releaseMonitorForGame(gaName);
                for (int i = 0; i < ga.maxPlayers; ++i)
                    if (humanConns[i] != null)
                    {
                        if (humanConns[i].getVersion() >= 1100)
                            humanConns[i].put(vrCmd);
                        else
                            ga.resetVoteRegister
                                (ga.getPlayer(humanConns[i].getData()).getPlayerNumber(), true);
                    }
            }
        }
    }

    /**
     * handle message of player's vote for a "reset-board" request.
     * Register the player's vote.
     * If all votes have now arrived, and the vote is unanimous,
     * reset the game to a copy with same name and players, new layout.
     *
     * @see #resetBoardAndNotify(String, int)
     *
     * @param c  the connection
     * @param mes  the message
     */
    private void handleRESETBOARDVOTE(StringConnection c, SOCResetBoardVote mes)
    {
        if (c == null)
            return;
        SOCGame ga = gameList.getGameData(mes.getGame());
        if (ga == null)
            return;
        final String plName = c.getData();
        SOCPlayer reqPlayer = ga.getPlayer(plName);
        if (reqPlayer == null)
        {
            return;  // Not playing in that game (Security)
        }

        // Register this player's vote, and let game members know.
        // If vote succeeded, go ahead and reset the game.
        // If vote rejected, let everyone know.

        resetBoardVoteNotifyOne(ga, reqPlayer.getPlayerNumber(), plName, mes.getPlayerVote());                
    }

    /**
     * "Reset-board" request: Register one player's vote, and let game members know.
     * If vote succeeded, go ahead and reset the game.
     * If vote rejected, let everyone know.
     *
     * @param ga      Game for this reset vote
     * @param pn      Player number who is voting
     * @param plName  Name of player who is voting
     * @param vyes    Player's vote, Yes or no
     */
    private void resetBoardVoteNotifyOne(SOCGame ga, final int pn, final String plName, final boolean vyes)
    {
        boolean votingComplete = false;

        final String gaName = ga.getName();
        try
        {
            // Register in game
            votingComplete = ga.resetVoteRegister(pn, vyes);
            // Tell other players
            messageToGame (gaName, new SOCResetBoardVote(gaName, pn, vyes));
        }
        catch (IllegalArgumentException e)
        {
            D.ebugPrintln("*Error in player voting: game " + gaName + ": " + e);
            return;
        }
        catch (IllegalStateException e)
        {
            D.ebugPrintln("*Voting not active: game " + gaName);
            return;
        }

        if (! votingComplete)
        {
            return;
        }
        
        if (ga.getResetVoteResult())
        {
            // Vote succeeded - Go ahead and reset.
            resetBoardAndNotify(gaName, ga.getResetVoteRequester());
        }
        else
        {
            // Vote rejected - Let everyone know.
            messageToGame(gaName, new SOCResetBoardReject(gaName));
        }
    }

    /**
     * process the "game option get defaults" message.
     * Responds to client by sending {@link SOCGameOptionGetDefaults GAMEOPTIONGETDEFAULTS}.
     * All of server's known options are sent, except empty string-valued options. 
     * Depending on client version, server's response may include option names that
     * the client is too old to use; the client is able to ignore them.
     * @param c  the connection
     * @param mes  the message
     * @since 1.1.07
     */
    private void handleGAMEOPTIONGETDEFAULTS(StringConnection c, SOCGameOptionGetDefaults mes)
    {
        if (c == null)
            return;
        c.put(SOCGameOptionGetDefaults.toCmd
              (SOCGameOption.packKnownOptionsToString(true)));
    }

    /**
     * process the "game option get infos" message; reply with the info, with
     * one {@link SOCGameOptionInfo GAMEOPTIONINFO} message per option keyname.
     * Mark the end of the option list with {@link SOCGameOptionInfo GAMEOPTIONINFO}("-").
     * If this list is empty, "-" will be the only GAMEOPTIONGETINFO message sent.
     *<P>
     * We check the default values, not current values, so the list is unaffected by
     * cases where some option values are restricted to newer client versions.
     * Any option where opt.{@link SOCGameOption#minVersion minVersion} is too new for
     * this client's version, is sent as {@link SOCGameOption#OTYPE_UNKNOWN}.
     *
     * @param c  the connection
     * @param mes  the message
     * @since 1.1.07
     */
    private void handleGAMEOPTIONGETINFOS(StringConnection c, SOCGameOptionGetInfos mes)
    {
        if (c == null)
            return;
        final int cliVers = c.getVersion();
        boolean vecIsOptObjs = false;
        boolean alreadyTrimmedEnums = false;
        Vector okeys = mes.getOptionKeys();

        if (okeys == null)
        {
            // received "-", look for newer options (cli is older than us).
            // okeys will be null if nothing is new.
            okeys = SOCGameOption.optionsNewerThanVersion(cliVers, false, true, null);
            vecIsOptObjs = true;
            alreadyTrimmedEnums = true;
        }

        if (okeys != null)
        {
            for (int i = 0; i < okeys.size(); ++i)
            {
                SOCGameOption opt;
                if (vecIsOptObjs)
                {
                    opt = (SOCGameOption) okeys.elementAt(i);
                    if (opt.minVersion > cliVers)
                        opt = new SOCGameOption(opt.optKey);  // OTYPE_UNKNOWN
                } else {
                    String okey = (String) okeys.elementAt(i);
                    opt = SOCGameOption.getOption(okey, false);
                    if ((opt == null) || (opt.minVersion > cliVers))  // Don't use opt.getMinVersion() here
                        opt = new SOCGameOption(okey);  // OTYPE_UNKNOWN
                }

                // Enum-type options may have their values restricted by version.
                if ( (! alreadyTrimmedEnums)
                    && (opt.enumVals != null)
                    && (opt.optType != SOCGameOption.OTYPE_UNKNOWN)
                    && (opt.lastModVersion > cliVers))
                {
                    opt = SOCGameOption.trimEnumForVersion(opt, cliVers);
                }

                c.put(new SOCGameOptionInfo(opt).toCmd());
            }
        }

        // mark end of list, even if list was empty
        c.put(SOCGameOptionInfo.OPTINFO_NO_MORE_OPTS.toCmd());  // GAMEOPTIONINFO("-")
    }

    /**
     * process the "new game with options request" message.
     * For messages sent, and other details,
     * see {@link #createOrJoinGameIfUserOK(StringConnection, String, String, String, Hashtable)}.
     * <P>
     * Because this message is sent only by clients newer than 1.1.06, we definitely know that
     * the client has already sent its version information.
     *
     * @param c  the connection
     * @param mes  the message
     * @since 1.1.07
     */
    private void handleNEWGAMEWITHOPTIONSREQUEST(StringConnection c, SOCNewGameWithOptionsRequest mes)
    {
        if (c == null)
            return;

        createOrJoinGameIfUserOK
            (c, mes.getNickname(), mes.getPassword(), mes.getGame(), mes.getOptions());
    }

    /**
     * Handle the client's debug Free Placement putpiece request.
     * @since 1.1.12
     */
    private final void handleDEBUGFREEPLACE(StringConnection c, SOCDebugFreePlace mes)
    {
        SOCGame ga = gameList.getGameData(mes.getGame());
        if ((ga == null) || ! ga.isDebugFreePlacement())
            return;
        final String gaName = ga.getName();

        final int coord = mes.getCoordinates();
        SOCPlayer player = ga.getPlayer(mes.getPlayerNumber());
        if (player == null)
            return;

        boolean didPut = false;
        final int pieceType = mes.getPieceType();

        final boolean initialDeny
            = ga.isInitialPlacement() && ! player.canBuildInitialPieceType(pieceType);

        switch (pieceType)
        {
        case SOCPlayingPiece.ROAD:
            if (player.isPotentialRoad(coord) && ! initialDeny)
            {
                ga.putPiece(new SOCRoad(player, coord, null));
                didPut = true;
            }
            break;

        case SOCPlayingPiece.SETTLEMENT:
            if (player.isPotentialSettlement(coord) && ! initialDeny)
            {
                ga.putPiece(new SOCSettlement(player, coord, null));
                didPut = true;
            }
            break;

        case SOCPlayingPiece.CITY:
            if (player.isPotentialCity(coord) && ! initialDeny)
            {
                ga.putPiece(new SOCCity(player, coord, null));
                didPut = true;
            }
            break;

        default:
            messageToPlayer(c, gaName, "* Unknown piece type: " + pieceType);
        }

        if (didPut)
        {
            messageToGame(gaName, new SOCPutPiece
                          (gaName, mes.getPlayerNumber(), pieceType, coord));
            if (ga.getGameState() >= SOCGame.OVER)
            {
                // exit debug mode, announce end of game
                processDebugCommand_freePlace(c, gaName, "0");
                sendGameState(ga, false);
            }
        } else {
            if (initialDeny)
            {
                final String pieceTypeFirst =
                    ((player.getPieces().size() % 2) == 0)
                    ? "settlement"
                    : "road";
                messageToPlayer(c, gaName, "Place a " + pieceTypeFirst + " before placing that.");
            } else {
                messageToPlayer(c, gaName, "Not a valid location to place that.");
            }
        }
    }

    /**
     * handle "create account" message
     *
     * @param c  the connection
     * @param mes  the message
     */
    private void handleCREATEACCOUNT(StringConnection c, SOCCreateAccount mes)
    {
        final int cliVers = c.getVersion();

        if (! SOCDBHelper.isInitialized())
        {
            // Send same SV_ status code as previous versions (before 1.1.19) which didn't check db.isInitialized
            // but instead fell through and sent "Account not created due to error."

            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_ACCT_NOT_CREATED_ERR, cliVers,
                     "This server does not use accounts and passwords."));
            return;
        }

        final String requester = c.getData();  // null if client isn't authenticated
        final Date currentTime = new Date();
        final boolean isOpenReg = features.isActive(SOCServerFeatures.FEAT_OPEN_REG);

        if ((databaseUserAdmins == null) && ! isOpenReg)
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_ACCT_NOT_CREATED_DENIED, cliVers,
                     "Your account is not authorized to create accounts."));

            printAuditMessage
                (requester,
                 "Requested jsettlers account creation, but no account admins list",
                 null, currentTime, c.host());

            return;
        }

        boolean isDBCountedEmpty = false;  // with null requester, did we query and find the users table is empty?
            // Not set if FEAT_OPEN_REG is active.

        // If client is not authenticated, does this server have open registration
        // or is an account required to create user accounts?
        if ((requester == null) && ! isOpenReg)
        {
            // SOCAccountClients older than v1.1.19 (VERSION_FOR_AUTHREQUEST, VERSION_FOR_SERVERFEATURES)
            // can't authenticate; all their user creation requests are anonymous (FEAT_OPEN_REG).
            // They can't be declined when SOCAccountClient connects, because v1.1.19 is when
            // SOCAuthRequest(ROLE_USER_ADMIN) message was added; we don't know why an older client
            // has connected until they try to create or join a game or channel or create a user.
            // It's fine for them to connect for games or channels, but user creation requires authentication.
            // Check client version now; an older client could create the first account without auth,
            // then not be able to create further ones which would be confusing.

            if (cliVers < SOCAuthRequest.VERSION_FOR_AUTHREQUEST)
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_CANT_JOIN_GAME_VERSION,  // cli knows this status value: defined in 1.1.06
                         cliVers,
                         ("To create accounts, use client version "
                          + Version.version(SOCServerFeatures.VERSION_FOR_SERVERFEATURES)
                          + " or newer.")));
                return;
            }

            // If account is required, are there any accounts in the db at all?
            // if none, this first account creation won't require auth.

            int count;
            try
            {
                count = SOCDBHelper.countUsers();
            }
            catch (SQLException e)
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_PROBLEM_WITH_DB, cliVers,
                         "Problem connecting to database, please try again later."));
                return;
            }

            if (count > 0)
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_PW_WRONG, cliVers,
                         "You must log in with a username and password before you can create accounts."));
                return;
            }

            isDBCountedEmpty = true;
        }

        //
        // check to see if the requested nickname is permissable
        //
        final String userName = mes.getNickname().trim();

        if (! SOCMessage.isSingleLineAndSafe(userName))
        {
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_NEWGAME_NAME_REJECTED, cliVers,
                     SOCStatusMessage.MSG_SV_NEWGAME_NAME_REJECTED));
            return;
        }

        //
        // Check if requester is on the user admins list; this check is also in isUserDBUserAdmin.
        //
        // If databaseUserAdmins != null, then requester != null because FEAT_OPEN_REG can't also be active.
        // If requester is null because db is empty, check new userName instead of requester name:
        // The first account created must be on the list in order to create further accounts.
        // If the db is empty when account client connects, server sends it FEAT_OPEN_REG so it won't require
        // user/password auth to create that first account; then requester == null, covered by isDBCountedEmpty.
        //
        if (databaseUserAdmins != null)
        {
            String chkName = (isDBCountedEmpty) ? userName : requester;
            if ((chkName != null) && (SOCDBHelper.getSchemaVersion() >= SOCDBHelper.SCHEMA_VERSION_1200))
                chkName = chkName.toLowerCase(Locale.US);

            if ((chkName == null) || ! databaseUserAdmins.contains(chkName))
            {
                // Requester not on user-admins list.

                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_ACCT_NOT_CREATED_DENIED, cliVers,
                         "Your account is not authorized to create accounts."));

                printAuditMessage
                    (requester,
                     (isDBCountedEmpty)
                     ? "Requested jsettlers account creation, database is empty - first, create a user named in account admins list"
                     : "Requested jsettlers account creation, this requester not on account admins list",
                     null, currentTime, c.host());

                if (isDBCountedEmpty)
                    System.err.println
                        ("User requested new account but database is currently empty: Run SOCAccountClient to create account(s) named in the admins list.");

                return;
            }
        }

        //
        // check if there's already an account with requested nickname
        //
        try
        {
            final String dbUserName = SOCDBHelper.getUser(userName);
            if (dbUserName != null)
            {
                c.put(SOCStatusMessage.toCmd
                        (SOCStatusMessage.SV_NAME_IN_USE, cliVers,
                         "The nickname '" + dbUserName + "' is already in use."));

                printAuditMessage
                    (requester, "Requested jsettlers account creation, already exists",
                     userName, currentTime, c.host());

                return;
            }
        }
        catch (SQLException sqle)
        {
            // Indicates a db problem: don't continue
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_PROBLEM_WITH_DB, cliVers,
                     "Problem connecting to database, please try again later."));
            return;
        }

        //
        // create the account
        //
        boolean success = false, pwTooLong = false;

        try
        {
            success = SOCDBHelper.createAccount(userName, c.host(), mes.getPassword(), mes.getEmail(), currentTime.getTime());
        }
        catch (IllegalArgumentException e)
        {
            pwTooLong = true;
        }
        catch (SQLException sqle)
        {
            System.err.println("SQL Error creating account in db.");
        }

        if (success)
        {
            final int stat = (isDBCountedEmpty)
                ? SOCStatusMessage.SV_ACCT_CREATED_OK_FIRST_ONE
                : SOCStatusMessage.SV_ACCT_CREATED_OK;
            c.put(SOCStatusMessage.toCmd
                    (stat, cliVers, "Account created for '" + userName + "'."));

            printAuditMessage(requester, "Created jsettlers account", userName, currentTime, c.host());

            if (acctsNotOpenRegButNoUsers)
                acctsNotOpenRegButNoUsers = false;
        }
        else
        {
            String errText = (pwTooLong)
                ? "That password is too long."
                : "Account not created due to error.";
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_ACCT_NOT_CREATED_ERR, cliVers, errText));
        }
    }

    /**
     * Client has been approved to join game; send the entire state of the game to client.
     * Unless <tt>isTakingOver</tt>, send client join event to other players.
     * Assumes NEWGAME (or NEWGAMEWITHOPTIONS) has already been sent out.
     * First message sent to connecting client is JOINGAMEAUTH, unless isReset.
     *<P>
     * Among other messages, player names are sent via SITDOWN, and pieces on board
     * sent by PUTPIECE. See comments here for further details. The group of messages
     * sent here to the client ends with GAMEMEMBERS, SETTURN and GAMESTATE.
     * If game has started (state &gt;= {@link SOCGame#START2A START2A}), they're
     * then prompted with a GAMETEXTMSG to take over a bot in order to play.
     *<P>
     * @param gameData Game to join
     * @param c        The connection of joining client
     * @param isReset  Game is a board-reset of an existing game
     * @param isTakingOver  Client is re-joining; this connection replaces an earlier one which
     *                      is defunct because of a network problem.
     *                      If <tt>isTakingOver</tt>, don't send anything to other players.
     *
     * @see #connectToGame(StringConnection, String, Hashtable)
     * @see #createOrJoinGameIfUserOK(StringConnection, String, String, String, Hashtable)
     */
    private void joinGame(SOCGame gameData, StringConnection c, boolean isReset, boolean isTakingOver)
    {
        boolean hasRobot = false;  // If game's already started, true if any bot is seated (can be taken over)
        String gameName = gameData.getName();
        if (! isReset)
        {
            c.put(SOCJoinGameAuth.toCmd(gameName));
            c.put(SOCStatusMessage.toCmd
                    (SOCStatusMessage.SV_OK,
                     "Welcome to Java Settlers of Catan!"));
        }

        //c.put(SOCGameState.toCmd(gameName, gameData.getGameState()));
        for (int i = 0; i < gameData.maxPlayers; i++)
        {
            /**
             * send the already-seated player information;
             * if isReset, don't send, because sitDown will
             * be sent from resetBoardAndNotify.
             */
            if (! isReset)
            {
                SOCPlayer pl = gameData.getPlayer(i);
                String plName = pl.getName();
                if ((plName != null) && ! gameData.isSeatVacant(i))
                {
                    final boolean isRobot = pl.isRobot();
                    if (isRobot)
                        hasRobot = true;
                    c.put(SOCSitDown.toCmd(gameName, plName, i, isRobot));
                }
            }

            /**
             * send the seat lock information
             */
            messageToPlayer(c, new SOCSetSeatLock(gameName, i, gameData.isSeatLocked(i)));
        }

        c.put(getBoardLayoutMessage(gameData).toCmd());

        for (int i = 0; i < gameData.maxPlayers; i++)
        {
            SOCPlayer pl = gameData.getPlayer(i);

            // Send piece info even if player has left the game (pl.getName() == null).
            // This lets them see "their" pieces before sitDown(), if they rejoin at same position.

            Enumeration piecesEnum = pl.getPieces().elements();

            while (piecesEnum.hasMoreElements())
            {
                SOCPlayingPiece piece = (SOCPlayingPiece) piecesEnum.nextElement();

                if (piece.getType() == SOCPlayingPiece.CITY)
                {
                    c.put(SOCPutPiece.toCmd(gameName, i, SOCPlayingPiece.SETTLEMENT, piece.getCoordinates()));
                }

                c.put(SOCPutPiece.toCmd(gameName, i, piece.getType(), piece.getCoordinates()));
            }

            /**
             * send potential settlement list
             */
            Vector psList = new Vector();
            {
                for (int j = gameData.getBoard().getMinNode(); j <= SOCBoard.MAXNODE; j++)
                {
                    if (pl.isPotentialSettlement(j))
                    {
                        psList.addElement(new Integer(j));
                    }
                }
            }

            c.put(SOCPotentialSettlements.toCmd(gameName, i, psList));

            /**
             * send coords of the last settlement
             */
            c.put(SOCLastSettlement.toCmd(gameName, i, pl.getLastSettlementCoord()));

            /**
             * send number of playing pieces in hand
             */
            c.put(SOCPlayerElement.toCmd(gameName, i, SOCPlayerElement.SET, SOCPlayerElement.ROADS, pl.getNumPieces(SOCPlayingPiece.ROAD)));
            c.put(SOCPlayerElement.toCmd(gameName, i, SOCPlayerElement.SET, SOCPlayerElement.SETTLEMENTS, pl.getNumPieces(SOCPlayingPiece.SETTLEMENT)));
            c.put(SOCPlayerElement.toCmd(gameName, i, SOCPlayerElement.SET, SOCPlayerElement.CITIES, pl.getNumPieces(SOCPlayingPiece.CITY)));

            c.put(SOCPlayerElement.toCmd(gameName, i, SOCPlayerElement.SET, SOCPlayerElement.UNKNOWN, pl.getResources().getTotal()));

            c.put(SOCPlayerElement.toCmd(gameName, i, SOCPlayerElement.SET, SOCPlayerElement.NUMKNIGHTS, pl.getNumKnights()));

            int numDevCards = pl.getDevCards().getTotal();

            for (int j = 0; j < numDevCards; j++)
            {
                c.put(SOCDevCard.toCmd(gameName, i, SOCDevCard.ADDOLD, SOCDevCardConstants.UNKNOWN));
            }

            c.put(SOCFirstPlayer.toCmd(gameName, gameData.getFirstPlayer()));

            c.put(SOCDevCardCount.toCmd(gameName, gameData.getNumDevCards()));

            c.put(SOCChangeFace.toCmd(gameName, i, pl.getFaceId()));

            c.put(SOCDiceResult.toCmd(gameName, gameData.getCurrentDice()));
        }

        ///
        /// send who has longest road
        ///
        SOCPlayer lrPlayer = gameData.getPlayerWithLongestRoad();
        int lrPlayerNum = -1;

        if (lrPlayer != null)
        {
            lrPlayerNum = lrPlayer.getPlayerNumber();
        }

        c.put(SOCLongestRoad.toCmd(gameName, lrPlayerNum));

        ///
        /// send who has largest army
        ///
        final SOCPlayer laPlayer = gameData.getPlayerWithLargestArmy();
        final int laPlayerNum;
        if (laPlayer != null)
        {
            laPlayerNum = laPlayer.getPlayerNumber();
        }
        else
        {
            laPlayerNum = -1;
        }

        c.put(SOCLargestArmy.toCmd(gameName, laPlayerNum));

        /**
         * If we're rejoining and taking over a seat after a network problem,
         * send our resource and hand information. 
         */
        if (isTakingOver)
        {
            SOCPlayer cliPl = gameData.getPlayer(c.getData());
            if (cliPl != null)
            {
                int pn = cliPl.getPlayerNumber();
                if ((pn != -1) && ! gameData.isSeatVacant(pn))
                    sitDown_sendPrivateInfo(gameData, c, pn, gameName);
            }
        }

        String membersCommand = null;
        gameList.takeMonitorForGame(gameName);

        /**
         * Almost done; send GAMEMEMBERS as a hint to client that we're almost ready for its input.
         * There's no new data in GAMEMEMBERS, because player names have already been sent by
         * the SITDOWN messages above.
         */
        try
        {
            Vector gameMembers = gameList.getMembers(gameName);
            membersCommand = SOCGameMembers.toCmd(gameName, gameMembers);
        }
        catch (Exception e)
        {
            D.ebugPrintln("Exception in handleJOINGAME (gameMembers) - " + e);
        }

        gameList.releaseMonitorForGame(gameName);
        c.put(membersCommand);
        c.put(SOCSetTurn.toCmd(gameName, gameData.getCurrentPlayerNumber()));
        c.put(SOCGameState.toCmd(gameName, gameData.getGameState()));

        if (D.ebugOn)
            D.ebugPrintln("*** " + c.getData() + " joined the game " + gameName + " at "
                + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));

        //messageToGame(gameName, new SOCGameTextMsg(gameName, SERVERNAME, n+" joined the game"));
        /**
         * Let everyone else know about the change
         */
        if (isTakingOver)
        {
            return;
        }
        messageToGame(gameName, new SOCJoinGame
            (c.getData(), "", "dummyhost", gameName));

        if ((! isReset) && (gameData.getGameState() >= SOCGame.START2A))
            messageToPlayer(c, gameName,
                (hasRobot) ? "This game has started. To play, take over a robot."
                           : "This game has started, no new players can sit down.");
    }

    /**
     * This player is sitting down at the game
     *
     * @param ga     the game
     * @param c      the connection for the player
     * @param pn     which seat the player is taking
     * @param robot  true if this player is a robot
     * @param isReset Game is a board-reset of an existing game
     */
    private void sitDown(SOCGame ga, StringConnection c, int pn, boolean robot, boolean isReset)
    {
        if ((c != null) && (ga != null))
        {
            ga.takeMonitor();

            try
            {
                final String gaName = ga.getName();
                if (! isReset)
                {
                    // If reset, player is already added and knows if robot.
                    try
                    {
                        SOCClientData cd = (SOCClientData) c.getAppData();
                        ga.addPlayer(c.getData(), pn);
                        ga.getPlayer(pn).setRobotFlag(robot, (cd != null) && cd.isBuiltInRobot);
                    }
                    catch (IllegalStateException e)
                    {
                        // Maybe already seated? (network lag)
                        if (! robot)
                            messageToPlayer(c, gaName, "You cannot sit down here.");
                        ga.releaseMonitor();
                        return;  // <---- Early return: cannot sit down ----
                    }
                }

                /**
                 * if the player can sit, then tell the other clients in the game
                 */
                SOCSitDown sitMessage = new SOCSitDown(gaName, c.getData(), pn, robot);
                messageToGame(gaName, sitMessage);

                D.ebugPrintln("*** sent SOCSitDown message to game ***");

                recordGameEvent(gaName, sitMessage.toCmd());

                Vector requests;
                if (! isReset)
                {
                    requests = (Vector) robotJoinRequests.get(gaName);
                }
                else
                {
                    requests = null;  // Game already has all players from old game
                }

                if (requests != null)
                {
                    /**
                     * if the request list is empty and the game hasn't started yet,
                     * then start the game
                     */
                    if (requests.isEmpty() && (ga.getGameState() < SOCGame.START1A))
                    {
                        startGame(ga);
                    }

                    /**
                     * if the request list is empty, remove the empty list
                     */
                    if (requests.isEmpty())
                    {
                        robotJoinRequests.remove(gaName);
                    }
                }

                broadcastGameStats(ga);

                /**
                 * send all the private information
                 */
                sitDown_sendPrivateInfo(ga, c, pn, gaName);
            }
            catch (Throwable e)
            {
                D.ebugPrintStackTrace(e, "Exception caught at sitDown");
            }

            ga.releaseMonitor();
        }
    }

    /**
     * When player has just sat down at a seat, send all the private information.
     * Called from {@link #sitDown(SOCGame, StringConnection, int, boolean, boolean)}.
     *<P>
     * <b>Locks:</b> Assumes ga.takeMonitor() is held, and should remain held.
     *
     * @param ga     the game
     * @param c      the connection for the player
     * @param pn     which seat the player is taking
     * @param gaName the game's name (for convenience)
     * @since 1.1.08
     */
    private void sitDown_sendPrivateInfo(SOCGame ga, StringConnection c, int pn, final String gaName)
    {
        /**
         * send all the private information
         */
        SOCResourceSet resources = ga.getPlayer(pn).getResources();
        // CLAY, ORE, SHEEP, WHEAT, WOOD, UNKNOWN
        for (int res = SOCPlayerElement.CLAY; res <= SOCPlayerElement.UNKNOWN; ++res)
            messageToPlayer(c, new SOCPlayerElement(gaName, pn, SOCPlayerElement.SET, res, resources.getAmount(res)));

        SOCDevCardSet devCards = ga.getPlayer(pn).getDevCards();

        /**
         * remove the unknown cards
         */
        int i;

        for (i = 0; i < devCards.getTotal(); i++)
        {
            messageToPlayer(c, new SOCDevCard(gaName, pn, SOCDevCard.PLAY, SOCDevCardConstants.UNKNOWN));
        }

        /**
         * send first all new cards, then all old cards
         */
        for (int dcAge = SOCDevCardSet.NEW; dcAge >= SOCDevCardSet.OLD; --dcAge)
        {
            final int addCmd = (dcAge == SOCDevCardSet.NEW) ? SOCDevCard.ADDNEW : SOCDevCard.ADDOLD;

            /**
             * loop from KNIGHT to TOW (MIN to MAX_KNOWN)
             */
            for (int dcType = SOCDevCardConstants.MIN; dcType <= SOCDevCardConstants.MAX_KNOWN; ++dcType)
            {
                int cardAmt = devCards.getAmount(dcAge, dcType);
                if (cardAmt > 0)
                {
                    SOCDevCard addMsg = new SOCDevCard(gaName, pn, addCmd, dcType);
                    for ( ; cardAmt > 0; --cardAmt)
                        messageToPlayer(c, addMsg);
                }

            }  // for (dcType)

        }  // for (dcAge)

        /**
         * send game state info such as requests for discards
         */
        sendGameState(ga);

        if ((ga.getCurrentDice() == 7) && ga.getPlayer(pn).getNeedToDiscard())
        {
            messageToPlayer(c, new SOCDiscardRequest(gaName, ga.getPlayer(pn).getResources().getTotal() / 2));
        }

        /**
         * send what face this player is using
         */
        messageToGame(gaName, new SOCChangeFace(gaName, pn, ga.getPlayer(pn).getFaceId()));
    }

    /**
     * The current player is stealing from another player.
     * Send messages saying what was stolen.
     *
     * @param ga  the game
     * @param pe  the perpetrator
     * @param vi  the the victim
     * @param rsrc  type of resource stolen, as in SOCResourceConstants
     */
    protected void reportRobbery(SOCGame ga, SOCPlayer pe, SOCPlayer vi, int rsrc)
    {
        if (ga != null)
        {
            final String gaName = ga.getName();
            final String peName = pe.getName();
            final String viName = vi.getName();
            final int pePN = pe.getPlayerNumber();
            final int viPN = vi.getPlayerNumber();
            StringBuffer mes = new StringBuffer(" stole ");  // " stole a sheep resource from "
            SOCPlayerElement gainRsrc = null;
            SOCPlayerElement loseRsrc = null;
            SOCPlayerElement gainUnknown;
            SOCPlayerElement loseUnknown;

            final String aResource = SOCResourceConstants.aResName(rsrc);
            mes.append(aResource);  // "a clay"

            // This works because SOCPlayerElement.SHEEP == SOCResourceConstants.SHEEP.
            gainRsrc = new SOCPlayerElement(gaName, pePN, SOCPlayerElement.GAIN, rsrc, 1);
            loseRsrc = new SOCPlayerElement(gaName, viPN, SOCPlayerElement.LOSE, rsrc, 1, true);

            mes.append(" resource from "); 

            /**
             * send the game messages
             */
            StringConnection peCon = getConnection(peName);
            StringConnection viCon = getConnection(viName);
            messageToPlayer(peCon, gainRsrc);
            messageToPlayer(peCon, loseRsrc);
            messageToPlayer(viCon, gainRsrc);
            messageToPlayer(viCon, loseRsrc);
            // Don't send generic message to pe or vi
            Vector exceptions = new Vector(2);
            exceptions.addElement(peCon);
            exceptions.addElement(viCon);
            gainUnknown = new SOCPlayerElement(gaName, pePN, SOCPlayerElement.GAIN, SOCPlayerElement.UNKNOWN, 1);
            loseUnknown = new SOCPlayerElement(gaName, viPN, SOCPlayerElement.LOSE, SOCPlayerElement.UNKNOWN, 1);
            messageToGameExcept(gaName, exceptions, gainUnknown, true);
            messageToGameExcept(gaName, exceptions, loseUnknown, true);

            /**
             * send the text messages:
             * "You stole a sheep resource from viName."
             * "peName stole a sheep resource from you."
             * "peName stole a resource from viName."
             */
            messageToPlayer(peCon, gaName,
                "You" + mes.toString() + viName + '.');
            messageToPlayer(viCon, gaName, 
                peName + mes.toString() + "you.");
            messageToGameExcept(gaName, exceptions, new SOCGameTextMsg(gaName, SERVERNAME,
                peName + " stole a resource from " + viName), true);
        }
    }

    /**
     * send the current state of the game with a message.
     * Assumes current player does not change during this state.
     * If we send a text message to prompt the new player to roll,
     * also sends a RollDicePrompt data message.
     * If the client is too old (1.0.6), it will ignore the prompt.
     *
     * @param ga  the game
     * 
     * @see #sendGameState(SOCGame, boolean)
     */
    protected void sendGameState(SOCGame ga)
    {
        sendGameState(ga, true);
    }
    
    /**
     * send the current state of the game with a message.
     * Note that the current (or new) player number is not sent here.
     * If game is now OVER, send appropriate messages.
     *
     * @see #sendTurn(SOCGame, boolean)
     * @see #sendGameState(SOCGame)
     * @see #sendGameStateOVER(SOCGame)
     *
     * @param ga  the game
     * @param sendRollPrompt  If true, and if we send a text message to prompt
     *    the player to roll, send a RollDicePrompt data message.
     *    If the client is too old (1.0.6), it will ignore the prompt.
     *
     * @return    did we send a text message to prompt the player to roll?
     *    If so, sendTurn can also send a RollDicePrompt data message.  
     */
    protected boolean sendGameState(SOCGame ga, boolean sendRollPrompt)
    {
        if (ga == null)
            return false;

        final String gname = ga.getName();
        boolean promptedRoll = false;
        if (ga.getGameState() == SOCGame.OVER)
        {
            /**
             * Before sending state "OVER", enforce current player number.
             * This helps the client's copy of game recognize winning condition.
             */
            messageToGame(gname, new SOCSetTurn(gname, ga.getCurrentPlayerNumber()));
        }
        messageToGame(gname, new SOCGameState(gname, ga.getGameState()));

        SOCPlayer player = null;

        if (ga.getCurrentPlayerNumber() != -1)
        {
            player = ga.getPlayer(ga.getCurrentPlayerNumber());
        }

        switch (ga.getGameState())
        {
        case SOCGame.START1A:
        case SOCGame.START2A:
            messageToGame(gname, "It's " + player.getName() + "'s turn to build a settlement.");

            break;

        case SOCGame.START1B:
        case SOCGame.START2B:
            messageToGame(gname, "It's " + player.getName() + "'s turn to build a road.");

            break;

        case SOCGame.PLAY:
            messageToGame(gname, "It's " + player.getName() + "'s turn to roll the dice.");
            promptedRoll = true;
            if (sendRollPrompt)
                messageToGame(gname, new SOCRollDicePrompt (gname, player.getPlayerNumber()));
                
            break;

        case SOCGame.WAITING_FOR_DISCARDS:

            int count = 0;
            String message = "error at sendGameState()";
            String[] names = new String[ga.maxPlayers];

            for (int i = 0; i < ga.maxPlayers; i++)
            {
                if (ga.getPlayer(i).getNeedToDiscard())
                {
                    names[count] = ga.getPlayer(i).getName();
                    count++;
                }
            }

            if (count == 1)
            {
                message = names[0] + " needs to discard.";
            }
            else if (count == 2)
            {
                message = names[0] + " and " + names[1] + " need to discard.";
            }
            else if (count > 2)
            {
                message = names[0];

                for (int i = 1; i < (count - 1); i++)
                {
                    message += (", " + names[i]);
                }

                message += (" and " + names[count - 1] + " need to discard.");
            }

            messageToGame(gname, message);

            break;

        case SOCGame.PLACING_ROBBER:
            messageToGame(gname, player.getName() + " will move the robber.");

            break;

        case SOCGame.WAITING_FOR_CHOICE:

            /**
             * get the choices from the game
             */
            boolean[] choices = new boolean[ga.maxPlayers];

            for (int i = 0; i < ga.maxPlayers; i++)
            {
                choices[i] = false;
            }

            Enumeration plEnum = ga.getPossibleVictims().elements();

            while (plEnum.hasMoreElements())
            {
                SOCPlayer pl = (SOCPlayer) plEnum.nextElement();
                choices[pl.getPlayerNumber()] = true;
            }

            /**
             * ask the current player to choose a player to steal from
             */
            StringConnection con = getConnection
                (ga.getPlayer(ga.getCurrentPlayerNumber()).getName());
            if (con != null)
            {
                con.put(SOCChoosePlayerRequest.toCmd(gname, choices));
            }

            break;

        case SOCGame.OVER:

            sendGameStateOVER(ga);
            
            break;
            
        }  // switch ga.getGameState
        
        return promptedRoll; 
    }
    
    /**
     *  If game is OVER, send messages reporting winner, final score,
     *  and each player's victory-point cards.
     *  Also give stats on game length, and on each player's connect time.
     *  If player has finished more than 1 game since connecting, send their win-loss count.
     *<P>
     *  Increments server stats' numberOfGamesFinished.
     *  If db is active, calls {@link #storeGameScores(SOCGame)}
     *  if {@link SOCDBHelper#PROP_JSETTLERS_DB_SAVE_GAMES} setting is active.
     *
     * @param ga This game is over; state should be OVER
     */
    protected void sendGameStateOVER(SOCGame ga)
    {
        final String gname = ga.getName();
        String msg;

        /**
         * Find and announce the winner
         * (the real "found winner" code is in SOCGame.checkForWinner;
         *  that was already called before sendGameStateOVER.)
         */
        SOCPlayer winPl = ga.getPlayer(ga.getCurrentPlayerNumber());

        if (winPl.getTotalVP() < ga.vp_winner)
        {
            // Should not happen: By rules FAQ, only current player can be winner.
            // This is fallback code.
            for (int i = 0; i < ga.maxPlayers; i++)
            {
                if (winPl.getTotalVP() >= ga.vp_winner)
                {
                    winPl = ga.getPlayer(i);        
                    break;
                }
            }
        }
        msg = ">>> " + winPl.getName() + " has won the game with " + winPl.getTotalVP() + " points.";
        messageToGameUrgent(gname, msg);
        
        /// send a message with the revealed final scores
        {
            int[] scores = new int[ga.maxPlayers];
            boolean[] isRobot = new boolean[ga.maxPlayers];
            for (int i = 0; i < ga.maxPlayers; ++i)
            {
                scores[i] = ga.getPlayer(i).getTotalVP();
                isRobot[i] = ga.getPlayer(i).isRobot();
            }
            messageToGame(gname, new SOCGameStats(gname, scores, isRobot));
        }
        
        ///
        /// send a message saying what VP cards each player has
        ///
        for (int i = 0; i < ga.maxPlayers; i++)
        {
            SOCPlayer pl = ga.getPlayer(i);
            SOCDevCardSet devCards = pl.getDevCards();

            if (devCards.getNumVPCards() > 0)
            {
                msg = pl.getName() + " has";
                int vpCardCount = 0;

                for (int devCardType = SOCDevCardConstants.CAP;
                        devCardType < SOCDevCardConstants.UNKNOWN;
                        devCardType++)
                {
                    if ((devCards.getAmount(SOCDevCardSet.OLD, devCardType) > 0) || (devCards.getAmount(SOCDevCardSet.NEW, devCardType) > 0))
                    {
                        if (vpCardCount > 0)
                        {
                            if ((devCards.getNumVPCards() - vpCardCount) == 1)
                            {
                                msg += " and";
                            }
                            else if ((devCards.getNumVPCards() - vpCardCount) > 0)
                            {
                                msg += ",";
                            }
                        }

                        vpCardCount++;

                        switch (devCardType)
                        {
                        case SOCDevCardConstants.CAP:
                            msg += " a Gov.House (+1VP)";

                            break;

                        case SOCDevCardConstants.LIB:
                            msg += " a Market (+1VP)";

                            break;

                        case SOCDevCardConstants.UNIV:
                            msg += " a University (+1VP)";

                            break;

                        case SOCDevCardConstants.TEMP:
                            msg += " a Temple (+1VP)";

                            break;

                        case SOCDevCardConstants.TOW:
                            msg += " a Chapel (+1VP)";

                            break;
                        }
                    }
                }  // for each devcard type

                messageToGame(gname, msg);

            }  // if devcards
        }  // for each player

        /**
         * send game-length and connect-length messages, possibly win-loss count.
         */
        {
            Date now = new Date();
            Date gstart = ga.getStartTime();
            final String gLengthMsg;
            if (gstart != null)
            {
                StringBuffer sb = new StringBuffer("This game was ");
                sb.append(ga.getRoundCount());
                sb.append(" rounds, and took ");
                long gameSeconds = ((now.getTime() - gstart.getTime())+500L) / 1000L;
                long gameMinutes = gameSeconds/60L;
                gameSeconds = gameSeconds % 60L;
                sb.append(gameMinutes);
                if (gameSeconds == 0)
                {
                    sb.append(" minutes.");
                } else if (gameSeconds == 1)
                {
                    sb.append(" minutes 1 second.");
                } else {
                    sb.append(" minutes ");
                    sb.append(gameSeconds);
                    sb.append(" seconds.");
                }
                gLengthMsg = sb.toString();
                messageToGame(gname, gLengthMsg);

                // Ignore possible "1 minutes"; that game is too short to worry about.
            } else {
                gLengthMsg = null;
            }

            /**
             * Update each player's win-loss count for this session.
             * Tell each player their resource roll totals.
             * Tell each player how long they've been connected.
             * (Robot players aren't told this, it's not necessary.)
             */
            String connMsg;
            if ((strSocketName != null) && (strSocketName.equals(PRACTICE_STRINGPORT)))
                connMsg = "You have been practicing ";
            else
                connMsg = "You have been connected ";

            for (int i = 0; i < ga.maxPlayers; i++)
            {
                if (ga.isSeatVacant(i))
                    continue;

                SOCPlayer pl = ga.getPlayer(i);
                StringConnection plConn = (StringConnection) conns.get(pl.getName());
                SOCClientData cd;
                if (plConn != null)
                {
                    // Update win-loss count, even for robots
                    cd = (SOCClientData) plConn.getAppData();
                    if (pl == winPl)
                        cd.wonGame();
                    else
                        cd.lostGame();
                } else {
                    cd = null;  // To satisfy compiler warning
                }

                if (pl.isRobot())
                    continue;  // <-- Don't bother to send any stats text to robots --

                if (plConn != null)
                {
                    if (plConn.getVersion() >= SOCPlayerStats.VERSION_FOR_RES_ROLL)
                    {
                        // Send total resources rolled
                        messageToPlayer(plConn, new SOCPlayerStats(pl, SOCPlayerStats.STYPE_RES_ROLL));
                    }

                    long connTime = plConn.getConnectTime().getTime();
                    long connMinutes = (((now.getTime() - connTime)) + 30000L) / 60000L;                    
                    StringBuffer cLengthMsg = new StringBuffer(connMsg);
                    cLengthMsg.append(connMinutes);
                    if (connMinutes == 1)
                        cLengthMsg.append(" minute.");
                    else
                        cLengthMsg.append(" minutes.");
                    messageToPlayer(plConn, gname, cLengthMsg.toString());

                    // Send client's win-loss count for this session,
                    // if more than 1 game has been played
                    {
                        int wins = cd.getWins();
                        int losses = cd.getLosses();
                        if (wins + losses < 2)
                            continue;  // Only 1 game played so far

                        StringBuffer winLossMsg = new StringBuffer("You have ");
                        if (wins > 0)
                        {
                            winLossMsg.append("won ");
                            winLossMsg.append(wins);
                            if (losses == 0)
                            {
                                if (wins != 1)
                                    winLossMsg.append(" games");
                                else
                                    winLossMsg.append(" game");
                            } else {
                                winLossMsg.append(" and ");
                            }
                        }
                        if (losses > 0)
                        {
                            winLossMsg.append("lost ");
                            winLossMsg.append(losses);
                            if (losses != 1)
                                winLossMsg.append(" games");
                            else
                                winLossMsg.append(" game");
                        }
                        winLossMsg.append(" since connecting.");
                        messageToPlayer(plConn, gname, winLossMsg.toString());
                    }
                }
            }  // for each player

        }  // send game timing stats, win-loss stats

        ++numberOfGamesFinished;

        //
        // Save game stats in the database,
        // if that setting is active
        //
        if (init_getBoolProperty
            (props, SOCDBHelper.PROP_JSETTLERS_DB_SAVE_GAMES, false))
        {
            storeGameScores(ga);
        }

    }

    /**
     * report a trade that has taken place between players, using {@link SOCPlayerElement}
     * and {@link SOCGameTextMsg} messages.  Trades are also reported to robots
     * by re-sending the accepting player's {@link SOCAcceptOffer} message.
     *
     * @param ga        the game
     * @param offering  the number of the player making the offer
     * @param accepting the number of the player accepting the offer
     *
     * @see #reportBankTrade(SOCGame, SOCResourceSet, SOCResourceSet)
     */
    protected void reportTrade(SOCGame ga, int offering, int accepting)
    {
        if (ga != null)
        {
            final String gaName = ga.getName();
            final SOCTradeOffer offer = ga.getPlayer(offering).getCurrentOffer();

            StringBuffer message = new StringBuffer(ga.getPlayer(offering).getName());
            message.append(" traded ");
            reportRsrcGainLoss(gaName, offer.getGiveSet(), true, false, offering, accepting, message, null);
            message.append(" for ");
            reportRsrcGainLoss(gaName, offer.getGetSet(), false, false, offering, accepting, message, null);
            message.append(" from ");
            message.append(ga.getPlayer(accepting).getName());
            message.append('.');
            messageToGame(gaName, message.toString());
        }
    }

    /**
     * report that the current player traded with the bank or a port,
     * using {@link SOCPlayerElement} and {@link SOCGameTextMsg} messages.
     *
     * @param ga        the game
     * @param give      the number of the player making the offer
     * @param get       the number of the player accepting the offer
     *
     * @see #reportTrade(SOCGame, int, int)
     */
    protected void reportBankTrade(SOCGame ga, SOCResourceSet give, SOCResourceSet get)
    {
        if (ga != null)
        {
            final String gaName = ga.getName();
            final int    cpn    = ga.getCurrentPlayerNumber();
            StringBuffer message = new StringBuffer (ga.getPlayer(cpn).getName());
            message.append(" traded ");
            reportRsrcGainLoss(gaName, give, true, false, cpn, -1, message, null);
            message.append(" for ");
            reportRsrcGainLoss(gaName, get, false, false, cpn, -1, message, null);

            // use total rsrc counts to determine bank or port
            final int giveTotal = give.getTotal(),
                getTotal = get.getTotal();
            final boolean tradeIsFromBank;
            if (giveTotal < getTotal)
                tradeIsFromBank = ((getTotal / giveTotal) == 4);
            else
                tradeIsFromBank = ((giveTotal / getTotal) == 4);

            if (tradeIsFromBank)
                message.append(" from the bank.");  // 4:1 trade
            else
                message.append(" from a port.");    // 3:1 or 2:1 trade

            if (giveTotal < getTotal)
                message.append(" (Undo previous trade)");

            messageToGame(gaName, message.toString());
        }
    }

    /**
     * Report the resources gained/lost by a player, and optionally (for trading)
     * lost/gained by a second player.
     * Sends PLAYERELEMENT messages, either to entire game, or to player only.
     * Builds the resource-amount string used to report the trade as text.
     * Takes and releases the gameList monitor for this game.
     *<P>
     * Used to report the resources gained from a roll, discard, or discovery (year-of-plenty) pick.
     * Also used to report the "give" or "get" half of a resource trade.
     *
     * @param gaName  Game name
     * @param rset    Resource set (from a roll, or the "give" or "get" side of a trade).
     *                Resource type {@link SOCResourceConstants#UNKNOWN} is ignored.
     *                Only positive resource amounts are sent (negative is ignored).
     * @param isLoss  If true, "give" ({@link SOCPlayerElement#LOSE}), otherwise "get" ({@link SOCPlayerElement#GAIN})
     * @param isNews  Is this element change notably good or an unexpected bad change or loss?
     *                Sets the {@link SOCPlayerElement#isNews()} flag in messages sent by this method.
     *                If there are multiple resource types, flag is set only for the first type sent
     *                to avoid several alert sounds at client.
     * @param mainPlayer     Player number "giving" if isLose==true, otherwise "getting".
     *                For each nonzero resource involved, PLAYERELEMENT messages will be sent about this player.
     * @param tradingPlayer  Player number on other side of trade, or -1 if no second player is involved.
     *                If not -1, PLAYERELEMENT messages will also be sent about this player.
     * @param message Append resource numbers/types to this stringbuffer,
     *                format like "3 clay,3 wood"; can be null.
     * @param playerConn     Null to announce to the entire game, or mainPlayer's connection to send messages
     *                there instead of sending to all players in game.  Because trades are public, there is no
     *                such parameter for tradingPlayer.
     *
     * @see #reportTrade(SOCGame, int, int)
     * @see #reportBankTrade(SOCGame, SOCResourceSet, SOCResourceSet)
     * @see #handleDISCARD(StringConnection, SOCDiscard)
     * @see #handleDISCOVERYPICK(StringConnection, SOCDiscoveryPick)
     * @see #handleROLLDICE(StringConnection, SOCRollDice)
     */
    private void reportRsrcGainLoss
        (final String gaName, final SOCResourceSet rset, final boolean isLoss, boolean isNews,
         final int mainPlayer, final int tradingPlayer, StringBuffer message, StringConnection playerConn)
    {
        final int losegain  = isLoss ? SOCPlayerElement.LOSE : SOCPlayerElement.GAIN;  // for pnA
        final int gainlose  = isLoss ? SOCPlayerElement.GAIN : SOCPlayerElement.LOSE;  // for pnB

        boolean needComma = false;  // Has a resource already been appended to message?

        gameList.takeMonitorForGame(gaName);

        for (int res = SOCResourceConstants.CLAY; res <= SOCResourceConstants.WOOD; ++res)
        {
            // This works because SOCPlayerElement.SHEEP == SOCResourceConstants.SHEEP.

            final int amt = rset.getAmount(res);
            if (amt <= 0)
                continue;

            if (playerConn != null)
                messageToPlayer(playerConn, new SOCPlayerElement(gaName, mainPlayer, losegain, res, amt, isNews));
            else
                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, mainPlayer, losegain, res, amt, isNews));
            if (tradingPlayer != -1)
                messageToGameWithMon(gaName, new SOCPlayerElement(gaName, tradingPlayer, gainlose, res, amt, isNews));
            if (isNews)
                isNews = false;

            if (message != null)
            {
                if (needComma)
                    message.append(", ");
                message.append(amt);
                message.append(" ");
                message.append(SOCResourceConstants.resName(res));
                needComma = true;
            }
        }

        gameList.releaseMonitorForGame(gaName);
    }

    /**
     * make sure it's the player's turn
     *
     * @param c  the connection for player
     * @param ga the game
     *
     * @return true if it is the player's turn;
     *         false if another player's turn, or if this player isn't in the game
     */
    protected boolean checkTurn(StringConnection c, SOCGame ga)
    {
        if ((c != null) && (ga != null))
        {
            try
            {
                if (ga.getCurrentPlayerNumber() != ga.getPlayer(c.getData()).getPlayerNumber())
                {
                    return false;
                }
                else
                {
                    return true;
                }
            }
            catch (Exception e)
            {
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    /**
     * do the stuff you need to do to start a game
     *
     * @param ga  the game
     */
    protected void startGame(SOCGame ga)
    {
        if (ga != null)
        {
            final String gaName = ga.getName();

            numberOfGamesStarted++;
            ga.startGame();
            gameList.takeMonitorForGame(gaName);

            /**
             * send the board layout
             */
            messageToGameWithMon(gaName, getBoardLayoutMessage(ga));

            /**
             * send the player info
             */            
            for (int i = 0; i < ga.maxPlayers; i++)
            {
                if (! ga.isSeatVacant(i))
                {
                    SOCPlayer pl = ga.getPlayer(i);
                    messageToGameWithMon(gaName, new SOCPlayerElement(gaName, i, SOCPlayerElement.SET, SOCPlayerElement.ROADS, pl.getNumPieces(SOCPlayingPiece.ROAD)));
                    messageToGameWithMon(gaName, new SOCPlayerElement(gaName, i, SOCPlayerElement.SET, SOCPlayerElement.SETTLEMENTS, pl.getNumPieces(SOCPlayingPiece.SETTLEMENT)));
                    messageToGameWithMon(gaName, new SOCPlayerElement(gaName, i, SOCPlayerElement.SET, SOCPlayerElement.CITIES, pl.getNumPieces(SOCPlayingPiece.CITY)));
                    messageToGameWithMon(gaName, new SOCSetPlayedDevCard(gaName, i, false));
                }
            }

            /**
             * send the number of dev cards
             */
            messageToGameWithMon(gaName, new SOCDevCardCount(gaName, ga.getNumDevCards()));

            /**
             * ga.startGame() picks who goes first, but feedback is nice
             */
            messageToGameWithMon(gaName, new SOCGameTextMsg(gaName, SERVERNAME, "Randomly picking a starting player..."));

            gameList.releaseMonitorForGame(gaName);

            /**
             * send the game state
             */
            sendGameState(ga, false);

            /**
             * start the game
             */
            messageToGame(gaName, new SOCStartGame(gaName));

            /**
             * send whose turn it is
             */
            sendTurn(ga, false);
        }
    }

    /**
     * Reset the board, to a copy with same players but new layout.
     * Here's the general outline; step 1 and 2 are done immediately here,
     * steps 3 through n are done (after robots are dismissed) within
     * {@link #resetBoardAndNotify_finish(SOCGameBoardReset, SOCGame)}.
     *<OL>
     * <LI value=1> Reset the board, remember player positions.
     *              If there are robots, set game state to
     *              {@link SOCGame#READY_RESET_WAIT_ROBOT_DISMISS}.
     * <LI value=2a> Send ResetBoardAuth to each client (like sending JoinGameAuth at new game)
     *    Humans will reset their copy of the game.
     *    Robots will leave the game, and soon be requested to re-join.
     *    (This simplifies the robot client.)
     *    If the game was in initial placement or was already over at reset time, different robots will
     *    be randomly chosen to join the reset game.
     * <LI value=2b> If there were robots, wait for them all to leave the old game.
     *    Otherwise, (race condition) they may leave the new game as it is forming.
     *    Set {@link SOCGame#boardResetOngoingInfo}.
     *    Wait for them to leave the old game before continuing.
     *    The call will be made from {@link #handleLEAVEGAME_maybeGameReset_oldRobot(String)}.
     * <LI value=2c> If no robots, immediately call {@link #resetBoardAndNotify_finish(SOCGameBoardReset, SOCGame)}.
     *   <P>
     *    <b>This ends this method.</b>  Step 3 and the rest are in
     *    {@link #resetBoardAndNotify_finish(SOCGameBoardReset, SOCGame)}.
     * <LI value=3> Send messages as if each human player has clicked "join" (except JoinGameAuth)
     * <LI value=4> Send as if each human player has clicked "sit here"
     * <LI value=5a> If no robots, send to game as if someone else has
     *              clicked "start game", and set up state to begin game play.
     * <LI value=5b>  If there are robots, set up wait-request
     *     queue (robotJoinRequests). Game will wait for robots to send
     *     JOINGAME and SITDOWN, as they do when joining a newly created game.
     *     Once all robots have re-joined, the game will begin.
     *</OL>
     */
    private void resetBoardAndNotify (final String gaName, final int requestingPlayer)
    {
        /**
         * 1. Reset the board, remember player positions.
         *    Takes the monitorForGame and (when reset is ready) releases it.
         *    If robots, resetBoard will also set gamestate
         *    and boardResetOngoingInfo field.
         */
        SOCGameBoardReset reBoard = gameList.resetBoard(gaName);
        if (reBoard == null)
        {
            messageToGameUrgent(gaName, ">>> Internal error, Game " + gaName + " board reset failed");
            return;  // <---- Early return: reset failed ----
        }
        SOCGame reGame = reBoard.newGame;

        // Announce who asked for this reset
        {
            String plName = reGame.getPlayer(requestingPlayer).getName();
            if (plName == null)
                plName = "player who left";
            messageToGameUrgent(gaName, ">>> Game " + gaName + " board reset by "
                + plName);
        }

        // If game is still initial-placing or was over, we'll shuffle the robots
        final boolean resetWithShuffledBots =
            (reBoard.oldGameState < SOCGame.PLAY) || (reBoard.oldGameState == SOCGame.OVER);

        /**
         * Player connection data:
         * - Humans are copied from old to new game
         * - Robots aren't copied to new game, must re-join
         */
        StringConnection[] huConns = reBoard.humanConns;
        StringConnection[] roConns = reBoard.robotConns;

        /**
         * Notify old game's players. (Humans and robots)
         *
         * 2a. Send ResetBoardAuth to each (like sending JoinGameAuth at new game).
         *    Humans will reset their copy of the game.
         *    Robots will leave the game, and soon will be requested to re-join.
         */
        for (int pn = 0; pn < reGame.maxPlayers; ++pn)
        {
            SOCResetBoardAuth resetMsg = new SOCResetBoardAuth(gaName, pn, requestingPlayer);
            if (huConns[pn] != null)
                messageToPlayer(huConns[pn], resetMsg);
            else if (roConns[pn] != null)
            {
                if (! resetWithShuffledBots)
                    messageToPlayer(roConns[pn], resetMsg);  // same robot will rejoin
                else
                    messageToPlayer(roConns[pn], new SOCRobotDismiss(gaName));  // could be different bot
            }
        }

        // If there are robots, wait for them to leave
        // before doing anything else.  Otherwise, go ahead.

        if (! reBoard.hadRobots)
            resetBoardAndNotify_finish(reBoard, reGame);
        // else
        //  gameState is READY_RESET_WAIT_ROBOT_DISMISS,
        //  and once the last robot leaves this game,
        //  handleLEAVEGAME will take care of the reset,
        //  by calling resetBoardAndNotify_finish.

    }  // resetBoardAndNotify

    /**
     * Complete steps 3 - n of the board-reset process
     * outlined in {@link #resetBoardAndNotify(String, int)},
     * after any robots have left the old game.
     * @param reBoard
     * @param reGame
     * @since 1.1.07
     */
    private void resetBoardAndNotify_finish(SOCGameBoardReset reBoard, SOCGame reGame)
    {
        final boolean resetWithShuffledBots =
            (reBoard.oldGameState < SOCGame.PLAY) || (reBoard.oldGameState == SOCGame.OVER);
        StringConnection[] huConns = reBoard.humanConns;

        /**
         * 3. Send messages as if each human player has clicked "join" (except JoinGameAuth)
         */
        for (int pn = 0; pn < reGame.maxPlayers; ++pn)
        {
            if (huConns[pn] != null)
                joinGame(reGame, huConns[pn], true, false);
        }

        /**
         * 4. Send as if each human player has clicked "sit here"
         */
        for (int pn = 0; pn < reGame.maxPlayers; ++pn)
        {
            if (huConns[pn] != null)
                sitDown(reGame, huConns[pn], pn, false /* isRobot*/, true /*isReset */ );
        }

        /**
         * 5a. If no robots, send to game as if someone else has
         *     clicked "start game", and set up state to begin game play.
         */
        if (! reBoard.hadRobots)
        {
            startGame (reGame);
        }
        else
        {

        /**
         * 5b. If there are robots, set up wait-request queue
         *     (robotJoinRequests) and ask robots to re-join.
         *     Game will wait for robots to send JOINGAME and SITDOWN,
         *     as they do when joining a newly created game.
         *     Once all robots have re-joined, the game will begin.
         */
            reGame.setGameState(SOCGame.READY);
            readyGameAskRobotsJoin
              (reGame, resetWithShuffledBots ? null : reBoard.robotConns);
        }

        // All set.
    }  // resetBoardAndNotify_finish

    /**
     * send {@link SOCTurn whose turn it is}. Optionally also send a prompt to roll.
     * If the client is too old (1.0.6), it will ignore the prompt.
     *
     * @param ga  the game
     * @param sendRollPrompt  whether to send a RollDicePrompt message afterwards
     */
    private void sendTurn(SOCGame ga, boolean sendRollPrompt)
    {
        if (ga != null)
        {
            String gname = ga.getName();
            int pn = ga.getCurrentPlayerNumber();   

            messageToGame(gname, new SOCSetPlayedDevCard(gname, pn, false));

            SOCTurn turnMessage = new SOCTurn(gname, pn);
            messageToGame(gname, turnMessage);
            recordGameEvent(gname, turnMessage.toCmd());

            if (sendRollPrompt)
                messageToGame(gname, new SOCRollDicePrompt(gname, pn));
        }
    }

    /**
     * put together the board layout message.
     * Message type will be {@link SOCBoardLayout} or {@link SOCBoardLayout2},
     * depending on {@link SOCBoard#getBoardEncodingFormat() ga.getBoard().getBoardEncodingFormat()}
     * and {@link SOCGame#getClientVersionMinRequired()}.
     *
     * @param  ga   the game
     * @return   a board layout message
     */
    private SOCMessage getBoardLayoutMessage(SOCGame ga)
    {
        SOCBoard board;
        int[] hexes;
        int[] numbers;
        int robber;

        board = ga.getBoard();
        hexes = board.getHexLayout();
        numbers = board.getNumberLayout();
        robber = board.getRobberHex();
        int bef = board.getBoardEncodingFormat();
        if ((bef == 1) && (ga.getClientVersionMinRequired() < SOCBoardLayout2.VERSION_FOR_BOARDLAYOUT2))
        {
            // SOCBoard.BOARD_ENCODING_ORIGINAL: v1
            return new SOCBoardLayout(ga.getName(), hexes, numbers, robber);
        } else {
            // SOCBoard.BOARD_ENCODING_6PLAYER: v2
            return new SOCBoardLayout2(ga.getName(), bef, hexes, numbers, board.getPortsLayout(), robber);
        }
    }

    /**
     * create a new game event record
     */
    // private void createNewGameEventRecord()
    // {
        /*
           currentGameEventRecord = new SOCGameEventRecord();
           currentGameEventRecord.setTimestamp(new Date());
         */
    // }

    /**
     * save the current game event record in the game record
     *
     * @param gn  the name of the game
     */
    // private void saveCurrentGameEventRecord(String gn)
    // {
        /*
           SOCGameRecord gr = (SOCGameRecord)gameRecords.get(gn);
           SOCGameEventRecord ger = currentGameEventRecord.myClone();
           gr.addEvent(ger);
         */
    // }

    /**
     * write a gameRecord out to disk
     *
     * @param na  the name of the record
     * @param gr  the game record
     */

    /*
       private void writeGameRecord(String na, SOCGameRecord gr) {
       FileOutputStream os = null;
       ObjectOutput output = null;
    
       try {
       Date theTime = new Date();
       os = new FileOutputStream("dataFiles/"+na+"."+theTime.getTime());
       output = new ObjectOutputStream(os);
       } catch (Exception e) {
       D.ebugPrintln(e.toString());
       D.ebugPrintln("Unable to open output stream.");
       }
       try{
       output.writeObject(gr);
       // D.ebugPrintln("*** Wrote "+na+" out to disk. ***");
       output.close();
       } catch (Exception e) {
       D.ebugPrintln(e.toString());
       D.ebugPrintln("Unable to write game record to disk.");
       }
       }
     */

    /**
     * if all the players stayed for the whole game,
     * or if the game has any human players,
     * record the winner and scores in the database.
     * Called only if property <tt>jsettlers.db.save.games</tt>
     * is true. ({@link SOCDBHelper#PROP_JSETTLERS_DB_SAVE_GAMES})
     *
     * @param ga  the game
     */
    protected void storeGameScores(SOCGame ga)
    {
        if ((ga == null) || ! SOCDBHelper.isInitialized())
            return;

        //D.ebugPrintln("allOriginalPlayers for "+ga.getName()+" : "+ga.allOriginalPlayers());
        if (! ((ga.getGameState() == SOCGame.OVER)
                && (ga.allOriginalPlayers() || ga.hasHumanPlayers())))
            return;

        try
        {
            final int gameSeconds = (int) (((System.currentTimeMillis() - ga.getStartTime().getTime())+500L) / 1000L);
            SOCDBHelper.saveGameScores(ga, gameSeconds);
        }
        catch (Exception e)
        {
            System.err.println("Error saving game scores in db: " + e);
        }
    }

    /**
     * record events that happen during the game
     *
     * @param gameName   the name of the game
     * @param event      the event
     */
    protected void recordGameEvent(String gameName, String event)
    {
        /*
           FileWriter fw = (FileWriter)gameDataFiles.get(gameName);
           if (fw != null) {
           try {
           fw.write(event+"\n");
           //D.ebugPrintln("WROTE |"+event+"|");
           } catch (Exception e) {
           D.ebugPrintln(e.toString());
           D.ebugPrintln("Unable to write to disk.");
           }
           }
         */
    }

    /**
     * this is a debugging command that gives resources to a player.
     * Format: rsrcs: #cl #or #sh #wh #wo playername
     */
    protected void giveResources(StringConnection c, String mes, SOCGame game)
    {
        StringTokenizer st = new StringTokenizer(mes.substring(6));
        int[] resources = new int[SOCResourceConstants.WOOD + 1];
        int resourceType = SOCResourceConstants.CLAY;
        String name = "";
        boolean parseError = false;

        while (st.hasMoreTokens())
        {
            if (resourceType <= SOCResourceConstants.WOOD)
            {
                String token = st.nextToken();
                try
                {
                    resources[resourceType] = Integer.parseInt(token);
                    resourceType++;
                }
                catch (NumberFormatException e)
                {
                    parseError = true;
                    break;
                }
            }
            else
            {
                // get all the of the line, in case there's a space in the player name ("robot 7"),
                //  by choosing an unlikely separator character
                name = st.nextToken(Character.toString( (char) 1 )).trim();
                break;
            }
        }

        SOCPlayer pl = null;
        if (! parseError)
        {
            pl = debug_getPlayer(c, game, name);
            if (pl == null)
                parseError = true;
        }

        if (parseError)
        {
            messageToPlayer(c, game.getName(), "### Usage: " + DEBUG_COMMANDS_HELP_RSRCS);
            messageToPlayer(c, game.getName(), DEBUG_COMMANDS_HELP_PLAYER);

            return;  // <--- early return ---
        }

        SOCResourceSet rset = pl.getResources();
        int pnum = pl.getPlayerNumber();
        String outMes = "### " + pl.getName() + " gets";

        for (resourceType = SOCResourceConstants.CLAY;
                resourceType <= SOCResourceConstants.WOOD; resourceType++)
        {
            rset.add(resources[resourceType], resourceType);
            outMes += (" " + resources[resourceType]);

            // SOCResourceConstants.CLAY == SOCPlayerElement.CLAY
            messageToGame(game.getName(), new SOCPlayerElement(game.getName(), pnum, SOCPlayerElement.GAIN, resourceType, resources[resourceType]));
        }

        messageToGame(game.getName(), outMes);
    }

    /**
     * this broadcasts game information to all people connected
     * used to display the scores on the player client
     */
    protected void broadcastGameStats(SOCGame ga)
    {
        /*
           if (ga != null) {
           int scores[] = new int[SOCGame.MAXPLAYERS];
           boolean robots[] = new boolean[SOCGame.MAXPLAYERS];
           for (int i = 0; i < SOCGame.MAXPLAYERS; i++) {
           SOCPlayer player = ga.getPlayer(i);
           if (player != null) {
           if (ga.isSeatVacant(i)) {
           scores[i] = -1;
           robots[i] = false;
           } else {
           scores[i] = player.getPublicVP();
           robots[i] = player.isRobot();
           }
           } else {
           scores[i] = -1;
           }
           }
        
           broadcast(SOCGameStats.toCmd(ga.getName(), scores, robots));
           }
         */
    }

    /**
     * check for games that have expired and destroy them.
     * If games are about to expire, send a warning.
     * As of version 1.1.09, practice games ({@link SOCGame#isPractice} flag set) don't expire.
     * Is callback method every few minutes from {@link SOCGameTimeoutChecker#run()}.
     *
     * @param currentTimeMillis  The time when called, from {@link System#currentTimeMillis()}
     * @see #GAME_EXPIRE_WARN_MINUTES
     * @see #checkForExpiredTurns(long)
     */
    public void checkForExpiredGames(final long currentTimeMillis)
    {
        // Take the gameList monitor, build arrays of expired and
        // expiring-soon games, then release it.
        ArrayList expiredGameNames = new ArrayList(), expiringSoonGames = new ArrayList(), pingIdle = new ArrayList();

        // Add 2 minutes because of coarse 5-minute granularity in SOCGameTimeoutChecker.run()
        long warn_ms = (2 + GAME_EXPIRE_WARN_MINUTES) * 60L * 1000L; 

        gameList.takeMonitor();

        try
        {
            for (Enumeration k = gameList.getGamesData(); k.hasMoreElements();)
            {
                SOCGame gameData = (SOCGame) k.nextElement();
                if (gameData.isPractice)
                    continue;  // <--- Skip practice games, they don't expire ---

                long gameExpir = gameData.getExpiration();

                if (gameExpir <= currentTimeMillis)
                    expiredGameNames.add(gameData.getName());
                else if ((gameExpir - warn_ms) <= currentTimeMillis)
                    expiringSoonGames.add(gameData);
                else if ((currentTimeMillis - gameData.lastActionTime) > (GAME_TIME_EXPIRE_CHECK_MINUTES * 60 * 1000))
                    pingIdle.add(gameData);
            }
        }
        catch (Exception e)
        {
            D.ebugPrintln("Exception in checkForExpiredGames - " + e);
        }

        gameList.releaseMonitor();

        //
        // give warning on the expiring-soon games
        // Start our text messages with ">>>" to mark as urgent to the client.
        //
        if (! expiringSoonGames.isEmpty())
        {
            for (Iterator esoon = expiringSoonGames.iterator(); esoon.hasNext(); )
            {
                //
                //  Give people a few minutes' warning (they may have a few warnings)
                //
                SOCGame gameData = (SOCGame) esoon.next();
                long minutes = ((gameData.getExpiration() - currentTimeMillis) / 60000);
                if (minutes < 1L)
                    minutes = 1;  // in case of rounding down

                messageToGameUrgent(gameData.getName(), ">>> Less than "
                    + minutes + " minutes remaining.  Type *ADDTIME* to extend this game another 30 minutes.");
            }
        }

        //
        // If games are idle since previous check, send keepalive ping to their clients
        // so the network doesn't disconnect while all players are taking a break
        //
        if (! pingIdle.isEmpty())
        {
            final SOCServerPing pmsg = new SOCServerPing(GAME_TIME_EXPIRE_CHECK_MINUTES * 60);

            for (Iterator esoon = pingIdle.iterator(); esoon.hasNext(); )
                messageToGame(((SOCGame) esoon.next()).getName(), pmsg);
        }

        //
        // destroy the expired games
        //
        if (expiredGameNames.isEmpty())
        {
            return;  // <--- Early return ---
        }
        for (Iterator eg = expiredGameNames.iterator(); eg.hasNext(); )
        {
            String ga = (String) eg.next();
            messageToGameUrgent(ga, ">>> The time limit on this game has expired and will now be destroyed.");

            gameList.takeMonitor();

            try
            {
                destroyGame(ga);
            }
            catch (Exception e)
            {
                D.ebugPrintln("Exception in checkForExpired - " + e);
            }
            finally
            {
                gameList.releaseMonitor();
            }

            broadcast(SOCDeleteGame.toCmd(ga));
        }
    }

    /**
     * Check all games for robot turns that have expired, and end that turn,
     * or stop waiting for non-current-player robot actions (discard picks, etc).
     * Robot turns may end from inactivity or from an illegal placement.
     * Checks each game's {@link SOCGame#lastActionTime} field, and starts
     * a {@link ForceEndTurnThread} if the last action is older than
     * {@link #ROBOT_FORCE_ENDTURN_SECONDS}.
     *<P>
     * Is callback method every few seconds from {@link SOCGameTimeoutChecker#run()}.
     *
     * @param currentTimeMillis  The time when called, from {@link System#currentTimeMillis()}
     * @see #ROBOT_FORCE_ENDTURN_SECONDS
     * @see #checkForExpiredGames(long)
     * @since 1.1.11
     */
    public void checkForExpiredTurns(final long currentTimeMillis)
    {
        // Because nothing's currently happening in such a turn,
        // and we force the end in another thread,
        // we shouldn't need to worry about locking.
        // So, we don't need gameList.takeMonitor().

        final long inactiveTime = currentTimeMillis - (1000L * ROBOT_FORCE_ENDTURN_SECONDS); 

        try
        {
            for (Enumeration k = gameList.getGamesData(); k.hasMoreElements();)
            {
                SOCGame ga = (SOCGame) k.nextElement();
                final int gameState = ga.getGameState();

                // lastActionTime is a recent time, or might be 0 to force end
                long lastActionTime = ga.lastActionTime;
                if (lastActionTime > inactiveTime)
                    continue;

                if (gameState >= SOCGame.OVER)
                {
                    // nothing to do.
                    // bump out that time, so we don't see
                    // it again every few seconds
                    ga.lastActionTime
                        += (SOCGameListAtServer.GAME_EXPIRE_MINUTES * 60 * 1000);
                    continue;
                }
                final int cpn = ga.getCurrentPlayerNumber();
                if (cpn == -1)
                    continue;  // not started yet
                SOCPlayer pl = ga.getPlayer(cpn);

                if (gameState == SOCGame.WAITING_FOR_DISCARDS)
                {
                    // Check if we're waiting on any humans too, not on robots only

                    SOCPlayer plEnd = null;  // bot the game is waiting to hear from
                    for (int i = 0; i < ga.maxPlayers; ++i)
                    {
                        final SOCPlayer pli = ga.getPlayer(i);
                        if (! pli.getNeedToDiscard())
                            continue;

                        if (pli.isRobot())
                        {
                            if (plEnd == null)
                                plEnd = pli;
                        } else {
                            return;  // <--- Waiting on humans, don't end bot's turn ---
                        }
                    }

                    if (plEnd == null)
                        return;  // <--- Not waiting on any bot ---

                    pl = plEnd;
                } else {
                    if (! pl.isRobot())
                        return;  // <--- not a robot's turn, and not WAITING_FOR_DISCARDS ---
                }

                if (pl.getCurrentOffer() != null)
                {
                    // Robot is waiting for response to a trade offer;
                    // check against that longer timeout.
                    final long tradeInactiveTime
                        = currentTimeMillis - (1000L * ROBOT_FORCE_ENDTURN_TRADEOFFER_SECONDS);
                    if (lastActionTime > tradeInactiveTime)
                        continue;
                }

                new ForceEndTurnThread(ga, pl).start();
            }
        }
        catch (Exception e)
        {
            D.ebugPrintln("Exception in checkForExpiredTurns - " + e);
        }
    }

    /** this is a debugging command that gives a dev card to a player.
     *  <PRE> dev: cardtype player </PRE>
     *  For card-types numbers, see {@link SOCDevCardConstants}
     *  or {@link #DEBUG_COMMANDS_HELP_DEV_TYPES}.
     */
    protected void giveDevCard(StringConnection c, String mes, SOCGame game)
    {
        StringTokenizer st = new StringTokenizer(mes.substring(5));
        String name = "";
        int cardType = -1;
        boolean parseError = false;

        while (st.hasMoreTokens())
        {
            if (cardType < 0)
            {
                try
                {
                    cardType = Integer.parseInt(st.nextToken());
                }
                catch (NumberFormatException e)
                {
                    parseError = true;
                    break;
                }
            }
            else
            {
                // get all of the line, in case there's a space in the player name ("robot 7"),
                //  by choosing an unlikely separator character
                name = st.nextToken(Character.toString( (char) 1 )).trim();
                break;
            }
        }

        SOCPlayer pl = null;
        if (! parseError)
        {
            pl = debug_getPlayer(c, game, name);
            if (pl == null)
                parseError = true;
        }

        if (parseError)
        {
            messageToPlayer(c, game.getName(), "### Usage: " + DEBUG_COMMANDS_HELP_DEV);
            messageToPlayer(c, game.getName(), DEBUG_COMMANDS_HELP_PLAYER);
            messageToPlayer(c, game.getName(), DEBUG_COMMANDS_HELP_DEV_TYPES);

            return;  // <--- early return ---
        }

        SOCDevCardSet dcSet = pl.getDevCards();
        dcSet.add(1, SOCDevCardSet.NEW, cardType);

        int pnum = pl.getPlayerNumber();
        String outMes = "### " + pl.getName() + " gets a " + cardType + " card.";
        messageToGame(game.getName(), new SOCDevCard(game.getName(), pnum, SOCDevCard.DRAW, cardType));
        messageToGame(game.getName(), outMes);
    }

    /**
     * Given a player <tt>name</tt> or player number, find that player in the game.
     * If not found by name, or player number doesn't match expected format, sends a message to the
     * requesting user.
     *
     * @param c  Connection of requesting debug user
     * @param ga  Game to find player
     * @param name  Player name, or player position number in format "<tt>#3</tt>"
     *     numbered 0 to {@link SOCGame#maxPlayers ga.maxPlayers}-1 inclusive
     * @return  {@link SOCPlayer} with this name or number, or <tt>null</tt> if an error was sent to the user
     * @since 1.1.20
     */
    private SOCPlayer debug_getPlayer(final StringConnection c, final SOCGame ga, final String name)
    {
        if (name.length() == 0)
        {
            return null;  // <--- early return ---
        }

        SOCPlayer pl = null;

        if (name.startsWith("#") && (name.length() > 1) && Character.isDigit(name.charAt(1)))
        {
            String err = null;
            final int max = ga.maxPlayers - 1;
            try
            {
                final int i = Integer.parseInt(name.substring(1).trim());
                if (i > max)
                    err = "Max player number is " + Integer.toString(max);
                else if (ga.isSeatVacant(i))
                    err = "Player number " + Integer.toString(i) + " is vacant";
                else
                    pl = ga.getPlayer(i);
            }
            catch (NumberFormatException e) {
                err = "Player number format is # followed by the number (0 to "
                    + Integer.toString(max) + " inclusive)";
            }

            if (err != null)
            {
                messageToPlayer(c, ga.getName(), "### " + err);

                return null;  // <--- early return ---
            }
        }

        if (pl == null)
            pl = ga.getPlayer(name);
        if (pl == null)
            messageToPlayer(c, ga.getName(), "### Player name not found: " + name);

        return pl;
    }

    /**
     * Quick-and-dirty command line parsing of a game option.
     * Calls {@link SOCGameOption#setKnownOptionCurrentValue(SOCGameOption)}.
     * If problems, throws an error message with text to print to console.
     * @param optNameValue Game option name+value, of <tt>optname=optvalue</tt> form expected by
     *                     {@link SOCGameOption#parseOptionNameValue(String, boolean)}.
     *                     Option keyname is case-insensitive.
     * @param optsAlreadySet  For tracking, game option names we've already encountered on the command line.
     *                        This method will add (<tt>optName</tt>, <tt>optNameValue</tt>) to this map.
     *                        Can be <tt>null</tt> if not needed.
     * @return the parsed SOCGameOption
     * @throws IllegalArgumentException if bad name, bad value, or already set from command line.
     *         {@link Throwable#getMessage()} will have problem details:
     *         <UL>
     *         <LI> Unknown or malformed game option name, from
     *           {@link SOCGameOption#parseOptionNameValue(String, boolean)}
     *         <LI> Bad option value, from {@link SOCGameOption#setKnownOptionCurrentValue(SOCGameOption)}
     *         <LI> Appears twice on command line, name is already in <tt>optsAlreadySet</tt>
     *         </UL>
     * @since 1.1.07
     */
    public static SOCGameOption parseCmdline_GameOption
        (final String optNameValue, HashMap optsAlreadySet)
        throws IllegalArgumentException
    {
        SOCGameOption op = SOCGameOption.parseOptionNameValue(optNameValue, true);
        if (op == null) 
            throw new IllegalArgumentException("Unknown or malformed game option: " + optNameValue);

        if (op.optType == SOCGameOption.OTYPE_UNKNOWN)
            throw new IllegalArgumentException("Unknown game option: " + op.optKey);

        if ((optsAlreadySet != null) && optsAlreadySet.containsKey(op.optKey))
            throw new IllegalArgumentException("Game option cannot appear twice on command line: " + op.optKey);

        try
        {
            SOCGameOption.setKnownOptionCurrentValue(op);
            if (optsAlreadySet != null)
                optsAlreadySet.put(op.optKey, optNameValue);
        } catch (Exception t) {
            throw new IllegalArgumentException("Bad value, cannot set game option: " + op.optKey);
        }

        return op;
    }

    /**
     * Quick-and-dirty parsing of command-line arguments with dashes.
     *<P>
     * Checks first for the optional server startup properties file <tt>"jsserver.properties"</tt>
     * ({@link #SOC_SERVER_PROPS_FILENAME}).
     * If the file exists but there is an error reading it, calls {@link System#exit(int) System.exit(1)}
     * to exit because currently only <tt>main(..)</tt> calls this method.
     * For details on the java properties file syntax (<tt>#</tt> starts a comment line, etc),
     * see {@link Properties#load(java.io.InputStream)}.
     *<P>
     * If a property appears on the command line and also in <tt>jsserver.properties</tt>,
     * the command line's value overrides the file's.
     *<P>
     * If any game options are set ("-o", "--option"), then
     * {@link #hasSetGameOptions} is set to true, and
     * {@link SOCGameOption#setKnownOptionCurrentValue(SOCGameOption)}
     * is called to set them globally.
     *<P>
     * If <tt>jsserver.properties</tt> file contains game option properties (<tt>jsettlers.gameopt.*</tt>),
     * they will be checked for possible problems:
     *<UL>
     * <LI> Empty game option name after <tt>jsettlers.gameopt.</tt> prefix
     * <LI> Unknown option name
     * <LI> Problem with name or value reported from {@link #parseCmdline_GameOption(String, HashMap)}
     *</UL>
     * See {@link #PROP_JSETTLERS_GAMEOPT_PREFIX} for game option property syntax.
     *<P>
     * If <tt>args[]</tt> is empty, it will use defaults for
     * {@link #PROP_JSETTLERS_PORT} and {@link #PROP_JSETTLERS_CONNECTIONS}}.
     *<P>
     * Does not use a {@link #PROP_JSETTLERS_STARTROBOTS} default, that's
     * handled in {@link #initSocServer(String, String, Properties)}.
     *<P>
     * Sets {@link #hasStartupPrintAndExit} if appropriate.
     *
     * @param args args as passed to main
     * @return Properties collection of args, or null for argument error or unknown argument(s).
     *     Will contain at least {@link #PROP_JSETTLERS_PORT},
     *     {@link #PROP_JSETTLERS_CONNECTIONS},
     *     {@link SOCDBHelper#PROP_JSETTLERS_DB_USER},
     *     {@link SOCDBHelper#PROP_JSETTLERS_DB_PASS}.
     * @since 1.1.07
     */
    public static Properties parseCmdline_DashedArgs(String[] args)
    {
        // javadoc note: This public method's javadoc section about game option properties
        // is copied for visibility from private init_propsSetGameopts.  If you update the
        // text here, also update the same text in init_propsSetGameopts's javadoc.

        Properties argp = new Properties();  // returned props, from "jsserver.properties" file and args[]
        boolean hasArgProblems = false;  // warn about each during parsing, instead of returning after first one
        boolean doPrintOptions = false;  // if true, call printGameOptions() at end of method

        // Check against options which are on command line twice: Can't just check argp keys because
        // argp is loaded from jsserver.properties, then command-line properties can override
        // anything set from there
        HashSet cmdlineOptsSet = new HashSet();
        HashMap gameOptsAlreadySet = new HashMap();  // used and updated by parseCmdline_GameOption

        /**
         * Read jsserver.properties first
         */
        try
        {
            final File pf = new File(SOC_SERVER_PROPS_FILENAME);
            if (pf.exists())
            {
                if (pf.isFile() && pf.canRead())
                {
                    System.err.println("Reading startup properties from " + SOC_SERVER_PROPS_FILENAME);
                    FileInputStream fis = new FileInputStream(pf);
                    argp.load(fis);
                    fis.close();
                    try
                    {
                        init_propsSetGameopts(argp);
                    }
                    catch (IllegalArgumentException e)
                    {
                        System.err.println(e.getMessage());
                        System.err.println
                            ("*** Error in properties file " + SOC_SERVER_PROPS_FILENAME + ": Exiting.");
                        System.exit(1);
                    }
                } else {
                    System.err.println
                    ("*** Properties file " + SOC_SERVER_PROPS_FILENAME
                      + " exists but isn't a readable plain file: Exiting.");
                    System.exit(1);
                }
            }
        }
        catch (Exception e)
        {
            // SecurityException from .exists, .isFile, .canRead
            // IOException from FileInputStream construc [FileNotFoundException], props.load
            // IllegalArgumentException from props.load (malformed Unicode escape)
            System.err.println
                ("*** Error reading properties file " + SOC_SERVER_PROPS_FILENAME
                 + ", exiting: " + e.toString());
            if (e.getMessage() != null)
                System.err.println("    : " + e.getMessage());
            System.exit(1);
        }

        /**
         * Now parse args[]
         */
        final int pfxL = PROP_JSETTLERS_GAMEOPT_PREFIX.length();
        int aidx = 0;
        while ((aidx < args.length) && (args[aidx].startsWith("-")))
        {
            String arg = args[aidx];

            if (arg.equals("-V") || arg.equalsIgnoreCase("--version"))
            {
                Version.printVersionText(System.err, "Java Settlers Server ");
                hasStartupPrintAndExit = true;
            }
            else if (arg.equalsIgnoreCase("-h") || arg.equals("?") || arg.equals("-?")
                     || arg.equalsIgnoreCase("--help"))
            {
                printUsage(true);
                hasStartupPrintAndExit = true;
            }
            else if (arg.startsWith("-o") || arg.equalsIgnoreCase("--option"))
            {
                hasSetGameOptions = true;

                boolean printedMsg = false;
                String argValue;
                if (arg.startsWith("-o") && (arg.length() > 2))
                {
                    argValue = arg.substring(2);
                } else {
                    ++aidx;
                    if (aidx < args.length)
                        argValue = args[aidx];
                    else 
                        argValue = null;
                }
                if (argValue != null)
                {
                    try
                    {
                        // canonicalize opt's keyname to all-uppercase
                        final int i = argValue.indexOf('=');
                        if (i > 0)
                        {
                            String oKey = argValue.substring(0, i),
                                   okUC = oKey.toUpperCase(Locale.US);
                            if (! oKey.equals(okUC))
                                argValue = okUC + argValue.substring(i);
                        }

                        // parse this opt, update known option's current value
                        SOCGameOption opt = parseCmdline_GameOption(argValue, gameOptsAlreadySet);

                        // Add or update in argp, in case this gameopt property also appears in the properties file;
                        // otherwise the SOCServer constructor will reset the known opt current value
                        // back to the properties file's contents, instead of keeping the command-line opt value.
                        // if not found, don't need to add it to argp: option's current value is already set.
                        final String propKey = PROP_JSETTLERS_GAMEOPT_PREFIX + opt.optKey;
                        if (argp.containsKey(propKey))
                            argp.put(propKey, opt.getPackedValue().toString());
                    } catch (IllegalArgumentException e) {
                        argValue = null;
                        System.err.println(e.getMessage());
                        printedMsg = true;
                    }
                }
                if (argValue == null)
                {
                    if (! printedMsg)
                    {
                        System.err.println("Missing required option name/value after " + arg);
                        System.err.println();
                    }
                    hasArgProblems = true;
                    doPrintOptions = true;
                }
            } else if (arg.startsWith("-D"))  // java-style props defines
            {
                // We get to here when a user uses -Dname=value. However, in
                // some cases, the OS goes ahead and parses this out to args
                //   {"-Dname", "value"}
                // so instead of parsing on "=", we just make the "-D"
                // characters go away and skip one argument forward.

                String name;
                if (arg.length() == 2) // "-D something"
                {
                    ++aidx;
                    if (aidx < args.length)
                    {
                        name = args[aidx];
                    } else {
                        System.err.println("Missing property name after -D");
                        return null;
                    }
                } else {
                    name = arg.substring(2, arg.length());
                }
                String value = null;
                int posEq = name.indexOf("=");
                if (posEq > 0)
                {
                    value = name.substring(posEq + 1);
                    name = name.substring(0, posEq);
                }
                else if (aidx < args.length - 1)
                {
                    ++aidx;
                    value = args[aidx];
                }
                else {
                    System.err.println("Missing value for property " + name);
                    return null;
                }
                if (cmdlineOptsSet.contains(name))
                {
                    System.err.println("Property cannot appear twice on command line: " + name);
                    return null;
                }
                argp.setProperty(name, value);
                cmdlineOptsSet.add(name);

                // Is it a game option default value?
                if (name.startsWith(PROP_JSETTLERS_GAMEOPT_PREFIX))
                {
                    final String optKey = name.substring(pfxL);
                    boolean ok = true;
                    if (optKey.length() == 0)
                    {
                        System.err.println("Empty game option name in property key: " + name);
                        ok = false;
                    } else {
                        hasSetGameOptions = true;
                        try
                        {
                            parseCmdline_GameOption(optKey + "=" + value, gameOptsAlreadySet);
                        } catch (IllegalArgumentException e) {
                            ok = false;
                            System.err.println(e.getMessage());
                            doPrintOptions = true;
                        }
                    }

                    if (! ok)
                        hasArgProblems = true;
                }
            }
            else if (arg.startsWith("--pw-reset"))
            {
                String name = null;

                if (arg.length() == 10)
                {
                    // next arg should be username
                    ++aidx;
                    if (aidx < args.length)
                        name = args[aidx];
                } else {
                    // this arg should continue: =username
                    if (arg.charAt(10) != '=')
                    {
                        System.err.println("Unknown argument: " + arg);
                        return null;
                    }
                    name = arg.substring(11);
                }

                if ((name == null) || (name.length() == 0))
                {
                    System.err.println("Missing username after --pw-reset");
                    return null;
                }
                argp.setProperty(SOCDBHelper.PROP_IMPL_JSETTLERS_PW_RESET, name);

            } else {
                System.err.println("Unknown argument: " + arg);
                hasArgProblems = true;
            }

            ++aidx;
        }

        // Done parsing flagged parameters.
        // Look for the positional ones.
        if ((args.length - aidx) == 0)
        {
            // No positional parameters: Take defaults.
            // Check each one before setting it, in case was specified in properties file
            if (! argp.containsKey(PROP_JSETTLERS_PORT))
                argp.setProperty(PROP_JSETTLERS_PORT, Integer.toString(SOC_PORT_DEFAULT));
            if (! argp.containsKey(PROP_JSETTLERS_CONNECTIONS))
                argp.setProperty(PROP_JSETTLERS_CONNECTIONS, Integer.toString(SOC_MAXCONN_DEFAULT));
            // PROP_JSETTLERS_DB_USER, _PASS are set below
        } else {
            // Require at least 2 parameters, or allow 4 but not 3
            // (optional db user also requires db password)
            final int L = args.length - aidx;
            if ((L < 2) || (L == 3))
            {
                if (! printedUsageAlready)
                {
                    // Print this hint only if parsed OK up to now, and
                    // if we haven't responded to -h / --help already.
                    System.err.println("SOCServer: Some required command-line parameters are missing.");
                }
                printUsage(false);
                return null;
            }

            argp.setProperty(PROP_JSETTLERS_PORT, args[aidx]);  ++aidx;
            argp.setProperty(PROP_JSETTLERS_CONNECTIONS, args[aidx]);  ++aidx;

            // Optional DB user and password
            if ((args.length - aidx) > 0)
            {
                // Check DB user and password against any -D parameters in properties
                if (cmdlineOptsSet.contains(SOCDBHelper.PROP_JSETTLERS_DB_USER)
                    || cmdlineOptsSet.contains(SOCDBHelper.PROP_JSETTLERS_DB_PASS))
                {
                    System.err.println("SOCServer: DB user and password cannot appear twice on command line.");
                    printUsage(false);
                    return null;
                }

                argp.setProperty(SOCDBHelper.PROP_JSETTLERS_DB_USER, args[aidx]);  ++aidx;
                argp.setProperty(SOCDBHelper.PROP_JSETTLERS_DB_PASS, args[aidx]);  ++aidx;
            }
        }

        // If no positional parameters db_user db_pass, take defaults.
        // Check each one before setting it, in case was specified in properties file
        if (! argp.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_USER))
        {
            argp.setProperty(SOCDBHelper.PROP_JSETTLERS_DB_USER, "socuser");
            if (! argp.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_PASS))
                argp.setProperty(SOCDBHelper.PROP_JSETTLERS_DB_PASS, "socpass");
        }
        else if (! argp.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_PASS))
        {
            // specified _USER but not _PASS: store "" for empty password instead of default
            argp.setProperty(SOCDBHelper.PROP_JSETTLERS_DB_PASS, "");
        }

        // Make sure no more flagged parameters
        if (aidx < args.length)
        {
            if (! printedUsageAlready)
            {
                if (args[aidx].startsWith("-"))
                {
                    System.err.println("SOCServer: Options must appear before, not after, the port number.");
                } else {
                    System.err.println("SOCServer: Options must appear before the port number, not after dbuser/dbpass.");
                }
                printUsage(false);
            }

            return null;
        }

        if (doPrintOptions)
            printGameOptions();

        if (hasArgProblems)
            return null;

        // Done parsing.
        return argp;
    }

    /**
     * Set game option defaults from any jsettlers.gameopt.* server properties found (<tt>jsettlers.gameopt.*</tt>).
     * Option keynames are case-insensitive past that prefix.
     * See {@link #PROP_JSETTLERS_GAMEOPT_PREFIX} for expected syntax.
     * Calls {@link #parseCmdline_GameOption(String, HashMap)} for each one found.
     *
     * @param pr  Properties which may contain {@link #PROP_JSETTLERS_GAMEOPT_PREFIX}* entries.
     *       If <tt>props<tt> contains entries with non-uppercase gameopt names, cannot be read-only:
     *       Will replace keys such as <tt>"jsettlers.gameopt.vp"</tt> with their canonical
     *       uppercase equivalent: <tt>"jsettlers.gameopt.VP"</tt>
     * @throws IllegalArgumentException if any game option property has a bad name or value.
     *     {@link Throwable#getMessage()} will collect all option problems to 1 string, separated by <tt>"\n"</tt>:
     *     <UL>
     *     <LI> Empty game option name after <tt>jsettlers.gameopt.</tt> prefix
     *     <LI> Unknown option name
     *     <LI> Problem with name or value reported from {@link #parseCmdline_GameOption(String, HashMap)}
     *     </UL>
     * @since 1.1.20
     */
    private static final void init_propsSetGameopts(Properties pr)
        throws IllegalArgumentException
    {
        // javadoc note: This method is private; public parseCmdline_DashedArgs calls it, so for visibility
        // this method's javadoc section about game option properties is also there.  If you update text here,
        // also update the same text in parseCmdline_DashedArgs's javadoc.

        final int pfxL = PROP_JSETTLERS_GAMEOPT_PREFIX.length();
        StringBuilder problems = null;

        // First, canonicalize any game opt key names to uppercase
        {
            ArrayList makeUpper = new ArrayList();
            Iterator it = pr.keySet().iterator();
            while (it.hasNext())
            {
                Object k = it.next();
                if (! ((k instanceof String) && ((String) k).startsWith(PROP_JSETTLERS_GAMEOPT_PREFIX)))
                    continue;

                final String optKey = ((String) k).substring(pfxL),
                             optUC = optKey.toUpperCase(Locale.US);
                if (! optKey.equals(optUC))
                {
                    makeUpper.add(k);
                    makeUpper.add(PROP_JSETTLERS_GAMEOPT_PREFIX + optUC);
                }
            }

            for (int i = 0; i < makeUpper.size(); i += 2)
            {
                final Object propKey = makeUpper.get(i),
                             propUC = makeUpper.get(i + 1);
                pr.put(propUC, pr.get(propKey));
                pr.remove(propKey);
            }
        }

        // Now parse, set current values, and look for problems
        Iterator it = pr.keySet().iterator();
        while (it.hasNext())
        {
            Object k = it.next();
            if (! ((k instanceof String) && ((String) k).startsWith(PROP_JSETTLERS_GAMEOPT_PREFIX)))
                continue;

            final String optKey = ((String) k).substring(pfxL);
            if (optKey.length() == 0)
            {
                if (problems == null)
                    problems = new StringBuilder();
                else
                    problems.append("\n");
                problems.append("Empty game option name in property key: ");
                problems.append(k);
                continue;
            }

            try
            {
                // parse this gameopt and set its current value in SOCGameOptions static set of known opts
                parseCmdline_GameOption(optKey + "=" + pr.getProperty((String) k), null);
                hasSetGameOptions = true;
            } catch (IllegalArgumentException e) {
                if (problems == null)
                    problems = new StringBuilder();
                else
                    problems.append("\n");
                problems.append(e.getMessage());
            }
        }

        if (problems != null)
            throw new IllegalArgumentException(problems.toString());
    }

    /**
     * If command line contains <tt>--pw-reset=username</tt>,
     * prompt for and change that user's password.
     *<P>
     * If successful, sets {@link #getUtilityModeMessage()} to "The password was changed"
     * or similar; if unsuccessful (no db, user not found, etc), prints an error and
     * sets {@link #getUtilityModeMessage()} to <tt>null</tt>.
     *
     * @param uname  Username to change password
     * @since 1.1.20
     */
    private void init_resetUserPassword(final String uname)
    {
        utilityModeMessage = null;

        if (! SOCDBHelper.isInitialized())
        {
            System.err.println("--pw-reset requires database connection properties.");
            return;
        }

        String dbUname = null;
        try
        {
            dbUname = SOCDBHelper.getUser(uname);
            if (dbUname == null)
            {
                System.err.println("pw-reset user " + uname + " not found in database.");
                return;
            }
        } catch (SQLException e) {
            System.err.println("Error while querying user " + uname + ": " + e.getMessage());
            return;
        }

        System.out.println("Resetting password for " + dbUname + ".");

        StringBuilder pw1 = null;
        boolean hasNewPW = false;
        for (int tries = 0; tries < 3; ++tries)
        {
            if (tries > 0)
                System.out.println("Passwords do not match; try again.");

            pw1 = readPassword("Enter the new password:");
            if ((pw1 == null) || (pw1.length() == 0))
                break;

            StringBuilder pw2 = readPassword("Confirm new password:  ");

            if (pw2 == null)
            {
                break;
            } else {
                // compare; unfortunately there is no StringBuffer.equals(sb) method

                final int L1 = pw1.length(), L2 = pw2.length();
                if (L1 == L2)
                {
                    final char[] pc1 = new char[L1], pc2 = new char[L2];
                    pw1.getChars(0, L1, pc1, 0);
                    pw2.getChars(0, L2, pc2, 0);

                    hasNewPW = (Arrays.equals(pc1, pc2));

                    Arrays.fill(pc1, (char) 0);
                    Arrays.fill(pc2, (char) 0);
                }

                if (hasNewPW)
                {
                    clearBuffer(pw2);
                    break;
                }
            }
        }

        if (! hasNewPW)
        {
            if (pw1 != null)
                clearBuffer(pw1);
            System.err.println("Password reset cancelled.");
            return;
        }

        try
        {
            SOCDBHelper.updateUserPassword(dbUname, pw1.toString());
            clearBuffer(pw1);
            utilityModeMessage = "The password was changed";
        } catch (IllegalArgumentException e) {
            System.err.println("Password was too long, max length is " + SOCDBHelper.getMaxPasswordLength());
        } catch (SQLException e) {
            System.err.println("Error while resetting password: " + e.getMessage());
        }

    }

    /**
     * Print a security-action audit message to {@link System#out} in a standard format.
     *<H5>Example with object:</H5>
     *   Audit: Requested jsettlers account creation, already exists: '<tt>obj</tt>'
     *      by '<tt>req</tt>' from <tt>reqHost</tt> at <tt>at</tt>
     *<H5>Example without object:</H5>
     *   Audit: Requested jsettlers account creation, this requester not on account admins list:
     *      '<tt>req</tt>' from <tt>reqHost</tt> at <tt>at</tt>
     *
     * @param req  Requesting user, or <tt>null</tt> if unknown
     * @param msg  Message text
     * @param obj  Object affected by the action, or <tt>null</tt> if none
     * @param at   Timestamp, or <tt>null</tt> to use current time
     * @param reqHost  Requester client's hostname, from {@link StringConnection#host()}
     * @since 1.1.20
     */
    private void printAuditMessage
        (final String req, final String msg, final String obj, Date at, final String reqHost)
    {
        if (at == null)
            at = new Date();

        if (obj != null)
            System.out.println
                ("Audit: " + msg + ": '" + obj
                 + ((req != null) ? "' by '" + req : "")
                 + "' from " + reqHost + " at " + at);
        else
            System.out.println
                ("Audit: " + msg + ": "
                 + ((req != null) ? "'" + req + "'" : "")
                 + " from " + reqHost + " at " + at);
    }

    /**
     * Track whether we've already called {@link #printUsage(boolean)}.
     * @since 1.1.07
     */
    public static boolean printedUsageAlready = false;

    /**
     * Print command line parameter information, including options ("--" / "-").
     * @param longFormat short or long? 
     * Long format gives details and also calls {@link Version#printVersionText(java.io.PrintStream, String)} beforehand.
     * Short format is printed at most once, after checking {@link #printedUsageAlready}.
     * @since 1.1.07
     */
    public static void printUsage(final boolean longFormat)
    {
        if (printedUsageAlready && ! longFormat)
            return;
        printedUsageAlready = true;

        if (longFormat)
        {
            Version.printVersionText(System.err, "Java Settlers Server ");
        }
        System.err.println("usage: java soc.server.SOCServer [option...] port_number max_connections [dbUser dbPass]");
        if (longFormat)
        {
            System.err.println("usage: recognized options:");
            System.err.println("       -V or --version    : print version information");
            System.err.println("       -h or --help or -? : print this screen");
            System.err.println("       -o or --option name=value : set per-game options' default values");
            System.err.println("       -D name=value : set properties such as " + SOCDBHelper.PROP_JSETTLERS_DB_USER);
            System.err.println("-- Recognized properties: --");
            for (int i = 0; i < PROPS_LIST.length; ++i)
            {
                System.err.print("\t");
                System.err.print(PROPS_LIST[i]);    // name
                ++i;
                System.err.print("\t");
                System.err.println(PROPS_LIST[i]);  // description
            }
            printGameOptions();
        } else {
            System.err.println("       use java soc.server.SOCServer --help to see recognized options");            
        }
    }

    /**
     * Print out the list of possible game options, and current values.
     * @since 1.1.07
     */
    public static void printGameOptions()
    {
        final Hashtable allopts = SOCGameOption.getAllKnownOptions();

        System.err.println("-- Current default game options: --");

        ArrayList okeys = new ArrayList(allopts.keySet());
        Collections.sort(okeys);
        for (Iterator it = okeys.iterator(); it.hasNext(); )
        {
            final String okey = (String) it.next();
            SOCGameOption opt = (SOCGameOption) allopts.get(okey);
            boolean quotes = (opt.optType == SOCGameOption.OTYPE_STR) || (opt.optType == SOCGameOption.OTYPE_STRHIDE);
            // OTYPE_* - consider any type-specific output in this method.

            StringBuffer sb = new StringBuffer("  ");
            sb.append(okey);
            sb.append(" (");
            sb.append(SOCGameOption.optionTypeName(opt.optType));
            sb.append(") ");
            if (quotes)
                sb.append('"');
            opt.packValue(sb);
            if (quotes)
                sb.append('"');
            sb.append("  ");
            sb.append(opt.optDesc);
            System.err.println(sb.toString());
            if (opt.enumVals != null)  // possible values of OTYPE_ENUM
            {
                sb = new StringBuffer("    option choices (1-n): ");
                for (int i = 1; i <= opt.maxIntValue; ++i)
                {
                    sb.append(' ');
                    sb.append(i);
                    sb.append(' ');
                    sb.append(opt.enumVals[i-1]);
                    sb.append(' ');
                }
                System.err.println(sb.toString());
            }
        }

        int optsVers = SOCGameOption.optionsMinimumVersion(allopts);
        if (optsVers > -1)
        {
            System.err.println
                ("*** Note: Client version " + Version.version(optsVers)
                 + " or newer is required for these game options. ***");
            System.err.println
                ("          Games created with different options may not have this restriction.");
        }
    }

    /**
     * Clear the contents of a StringBuffer by setting to ' '
     * each character in its current {@link StringBuilder#length()}.
     * @param sb  StringBuilder to clear
     * @since 1.1.20
     */
    private static void clearBuffer(StringBuilder sb)
    {
        final int L = sb.length();
        for (int i = 0; i < L; ++i)
            sb.setCharAt(i, (char) 0);
    }

    /**
     * Buffered {@link System#in} for {@link #readPassword(String)},
     * is <tt>null</tt> until first call to that method.
     * @since 1.1.20
     */
    private static BufferedReader sysInBuffered = null;

    /**
     * Read a password from the console; currently used for password reset.
     * Blocks the calling thread while waiting for input.
     *<P>
     * This rudimentary method exists for compatability: java 1.4 nor 1.5 doesn't have
     * <tt>System.console.readPassword()</tt>, and the Eclipse console also
     * doesn't offer <tt>System.console</tt>.
     *<P>
     * <B>The input is not masked</B> because there's no cross-platform way to do so in 1.4 or 1.5.
     *
     * @param prompt  Optional password prompt; default is "Password:"
     * @return  The password read, or an empty string "" if an error occurred.
     *     This is returned as a mutable StringBuilder
     *     so the caller can clear its contents when done, using
     *     {@link #clearBuffer(StringBuilder)}.
     *     If ^C or an error occurs, returns <tt>null</tt>.
     * @since 1.1.20
     */
    private static StringBuilder readPassword(String prompt)
    {
        // java 1.4, 1.5 doesn't have System.console.readPassword
        // (TODO) consider reflection for 1.6+ JREs

        // System.in can read only an entire line (no portable raw mode in 1.4 or 1.5),
        // so we can't mask after each character.

        if (prompt == null)
            prompt = "Password:";

        System.out.print(prompt);
        System.out.print(' ');
        System.out.flush();

        if (sysInBuffered == null)
            sysInBuffered = new BufferedReader(new InputStreamReader(System.in));

        try
        {
            StringBuilder sb = new StringBuilder();
            sb.append(sysInBuffered.readLine());

            // Remove trailing newline char(s)
            while (true)
            {
                int L = sb.length();
                if (L == 0)
                    break;

                final char ch = sb.charAt(L - 1);
                if ((ch != '\n') && (ch != '\r'))
                    break;

                sb.setLength(L - 1);
            }

            return sb;
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * Starting the server from the command line.
     * Creates and starts a {@link SOCServer} via {@link #SOCServer(int, Properties)}.
     *<P>
     * Checks for the optional server startup properties file <tt>jsserver.properties</tt>,
     * and parses the command line for switches. If a property appears on the command line and
     * also in <tt>jsserver.properties</tt>, the command line's value overrides the file's.
     *<P>
     * If there are problems with the network setup, the jar packaging,
     * or with running a {@link SOCDBHelper#PROP_JSETTLERS_DB_SCRIPT_SETUP db setup script}
     * or {@link SOCDBHelper#PROP_JSETTLERS_DB_UPGRADE__SCHEMA schema upgrade},
     * this method will call {@link System#exit(int) System.exit(1)}.
     *<P>
     * If a db setup script runs successfully,
     * this method will call {@link System#exit(int) System.exit(2)}.
     *
     * @param args  arguments: port number, etc
     * @see #printUsage(boolean)
     */
    static public void main(String[] args)
    {
        Properties argp = parseCmdline_DashedArgs(args);  // also reads jsserver.properties if exists
        if (argp == null)
        {
            printUsage(false);
            return;
        }

        if (hasStartupPrintAndExit)
        {
            return;
        }

        if (Version.versionNumber() == 0)
        {
            System.err.println("\n*** Packaging Error in server JAR: Cannot determine JSettlers version. Exiting now.");
            System.exit(1);
        }

        try
        {
            int port = Integer.parseInt(argp.getProperty(PROP_JSETTLERS_PORT));

            // SOCServer constructor will also print game options if we've set them on
            // commandline, or if any option defaults require a minimum client version.

            try
            {
                SOCServer server = new SOCServer(port, argp);
                if (! server.hasUtilityModeProperty())
                {
                    server.setPriority(5);
                    server.start();  // <---- Start the Main SOCServer Thread ----
                } else {
                    String pval = argp.getProperty(SOCDBHelper.PROP_IMPL_JSETTLERS_PW_RESET);
                    if (pval != null)
                        server.init_resetUserPassword(pval);

                    final String msg = server.getUtilityModeMessage();
                    System.err.println(
                        (msg != null)
                            ? "\n" + msg + ". Exiting now.\n"
                            : "\nExiting now.\n"
                        );
                }

                // Most threads are started in the SOCServer constructor, via initSocServer.
                // Messages from clients are handled in processCommand's loop.
            }
            catch (SocketException e)
            {
                // network setup problem
                System.err.println(e.getMessage());  // "* Exiting due to network setup problem: ..."
                System.exit (1);
            }
            catch (EOFException e)
            {
                // The sql setup script or schema upgrade was ran successfully by initialize;
                // exit server, user will re-run without the setup script or schema upgrade param.
                if (argp.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_SCRIPT_SETUP))
                {
                    System.err.println("\nDB setup script was successful. Exiting now.\n");
                } else {
                    // assume is from SOCDBHelper.PROP_JSETTLERS_DB_UPGRADE__SCHEMA
                    // and getMessage() is from initSocServer's call to SOCDBHelper.upgradeSchema();
                    // text will be "DB schema upgrade was successful", possibly with detail like
                    // "some upgrade tasks will complete in the background during normal server operation".

                    System.err.println("\n" + e.getMessage() + ". Exiting now.\n");
                }
                System.exit(2);
            }
            catch (SQLException e)
            {
                // the sql setup script was ran by initialize, but failed to complete.
                // or, a db URL was specified and server was unable to connect.
                // exception detail was printed in initSocServer.
                if (argp.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_SCRIPT_SETUP))
                    System.err.println("\n* DB setup script failed. Exiting now.\n");
                else if (argp.containsKey(SOCDBHelper.PROP_JSETTLERS_DB_UPGRADE__SCHEMA))
                    System.err.println("\n* DB schema upgrade failed. Exiting now.\n");
                System.exit(1);
            }
            catch (IllegalArgumentException e)
            {
                System.err.println
                    ("\n" + e.getMessage()
                     + "\n* Error in game options properties: Exiting now.\n");
                System.exit(1);
            }
            catch (IllegalStateException e)
            {
                System.err.println
                    ("\n" + e.getMessage()
                     + "\n* Packaging Error in server JAR: Exiting now.\n");
                System.exit(1);
            }
        }
        catch (RuntimeException e)
        {
            System.err.println
                ("\n" + e.getMessage()
                 + "\n* Internal error during startup: Exiting now.\n");
            e.printStackTrace();
            System.exit(1);
        }
        catch (Throwable e)
        {
            printUsage(false);
            return;
        }

    }  // main

    /**
     * Each local robot gets its own thread.
     * Equivalent to main thread in SOCRobotClient in network games.
     *<P>
     * Before 1.1.09, this class was part of SOCPlayerClient.
     * @see SOCServer#setupLocalRobots(int, int)
     * @since 1.1.00
     */
    private static class SOCPlayerLocalRobotRunner implements Runnable
    {
        /**
         * All the started {@link SOCRobotClient}s. Key is the bot nickname.
         *<P>
         *<b>Note:</b> If a bot is disconnected from the server, it's not
         * removed from this list, because the same bot will try to reconnect.
         * To see if a bot is connected, check {@link SOCServer#robots} instead.
         * @since 1.1.13
         */
        public static Hashtable robotClients = new Hashtable();

        SOCRobotClient rob;

        protected SOCPlayerLocalRobotRunner (SOCRobotClient rc)
        {
            rob = rc;
        }

        public void run()
        {
            final String rname = rob.getNickname();
            Thread.currentThread().setName("robotrunner-" + rname);
            robotClients.put(rname, rob);
            rob.init();
        }

        /**
         * Create and start a robot client within a {@link SOCPlayerLocalRobotRunner} thread.
         * After creating it, {@link Thread#yield() yield} the current thread and then sleep
         * 75 milliseconds, to give the robot time to start itself up.
         * The SOCPlayerLocalRobotRunner's run() will add the {@link SOCRobotClient} to {@link #robotClients}.
         * @param rname  Name of robot
         * @param strSocketName  Server's stringport socket name, or null
         * @param port    Server's tcp port, if <tt>strSocketName</tt> is null
         * @param cookie  Cookie for robot connections to server
         * @since 1.1.09
         * @see SOCServer#setupLocalRobots(int, int)
         * @throws ClassNotFoundException  if a robot class, or SOCDisplaylessClient,
         *           can't be loaded. This can happen due to packaging of the server-only JAR.
         * @throws LinkageError  for same reason as ClassNotFoundException
         */
        public static void createAndStartRobotClientThread
            (final String rname, final String strSocketName, final int port, final String cookie)
            throws ClassNotFoundException, LinkageError
        {
            SOCRobotClient rcli;
            if (strSocketName != null)
                rcli = new SOCRobotClient(strSocketName, rname, "pw", cookie);
            else
                rcli = new SOCRobotClient("localhost", port, rname, "pw", cookie);
            Thread rth = new Thread(new SOCPlayerLocalRobotRunner(rcli));
            rth.setDaemon(true);
            rth.start();  // run() will add to robotClients

            Thread.yield();
            try
            {
                Thread.sleep(75);  // Let that robot go for a bit.
                    // robot runner thread will call its init()
            }
            catch (InterruptedException ie) {}
        }

    }  // nested static class SOCPlayerLocalRobotRunner

    /**
     * Force-end this robot's turn.
     * Done in a separate thread in case of deadlocks.
     * Created from {@link SOCGameTimeoutChecker#run()}.
     * @author Jeremy D Monin
     * @since 1.1.11
     */
    private class ForceEndTurnThread extends Thread
    {
        private SOCGame ga;
        private SOCPlayer pl;

        public ForceEndTurnThread(SOCGame g, SOCPlayer p)
        {
            setDaemon(true);
            ga = g;
            pl = p;
        }

        /**
         * If our targeted robot player is still the current player, force-end their turn.
         * If not current player but game is waiting for them to discard resources,
         * choose randomly so the game can continue.
         * Calls {@link SOCServer#endGameTurnOrForce(SOCGame, int, String, StringConnection, boolean)}.
         */
        public void run()
        {
            final String rname = pl.getName();
            final int plNum = pl.getPlayerNumber();
            final int gs = ga.getGameState();
            final boolean notCurrentPlayer = (ga.getCurrentPlayerNumber() != plNum);

            // Ignore if not current player, unless game is
            // waiting for the bot to discard resources.
            if (notCurrentPlayer && (gs != SOCGame.WAITING_FOR_DISCARDS))
            {
                return;
            }

            StringConnection rconn = getConnection(rname);
            System.err.println
                ("For robot " + rname +
                 ((notCurrentPlayer) ? ": force discard" : ": force end turn")
                 + " in game " + ga.getName() + " pn=" + plNum + " state " + gs);
            if (gs == SOCGame.WAITING_FOR_DISCARDS)
                System.err.println("  srv card count = " + pl.getResources().getTotal());
            if (rconn == null)
            {
                System.err.println("L9120: internal error: can't find connection for " + rname);
                return;  // shouldn't happen
            }

            // if it's the built-in type, print brain variables
            SOCClientData scd = (SOCClientData) rconn.getAppData();
            if (scd.isBuiltInRobot)
            {
                SOCRobotClient rcli = (SOCRobotClient) SOCPlayerLocalRobotRunner.robotClients.get(rname);
                if (rcli != null)
                    rcli.debugPrintBrainStatus(ga.getName(), false);
                else
                    System.err.println("L9397: internal error: can't find robotClient for " + rname);
            } else {
                System.err.println("  Can't print brain status; robot type is " + scd.robot3rdPartyBrainClass);
            }

            endGameTurnOrForce(ga, plNum, rname, rconn, false);
        }

    }  // inner class ForceEndTurnThread

    /**
     * Interface for asynchronous callbacks from
     * {@link SOCServer#authOrRejectClientUser(StringConnection, String, String, int, boolean, boolean, AuthSuccessRunnable)}
     * for better multithreading granularity.
     * If auth succeeds, calls {@link #success(StringConnection, int)}.
     * If auth fails, {@code authOrRejectClientUser(..)} sends the client a failure message
     * and no callback is made.
     *<P>
     * Before v1.2.00, {@code authOrRejectClientUser(..)} returned status flags like
     * {@link SOCServer#AUTH_OR_REJECT__OK AUTH_OR_REJECT__OK} directly instead of using a callback.
     *
     * @author Jeremy D Monin &lt;jeremy@nand.net&gt;
     * @since 1.2.00
     */
    public interface AuthSuccessRunnable
    {
        /**
         * Called on successful client authentication, or if user was already authenticated.
         * @param c  Client connection which was authenticated
         * @param authResult  Auth check result flags; {@link SOCServer#AUTH_OR_REJECT__OK AUTH_OR_REJECT__OK},
         *     {@link SOCServer#AUTH_OR_REJECT__SET_USERNAME AUTH_OR_REJECT__SET_USERNAME}, etc.
         *     See {@link SOCServer#authOrRejectClientUser(StringConnection, String, String, int, boolean, boolean, AuthSuccessRunnable)}
         *     for details.
         */
        void success(final StringConnection c, final int authResult);
    }

}  // public class SOCServer
