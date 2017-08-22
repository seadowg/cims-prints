package com.openandid.core;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import logic.FingerType;
import logic.HostUsbManager;
import logic.Scanner;
import logic.Scanner_Lumidigm_Mercury;

public class ScanningActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.openandid.screentest.ScanningActivity.USB_PERMISSION";
    private static final String TAG = "ScanningActivity";

    /*
        I'm embarrassed by this mess. Please, refactor...
     */

    public static boolean waitingForPermission = true;

    private static UsbDevice freeScanner = null;
    private static HashMap<String, Bitmap> scanImages;
    private static HashMap<String, Boolean> scannedFingers;
    private static HashMap<String, String> templateCache;
    private static HashMap<String, String> isoTemplateCache;

    private PendingIntent mPermissionIntent;

    FingerType leftFinger;
    FingerType rightFinger;
    ImageButton leftButton;
    ImageButton rightButton;
    ImageButton proceed;
    ImageButton skip;

    boolean easySkip = false;
    ImageView restartScanner;
    Button cancelPopupButton;
    View popupView;
    PopupWindow popupWindow;
    TextView popupPrompt;
    Map<String, String> opts = null;
    Map<String, Object> binaryOpts = null;
    boolean killSwitch = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        scanImages = new HashMap<>();
        scannedFingers = new HashMap<>();
        templateCache = new HashMap<>();
        isoTemplateCache = new HashMap<>();

        if (savedInstanceState != null) {
            Log.i(TAG, "Found saved instance");
            ArrayList<String> keyList = new ArrayList<>();
            for (String key : savedInstanceState.keySet()) {
                keyList.add(key);
            }
            Log.i(TAG, String.format("Found keys: %s", Arrays.toString(keyList.toArray())));
            loadAssets(savedInstanceState);
        } else {
            Log.i(TAG, "No saved instance");
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String rotation = prefs.getString("bmt.rotation", "Horizontal");
        if (rotation.equals("Horizontal")) {
            if (getResources().getConfiguration().orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                Log.i(TAG, "Rotating to Landscape");
            }
            Log.i(TAG, "Setting Layout Landscape");
            setCorrectContentView(Configuration.ORIENTATION_LANDSCAPE);

        } else {
            if (getResources().getConfiguration().orientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                Log.i(TAG, "Rotating to Portrait");
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            Log.i(TAG, "Setting Layout Portrait");
            setCorrectContentView(Configuration.ORIENTATION_PORTRAIT);
        }

        load_options(getIntent().getExtras());
        //If we have options from the intent Bundle, parse and enact them
        if (opts != null || binaryOpts != null) {
            parse_options();
        } else {
            default_options();
        }

        //TODO Enable or edit exit button
        setupUI();
        if (savedInstanceState != null) {
            Log.d(TAG, "Redrawing assets");
            redrawAssets();
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resumed.");
        super.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        saveAssets(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "Paused.");
        super.onPause();
    }

    @Override
    protected void onRestart() {
        Log.i(TAG, "Restart.");
        super.onRestart();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Destroy");
        dismissProgressDialog();
        super.onDestroy();
    }

    private void dismissProgressDialog() {
        if (popupWindow != null && popupWindow.isShowing()) {
            try {
                popupWindow.dismiss();
            } catch (IllegalArgumentException e) {
                Log.i(TAG, "PopUp not attached to window: " + e.getMessage());

            } catch (Exception e2) {
                Log.e(TAG, "Unspecified Error with dismiss popup: " + e2.getMessage());
                e2.printStackTrace();
            }
        }
    }

    private void setupUI() {

        popupView = getLayoutInflater().inflate(R.layout.pop_up_wait, null);
        popupWindow = new PopupWindow(popupView);
        popupWindow.setWindowLayoutMode(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        popupPrompt = (TextView) popupView.findViewById(R.id.pop_up_wait_title);

        cancelPopupButton = (Button) popupView.findViewById(R.id.pop_up_wait_cancel_btn);

        TextView leftButtonText = (TextView) findViewById(R.id.scanner_txt_finger_1_title);
        leftButtonText.setText(leftFinger.finger_name);

        leftButton = (ImageButton) findViewById(R.id.scanner_btn_finger_1);
        leftButton.setImageDrawable(getResources().getDrawable(leftFinger.finger_image_location));
        leftButton.setOnClickListener(getScanClickListener(leftFinger, leftButton));
        leftButton.setOnLongClickListener(getScanLongClickListener(leftFinger, leftButton));

        TextView rightButtonText = (TextView) findViewById(R.id.scanner_txt_finger_2_title);
        rightButtonText.setText(rightFinger.finger_name);

        rightButton = (ImageButton) findViewById(R.id.scanner_btn_finger_2);
        rightButton.setImageDrawable(getResources().getDrawable(rightFinger.finger_image_location));
        rightButton.setOnClickListener(getScanClickListener(rightFinger, rightButton));
        rightButton.setOnLongClickListener(getScanLongClickListener(rightFinger, rightButton));

        proceed = (ImageButton) findViewById(R.id.scan_btn_proceed);
        proceed.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Controller.mScanner == null) {
                    restart_scanner(view);
                    Toast.makeText(ScanningActivity.this, getResources().getString(R.string.scanner_not_connected), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Controller.mScanner.get_ready()) {
                    check_nfiq_scores();
                } else {
                    Toast.makeText(ScanningActivity.this, getResources().getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
                }
            }
        });

        skip = (ImageButton) findViewById(R.id.scan_btn_skip);
        if (!easySkip) {
            skip.setVisibility(View.GONE);
        } else {
            skip.setOnClickListener(new Button.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //TODO add confirmation?
                    finish_cancel();
                }
            });

        }

        restartScanner = (ImageView) findViewById(R.id.headbar_scanner_btn);
        restartScanner.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                unplug_scanner(v);
            }
        });

        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
    }

    private void setCorrectContentView(int configuration) {
        switch (configuration) {
            case Configuration.ORIENTATION_LANDSCAPE:
                Log.i(TAG, "landscape");
                setContentView(R.layout.scanner_flex_layout_landscape);
                break;
            case Configuration.ORIENTATION_PORTRAIT:
                Log.i(TAG, "portrait");
                //setContentView(R.layout.scanner_flex_layout);
                setContentView(R.layout.scanner_flex_layout);
                break;
        }
    }

    private OnClickListener getScanClickListener(final FingerType finger, final ImageButton btn) {
        return new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleClick(finger, btn, false, view);
            }
        };
    }

    private Button.OnLongClickListener getScanLongClickListener(final FingerType finger, final ImageButton btn) {
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                handleClick(finger, btn, true, view);
                return true;
            }
        };
    }

    private void handleClick(final FingerType finger, final ImageButton btn, final boolean instant, View view) {
        if (Controller.mScanner == null) {
            restart_scanner(view);
            Toast.makeText(this, getResources().getString(R.string.scanner_not_connected), Toast.LENGTH_SHORT).show();
            return;
        } else {
            HashMap<String, UsbDevice> deviceList = Controller.mUsbManager.getDeviceList();
            Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
            if (!deviceIterator.hasNext()) {
                Log.i(TAG, "Device Unplugged");
                restart_scanner(view);
                Toast.makeText(this, getResources().getString(R.string.scanner_not_connected), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (Controller.mScanner.get_ready()) {
            popupPrompt.setText(getResources().getString(R.string.scan_prompt) + " " + finger.finger_name);
            popupWindow.setWindowLayoutMode(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            popupWindow.showAtLocation(view, Gravity.CENTER_VERTICAL, 0, 0);
            final FingerScanInterface f = new FingerScanInterface(finger.finger_key, Controller.mScanner, btn, view, instant);
            cancelPopupButton.setOnClickListener(new Button.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancel_scan(f);
                }
            });
            cancelPopupButton.setVisibility(View.VISIBLE);
            popupWindow.update();
            f.execute();
        }
    }

    public Bundle saveAssets(Bundle bundle) {

        bundle.putSerializable("scanImages", scanImages);
        bundle.putSerializable("scannedFingers", scannedFingers);
        bundle.putSerializable("template_cache", templateCache);
        bundle.putSerializable("iso_template_cache", isoTemplateCache);
        return bundle;
    }


    @SuppressWarnings("unchecked")
    public void loadAssets(Bundle bundle) {
        if (bundle != null) {
            Log.e(TAG, "Loading Assets");
            scanImages = (HashMap<String, Bitmap>) bundle.getSerializable("scanImages");
            scannedFingers = (HashMap<String, Boolean>) bundle.getSerializable("scannedFingers");
            templateCache = (HashMap<String, String>) bundle.getSerializable("template_cache");
            isoTemplateCache = (HashMap<String, String>) bundle.getSerializable("iso_template_cache");
        }
    }

    public void redrawAssets() {
        if (scanImages == null) {
            return;
        }
        for (String key : scanImages.keySet()) {
            Log.d(TAG, "found in scan Images: " + key);
        }
        Bitmap leftScanImage = scanImages.get(leftFinger.finger_key);
        if (leftScanImage != null) {
            leftButton.setImageBitmap(leftScanImage);
            Log.i(TAG, "Drew left Image from previous");
        } else {
            Log.i(TAG, "Left image is null");
        }
        Bitmap rightScanImage = scanImages.get(rightFinger.finger_key);
        if (rightScanImage != null) {
            rightButton.setImageBitmap(rightScanImage);
            Log.i(TAG, "Drew right Image from previous");
        } else {
            Log.i(TAG, "Right image is null");
        }
        //colorFinger();
    }

    @Override
    public void onBackPressed() {
        finish_cancel();
    }


    public boolean check_kill_switch() {
        if (!killSwitch) {
            return false;
        }
        killSwitch = false;
        return true;
    }

    public static void setFreeDevice(UsbDevice device) {
        Log.d(TAG, "Found free device!");
        freeScanner = device;
    }

    public static UsbDevice getFreeDevice() {
        return freeScanner;
    }

    private void load_options(Bundle extras) {
        try {
            if (opts == null || binaryOpts == null) {
                opts = new HashMap<>();
                binaryOpts = new HashMap<>();
            }
            for (String key : extras.keySet()) {
                try {
                    String val = extras.getString(key);
                    if (val == null) {
                        throw new Exception();
                    }
                    Log.i(TAG, "Key " + key + " val " + val);
                    opts.put(key, val);
                } catch (Exception e) {
                    Log.i(TAG, "Couldn't get string value for key " + key);
                    try {
                        binaryOpts.put(key, extras.getParcelable(key));
                    } catch (Exception e2) {
                        Log.i(TAG, "Couldn't get binary value for key " + key);
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "Error parsing options from bundle");
            e.printStackTrace();
            if (opts.isEmpty()) {
                opts = null;
            }
            if (binaryOpts.isEmpty()) {
                binaryOpts = null;
            }
        }
    }

    private void parse_options() {
        //TODO Write API for .SCAN broadcast

        try {
            String prompt = opts.get("prompt");
            TextView prompt_text = (TextView) findViewById(R.id.scanner_view_prompt);
            if (prompt_text != null) {
                prompt_text.setText(prompt);
            }
        } catch (Exception e) {
            Log.i(TAG, "No prompt to load");
        }
        try {
            String easy_skip_str = opts.get("easy_skip");
            if (easy_skip_str != null) {
                easySkip = Boolean.parseBoolean(easy_skip_str);
            } else {
                Log.i(TAG, "easy_skip_str was null...");
            }
        } catch (Exception e) {
            Log.i(TAG, "Couldn't parse for Easy Skip");
        }
        try {
            String left_finger_assignment = opts.get("left_finger_assignment");
            if (left_finger_assignment == null) {
                throw new NullPointerException();
            }
            leftFinger = new FingerType(left_finger_assignment);
        } catch (Exception e) {
            Log.i(TAG, "No assignment for left_finger, defaulting to thumb");
            leftFinger = new FingerType("left_thumb");
        }
        try {
            String right_finger_assignment = opts.get("right_finger_assignment");
            if (right_finger_assignment == null) {
                throw new NullPointerException();
            }
            rightFinger = new FingerType(right_finger_assignment);
        } catch (Exception e) {
            Log.i(TAG, "No assignment for right_finger, defaulting to thumb");
            rightFinger = new FingerType("right_thumb");
        }

    }

    private void default_options() {
        //TODO Write API for .SCAN broadcast
        easySkip = false;
        leftFinger = new FingerType("left_thumb");
        rightFinger = new FingerType("right_thumb");
    }

    public void colorFinger() {
        if (scannedFingers.get(leftFinger.finger_key) != null) {
            leftButton.setBackground(getResources().getDrawable(R.drawable.bg_shape_green_round));
        }
        if (scannedFingers.get(rightFinger.finger_key) != null) {
            rightButton.setBackground(getResources().getDrawable(R.drawable.bg_shape_green_round));
        }
    }

    public void check_nfiq_scores() {
        if (scannedFingers.keySet().size() < 2) {
            Toast t = Toast.makeText(this, getResources().getString(R.string.scan_all_fingers), Toast.LENGTH_LONG);
            t.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
            t.show();
            Log.i("scanning", "quality not enough keys");
            return;
        }
        Map<String, String> iso_set = Controller.mScanner.get_iso_biometrics();

        Map<String, String> out = new HashMap<>();
        for (String key : iso_set.keySet()) {
            String iso_key = key + "_iso";
            Log.i("Debug", "Found Key: " + iso_key);
            Log.i("Debug", "Val: " + iso_set.get(key));
            if (opts != null) {
                if (opts.get(key) != null) {
                    String new_name = opts.get(iso_key);
                    Log.i(TAG, "New name! :" + new_name);
                    out.put(new_name, iso_set.get(key));
                } else {
                    Log.i(TAG, "No rename rule for " + key);
                    out.put(key, iso_set.get(key));
                }
            } else {
                Log.i(TAG, "No rename rule for " + key);
                out.put(key, iso_set.get(key));
            }

        }
        finish_ok(out);
    }

    public void cancel_scan(FingerScanInterface inter) {
        inter.cancel(true);
    }

    public void restart_scanner(View parent) {
        //TEST
        Log.i(TAG, "Starting restart");
        ScannerSetup ss = new ScannerSetup(this, parent);
        ss.execute();
    }

    protected void unplug_scanner(View parent) {
        Log.i(TAG, "Starting Unplug");
        ScannerUnplug s = new ScannerUnplug(parent);
        s.execute();
    }

    protected void finish_ok(Map<String, String> output) {
        Log.i(TAG, "Finish OK -- Start");
        Log.i(TAG, "package keys -- " + output.keySet().toString());
        Intent i = new Intent();
        for (String k : output.keySet()) {
            i.putExtra(k, output.get(k));
            Log.i(TAG, String.format("K: %s V: %s", k, output.get(k)));
        }
        setResult(Activity.RESULT_OK, i);
        Log.i(TAG, "Finish OK -- Finished");
        try {
            Controller.mScanner.reset_dicts();
        } catch (Exception e) {
            Log.d(TAG, "Couldn't clear scanner");
        }
        finish();
    }

    protected void finish_cancel() {
        Log.i(TAG, "Caught Cancel Signal");
        Intent i = new Intent();
        setResult(RESULT_CANCELED, i);
        //finished = true;
        try {
            Controller.mScanner.reset_dicts();
        } catch (Exception e) {
            Log.d(TAG, "Couldn't clear scanner");
        }
        finish();
    }

    /*
     *    Scanning Task Async
     */

    private class FingerScanInterface extends AsyncTask<Void, Void, Void> {

        ImageButton view;
        View parent;
        Scanner mScanner;
        String finger;
        boolean success = false;
        boolean reconnect = false;
        boolean triggered = false;
        int starting_image_width, starting_image_height;

        private FingerScanInterface(String name, Scanner scanner, ImageButton spot, View parent, boolean instant) {
            super();
            this.parent = parent;
            view = spot;
            finger = name;
            mScanner = scanner;
            this.triggered = !instant;
            starting_image_height = view.getHeight();
            starting_image_width = view.getWidth();
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (Controller.mScanner == null) {
                Log.d(TAG, "Reconnect pre!");
                reconnect = true;
                cancel(true);
            }
        }

        @Override
        protected Void doInBackground(Void... params) {
            if (isCancelled() || check_kill_switch()) {
                Log.d(TAG, "Canceled background!");
                success = false;
                return null;
            }

            new Thread(new Runnable() {
                public void run() {
                    try {
                        while (!Controller.mScanner.finger_sensed()) {
                            if (isCancelled() || check_kill_switch()) {
                                Controller.mScanner.cancel_scan();
                                return;
                            }
                            if (Controller.mScanner.get_ready()) {
                                break;
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Log.d(TAG, "interrupted during sleep");
                            }
                        }
                    } catch (Exception e) {
                        if (Controller.mScanner != null) {
                            Controller.mScanner.cancel_scan();
                        }
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            //FIX
                            dismissProgressDialog();
                            popupPrompt.setText(getResources().getString(R.string.scan_complete));
                            Button b = cancelPopupButton;
                            b.setVisibility(View.GONE);
                            popupWindow.setWindowLayoutMode(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            //popUp.setBackgroundDrawable(grey_box);
                            try {
                                popupWindow.showAtLocation(parent, Gravity.CENTER_VERTICAL, 0, 0);
                                popupWindow.update();
                            } catch (WindowManager.BadTokenException e) {
                                Log.e(TAG, "No window to attach popup: " + e.getMessage());
                            }
                        }
                    });
                }
            }).start();
            success = mScanner.run_scan(finger, triggered);
            if (success) {
                //in case of disconnect, cache items locally
                try {
                    HashMap<String, String> existingBiometrics = Controller.mScanner.getBiometrics();
                    if (!existingBiometrics.isEmpty()) {
                        templateCache = existingBiometrics;
                    }
                    existingBiometrics = Controller.mScanner.get_iso_biometrics();
                    if (!existingBiometrics.isEmpty()) {
                        isoTemplateCache = existingBiometrics;
                    }
                    Log.i("PostExec", "Started");
                    Bitmap result = mScanner.get_image(finger);
                    Log.i("bmp_info", Integer.toString(result.getHeight()));
                    Log.i("bmp_info", Integer.toString(result.getWidth()));

                    Log.i(TAG, "Max Image width: " + starting_image_width + " Height: " + starting_image_height);

                    int new_height = (int) Math.round(starting_image_height * .92);
                    int new_width = (int) Math.round(starting_image_width * .92);
                    Log.i(TAG, "new height: " + Integer.toString(new_height));
                    Log.i(TAG, "new width: " + Integer.toString(new_width));

                    final Bitmap scaled = Bitmap.createScaledBitmap(result, new_width, new_height, true);
                    scanImages.put(finger, scaled);
                    Log.i(TAG, "Scaled BMP width: " + Integer.toString(scaled.getWidth()));

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                view.setScaleType(ScaleType.CENTER);
                                view.setImageBitmap(scaled);
                            } catch (IllegalArgumentException ille) {
                                Log.e(TAG, "Couldn't apply image to view: " + ille.getMessage());
                            }
                        }
                    });

                    scannedFingers.put(finger, true);

                } catch (RuntimeException np) {
                    Log.e(TAG, "Lost reference to an object!");
                    np.printStackTrace();
                    try {
                        Log.e(TAG, "Attempting to cancel");
                        this.cancel(true);
                    } catch (Exception e3) {
                        Log.e(TAG, "Couldn't cancel!");
                    }
                }
            } else {
                Log.i(TAG, "Caught Scan Failure. Canceling");
                this.cancel(true);
            }
            while (!mScanner.ready) {
                try {
                    Thread.sleep(200);
                } catch (Exception e) {
                    Log.d(TAG, "Waiting for scanner to complete flush.");
                }
                if (isCancelled()) {
                    Log.i(TAG, "Caught Cancel. Killing");
                    mScanner.ready = true;
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onCancelled() {
            try {
                dismissProgressDialog();
                unplug_scanner(parent);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, String.format("Cancel: %s", e.getMessage()));
            }
            super.onCancelled();
        }

        protected void onPostExecute(Void res) {
            dismissProgressDialog();
            colorFinger();
            if (!success) {
                Log.i(TAG, "Scan failed!");
            }
            if (reconnect) {
                Log.d(TAG, "Reconnect post!");
                unplug_scanner(view);
            }

        }
    }

    /*
     *    Scanner Unplug Async
     */
    private class ScannerUnplug extends AsyncTask<Void, Void, Void> {
        View parent;

        private ScannerUnplug(View parent) {
            this.parent = parent;
        }

        @Override
        protected void onPreExecute() {
            //popUp.setBackgroundDrawable(grey_box);
            popupPrompt.setText(getResources().getString(R.string.unplug_scanner));
            cancelPopupButton.setVisibility(View.GONE);
            popupWindow.showAtLocation(parent, Gravity.CENTER_VERTICAL, 0, 0);
            popupWindow.update();
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... params) {
            HashMap<String, UsbDevice> deviceList;
            Iterator<UsbDevice> deviceIterator;
            while (true) {
                try {
                    deviceList = Controller.mUsbManager.getDeviceList();
                } catch (NullPointerException e) {
                    Log.i(TAG, "No UsbManager Active");
                    return null;
                }
                deviceIterator = deviceList.values().iterator();
                if (!deviceIterator.hasNext()) {
                    Log.i(TAG, "Device Unplugged");
                    break;
                } else {
                    boolean foundValid = false;
                    while (deviceIterator.hasNext()) {
                        UsbDevice device = deviceIterator.next();
                        if (!HostUsbManager.vendor_blacklist.contains(device.getVendorId())) {
                            foundValid = true;
                        }
                    }
                    if (!foundValid) {
                        break;
                    }
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Log.d(TAG, "interrupted during sleep");
                }
            }
            return null;

        }

        @Override
        protected void onPostExecute(Void result) {
            dismissProgressDialog();
            restart_scanner(parent);
            super.onPostExecute(result);
        }
    }

    /*
    *    Scanner Setup Async
    */
    private class ScannerSetup extends AsyncTask<Void, Void, Void> {
        private Context oldContext;
        private View parent;
        ScannerSetup a_process;

        ScannerSetup(Context context, View parent) {
            super();
            a_process = this;
            this.parent = parent;
            oldContext = context;
        }

        protected void onPreExecute() {
            popupPrompt.setText(getResources().getString(R.string.attach_scanner));
            cancelPopupButton.setVisibility(View.VISIBLE);
            cancelPopupButton.setOnClickListener(new Button.OnClickListener() {

                @Override
                public void onClick(View v) {
                    a_process.cancel(true);
                }
            });
            //pop_cancel.setVisibility(View.GONE);
            //FIX
            popupWindow.showAtLocation(parent, Gravity.CENTER_VERTICAL, 0, 0);
            popupWindow.update();
        }

        @Override
        protected Void doInBackground(Void... params) {
            //TODO kill getApp
            Controller.mUsbManager = null;
            Controller.mHostUsbManager = null;
            Controller.mDevice = null;
            Controller.mUsbManager = (UsbManager) oldContext.getSystemService(Context.USB_SERVICE);
            Controller.mHostUsbManager = new HostUsbManager(Controller.mUsbManager);
            waitingForPermission = true;
            while (true) {
                try {
                    if (isCancelled() || killSwitch) {
                        Log.i(TAG, "Fingerprint Scanner Not Needed -- Canceling Receiver");
                        Controller.mHostUsbManager = null;
                        Controller.mDevice = null;
                        throw new Exception("FP Canceled!");
                    }
                    Thread.sleep(250);

                    if (freeScanner != null) {
                        Controller.mDevice = freeScanner;
                        if (Controller.mUsbManager.hasPermission(Controller.mDevice)) {
                            Log.d(TAG, "We have permission, loading attached usb");
                            waitingForPermission = false;

                        } else {
                            Log.e(TAG, "No Scanner permission!!");
                            Controller.mUsbManager.requestPermission(Controller.mDevice, mPermissionIntent);
                        }
                        break;
                    }

                } catch (NullPointerException e2) {
                    Log.i(TAG, "no permission...");
                } catch (Exception e2) {
                    Log.i(TAG, "Cancelled!");
                    e2.printStackTrace();
                    break;
                }
            }
            while (true) {
                Log.i(TAG, "In loop...");
                if (!isCancelled()) {
                    int c = 0;
                    while (waitingForPermission && !killSwitch) {
                        try {
                            Thread.sleep(100);
                            Log.i(TAG, String.format("waiting still for permission... killed: %s", killSwitch));
                        } catch (InterruptedException e) {
                            Log.d(TAG, "interrupted during sleep");
                        }
                        if (isCancelled() || check_kill_switch()) {
                            return null;
                        }
                        c += 1;
                        if (c > 50) {
                            cancel(true);
                        }
                    }
                    if (Controller.mDevice != null) {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        if (Controller.mScanner == null) {
                            Log.i(TAG, "Scanner is null!");
                            Controller.mScanner = new Scanner_Lumidigm_Mercury(Controller.mDevice, Controller.mUsbManager);
                        } else {
                            Log.i(TAG, "Scanner already exists!");
                            Log.i(TAG, "Wiping Scanner");
                            Controller.mScanner = new Scanner_Lumidigm_Mercury(Controller.mDevice, Controller.mUsbManager);
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                popupPrompt.setText(getResources().getString(R.string.please_wait));
                                Button b = cancelPopupButton;
                                b.setVisibility(View.GONE);
                                popupWindow.setWindowLayoutMode(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                try {
                                    popupWindow.showAtLocation(parent, Gravity.CENTER_VERTICAL, 0, 0);
                                    popupWindow.update();
                                } catch (WindowManager.BadTokenException e) {
                                    Log.e(TAG, "No window to attach popup: " + e.getMessage());
                                }
                            }
                        });
                        while (!Controller.mScanner.get_ready()) {
                            if (isCancelled() || check_kill_switch()) {
                                return null;
                            }
                            if (Scanner.scan_cancelled) {
                                return null;
                            }
                            //if scan init failed
                            try {
                                Thread.sleep(250);
                                if (Controller.mScanner == null) {
                                    break;
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            Log.i(TAG, "Scanner isn't ready...");
                        }
                        break;
                    } else {
                        Log.i(TAG, "Scanner issue! scanner is null");
                        break;
                    }
                } else {
                    //Cancelled
                    Log.i(TAG, "Cancelled");
                    break;
                }
            }
            if (isCancelled()) {
                return null;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Log.d(TAG, "interrupted during sleep", e);
            }
            return null;
        }

        @Override
        protected void onCancelled(Void res) {
            try {
                Controller.mScanner = null;
                dismissProgressDialog();
            } catch (Exception e) {
                Log.i(TAG, "Couldn't kill scanner on cancel");
            }
        }

        @Override
        protected void onPostExecute(Void res) {
            try {
                if (Controller.mScanner != null) {
                    for (String k : templateCache.keySet()) {
                        Controller.mScanner.setBiometrics(k, templateCache.get(k));
                    }
                    for (String k : isoTemplateCache.keySet()) {
                        Controller.mScanner.set_iso_template(k, isoTemplateCache.get(k));
                    }
                }
                dismissProgressDialog();
                Log.d(TAG, "Finished Setup.");
            } catch (NullPointerException np) {
                Log.e(TAG, "Couldn't finish scanner setup");
                np.printStackTrace();
                try {
                    dismissProgressDialog();
                } catch (Exception e2) {
                    Log.e(TAG, "Couldn't dismiss dialog!");
                }
            }
        }
    }

    public static Intent get_next_scanning_bundle(Intent i, int index) {
        Log.i(TAG, "Getting Next Scanning Bundle");
        Bundle data = i.getExtras();
        return get_next_scanning_bundle(data, index);
    }

    public static Intent get_next_scanning_bundle(Bundle data, int index) {
        List<String> fields = new ArrayList<String>() {{
            add("prompt");
            add("left_finger_assignment");
            add("right_finger_assignment");
            add("easy_skip");
        }};
        Bundle b = new Bundle();
        boolean all_null = true;
        if (index == 0) {
            for (String field : fields) {
                try {
                    String a = data.getString(field);
                    if (a != null) {
                        b.putString(field, a);
                        Log.i(TAG, "Plain Field found | " + a + " | for" + field + " for # " + Integer.toString(index));
                        all_null = false;
                    } else {
                        Log.i(TAG, "Plain Field empty | " + field + " for # " + Integer.toString(index));
                    }
                } catch (Exception e) {
                    Log.i(TAG, "Error in bundle");
                    return null;
                }
            }
        }
        if (all_null) {
            for (String field : fields) {
                try {
                    String a = data.getString(field + "_" + Integer.toString(index));
                    if (a != null) {
                        b.putString(field, a);
                    } else {
                        Log.i(TAG, "Numbered Field empty | " + field + " for # " + Integer.toString(index));
                    }
                    b.putString(field, a);
                } catch (Exception e) {
                    Log.i(TAG, "Error in bundle");
                    return null;
                }
            }
        }

        Intent out = new Intent();
        out.setAction("com.openandid.internal.SCAN");
        out.putExtras(b);
        return out;
    }

    public static int get_total_scans_from_bundle(Bundle data) {
        Log.i(TAG, "Looking for many scan bundle");
        int out = 1;
        for (int x = 0; x < 10; x++) {
            String left = data.getString("left_finger_assignment_" + Integer.toString(x));
            if (left == null) {
                out = x;
                Log.i(TAG, "broke on " + Integer.toString(out));
                break;
            }
        }
        Log.i(TAG, String.format("Found %s scans in bundle", out));
        if (out < 1) {
            return 1;
        }
        return out;
    }
}


