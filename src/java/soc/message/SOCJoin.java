/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file Copyright (C) 2014,2016 Jeremy D Monin <jeremy@nand.net>
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
package soc.message;

import java.util.StringTokenizer;


/**
 * This message means that someone is joining a channel
 *<P>
 * Once the client has successfully joined or created a channel or game, the
 * password field can be left blank in later join/create requests.  All server
 * versions ignore the password field after a successful request.
 *
 * @author Robert S Thomas
 */
public class SOCJoin extends SOCMessage
{
    /**
     * symbol to represent a null or empty password over the network, to avoid 2 adjacent field-delimiter characters
     */
    private static String NULLPASS = "\t";

    /**
     * Nickname of the joining member
     */
    private String nickname;

    /**
     * Optional password, or "" if none
     */
    private String password;

    /**
     * Name of channel
     */
    private String channel;

    /**
     * Server host name to which the client is connecting.
     * Since the client is already connected when it sends the message, this is informational.
     */
    private String host;

    /**
     * Create a Join Channel message.
     *
     * @param nn  nickname
     * @param pw  optional password, or "" if none
     * @param hn  server host name
     * @param ch  name of chat channel
     */
    public SOCJoin(String nn, String pw, String hn, String ch)
    {
        messageType = JOIN;
        nickname = nn;
        password = pw;
        channel = ch;
        host = hn;
    }

    /**
     * @return the nickname
     */
    public String getNickname()
    {
        return nickname;
    }

    /**
     * @return the optional password, or "" if none
     */
    public String getPassword()
    {
        return password;
    }

    /**
     * Get the server host name to which the client is connecting.
     * Since the client is already connected when it sends the message, this is only informational.
     * @return the host name
     */
    public String getHost()
    {
        return host;
    }

    /**
     * @return the channel name
     */
    public String getChannel()
    {
        return channel;
    }

    /**
     * JOIN sep nickname sep2 password sep2 host sep2 channel
     *
     * @return the command String
     */
    public String toCmd()
    {
        return toCmd(nickname, password, host, channel);
    }

    /**
     * JOIN sep nickname sep2 password sep2 host sep2 channel
     *
     * @param nn  the nickname
     * @param pw  the optional password, or "" if none
     * @param hn  the server host name
     * @param ch  the channel name
     * @return    the command string
     */
    public static String toCmd(String nn, String pw, String hn, String ch)
    {
        String temppw = new String(pw);

        if (temppw.equals(""))
        {
            temppw = NULLPASS;
        }

        return JOIN + sep + nn + sep2 + temppw + sep2 + hn + sep2 + ch;
    }

    /**
     * Parse the command String into a Join Channel message
     *
     * @param s   the String to parse
     * @return    a Join message, or null of the data is garbled
     */
    public static SOCJoin parseDataStr(String s)
    {
        String nn;
        String pw;
        String hn;
        String ch;

        StringTokenizer st = new StringTokenizer(s, sep2);

        try
        {
            nn = st.nextToken();
            pw = st.nextToken();
            hn = st.nextToken();
            ch = st.nextToken();

            if (pw.equals(NULLPASS))
            {
                pw = "";
            }
        }
        catch (Exception e)
        {
            return null;
        }

        return new SOCJoin(nn, pw, hn, ch);
    }

    /**
     * @return a human readable form of the message
     */
    public String toString()
    {
        final String pwmask;
        if ((password == null) || (password.length() == 0) || password.equals("\t"))
            pwmask = "|password empty";
        else
            pwmask = "|password=***";

        String s = "SOCJoin:nickname=" + nickname + pwmask + "|host=" + host + "|channel=" + channel;

        return s;
    }

}
