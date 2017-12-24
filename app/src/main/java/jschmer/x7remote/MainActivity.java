package jschmer.x7remote;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_COMING_FROM_MAIN = "Coming from Main";

    private static final String LOGTAG = MainActivity.class.getSimpleName();
    private WifiStateReceiver wifiListener = new WifiStateReceiver();
    private boolean wifiListenerActive = false;
    private boolean firstStart = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiListener.addListener(state -> {
            if (state == NetworkInfo.State.CONNECTED) {
                checkAndUpdateConnectivity();
            } else {
                showWifiConfigUI();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(wifiListener, intentFilter);
        wifiListenerActive = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeWifiListener();
    }

    private void removeWifiListener() {
        if (wifiListenerActive) {
            unregisterReceiver(wifiListener);
            wifiListenerActive = false;
        }
    }

    private void showConnectCamUI() {
        runOnUiThread(() -> {
            findViewById(R.id.btn_wifiSettings).setVisibility(View.GONE);
            findViewById(R.id.pleaseConnectToWifi).setVisibility(View.GONE);
            findViewById(R.id.btn_connectCam).setVisibility(View.VISIBLE);
        });
    }

    private void showWifiConfigUI() {
        runOnUiThread(() -> {
            findViewById(R.id.btn_wifiSettings).setVisibility(View.VISIBLE);
            findViewById(R.id.pleaseConnectToWifi).setVisibility(View.VISIBLE);
            findViewById(R.id.btn_connectCam).setVisibility(View.GONE);
        });
    }

    private void checkAndUpdateConnectivity() {
        AsyncTask.execute(() -> {
            runOnUiThread(() -> {
                findViewById(R.id.progressBar).setVisibility(View.VISIBLE);
                findViewById(R.id.checkingConnectivity).setVisibility(View.VISIBLE);
            });

            int i = 0;
            boolean reachable = isCamReachable();
            // It happens that the cam is not reachable yet directly after we receive
            // a CONNECTED state from WifiManager, so do a simple retry for some time.
            // Only do retry if the loading spinner is not active as we want to show the
            // UI as fast as possible for the first time.
            while (!reachable && i < 5 && !firstStart) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                ++i;
                reachable = isCamReachable();
            }

            if (reachable) {
                showConnectCamUI();
            } else {
                showWifiConfigUI();
            }

            firstStart = false;
            runOnUiThread(() -> {
                findViewById(R.id.progressBar).setVisibility(View.GONE);
                findViewById(R.id.checkingConnectivity).setVisibility(View.GONE);
            });
        });
    }

    private boolean isCamReachable() {
        try {
            try (Socket soc = new Socket()) {
                soc.connect(new InetSocketAddress(X7RemoteSession.CamAddress, X7RemoteSession.CamPort), 2000);
            }
            Log.i(LOGTAG, "Camera reachable");
            return true;
        } catch (IOException ex) {
            Log.i(LOGTAG, "Camera NOT reachable");
            return false;
        }
    }

    public void connectCamera(View view) {
        Intent intent = new Intent(this, CameraControl.class);
        intent.putExtra(EXTRA_COMING_FROM_MAIN, true);
        startActivity(intent);
        removeWifiListener();
    }

    public void goToWifiSettings(View view) {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException ex) {
            ex.printStackTrace();
        }
    }
}
