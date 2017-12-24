package jschmer.x7remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class WifiStateReceiver extends BroadcastReceiver {

    private static final String LOGTAG = WifiStateReceiver.class.getSimpleName();
    private final List<WifiStateChangeListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        final NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        final String bssid = intent.getStringExtra(WifiManager.EXTRA_BSSID);
//        final WifiInfo wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
        Log.i(LOGTAG, String.format("WIFI state: %s", netInfo.getState().toString()));
        // wifi is fully connected when a bssid is available
        if (netInfo.getState() != NetworkInfo.State.CONNECTED || bssid != null) {
            fireStateChanged(netInfo.getState());
        }
    }

    public void addListener(WifiStateChangeListener listener) {
        listeners.add(listener);
    }
    public void removeMyEventListener(WifiStateChangeListener listener) {
        listeners.remove(listener);
    }

    private void fireStateChanged(NetworkInfo.State state) {
        for (WifiStateChangeListener listener : listeners) {
            listener.wifiStateChanged(state);
        }
    }
}
