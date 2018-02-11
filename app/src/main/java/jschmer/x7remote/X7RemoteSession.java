package jschmer.x7remote;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

enum BatteryLevel {
    L0(0),
    L1(1),
    L2(2),
    L3(3),
    L4(4),
    AC(5);

    private final int value;

    BatteryLevel(int value) {
        this.value = value;
    }

    static BatteryLevel fromValue(int value) {
        BatteryLevel[] levels = BatteryLevel.values();
        for (BatteryLevel level : levels) {
            if (level.getValue() == value)
                return level;
        }
        throw new ArrayIndexOutOfBoundsException("BatteryLevel does not have value '" + value + "'");
    }

    public int getValue() {
        return value;
    }
}

enum ReplyMode {
    Ignore,
    Read
}

interface X7RemoteSessionListener
{
    void stateChanged(NetworkInfo.State newstate, String reason);
    void recordingStatusChanged(boolean recording);
    void generalInfoChanged(BatteryLevel level, int sdCardCapacity);
    void newCamPreviewImageAvailable(Bitmap bmp);
}

public class X7RemoteSession implements AutoCloseable {
    private static final String LOGTAGNETWORK = "|Network";
    private static final String LOGTAG = X7RemoteSession.class.getSimpleName();
    // camera config key -> (sharedPref config key, is boolean config)
    private static Map<String, Pair<String, Boolean>> camConfigKeyToSharedPrefKeyMap = null;

    // list of (property, value) messages to skip
    private static final List<Pair<String, String>> tcpRepliesToSkip = new ArrayList<Pair<String, String>>() {{
        add(Pair.create("msg_id", "16777217"));
    }};

    static final String CamAddress = "192.168.42.1";
    static final int CamPort = 7878;

    private final List<X7RemoteSessionListener> listeners  = new CopyOnWriteArrayList<>();
    private NetworkInfo.State state = NetworkInfo.State.DISCONNECTED;

    private Socket sock = null;
    private BufferedReader sock_in = null;
    private BufferedWriter sock_out = null;
    private int SessionID = 0;
    private boolean recording = false;
    private boolean previewSupported = false;

    private Timer periodicTimer = new Timer();
    private Timer previewImageTimer = new Timer();

    enum CameraCommand {
        Session_Init(1),
        Session_Close(2),
        Video_Capture_Start(3),
        Video_Capture_Stop(4),
        Take_Picture(5),
        Set_Setting(6),
        Get_Setting(7),
        Remote_Pair(8),
        Setting_Change_Stop(12),
        Setting_Change_Start(13),
        Power_OFF(32),
        Switch_Mode_Video(33),
        Switch_Mode_Picture(34),
        Keep_Alive(64);

        private final int id;
        CameraCommand(int id) { this.id = id; }
        public int getId() { return id; }
    }

    //region Public interface
    X7RemoteSession(SharedPreferences sharedPrefs, Resources res) throws ConnectionException, AssertionException {
        initSession(sharedPrefs, res);
    }

    boolean canChangeSettings() {
        return !isRecording();
    }

    boolean isRecording() {
        return recording;
    }

    boolean isPreviewSupported() {
        return previewSupported;
    }

    @Override
    public void close() {
        shutdown();
    }

    void addListener(X7RemoteSessionListener listener) {
        listeners.add(listener);
    }
    void removeMyEventListener(X7RemoteSessionListener listener) {
        listeners.remove(listener);
    }

    void startVideoCapture() throws SendMessageException {
        sendCommandWithAssert(CameraCommand.Video_Capture_Start, 0);
        recording = true;
        fireRecordingStatusChanged(true);
    }

    void stopVideoCapture() throws SendMessageException {
        sendCommandWithAssert(CameraCommand.Video_Capture_Stop, 0);
        recording = false;
        fireRecordingStatusChanged(false);
    }

    boolean canSnapshot() { return !isRecording(); }
    void snapshot() throws SendMessageException {
        sendCommandWithAssert(CameraCommand.Take_Picture, 0);
    }

    void powerOff() throws SendMessageException {
        sendCommand(CameraCommand.Power_OFF, ReplyMode.Ignore);
        shutdown();
    }
    //endregion

    //region Private interface
    private static Map<String, Pair<String, Boolean>> buildKeyMap(Resources res) {
        Map<String, Pair<String, Boolean>> aMap = new HashMap<>();
        aMap.put("video_option", new Pair<>(res.getString(R.string.pref_video_mode_key), false));
        aMap.put("video_resolution", new Pair<>(res.getString(R.string.pref_video_resolution_key), false));
        aMap.put("video_quality", new Pair<>(res.getString(R.string.pref_video_quality_key), false));
        aMap.put("auto_rec", new Pair<>(res.getString(R.string.pref_video_autorec_key), false));
        aMap.put("photo_option", new Pair<>(res.getString(R.string.pref_photo_mode_key), false));
        aMap.put("photo_size", new Pair<>(res.getString(R.string.pref_photo_resolution_key), false));
        aMap.put("photo_quality", new Pair<>(res.getString(R.string.pref_photo_quality_key), false));
        aMap.put("time_stamp", new Pair<>(res.getString(R.string.pref_effects_timestap_key), true));
        aMap.put("aqua_mode", new Pair<>(res.getString(R.string.pref_effects_aqua_mode_key), true));
        aMap.put("fov", new Pair<>(res.getString(R.string.pref_effects_fov_key), false));
        aMap.put("ae_metering", new Pair<>(res.getString(R.string.pref_effects_ae_metering_key), false));
        aMap.put("vout", new Pair<>(res.getString(R.string.pref_system_tv_mode_key), false));
        aMap.put("mic", new Pair<>(res.getString(R.string.pref_system_mic_volume_key), false));
        aMap.put("buzzer", new Pair<>(res.getString(R.string.pref_system_buzzer_key), true));
        aMap.put("led", new Pair<>(res.getString(R.string.pref_system_led_key), true));
        aMap.put("auto_lcd_off", new Pair<>(res.getString(R.string.pref_system_auto_lcd_off_key), true));
        aMap.put("auto_power_off", new Pair<>(res.getString(R.string.pref_system_auto_power_off_key), true));
        return aMap;
    }

    private boolean getSettings(SharedPreferences sharedPrefs) {
        Log.i(LOGTAG, "Initializing shared preferences with cam preferences");

        boolean ok = true;

        SharedPreferences.Editor edit = sharedPrefs.edit();

        for (Map.Entry<String, Pair<String, Boolean>> entry : camConfigKeyToSharedPrefKeyMap.entrySet()) {
            String camKey = entry.getKey();
            String sharedprefKey = entry.getValue().first;
            boolean isBoolPref = entry.getValue().second;

            try {
                String camValue = getSetting(camKey);
                if (isBoolPref) {
                    boolean boolVal = camValue.equals("1");
                    edit.putBoolean(sharedprefKey, boolVal);
                } else {
                    edit.putString(sharedprefKey, camValue);
                }
            } catch (SendMessageException e) {
                e.printStackTrace();
                Log.e(LOGTAG, "Failed to get setting '" + camKey + "' from camera!");
                ok = false;
            }
        }
        ok = edit.commit() && ok;

        return ok;
    }

    private boolean setSettings(SharedPreferences sharedPrefs) {
        Log.i(LOGTAG, "Syncing preferences to cam");

        boolean ok = true;

        for (Map.Entry<String, Pair<String, Boolean>> entry : camConfigKeyToSharedPrefKeyMap.entrySet()) {
            String camKey = entry.getKey();
            String sharedprefKey = entry.getValue().first;
            boolean isBoolPref = entry.getValue().second;

            String camValue;
            try {
                camValue = getSetting(camKey);
            } catch (SendMessageException e) {
                e.printStackTrace();
                Log.e(LOGTAG, "Failed to get setting '" + camKey + "' from camera: " + e.getMessage());
                ok = false;
                continue;
            }

            try {
                if (isBoolPref) {
                    boolean sharedPrefValue = sharedPrefs.getBoolean(sharedprefKey, false);
                    boolean camConfBoolValue = camValue.equals("1");
                    if (sharedPrefValue != camConfBoolValue) {
                        String newCamValue = sharedPrefValue ? "1" : "0";
                        Log.i(LOGTAG, "Update setting: " + camKey + "=" + newCamValue);
                        setSetting(camKey, newCamValue);
                    }
                } else {
                    String sharedPrefValue = sharedPrefs.getString(sharedprefKey, "");
                    if (!sharedPrefValue.equals(camValue)) {
                        Log.i(LOGTAG, "Update setting: " + camKey + "=" + sharedPrefValue);
                        setSetting(camKey, sharedPrefValue);
                    }
                }
            } catch (SendMessageException e) {
                e.printStackTrace();
                Log.e(LOGTAG, "Failed to set setting '" + camKey + "' on camera: " + e.getMessage());
                ok = false;

                // Set shared pref back to camera value
                if (isBoolPref) {
                    sharedPrefs.edit().putBoolean(sharedprefKey, camValue.equals("1")).apply();
                } else {
                    sharedPrefs.edit().putString(sharedprefKey, camValue).apply();
                }
            }
        }
        return ok;
    }

    private void shutdown() {
        shutdown(false, "");
    }

    synchronized private void shutdown(boolean abnormal, String extraMessage) {
        String shutdownReason = abnormal
                ? String.format("Abnormal shutdown: %s", extraMessage)
                : "Normal shutdown";
        Log.i(LOGTAG, String.format("Connection shutting down: %s", shutdownReason));

        if (!abnormal && state == NetworkInfo.State.CONNECTED)
            fireStateChanged(NetworkInfo.State.DISCONNECTING, shutdownReason);

        previewImageTimer.cancel();
        periodicTimer.cancel();

        try {
            if (sock != null)
                sock.setSoTimeout(500);
            sendCommand(CameraCommand.Session_Close);
        } catch (SendMessageException | SocketException ignored) {
        }

        try {
            if (sock != null)
                sock.close();
        } catch (IOException ignored) {
        }
        sock = null;
        sock_out = null;
        sock_in = null;

        // give the cam some time to do whatever it does, directly connecting again sometimes
        // fails so add a timeout here to prevent that
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        if (abnormal || state == NetworkInfo.State.DISCONNECTING)
            fireStateChanged(NetworkInfo.State.DISCONNECTED, shutdownReason);
        Log.i(LOGTAG, "Connection closed");
    }

    private void fireStateChanged(NetworkInfo.State state, String reason) {
        for (X7RemoteSessionListener listener : listeners) {
            listener.stateChanged(state, reason);
        }
    }

    private void fireRecordingStatusChanged(boolean recording) {
        for (X7RemoteSessionListener listener : listeners) {
            listener.recordingStatusChanged(recording);
        }
    }

    private void fireCamStatusChanged(BatteryLevel level, int sdCardcapactiy) {
        for (X7RemoteSessionListener listener : listeners) {
            listener.generalInfoChanged(level, sdCardcapactiy);
        }
    }

    private void fireNewCamPreviewImageAvailable(Bitmap bmp) {
        for (X7RemoteSessionListener listener : listeners) {
            listener.newCamPreviewImageAvailable(bmp);
        }
    }

    private byte[] httpGET(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setDoOutput(true);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.connect();

        byte[] imageBytes = IOUtils.toByteArray(connection.getInputStream());

        connection.disconnect();
        return imageBytes;
}

    @NonNull
    private JSONObject getConfig() throws IOException, JSONException {
        byte[] content = httpGET(String.format("http://%s/pref/config", CamAddress));
        String str = new String(content, StandardCharsets.UTF_8);
        return new JSONObject(str);
    }

    private void abort(String why) {
        Log.e(LOGTAG, why);
        shutdown(true, why);
    }

    synchronized private void initSession(SharedPreferences sharedPrefs, Resources res) throws ConnectionException, AssertionException {
        fireStateChanged(NetworkInfo.State.CONNECTING, "");

        // init socket connection
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(CamAddress, CamPort), 2000);
            sock_in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            sock_out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));

            Log.i(LOGTAG, "Connection successful");
        } catch(IOException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw new ConnectionException(e.getMessage());
        }

        // init TCP session
        try {
            JSONObject answer = sendCommandWithAssert(CameraCommand.Session_Init, 0);
            SessionID = answer.getJSONArray("param").getInt(0);
        }  catch (JSONException | SendMessageException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw new ConnectionException(e.getMessage());
        }
        Log.i(LOGTAG, String.format("Session ID: %d", SessionID));

        // pair with camera
        try {
            sendCommandWithAssert(CameraCommand.Remote_Pair, 0);
        } catch (SendMessageException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw new ConnectionException(e.getMessage());
        }

        // Setup keep alive and getting battery and sd card capacity status
        // Keep alive needs to be sent every 4.5 seconds
        periodicTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    sendCommandWithAssert(CameraCommand.Keep_Alive, -26);
                } catch (SendMessageException e) {
                    e.printStackTrace();
                    abort(e.getMessage());
                    periodicTimer.cancel();
                }

                try {
                    int batteryLevel = Integer.parseInt(getSetting("battery_level"));
                    int sdCardCapacity = Integer.parseInt(getSetting("sd_card_capacity"));
                    fireCamStatusChanged(BatteryLevel.fromValue(batteryLevel), sdCardCapacity);
                } catch (SendMessageException e) {
                    e.printStackTrace();
                    abort(e.getMessage());
                    periodicTimer.cancel();
                }
            }
        }, 0, 4500);

        // get current recording status
        try {
            JSONObject config = getConfig();
            if (!config.has("recording_status"))
                throw new ConnectionException("Config doesn't have recording_status?!");
            recording = config.getInt("recording_status") == 1;
            Log.i(LOGTAG, String.format("Currently recording: %b", recording));
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw new ConnectionException(e.getMessage());
        }

        // initialize/sync settings
        if (camConfigKeyToSharedPrefKeyMap == null) {
            camConfigKeyToSharedPrefKeyMap = buildKeyMap(res);
        }

        try {
            boolean update = sharedPrefs.getBoolean(res.getString(R.string.pref_update), false);
            if (update) {
                boolean ok = setSettings(sharedPrefs);
                sharedPrefs.edit().putBoolean(res.getString(R.string.pref_update), false).apply();
                if (!ok)
                    throw new ConnectionException("Failed to synchronize settings");
            } else {
                if (!getSettings(sharedPrefs))
                    throw new ConnectionException("Failed to initialize settings");
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw e;
        }

        // enable camera preview
        try {
            enableCamPreview();
        } catch (SendMessageException | JSONException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw new ConnectionException(e.getMessage());
        }

        fireStateChanged(NetworkInfo.State.CONNECTED, "");
    }

    enum StreamConfigResult
    {
        AlreadyOn,
        Activated,
        Error
    }

    private StreamConfigResult send_dual_streams_config_first() throws SendMessageException, JSONException {
        sendCommandWithAssert(CameraCommand.Setting_Change_Start, 0);

        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":6,\"type\":\"dual streams\",\"param\":\"on\",\"param_size\":2}",
                SessionID
        );
        JSONObject answer = sendMessage(payload);

        if (answer.getInt("rval") == 0 && answer.has("settable") && answer.getString("settable").contains("streaming;off")) {
            sendCommandWithAssert(CameraCommand.Setting_Change_Stop, 0);
            return StreamConfigResult.AlreadyOn;
        }
        else if (answer.getInt("rval") == 0 && !answer.has("settable")) {
            sendCommandWithAssert(CameraCommand.Setting_Change_Stop, 0);
            return StreamConfigResult.Activated;
        }
        else {
            return StreamConfigResult.Error;
        }
    }

    private void send_dual_streams_config() throws SendMessageException, JSONException {
        sendCommandWithAssert(CameraCommand.Setting_Change_Start, 0);

        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":6,\"type\":\"dual streams\",\"param\":\"on\",\"param_size\":2}",
                SessionID
        );
        JSONObject answer = sendMessage(payload);
        if (answer.getInt("rval") == 0) {
            sendCommandWithAssert(CameraCommand.Setting_Change_Stop, 0);
        }
    }

    private void send_stream_type_config() throws SendMessageException, JSONException {
        sendCommandWithAssert(CameraCommand.Setting_Change_Start, 0);

        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":6,\"type\":\"stream type\",\"param\":\"mjpg\",\"param_size\":4}",
                SessionID
        );
        JSONObject answer = sendMessage(payload);
        if (answer.getInt("rval") == 0) {
            sendCommandWithAssert(CameraCommand.Setting_Change_Stop, 0);
        }
    }

    private void enableCamPreview() throws SendMessageException, JSONException {
        switch (send_dual_streams_config_first()) {
            case Activated:
                send_stream_type_config();
                send_dual_streams_config();
            case AlreadyOn:
                previewSupported = true;
                break;
            default:
                previewSupported = false;
        }

        setupPreviewImageTimer();
    }

    private byte[] getPreviewImage() throws IOException {
        if (!previewSupported)
            return new byte[]{};

        try {
            return httpGET(String.format("http://%s/mjpeg/amba.jpg", CamAddress));
        } catch (FileNotFoundException | SocketTimeoutException e) {
            Log.w(LOGTAG, e.toString());
            return new byte[]{};
        } catch (IOException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw e;
        }
    }

    private void generateCamPreviewImage() {
        try {
            byte[] imagebuf = getPreviewImage();
            if (imagebuf.length == 0)
                return;
            Bitmap bitmap = BitmapFactory.decodeByteArray(imagebuf, 0, imagebuf.length);

            fireNewCamPreviewImageAvailable(bitmap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupPreviewImageTimer() {
        int interval = 50;
        if (isRecording())
            interval = 200;

        previewImageTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                generateCamPreviewImage();
            }
        }, 0, interval);
    }

    private boolean shouldSkipReply(JSONObject reply) throws JSONException {
        for ( Pair<String, String> skipEntry : tcpRepliesToSkip ) {
            String key = skipEntry.first;
            String val = skipEntry.second;

            if (reply.has(key) && reply.get(key).toString().equals(val))
                return true;
        }
        return false;
    }

    private JSONObject getNextReply(List<String> replies) throws JSONException {
        for (Iterator<String> iterator = replies.iterator(); iterator.hasNext();) {
            String reply = iterator.next();
            JSONObject jsonReply = new JSONObject(reply);

            if (shouldSkipReply(jsonReply)) {
                iterator.remove();
            } else {
                return jsonReply;
            }
        }

        return null;
    }

    synchronized private JSONObject sendMessage(String payload) throws SendMessageException {
        return sendMessage(payload, ReplyMode.Read);
    }

    synchronized private JSONObject sendMessage(String payload, ReplyMode replyMode) throws SendMessageException {
        if (sock == null)
            throw new SendMessageException("Socket does not exist");

        try {
            Log.d(LOGTAG + LOGTAGNETWORK, String.format("TCP RQ: %s", payload));
            sock_out.write(payload);
            sock_out.flush();

            while (replyMode == ReplyMode.Read) {
                char[] buf = new char[65556];
                int bytes_read = sock_in.read(buf);
                String replyStr = String.valueOf(buf, 0, bytes_read-1);

                Log.d(LOGTAG + LOGTAGNETWORK, String.format("TCP RP: %s", replyStr));

                // reply can have multiple answers,
                // first remove any answers to skip and then check if there is on
                // valid answer left. If there is none left, try read from the socket again

                List<String> replies = new ArrayList<>(Arrays.asList(replyStr.split("\0")));

                JSONObject nextReply = getNextReply(replies);
                if (nextReply == null) {
                    // everything skipped, read from socket again
                    continue;
                }

                if (replies.size() > 1)
                    throw new SendMessageException("Too much replies left over!");

                return nextReply;
            }

            return null;
        } catch (IOException | JSONException e) {
            Log.e(LOGTAG + LOGTAGNETWORK, e.getMessage());
            throw new SendMessageException(e.getMessage());
        }
    }

    private JSONObject sendMessageWithAssert(String payload, int expectedReturnValue) throws SendMessageException {
        JSONObject answer = sendMessage(payload);
        try {
            if (!answer.has("rval") || Integer.parseInt(answer.getString("rval")) != expectedReturnValue)
                throw new AssertionException("Return value missing or does not match!");
        } catch (JSONException | AssertionException e) {
            e.printStackTrace();
            abort(e.getMessage());
            throw new SendMessageException(e.getMessage());
        }
        return answer;
    }

    private JSONObject sendCommandWithAssert(CameraCommand command, int expectedReturnValue) throws SendMessageException {
        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":%d,\"param_size\":0}",
                SessionID,
                command.getId()
        );
        return sendMessageWithAssert(payload, expectedReturnValue);
    }

    private void sendCommand(CameraCommand command, ReplyMode replyMode) throws SendMessageException {
        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":%d,\"param_size\":0}",
                SessionID,
                command.getId()
        );
        sendMessage(payload, replyMode);
    }

    private void sendCommand(CameraCommand command) throws SendMessageException {
        sendCommand(command, ReplyMode.Read);
    }

    private String getSetting(String key) throws SendMessageException {
        // TCP RQ: {"token":12,"msg_id":7,"type":"sd_card_capacity","param_size":0} -> TCP RP: { "rval": 0, "param_size": 2, "param": "40" }
        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":%d,\"type\":\"%s\",\"param_size\":0}",
                SessionID,
                CameraCommand.Get_Setting.getId(),
                key
        );
        JSONObject answer = sendMessageWithAssert(payload, 0);
        try {
            if (!answer.has("param"))
                throw new AssertionException("Config value was not changed!");
            return answer.getString("param");
        } catch (JSONException | AssertionException e) {
            e.printStackTrace();
            throw new SendMessageException(e.getMessage());
        }
    }

    private void setSetting(String key, String value) throws SendMessageException {
        sendCommandWithAssert(CameraCommand.Setting_Change_Start, 0);

        String payload = String.format(
                Locale.US,
                "{\"token\":%d,\"msg_id\":%d,\"type\":\"%s\",\"param\":\"%s\",\"param_size\":%d}",
                SessionID,
                CameraCommand.Set_Setting.getId(),
                key,
                value,
                value.length()
        );
        JSONObject answer = sendMessageWithAssert(payload, 0);
        try {
            if (!answer.has("param") || !answer.getString("param").equals(value))
                throw new AssertionException("Config value was not changed!");
        } catch (JSONException | AssertionException e) {
            e.printStackTrace();
            throw new SendMessageException(e.getMessage());
        }

        sendCommandWithAssert(CameraCommand.Setting_Change_Stop, 0);
    }
    //endregion
}
