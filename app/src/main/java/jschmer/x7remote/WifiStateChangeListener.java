package jschmer.x7remote;

import android.net.NetworkInfo;

public interface WifiStateChangeListener {
    void wifiStateChanged(NetworkInfo.State state);
}
