package com.devteam.acceleration.jabber;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.devteam.acceleration.ui.ChatActivity;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.iqregister.AccountManager;
import org.jxmpp.jid.EntityJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.stringprep.XmppStringprepException;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import static android.content.Context.CONNECTIVITY_SERVICE;

/**
 * Created by admin on 13.04.17.
 */

public class AccelerationJabberConnection implements ConnectionListener {

    private static final String TAG = AccelerationJabberConnection.class.getSimpleName();

    private XMPPTCPConnection connection;
    private Context applicationContext;
    private JabberModel jabberModel = new JabberModel();
    private ChatMessageListener chatMessageListener;
    private BroadcastReceiver uiThreadMessageReceiver;

    private boolean isNewAccount = false;

    public enum ConnectionState {
        CONNECTED, AUTHENTICATED, CONNECTING, DISCONNECTING, DISCONNECTED, ERROR;
    }

    public static enum LoggedInState {
        LOGGED_IN, LOGGED_OUT;
    }

    public AccelerationJabberConnection(Context context) {
        Log.d(TAG, "RoosterConnection Constructor called.");
        applicationContext = context.getApplicationContext();
        String jid = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getString(AccelerationJabberParams.JABBER_ID, null);
        jabberModel.setPassword(PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getString(AccelerationJabberParams.USER_PASSWORD, null));
        jabberModel.setName(PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getString(AccelerationJabberParams.USER_NAME, null));
        jabberModel.setEmail(PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getString(AccelerationJabberParams.USER_EMAIL, null));
        isNewAccount = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getBoolean(AccelerationJabberParams.isRegistration, false);

        if (jid != null) {
            String[] params = jid.split("@");
            jabberModel.setJabberId(params[0]);
            jabberModel.setServiceName(params[1]);
        }

        setupUiThreadBroadCastMessageReceiver();

    }

    public void createAccount() throws IOException, InterruptedException, XMPPException, SmackException {

        if (connection == null || !connection.isConnected()) {
            connect();
        }

        AccountManager accountManager = AccountManager.getInstance(connection);
        accountManager.sensitiveOperationOverInsecureConnection(true);
        Map<String, String> atr = new HashMap<>();
        atr.put(AccelerationJabberParams.USER_EMAIL, jabberModel.getEmail());
        atr.put(AccelerationJabberParams.USER_NAME, jabberModel.getName());
        accountManager.createAccount(Localpart.from(jabberModel.getJabberId()), jabberModel.getPassword(), atr);

        loginToChat();
    }

    private void connect() throws InterruptedException, IOException, SmackException, XMPPException {
        Log.d(TAG, "Connecting to server " + jabberModel.getServiceName());
        XMPPTCPConnectionConfiguration.Builder builder =
                XMPPTCPConnectionConfiguration.builder();
        builder.setSecurityMode(ConnectionConfiguration.SecurityMode.disabled);
        builder.setUsernameAndPassword(jabberModel.getJabberId(), jabberModel.getPassword());
        builder.setHostAddress(InetAddress.getByName(jabberModel.getServiceName()));
        builder.setXmppDomain(JidCreate.from(jabberModel.getServiceName()).asDomainBareJid());
        builder.setConnectTimeout(3000);

        connection = new XMPPTCPConnection(builder.build());
        connection.addConnectionListener(this);
        connection.connect();
    }

    public void loginToChat() throws InterruptedException, IOException, SmackException, XMPPException {

        if (connection == null || !connection.isConnected()) {
            connect();
        }

        connection.login();

        chatMessageListener = new ChatMessageListener() {
            @Override
            public void processMessage(Chat chat, Message message) {
                ///ADDED
                Log.d(TAG, "message.getBody() :" + message.getBody());
                Log.d(TAG, "message.getFrom() :" + message.getFrom());

                String from = message.getFrom().toString();
                String contactJid = "";
                if (from.contains("/")) {
                    contactJid = from.split("/")[0];
                    Log.d(TAG, "The real jid is :" + contactJid);
                } else {
                    contactJid = from;
                }
                //Bundle up the intent and send the broadcast.
                Intent intent = new Intent(AccelerationConnectionService.NEW_MESSAGE);
                intent.setPackage(applicationContext.getPackageName());
                intent.putExtra(AccelerationConnectionService.BUNDLE_FROM_JID, contactJid);
                intent.putExtra(AccelerationConnectionService.MESSAGE_BODY, message.getBody());
                applicationContext.sendBroadcast(intent);
                Log.d(TAG, "Received message from :" + contactJid + " broadcast sent.");
                ///ADDED
            }
        };

        //The snippet below is necessary for the message listener to be attached to our connection.
        ChatManager.getInstanceFor(connection).addChatListener(new ChatManagerListener() {
            @Override
            public void chatCreated(Chat chat, boolean createdLocally) {

                //If the line below is missing ,processMessage won't be triggered and you won't receive messages.
                chat.addMessageListener(chatMessageListener);

            }
        });

        ReconnectionManager reconnectionManager = ReconnectionManager.getInstanceFor(connection);
        reconnectionManager.setEnabledPerDefault(true);
        reconnectionManager.enableAutomaticReconnection();
    }


    private void setupUiThreadBroadCastMessageReceiver() {
        uiThreadMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                //Check if the Intents purpose is to send the message.
                String action = intent.getAction();
                if (action.equals(AccelerationConnectionService.SEND_MESSAGE)) {
                    //Send the message.
                    sendMessage(intent.getStringExtra(AccelerationConnectionService.MESSAGE_BODY),
                            intent.getStringExtra(AccelerationConnectionService.BUNDLE_TO));
                }
            }
        };

        IntentFilter filter = new IntentFilter(AccelerationConnectionService.SEND_MESSAGE);
        filter.addAction(AccelerationConnectionService.SEND_MESSAGE);
        applicationContext.registerReceiver(uiThreadMessageReceiver, filter);

    }

    public void sendMessage(String body, String toJid) {
        Log.d(TAG, "Sending message to :" + toJid);
        try {

            ConnectivityManager cm = (ConnectivityManager) applicationContext
                    .getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();

            if (ni != null && ni.isConnected()) {
                Chat chat = ChatManager.getInstanceFor(connection)
                        .createChat((EntityJid) JidCreate.from(toJid), chatMessageListener);
                chat.sendMessage(body);
            } else {
                Toast.makeText(applicationContext, "No network", Toast.LENGTH_LONG).show();
            }
        } catch (SmackException.NotConnectedException | XmppStringprepException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void disconnect() {
        Log.d(TAG, "Disconnecting from server " + jabberModel.getServiceName());

        if (connection != null && connection.isConnected()) {
            connection.disconnect();
        }
        connection = null;
    }


    @Override
    public void connected(XMPPConnection connection) {
        AccelerationConnectionService.connectionState = ConnectionState.CONNECTED;
    }

    @Override
    public void authenticated(XMPPConnection connection, boolean resumed) {
        Log.d(TAG, "Authenticated Successfully");
        AccelerationConnectionService.connectionState = ConnectionState.AUTHENTICATED;
        showChatActivity();
    }

    @Override
    public void connectionClosed() {
        AccelerationConnectionService.connectionState = ConnectionState.DISCONNECTED;
    }

    @Override
    public void connectionClosedOnError(Exception e) {
        AccelerationConnectionService.connectionState = ConnectionState.DISCONNECTED;
    }

    @Override
    public void reconnectionSuccessful() {
        AccelerationConnectionService.connectionState = ConnectionState.CONNECTED;
    }

    @Override
    public void reconnectingIn(int seconds) {
        AccelerationConnectionService.connectionState = ConnectionState.CONNECTING;
    }

    @Override
    public void reconnectionFailed(Exception e) {
        AccelerationConnectionService.connectionState = ConnectionState.DISCONNECTED;
    }

    private void showChatActivity() {
        Intent intent = new Intent(applicationContext, ChatActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        applicationContext.startActivity(intent);
    }

    public boolean isNewAccount() {
        return isNewAccount;
    }

}
