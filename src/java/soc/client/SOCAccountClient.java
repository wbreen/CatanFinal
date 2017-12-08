/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file copyright (C) 2009-2011,2013-2017 Jeremy D Monin <jeremy@nand.net>
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
package soc.client;

import soc.baseclient.SOCDisplaylessPlayerClient;
import soc.disableDebug.D;

import soc.message.SOCAuthRequest;
import soc.message.SOCChannels;
import soc.message.SOCCreateAccount;
import soc.message.SOCMessage;
import soc.message.SOCRejectConnection;
import soc.message.SOCStatusMessage;
import soc.message.SOCVersion;

import soc.util.SOCServerFeatures;
import soc.util.Version;

import java.applet.Applet;
import java.applet.AppletContext;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import java.net.Socket;


/**
 * Applet/Standalone client for connecting to the SOCServer and
 * making user accounts.
 *<P>
 * This account-management client will connect to server version v1.1.19 and higher
 * ({@link #SRV_VERSION_MIN}). The required minimum version simplifies logic and
 * assumptions about available {@link SOCServerFeatures}. If needed to manage older servers,
 * older client JARs can be downloaded or built from git release tags.
 *<P>
 * To connect the applet to a non-default server port, you must specify it as the
 * {@code port} parameter in the html applet tag. If you run {@code SOCAccountClient}
 * as a stand-alone app, specify the server's hostname and port on the command line.
 *
 * @author Robert S Thomas
 */
public class SOCAccountClient extends Applet
    implements Runnable, ActionListener, KeyListener
{
    private static final long serialVersionUID = 1120L;  // Last structural change v1.1.20

    /**
     * Minimum server version (v1.1.19) to which this account-management client will connect;
     * see class javadoc.
     *<P>
     * Same format as {@link soc.util.Version#versionNumber()}.
     * Same value as {@link SOCServerFeatures#VERSION_FOR_SERVERFEATURES}.
     * @since 1.1.20
     */
    public static final int SRV_VERSION_MIN = SOCServerFeatures.VERSION_FOR_SERVERFEATURES;  // v1.1.19 (1119)

    /**
     * CardLayout string for the main panel while connected to a server:
     * Has fields to enter {@link #nick}, {@link #pass}, etc,
     * {@link #status} display and {@link #submit} button.
     */
    private static final String MAIN_PANEL = "main";
    private static final String MESSAGE_PANEL = "message";
    /** CardLayout string for {@link #connPanel}. */
    private static final String CONN_PANEL = "conn";

    // Most of these dialog fields are set up in initVisualElements().

    /**
     * Account info dialog prompt/label.
     * @since 1.1.20
     */
    private Label promptLabel;

    /**
     * Nickname field label.
     * @since 1.1.20
     */
    private Label nickLabel;

    /** Nickname field ({@link #nick}) tooltip. */
    private AWTToolTip nickTTip;

    /** Name of new user to be created. */
    protected TextField nick;
    protected TextField pass;
    protected TextField pass2;
    protected TextField email;

    protected TextField status;
    protected Button submit;
    protected Label messageLabel;

    protected AppletContext ac;
    protected boolean submitLock;

    /**
     * Connect and Authenticate panel ({@link #CONN_PANEL}), for when
     * server needs authentication to create a user account.
     * Created in {@link #initInterface_conn()}.
     * @since 1.1.19
     */
    private Panel connPanel;

    /**
     * Username, password, and status fields on {@link #connPanel}.
     * @since 1.1.19
     */
    private TextField conn_user, conn_pass, conn_status;

    /**
     * Connect and Cancel buttons on {@link #connPanel}.
     * @since 1.1.19
     */
    private Button conn_connect, conn_cancel;

    /**
     * If true, a username/password {@link SOCAuthRequest} has been sent to the server from {@link #connPanel}.
     * Used by {@link #handleSTATUSMESSAGE(SOCStatusMessage)} to show error results using <tt>connPanel</tt>
     * instead of the main panel.
     * @since 1.1.19
     */
    private boolean conn_sentAuth;

    protected CardLayout cardLayout;
    
    protected String host;
    protected int port;
    protected Socket s;
    protected DataInputStream in;
    protected DataOutputStream out;
    protected Thread reader = null;
    protected Exception ex = null;
    protected boolean connected = false;

    /**
     * Server version number for remote server, sent soon after connect.
     * @since 1.1.19
     */
    protected int sVersion;

    /**
     * Server's active optional features, sent soon after connect, or null if unknown.
     * @since 1.1.19
     */
    protected SOCServerFeatures sFeatures;

    /**
     * the nickname
     */
    protected String nickname = null;

    /**
     * the password
     */
    protected String password = null;

    /**
     * the second password
     */
    protected String password2 = null;

    /**
     * the email address
     */
    protected String emailAddress = null;

    /**
     * True if contents of incoming and outgoing network message traffic should be debug-printed.
     * Set if optional system property {@link SOCDisplaylessPlayerClient#PROP_JSETTLERS_DEBUG_TRAFFIC} is set.
     * @since 1.2.00
     */
    private boolean debugTraffic;

    /**
     * Create a SOCAccountClient connecting to localhost port 8880
     */
    public SOCAccountClient()
    {
        this(null, 8880);
    }

    /**
     * Constructor for connecting to the specified host, on the specified port.
     * Must call 'init' to start up and do layout.
     *
     * @param h  host
     * @param p  port
     */
    public SOCAccountClient(String h, int p)
    {
        host = h;
        port = p;

        if (null != System.getProperty(SOCDisplaylessPlayerClient.PROP_JSETTLERS_DEBUG_TRAFFIC))
            debugTraffic = true;  // set flag if debug prop has any value at all
    }

    /**
     * init the visual elements at startup: {@link #MESSAGE_PANEL}, {@link #MAIN_PANEL}.
     * Labels' text assumes self-registration (Open Registration); if this is not the case
     * call {@link #updateLabelsIfNotOpenReg()} afterwards.
     * @see #initInterface_conn()
     */
    protected void initVisualElements()
    {
        char pwchar = SOCPlayerClient.isJavaOnOSX ? '\u2022' : '*';

        setFont(new Font("SansSerif", Font.PLAIN, 12));
        
        nick = new TextField(20);
        pass = new TextField(10);
        pass.setEchoChar(pwchar);
        pass2 = new TextField(10);
        pass2.setEchoChar(pwchar);
        email = new TextField(50);
        status = new TextField(50);
        status.setEditable(false);
        submit = new Button("Create Account");
        submitLock = false;

        submit.addActionListener(this);

        ac = null;

        GridBagLayout gbl = new GridBagLayout();
        GridBagConstraints c = new GridBagConstraints();
        Panel mainPane = new Panel(gbl);

        c.fill = GridBagConstraints.BOTH;

        Label l;

        promptLabel = new Label("To create an account, please enter your information.");
        promptLabel.setAlignment(Label.CENTER);
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(promptLabel, c);
        mainPane.add(promptLabel);

        l = new Label();  // spacer
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        nickLabel = new Label("Your Nickname:");
        c.gridwidth = 1;
        gbl.setConstraints(nickLabel, c);
        mainPane.add(nickLabel);
        nickTTip = new AWTToolTip("This will be your username.", nick);

        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(nick, c);
        mainPane.add(nick);

        l = new Label();
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        l = new Label("Password:");
        c.gridwidth = 1;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(pass, c);
        mainPane.add(pass);

        l = new Label();
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        l = new Label("Password (again):");
        c.gridwidth = 1;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(pass2, c);
        mainPane.add(pass2);

        l = new Label();
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        l = new Label("Email (optional):");
        c.gridwidth = 1;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(email, c);
        mainPane.add(email);

        l = new Label();
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        l = new Label();
        c.gridwidth = 1;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(submit, c);
        mainPane.add(submit);

        l = new Label();
        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(l, c);
        mainPane.add(l);

        c.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(status, c);
        mainPane.add(status);

        // message label that takes up the whole pane
        messageLabel = new Label("", Label.CENTER);

        Panel messagePane = new Panel(new BorderLayout());
        messagePane.add(messageLabel, BorderLayout.CENTER);
        
        // all together now...
        cardLayout = new CardLayout();
        setLayout(cardLayout);

        add(messagePane, MESSAGE_PANEL); // shown first
        add(mainPane, MAIN_PANEL);
    }

    /**
     * Connect setup for username and password authentication: {@link #connPanel} / {@link #CONN_PANEL}.
     * Called if server doesn't have {@link SOCServerFeatures#FEAT_OPEN_REG}.
     * Calls {@link #validate()} and {@link #conn_user}.{@link java.awt.Component#requestFocus() requestFocus()}.
     * @since 1.1.19
     * @see #initVisualElements()
     */
    private void initInterface_conn()
    {
        Panel pconn = new Panel();
        Label L;

        GridBagLayout gbl = new GridBagLayout();
        GridBagConstraints gbc = new GridBagConstraints();
        pconn.setLayout(gbl);
        gbc.fill = GridBagConstraints.BOTH;

        // heading row
        L = new Label("You must log in with a username and password before you can create accounts.");
        L.setAlignment(Label.CENTER);
        gbc.gridwidth = 4;
        gbl.setConstraints(L, gbc);
        pconn.add(L);
        L = new Label(" ");  // Spacing for rest of form's rows
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(L, gbc);
        pconn.add(L);

        // blank row
        L = new Label();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(L, gbc);
        pconn.add(L);

        L = new Label("Server");
        gbc.gridwidth = 1;
        gbl.setConstraints(L, gbc);
        pconn.add(L);
        L = new Label(host);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(L, gbc);
        pconn.add(L);

        L = new Label("Port");
        gbc.gridwidth = 1;
        gbl.setConstraints(L, gbc);
        pconn.add(L);
        L = new Label(Integer.toString(port));
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(L, gbc);
        pconn.add(L);

        L = new Label("Nickname");
        gbc.gridwidth = 1;
        gbl.setConstraints(L, gbc);
        pconn.add(L);
        conn_user = new TextField(20);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(conn_user, gbc);
        conn_user.addKeyListener(this);
        pconn.add(conn_user);

        L = new Label("Password");
        gbc.gridwidth = 1;
        gbl.setConstraints(L, gbc);
        pconn.add(L);
        conn_pass = new TextField(20);
        conn_pass.setEchoChar ((SOCPlayerClient.isJavaOnOSX) ? '\u2022' : '*');  // OSX: round bullet (option-8)
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(conn_pass, gbc);
        conn_pass.addKeyListener(this);
        pconn.add(conn_pass);

        L = new Label(" ");  // spacer row
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(L, gbc);
        pconn.add(L);

        // Connect and Cancel buttons shouldn't stretch entire width, so they get their own sub-Panel
        Panel btnsRow = new Panel();

        conn_connect = new Button("Connect");
        conn_connect.addActionListener(this);
        conn_connect.addKeyListener(this);  // for win32 keyboard-focus ESC/ENTER
        btnsRow.add(conn_connect);

        conn_cancel = new Button("Cancel");
        conn_cancel.addActionListener(this);
        conn_cancel.addKeyListener(this);
        btnsRow.add(conn_cancel);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(btnsRow, gbc);
        pconn.add(btnsRow);

        L = new Label(" ");  // spacer row
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(L, gbc);
        pconn.add(L);
        conn_status = new TextField(50);
        conn_status.setEditable(false);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbl.setConstraints(conn_status, gbc);
        pconn.add(conn_status);

        connPanel = pconn;

        add(pconn, CONN_PANEL);
        cardLayout.show(this, CONN_PANEL);
        validate();

        conn_user.requestFocus();
    }

    /**
     * Update account-info label texts if needed.
     * If server supports self-registration (Open Registration), does nothing.
     * Assumes {@link #initVisualElements()} has already been called.
     * @since 1.1.20
     */
    private void updateLabelsIfNotOpenReg()
    {
        if (sFeatures.isActive(SOCServerFeatures.FEAT_OPEN_REG))
            return;

        promptLabel.setText("To create an account, please enter its information.");
        nickLabel.setText("Nickname");
        nickTTip.setTip("This will be the new account's username.");
    }

    /**
     * Retrieve a parameter and translate to a hex value.
     *
     * @param name a parameter name. null is ignored
     * @return the parameter parsed as a hex value or -1 on error
     */
    public int getHexParameter(String name)
    {
        String value = null;
        int iValue = -1;
        try
        {
            value = getParameter(name);
            if (value != null)
            {
                iValue = Integer.parseInt(value, 16);
            }
        }
        catch (Exception e)
        {
            System.err.println("Invalid " + name + ": " + value);
        }
        return iValue;
    }

    /**
     * Initialize the applet
     */
    public synchronized void init()
    {
        Version.printVersionText(System.out, "Java Settlers Account Client ");

        String param = null;
        int intValue;
            
        intValue = getHexParameter("background"); 
        if (intValue != -1)
            setBackground(new Color(intValue));

        intValue = getHexParameter("foreground");
        if (intValue != -1)
            setForeground(new Color(intValue));

        initVisualElements(); // after the background is set

        System.out.println("Getting host...");
        host = getCodeBase().getHost();
        if (host.equals(""))
            host = null;  // localhost

        try {
            param = getParameter("PORT");
            if (param != null)
                port = Integer.parseInt(param);
        }
        catch (Exception e) {
            System.err.println("Invalid port: " + param);
        }

        connect();
    }

    /**
     * Attempts to connect to the server. See {@link #connected} for success or
     * failure.
     * @throws IllegalStateException if already connected 
     *         or if {@link Version#versionNumber()} returns 0 (packaging error)
     */
    public synchronized void connect()
    {
        String hostString = (host != null ? host : "localhost") + ":" + port;
        if (connected)
        {
            throw new IllegalStateException("Already connected to " +
                                            hostString);
        }

        if (Version.versionNumber() == 0)
        {
            messageLabel.setText("Packaging error: Cannot determine JSettlers version");
            throw new IllegalStateException("Packaging error: Cannot determine JSettlers version");
        }

        System.out.println("Connecting to " + hostString);
        messageLabel.setText("Connecting to server...");

        try
        {
            s = new Socket(host, port);
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
            connected = true;
            (reader = new Thread(this)).start();
            // send VERSION right away (1.1.07 and later)
            put(SOCVersion.toCmd(Version.versionNumber(), Version.version(), Version.buildnum(), null));
        }
        catch (Exception e)
        {
            ex = e;
            String msg = "Could not connect to the server: " + ex;
            System.err.println(msg);
            messageLabel.setText(msg);
        }
    }

    /**
     * "Connect" button, from connect/authenticate panel; check nickname & password fields,
     * send auth request to server, set {@link #conn_sentAuth} flag.
     * When the server responds with {@link SOCStatusMessage#SV_OK}, the {@link #MAIN_PANEL} will be shown.
     * @since 1.1.19
     */
    private void clickConnConnect()
    {
        final String user = conn_user.getText().trim(), pw = conn_pass.getText();

        if (user.length() == 0)
        {
            conn_status.setText("You must enter a nickname.");
            conn_user.requestFocus();
            return;
        }

        if (pw.length() == 0)
        {
            conn_status.setText("You must enter a password.");
            conn_pass.requestFocus();
            return;
        }

        if (pw.length() > SOCAuthRequest.PASSWORD_LEN_MAX)
        {
            conn_status.setText("That password is too long.");
            conn_pass.requestFocus();
            return;
        }

        conn_sentAuth = true;
        put(SOCAuthRequest.toCmd
            (SOCAuthRequest.ROLE_USER_ADMIN, user, pw, SOCAuthRequest.SCHEME_CLIENT_PLAINTEXT, host));
    }

    /**
     * Disconnect from connect/auth panel, show "disconnected" message.
     * @since 1.1.19
     */
    private void clickConnCancel()
    {
        if ((connPanel != null) && connPanel.isVisible())
        {
            connPanel.setVisible(false);
        }

        disconnect();

        messageLabel.setText("Connection canceled.");
        cardLayout.show(this, MESSAGE_PANEL);
        validate();
    }

    /**
     * Handle mouse clicks and keyboard
     */
    public void actionPerformed(ActionEvent e)
    {
        Object target = e.getSource();

        if (target == submit)
        {
            String n = nick.getText().trim();

            if (n.length() > 20)
            {
                nickname = n.substring(0, 20);
            }
            else
            {
                nickname = n;
            }
            if (! SOCMessage.isSingleLineAndSafe(nickname))
            {
                status.setText(SOCStatusMessage.MSG_SV_NEWGAME_NAME_REJECTED);
                nick.requestFocusInWindow();
                return;  // Not a valid username
            }

            password = pass.getText().trim();
            password2 = pass2.getText().trim();
            emailAddress = email.getText().trim();

            //
            // make sure all the info is ok
            //
            if (nickname.length() == 0)
            {
                status.setText("You must enter a nickname.");
                nick.requestFocus();
            }
            else if (password.length() == 0)
            {
                status.setText("You must enter a password.");
                pass.requestFocus();
            }
            else if (password.length() > SOCAuthRequest.PASSWORD_LEN_MAX)
            {
                status.setText("That password is too long.");
                pass.requestFocus();
            }
            else if (!password.equals(password2))
            {
                pass.requestFocus();
                status.setText("Your passwords don't match.");
            }
            else if (!submitLock)
            {
                submitLock = true;
                status.setText("Creating account ...");
                put(SOCCreateAccount.toCmd(nickname, password, host, emailAddress));
            }
        }
        else if (target == conn_connect)
        {
            clickConnConnect();
        }
        else if (target == conn_cancel)
        {
            clickConnCancel();
        }
    }

    /**
     * continuously read from the net in a separate thread
     */
    public void run()
    {
        try
        {
            while (connected)
            {
                String s = in.readUTF();
                treat((SOCMessage) SOCMessage.toMsg(s));
            }
        }
        catch (IOException e)
        {
            // purposefully closing the socket brings us here too
            if (connected)
            {
                ex = e;
                System.out.println("could not read from the net: " + ex);
                destroy();
            }
        }
    }

    /**
     * write a message to the net
     *
     * @param s  the message
     * @return true if the message was sent, false if not
     */
    public synchronized boolean put(String s)
    {
        if (debugTraffic || D.ebugIsEnabled())
            soc.debug.D.ebugPrintln("OUT - " + s);

        if ((ex != null) || !connected)
        {
            return false;
        }

        try
        {
            out.writeUTF(s);
        }
        catch (IOException e)
        {
            ex = e;
            System.err.println("could not write to the net: " + ex);
            destroy();

            return false;
        }

        return true;
    }

    /**
     * Treat the incoming messages.
     *<P>
     * If {@link SOCDisplaylessPlayerClient#PROP_JSETTLERS_DEBUG_TRAFFIC} is set, debug-prints message contents.
     *
     * @param mes    the message
     */
    public void treat(SOCMessage mes)
    {
        if (mes == null)
            return;  // Msg parsing error

        if (debugTraffic || D.ebugIsEnabled())
            soc.debug.D.ebugPrintln("IN - " + mes.toString());

        try
        {
            switch (mes.getType())
            {
            /**
             * server's version and feature report (among first messages from server)
             */
            case SOCMessage.VERSION:
                handleVERSION((SOCVersion) mes);
                break;

            /**
             * list of channels on the server: Among first messages from server, after VERSION.
             * Show {@link #MAIN_PANEL} if not already showing; see handleCHANNELS javadoc.
             */
            case SOCMessage.CHANNELS:
                handleCHANNELS((SOCChannels) mes);

                break;

            /**
             * status message
             */
            case SOCMessage.STATUSMESSAGE:
                handleSTATUSMESSAGE((SOCStatusMessage) mes);

                break;

            /**
             * handle the reject connection message
             */
            case SOCMessage.REJECTCONNECTION:
                handleREJECTCONNECTION((SOCRejectConnection) mes);

                break;
            }
        }
        catch (Exception e)
        {
            System.out.println("SOCAccountClient treat ERROR - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle the "version" message: Server's version and feature report.
     * Will disconnect if server is older than {@link #SRV_VERSION_MIN}.
     *
     * @param mes  the message
     * @since 1.1.19
     */
    private void handleVERSION(SOCVersion mes)
    {
        sVersion = mes.getVersionNumber();
        if (sVersion < SRV_VERSION_MIN)
        {
            disconnect();

            messageLabel.setText
                ("This server has old version " + Version.version(sVersion)
                 + "; this client works only with " + Version.version(SRV_VERSION_MIN)
                 + " and newer servers.");
            cardLayout.show(this, MESSAGE_PANEL);
            validate();
            return;
        }

        sFeatures =
            (sVersion >= SOCServerFeatures.VERSION_FOR_SERVERFEATURES)
            ? new SOCServerFeatures(mes.feats)
            : new SOCServerFeatures(true);

        if (! sFeatures.isActive(SOCServerFeatures.FEAT_ACCTS))
        {
            disconnect();

            messageLabel.setText("This server does not use accounts and passwords.");
            cardLayout.show(this, MESSAGE_PANEL);
            validate();
            return;
        }

        if (! sFeatures.isActive(SOCServerFeatures.FEAT_OPEN_REG))
        {
            initInterface_conn();  // adds connPanel, sets it active, calls validate()
            updateLabelsIfNotOpenReg();  // update account-info label texts for use after authentication
        }
    }

    /**
     * Handle the "list of channels" message:
     * Server connection is complete, show {@link #MAIN_PANEL} unless {@link #connPanel} is already showing.
     * @param mes  the message
     */
    protected void handleCHANNELS(SOCChannels mes)
    {
        //
        // this message indicates that we're connected to the server
        //
        if ((connPanel != null) && connPanel.isVisible())
            return;

        cardLayout.show(this, MAIN_PANEL);
        validate();
        nick.requestFocus();
    }

    /**
     * handle the "reject connection" message
     * @param mes  the message
     */
    protected void handleREJECTCONNECTION(SOCRejectConnection mes)
    {
        disconnect();

        messageLabel.setText(mes.getText());
        cardLayout.show(this, MESSAGE_PANEL);
        validate();
    }

    /**
     * handle the "status message" message.
     * If this is in response to authrequest when connecting, show {@link #MAIN_PANEL}.
     * If we've just created a user ({@link SOCStatusMessage#SV_ACCT_CREATED_OK}),
     * clear the password fields: must re-enter if creating another.
     * @param mes  the message
     */
    protected void handleSTATUSMESSAGE(SOCStatusMessage mes)
    {
        int sv = mes.getStatusValue();
        String statusText = mes.getStatus();

        if (sv == SOCStatusMessage.SV_OK_SET_NICKNAME)
        {
            sv = SOCStatusMessage.SV_OK;

            final int i = statusText.indexOf(SOCMessage.sep2_char);
            if (i > 0)
            {
                nickname = statusText.substring(0, i);
                statusText = statusText.substring(i + 1);
            }
        }

        if ((connPanel != null) && connPanel.isVisible())
        {
            // Initial connect/authentication panel is showing.
            // This is either the initial STATUSMESSAGE from server, such as
            // when debug is on, or a response to the authrequest we've sent.

            if ((sv != SOCStatusMessage.SV_OK) || ! conn_sentAuth)
            {
                conn_status.setText(statusText);
                return;
            }

            connPanel.setVisible(false);
            cardLayout.show(this, MAIN_PANEL);
            validate();
            nick.requestFocus();
        }

        status.setText(statusText);
        if ((sv == SOCStatusMessage.SV_ACCT_CREATED_OK)
            || (sv == SOCStatusMessage.SV_ACCT_CREATED_OK_FIRST_ONE))
        {
            // Clear password fields: must re-enter if creating another
            pass.setText("");
            pass2.setText("");

            if (sv == SOCStatusMessage.SV_ACCT_CREATED_OK_FIRST_ONE)
                // auth to server with new account
                put(SOCAuthRequest.toCmd
                    (SOCAuthRequest.ROLE_USER_ADMIN, nickname, password,
                     SOCAuthRequest.SCHEME_CLIENT_PLAINTEXT, host));
        }

        submitLock = false;
    }

    /**
     * disconnect from the net
     */
    protected synchronized void disconnect()
    {
        connected = false;

        // reader will die once 'connected' is false, and socket is closed

        try
        {
            s.close();
        }
        catch (Exception e)
        {
            ex = e;
        }
    }

    /**
     * applet info
     */
    public String getAppletInfo()
    {
        return "SOCAccountClient 0.1 by Robert S. Thomas.";
    }

    /** destroy the applet */
    public void destroy()
    {
        String err = "Sorry, the applet has been destroyed. " + ((ex == null) ? "Load the page again." : ex.toString());

        disconnect();
        
        messageLabel.setText(err);
        cardLayout.show(this, MESSAGE_PANEL);
        validate();
    }

    /**
     * for stand-alones
     */
    public static void usage()
    {
        System.err.println("usage: java soc.client.SOCAccountClient <host> <port>");
    }

    /**
     * for stand-alones
     */
    public static void main(String[] args)
    {
        SOCAccountClient client = new SOCAccountClient();
        
        if (args.length != 2)
        {
            usage();
            System.exit(1);
        }

        try {
            client.host = args[0];
            client.port = Integer.parseInt(args[1]);
        } catch (NumberFormatException x) {
            usage();
            System.err.println("Invalid port: " + args[1]);
            System.exit(1);
        }

        Frame frame = new Frame("SOCAccountClient");
        frame.setBackground(new Color(Integer.parseInt("61AF71",16)));
        frame.setForeground(Color.black);
        // Add a listener for the close event
        frame.addWindowListener(client.createWindowAdapter());

        client.initVisualElements(); // after the background is set
        
        frame.add(client, BorderLayout.CENTER);
        frame.setSize(600, 350);
        frame.setVisible(true);

        client.connect();
    }

    private WindowAdapter createWindowAdapter()
    {
        return new MyWindowAdapter();
    }
    
    private class MyWindowAdapter extends WindowAdapter
    {
        public void windowClosing(WindowEvent evt)
        {
            System.exit(0);
        }

        public void windowOpened(WindowEvent evt)
        {
            nick.requestFocus();
        }
    }

    /**
     * For Connect panel, handle Enter or Esc key (KeyListener).
     * @since 1.1.19
     */
    public void keyPressed(KeyEvent e)
    {
        if (e.isConsumed())
            return;

        switch (e.getKeyCode())
        {
        case KeyEvent.VK_ENTER:
            e.consume();
            clickConnConnect();
            break;

        case KeyEvent.VK_CANCEL:
        case KeyEvent.VK_ESCAPE:
            e.consume();
            clickConnCancel();
            break;
        }  // switch(e)
    }

    /** Stub required by KeyListener */
    public void keyReleased(KeyEvent e) { }

    /** Stub required by KeyListener */
    public void keyTyped(KeyEvent e) { }

}
