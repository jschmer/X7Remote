package jschmer.x7remote;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

public class CameraControl extends AppCompatActivity {
    private static final String LOGTAG = CameraControl.class.getSimpleName();

    private X7RemoteSession x7session = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_control);
    }

    @Override
    protected void onResume() {
        super.onResume();

        connectToCam();
    }

    @Override
    protected void onPause() {
        super.onPause();

        disconnectFromCam();
    }

    private void returnToMainActivity() {
        Intent intent = new Intent(CameraControl.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void showErrorPopup(String message) {
        Log.e(LOGTAG, message);
        runOnUiThread(() -> {
            AlertDialog.Builder dlg = new AlertDialog.Builder(CameraControl.this)
                    .setTitle("Error")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Got it", (dialog, which) -> {
                                dialog.dismiss();
                                returnToMainActivity();
                            }
                    );
            dlg.show();
        });
    }

    private void handleRuntimeError(String why) {
        // this is an x7session triggered error, the session internally
        // already shut down, so no need to close again
        x7session = null;

        showErrorPopup(
            "Failed to communication with camera!\n" +
            "Please retry or restart your camera.\n" +
            String.format("Error: %s", why)
        );
    }

    private void handleConnectionError(String why) {
        // this happens when not connected yet, so no need
        // to close the session
        x7session = null;

        showErrorPopup(
            "Failed to connect to remote camera TCP API!\n" +
            "Please retry or restart your camera.\n" +
            String.format("Error: %s", why)
        );
    }

    private Drawable convertToDisabledIcon(Drawable img) {
        Drawable res = img.mutate();
        res.setAlpha(255/4);
        return res;
    }

    private void updateImageButton(ImageButton imgBtn, boolean enabled, int resourceId) {
        imgBtn.setEnabled(enabled);

        Drawable originalIcon = getResources().getDrawable(resourceId);
        Drawable icon = enabled ? originalIcon : convertToDisabledIcon(originalIcon);

        imgBtn.setImageDrawable(icon);
    }

    private void updateButtonUI(boolean recording) {
        runOnUiThread(
            () -> {
                ImageButton btn = findViewById(R.id.btn_videoCapture);
                if (recording) {
                    btn.setImageResource(R.drawable.ic_record_video_stop);
                } else {
                    btn.setImageResource(R.drawable.ic_record_video_start);
                }

                // disable other buttons when recording
                updateImageButton(findViewById(R.id.btn_snapshot), x7session.canSnapshot(), R.drawable.ic_snapshot);
                updateImageButton(findViewById(R.id.btn_settings), x7session.canChangeSettings(), R.drawable.ic_settings);

                // hook up recording indicator blinking animation
                findViewById(R.id.layout_recording).setVisibility(recording ? View.VISIBLE : View.INVISIBLE);
                ImageView recordingIcon = findViewById(R.id.img_recording);
                recordingIcon.clearAnimation();
                if (recording) {
                    Animation blinkingAnimation = AnimationUtils.loadAnimation(this, R.anim.blinking);
                    recordingIcon.startAnimation(blinkingAnimation);
                }
            }
        );
    }

    private void connectToCam() {
        runOnUiThread(
            () -> {
                // show loader
                findViewById(R.id.loaderPreview).setVisibility(View.VISIBLE);
                findViewById(R.id.loaderActions).setVisibility(View.VISIBLE);

                // hide camera controls
                findViewById(R.id.layout_actions).setVisibility(View.INVISIBLE);
                findViewById(R.id.layout_recording).setVisibility(View.INVISIBLE);
                findViewById(R.id.layout_camInfo).setVisibility(View.INVISIBLE);
            }
        );

        AsyncTask.execute(() -> {
            Log.i(LOGTAG, "Connecting to camera...");
            try {
                x7session = new X7RemoteSession(
                        PreferenceManager.getDefaultSharedPreferences(this),
                        getResources()
                );
            } catch (ConnectionException | AssertionException e) {
                Log.e(LOGTAG, e.getMessage());
                handleConnectionError(e.getMessage());
                return;
            }

            updateButtonUI(x7session.isRecording());

            x7session.addListener(new X7RemoteSessionListener() {
                @Override
                public void stateChanged(NetworkInfo.State newstate, String reason) {
                    if (reason.length() > 0)
                        handleRuntimeError(reason);
                }

                @Override
                public void recordingStatusChanged(boolean recording) {
                    updateButtonUI(recording);
                }

                @Override
                public void generalInfoChanged(BatteryLevel level, int sdCardCapacity) {
                    runOnUiThread(() -> {
                        // set battery level image
                        ImageView batteryImg = findViewById(R.id.img_battery);
                        batteryImg.clearAnimation();
                        int imageId = R.drawable.ic_battery_4_black_24dp;
                        switch (level) {
                            case L0:
                            case L1:
                                imageId = R.drawable.ic_battery_0_black_24dp;
                                Animation blinkingAnimation = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.blinking);
                                batteryImg.startAnimation(blinkingAnimation);
                                break;
                            case L2:
                                imageId = R.drawable.ic_battery_2_black_24dp;
                                break;
                            case L3:
                                imageId = R.drawable.ic_battery_3_black_24dp;
                                break;
                            case L4:
                                imageId = R.drawable.ic_battery_4_black_24dp;
                                break;
                            case AC:
                                imageId = R.drawable.ic_battery_charging_black_24dp;
                                break;
                        }
                        batteryImg.setImageResource(imageId);

                        // update sd card capactity
                        TextView sdCardCapacityText = findViewById(R.id.txt_sdCardCapactiy);
                        sdCardCapacityText.setText(String.format(Locale.US, "%d %%", sdCardCapacity));

                        // and show the data
                        findViewById(R.id.layout_camInfo).setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void newCamPreviewImageAvailable(Bitmap bmp) {
                    runOnUiThread(
                            () -> {
                                ProgressBar spinner = findViewById(R.id.loaderPreview);
                                spinner.setVisibility(View.GONE);

                                ImageView imageView = findViewById(R.id.camPreview);
                                imageView.setImageBitmap(bmp);
                            }
                    );
                }
            });

            Log.i(LOGTAG, "Connecting to camera... DONE!");

            // connection camera done, show user interaction controls and hide corresponding loader
            runOnUiThread(
                    () -> {
                        findViewById(R.id.loaderActions).setVisibility(View.INVISIBLE);
                        findViewById(R.id.layout_actions).setVisibility(View.VISIBLE);
                    }
            );
        });
    }

    private void disconnectFromCam() {
        AsyncTask.execute(() -> {
            Log.i(LOGTAG, "Disconnect from camera...");
            try {
                if (x7session != null)
                    x7session.close();
            } catch (Exception ignored) {
            }
            Log.i(LOGTAG, "Disconnect from camera... DONE");
        });
    }

    public void onVideoCapture(View view) {
        AsyncTask.execute(() -> {
            try {
                if (x7session.isRecording())
                    x7session.stopVideoCapture();
                else
                    x7session.startVideoCapture();
            } catch (SendMessageException e) {
                e.printStackTrace();
            }
        });
    }

    public void onImageCapture(View view) {
        AsyncTask.execute(() -> {
            try {
                x7session.snapshot();
            } catch (SendMessageException e) {
                e.printStackTrace();
            }
        });
    }

    public void onSettings(View view) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    public void onPowerOff(View view) {
        AsyncTask.execute(() -> {
            try {
                x7session.powerOff();
            } catch (SendMessageException e) {
                e.printStackTrace();
            }
            disconnectFromCam();
            returnToMainActivity();
        });
    }
}
