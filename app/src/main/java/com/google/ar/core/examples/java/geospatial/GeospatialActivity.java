package com.google.ar.core.examples.java.geospatial; // Ensure this matches your package

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
// Removed: import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
// Removed: import android.view.GestureDetector; // Not using tap-to-place
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment; // Ensure this import is present

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener; // Keep this if needed by location check
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Earth;
import com.google.ar.core.GeospatialPose;
import com.google.ar.core.PlaybackStatus; // Import if checking playback status
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.VpsAvailability;
import com.google.ar.core.VpsAvailabilityFuture;
import com.google.ar.core.exceptions.*;

// --- Helpers ---
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.LocationPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
// Removed: SampleRender imports

// --- Sceneform Imports ---
import com.google.ar.sceneform.AnchorNode;
// import com.google.ar.sceneform.FrameTime; // Keep if implementing Scene.OnUpdateListener
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene; // Keep if implementing Scene.OnUpdateListener
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;

// --- JSON & Networking Imports ---
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList; // Keep if needed by placedAnchorNodes
import java.util.List;
import java.util.Locale; // Needed for String.format
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;


public class GeospatialActivity extends AppCompatActivity
        implements // Removed: SampleRender.Renderer,
        // Scene.OnUpdateListener, // Keep if implementing
        VpsAvailabilityNoticeDialogFragment.NoticeDialogListener,
        PrivacyNoticeDialogFragment.NoticeDialogListener {

    private static final String TAG = GeospatialActivity.class.getSimpleName();

    // --- Constants ---
    private static final String ALLOW_GEOSPATIAL_ACCESS_KEY = "ALLOW_GEOSPATIAL_ACCESS";
    private static final double LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10;
    private static final double LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES = 15;
    private static final double LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10;
    private static final double LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES = 10;
    private static final int LOCALIZING_TIMEOUT_SECONDS = 180;
    private static final int MAXIMUM_ANCHORS = 10; // Max AR text boxes
    // !!! IMPORTANT: UPDATE THIS URL !!!
    private static final String BACKEND_URL = "https://342b-2600-4040-2037-d00-4c37-86eb-afff-9c90.ngrok-free.app/get_description";

    // --- State Enums ---
    enum State { UNINITIALIZED, UNSUPPORTED, EARTH_STATE_ERROR, PRETRACKING, LOCALIZING, LOCALIZING_FAILED, LOCALIZED }
    private State state = State.UNINITIALIZED;

    // --- Core Components & Helpers ---
    private Session session;
    private ArFragment arFragment;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
    private SharedPreferences sharedPreferences;
    private FusedLocationProviderClient fusedLocationClient;

    // --- UI Elements ---
    private TextView geospatialPoseTextView;
    private TextView statusTextView;
    private TextView freezeStatusText; // Member variable
    private Button clearAnchorsButton;
    private Switch streetscapeGeometrySwitch;

    // --- State Variables ---
    private boolean installRequested;
    private long localizingStartTimestamp;
    private String lastStatusText;
    private boolean isRenderStreetscapeGeometry = false;

    // --- Sceneform Node Tracking ---
    private final List<AnchorNode> placedAnchorNodes = new CopyOnWriteArrayList<>();


    // --- Lifecycle Methods ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        setContentView(R.layout.activity_main); // Load your main layout

        Log.d(TAG, "onCreate started.");

        // Initialize Core Components
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        displayRotationHelper = new DisplayRotationHelper(this);
        installRequested = false;

        // Find UI View Components
        geospatialPoseTextView = findViewById(R.id.geospatial_pose_view);
        statusTextView = findViewById(R.id.status_text_view);
        clearAnchorsButton = findViewById(R.id.clear_anchors_button);
        streetscapeGeometrySwitch = findViewById(R.id.streetscape_geometry_switch);
        View freezeSendButton = findViewById(R.id.freeze_send_button);
        freezeStatusText = findViewById(R.id.freeze_status_text); // Initialize member variable

        // Get ArFragment Reference
        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.arFragment);
        if (arFragment == null) {
            Log.e(TAG, "AR Fragment with ID 'arFragment' not found!");
            Toast.makeText(this, "AR Fragment not found!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // Optional: arFragment.getArSceneView().getScene().addOnUpdateListener(this);

        // Setup Button Click Listeners
        setupButtonClickListeners();

        Log.d(TAG, "onCreate setup finished.");
    }

    private void setupButtonClickListeners() {
        View freezeSendButton = findViewById(R.id.freeze_send_button); // Ensure correct ID
        freezeSendButton.setOnClickListener(v -> { // Java Lambda syntax
            Log.d(TAG, "Freeze button clicked.");
            if (session == null) {
                Log.w(TAG,"Session null");
                freezeStatusText.setText("Session not ready."); // Use member variable
                return;
            }
            Earth earth = session.getEarth();

            // --- FIX: Use standard Java null check ---
            if (earth != null && earth.getTrackingState() == TrackingState.TRACKING && state == State.LOCALIZED) {
                // -----------------------------------------
                GeospatialPose pose = earth.getCameraGeospatialPose();
                // --- FIX: Standard Java null check ---
                if (pose != null && !(pose.getLatitude() == 0.0 && pose.getLongitude() == 0.0)) {
                    // -----------------------------------------
                    if (placedAnchorNodes.size() >= MAXIMUM_ANCHORS) {
                        Log.w(TAG, "Maximum info points reached.");
                        Toast.makeText(this, R.string.warning_max_anchors, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    freezeStatusText.setText("Sending Request..."); // Use member variable
                    sendFrozenPoseToBackend(pose.getLatitude(), pose.getLongitude(), pose.getAltitude(), pose.getEastUpSouthQuaternion(), freezeStatusText); // Pass member variable
                } else {
                    Log.w(TAG, "Pose is null or invalid (0,0).");
                    // --- FIX: Use member variable ---
                    freezeStatusText.setText("Invalid pose data.");
                }
            } else {
                Log.w(TAG, "Not tracking or not localized. State: " + state);
                // --- FIX: Use member variable ---
                freezeStatusText.setText("Not tracking/localized.");
                Toast.makeText(this, "Wait for tracking & localization.", Toast.LENGTH_SHORT).show();
            }
        });

        clearAnchorsButton.setOnClickListener(view -> handleClearAnchorsButton());

        streetscapeGeometrySwitch.setChecked(false);
        streetscapeGeometrySwitch.setOnCheckedChangeListener(this::onRenderStreetscapeGeometryChanged);
    }


    // Inside the GeospatialActivity class

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        // Check privacy notice acceptance before creating/resuming session
        if (sharedPreferences.getBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, false)) {
            createSession(); // Attempt to create session if null, or potentially resume if already created
        } else {
            showPrivacyNoticeDialog(); // Show notice if not accepted
        }

        // Resume display helper
        if (displayRotationHelper != null) { // Add null check for safety
            displayRotationHelper.onResume();
        }

        // Resume session if it exists and was paused
        // Check using toString() as a workaround for the build error
        if (session != null) {
            PlaybackStatus currentStatus = session.getPlaybackStatus();
            // Compare the string representation of the enum constant
            if (currentStatus != null && currentStatus.toString().equals("PAUSED")) {
                try {
                    session.resume();
                    Log.d(TAG, "Session resumed in onResume (checked via toString).");
                } catch (CameraNotAvailableException e) {
                    Log.e(TAG, "Camera not available on resume", e);
                    // Show error message to the user
                    if (messageSnackbarHelper != null) { // Add null check
                        messageSnackbarHelper.showError(this, "Camera not available.");
                    }
                    // If resume fails critically, maybe null out the session
                    session = null;
                } catch (Exception e) {
                    // Catch other potential exceptions during resume
                    Log.e(TAG, "Error resuming session", e);
                    if (messageSnackbarHelper != null) {
                        messageSnackbarHelper.showError(this, "Error resuming session: " + e.getMessage());
                    }
                    session = null; // Reset session on other errors too?
                }
            }
        }

        // ArFragment handles its own view resume automatically when the Activity resumes.
        // No explicit ArFragment.onResume() call needed here.
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        displayRotationHelper.onPause();
        if (session != null) { // Standard null check
            session.pause();
            Log.d(TAG,"Session paused.");
        }
        // ArFragment handles its own view pause
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        handleClearAnchorsButton(); // Clear Sceneform nodes and anchors
        if (session != null) { // Standard null check
            session.close();
            session = null;
            Log.d(TAG, "Session closed.");
        }
    }


    // --- Permission Handling ---
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_LONG).show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) CameraPermissionHelper.launchPermissionSettings(this);
            finish(); return;
        }
        if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions) && !LocationPermissionHelper.hasFineLocationPermission(this)) {
            Toast.makeText(this, R.string.error_location_permission, Toast.LENGTH_LONG).show();
            if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) LocationPermissionHelper.launchPermissionSettings(this);
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    // --- Session Creation & Configuration ---
    private void showPrivacyNoticeDialog() {
        if (getSupportFragmentManager().findFragmentByTag(PrivacyNoticeDialogFragment.class.getName()) == null) {
            PrivacyNoticeDialogFragment.createDialog().show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
        }
    }

    private void createSession() {
        if (session != null) { Log.d(TAG,"Session exists."); return; }
        Exception exception = null; String message = null;
        try {
            switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                case INSTALL_REQUESTED: installRequested = true; return;
                case INSTALLED: break;
            }
            if (!CameraPermissionHelper.hasCameraPermission(this)) { CameraPermissionHelper.requestCameraPermission(this); return; }
            if (!LocationPermissionHelper.hasFineLocationPermission(this)) { LocationPermissionHelper.requestFineLocationPermission(this); return; }

            Log.d(TAG, "Creating Session...");
            session = new Session(this);
            Log.d(TAG,"Session created.");

            // --- Configure session FIRST ---
            configureSession();
            if (state == State.UNSUPPORTED) return; // Stop if configure failed

            // Check VPS availability (can happen after configure)
            getLastLocation();

            // Set session on ArFragment IF it was created manually AFTER fragment
            // Generally ArFragment handles session creation/resume itself if configured in XML correctly
            // ArSceneView sceneView = arFragment.getArSceneView();
            // if (sceneView.getSession() == null) {
            //     sceneView.setupSession(session); // Only needed in specific scenarios
            // }

            // Resume might happen automatically via ArFragment or need manual call
            // if(session.getPlaybackStatus() != PlaybackStatus.OK) { session.resume(); }


        } catch (Exception e) {
            message = getSessionErrorMessage(e);
            exception = e;
        }
        if (message != null) { messageSnackbarHelper.showError(this, message); Log.e(TAG,"Session init error",exception); if (session != null) { session.close(); session = null;} }
    }

    private String getSessionErrorMessage(Exception e) {
        if (e instanceof UnavailableArcoreNotInstalledException || e instanceof UnavailableUserDeclinedInstallationException) return "Please install ARCore";
        if (e instanceof UnavailableApkTooOldException) return "Please update ARCore";
        if (e instanceof UnavailableSdkTooOldException) return "Please update this app";
        if (e instanceof UnavailableDeviceNotCompatibleException) return "Device not AR compatible";
        if (e instanceof CameraNotAvailableException) return "Camera unavailable";
        if (e instanceof GooglePlayServicesLocationLibraryNotLinkedException) return "Location library error";
        if (e instanceof FineLocationPermissionNotGrantedException) return "Location permission needed";
        if (e instanceof UnsupportedConfigurationException) return "Geospatial Mode unsupported";
        if (e instanceof SecurityException) return "Permission denied";
        return "Session setup failed: " + e.getClass().getSimpleName();
    }


    private void getLastLocation() {
        try {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(this, location -> checkVpsAvailability(location != null ? location.getLatitude() : 0.0, location != null ? location.getLongitude() : 0.0))
                    .addOnFailureListener(this, e -> { Log.e(TAG,"Location error", e); checkVpsAvailability(0.0, 0.0); });
        } catch (SecurityException e) { Log.e(TAG,"Location permission error", e); }
    }

    private void checkVpsAvailability(double latitude, double longitude) {
        if (session != null) { // Java null check
            session.checkVpsAvailabilityAsync(latitude, longitude, availability -> {
                Log.d(TAG, "VPS for (" + latitude + ", " + longitude + "): " + availability);
                if (availability != VpsAvailability.AVAILABLE) {
                    runOnUiThread(this::showVpsNotAvailabilityNoticeDialog);
                }
            });
        } else { Log.w(TAG, "Session null, skipping VPS check."); }
    }

    private void showVpsNotAvailabilityNoticeDialog() {
        if (getSupportFragmentManager().findFragmentByTag(VpsAvailabilityNoticeDialogFragment.class.getName()) == null) {
            VpsAvailabilityNoticeDialogFragment.createDialog().show(getSupportFragmentManager(), VpsAvailabilityNoticeDialogFragment.class.getName());
        }
    }

    private void configureSession() {
        Session currentSession = session; // Assign to local variable for check
        if (currentSession == null) { Log.e(TAG, "Cannot configure null session."); return; }
        Log.d(TAG, "Configuring session...");
        try {
            Config config = currentSession.getConfig(); // Get current config or default if null
            if (config == null) config = new Config(currentSession);

            if (!currentSession.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                Log.e(TAG, "Geospatial NOT SUPPORTED."); state = State.UNSUPPORTED; return;
            }
            config.setGeospatialMode(Config.GeospatialMode.ENABLED);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE); // Recommended for Sceneform
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL); // Often needed for Sceneform taps
            config.setStreetscapeGeometryMode(isRenderStreetscapeGeometry ? Config.StreetscapeGeometryMode.ENABLED : Config.StreetscapeGeometryMode.DISABLED);

            currentSession.configure(config);
            Log.i(TAG, "Session configured for Geospatial.");
            if (state != State.UNSUPPORTED && state != State.EARTH_STATE_ERROR) { state = State.PRETRACKING; localizingStartTimestamp = System.currentTimeMillis(); }
        } catch (Exception e) { Log.e(TAG,"Config error", e); state = State.EARTH_STATE_ERROR; }
    }

    // --- NO onSurfaceCreated, onSurfaceChanged, onDrawFrame needed ---


    // --- Geospatial State Machine ---
    private void updateGeospatialState(Earth earth) {
        if (earth.getEarthState() != Earth.EarthState.ENABLED) { if (state != State.EARTH_STATE_ERROR) Log.e(TAG,"Earth state error"); state = State.EARTH_STATE_ERROR; return; }
        if (earth.getTrackingState() != TrackingState.TRACKING) { if (state != State.EARTH_STATE_ERROR && state != State.UNSUPPORTED && state != State.LOCALIZING_FAILED) state = State.PRETRACKING; return; }
        switch (state) {
            case PRETRACKING: Log.i(TAG,"PRE->LOC"); state = State.LOCALIZING; break;
            case LOCALIZING: updateLocalizingState(earth); break;
            case LOCALIZED: updateLocalizedState(earth); break;
            default: break; // Stay in terminal states
        }
    }
    private void updateLocalizingState(Earth earth) {
        GeospatialPose pose = earth.getCameraGeospatialPose();
        if (pose != null && pose.getHorizontalAccuracy() <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS && pose.getOrientationYawAccuracy() <= LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES) {
            if(state == State.LOCALIZING) Log.i(TAG,"LOC->LOCALIZED"); state = State.LOCALIZED; runOnUiThread(() -> findViewById(R.id.freeze_send_button).setEnabled(true));
        } else if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - localizingStartTimestamp) > LOCALIZING_TIMEOUT_SECONDS) {
            if(state == State.LOCALIZING) Log.w(TAG,"LOC->FAILED (Timeout)"); state = State.LOCALIZING_FAILED; runOnUiThread(() -> findViewById(R.id.freeze_send_button).setEnabled(false));
        }
    }
    private void updateLocalizedState(Earth earth) {
        GeospatialPose pose = earth.getCameraGeospatialPose();
        if (pose != null && (pose.getHorizontalAccuracy() > LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS + LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS ||
                pose.getOrientationYawAccuracy() > LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES + LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES)) {
            if(state == State.LOCALIZED) Log.w(TAG,"LOCALIZED->LOC (Degraded)"); state = State.LOCALIZING; localizingStartTimestamp = System.currentTimeMillis(); runOnUiThread(() -> findViewById(R.id.freeze_send_button).setEnabled(false));
        }
    }

    // --- UI Text Updates ---
    private void updateStatusText() {
        String message = null;
        switch (state) {
            case UNINITIALIZED: message = getString(R.string.status_initial); break;
            case UNSUPPORTED: message = getString(R.string.status_unsupported); break;
            case PRETRACKING: message = getString(R.string.status_pretracking); break;
            case EARTH_STATE_ERROR: message = getString(R.string.status_earth_state_error); break;
            case LOCALIZING: message = getString(R.string.status_localize_hint); break;
            case LOCALIZING_FAILED: message = getString(R.string.status_localize_timeout); break;
            case LOCALIZED:
                if (lastStatusText != null && lastStatusText.equals(getString(R.string.status_localize_hint))) {
                    message = getString(R.string.status_localize_complete);
                } else {
                    int visibleCount = 0;
                    for(AnchorNode node : placedAnchorNodes) {
                        if(node.getAnchor() != null && node.getAnchor().getTrackingState() == TrackingState.TRACKING) {
                            visibleCount++;
                        }
                    }
                    if (visibleCount > 0) message = "Displaying " + visibleCount + " info point(s).";
                    else if (!placedAnchorNodes.isEmpty()) message = "Info points placed, move to view.";
                    else message = "Localized. Ready.";
                }
                break;
        }

    // Check if the message changed
    if (message != null && !message.equals(lastStatusText)) {
        lastStatusText = message;

        // --- FIX: Create a final variable for the lambda ---
        final String finalMessage = message;
        // --------------------------------------------------

        runOnUiThread(() -> {
            // --- FIX: Use the final variable inside the lambda ---
            statusTextView.setText(finalMessage);
            statusTextView.setVisibility(View.VISIBLE);
        });
    } else if (message == null && lastStatusText != null) {
        // This part is okay as it doesn't capture a non-final variable from outside
        lastStatusText = null;
        runOnUiThread(() -> {
            statusTextView.setVisibility(View.INVISIBLE);
        });
    }

    // Update pose text (This part seems fine as it calls another method)
    if (session != null) {
        Earth earth = session.getEarth();
        if (earth != null) {
             updateGeospatialPoseText(earth.getCameraGeospatialPose());
        } else { updateGeospatialPoseText(null); }
    } else { updateGeospatialPoseText(null); }
    }

    private void updateGeospatialPoseText(GeospatialPose geospatialPose) {
        String poseText;
        if (geospatialPose != null) {
            float[] quat = geospatialPose.getEastUpSouthQuaternion();
            poseText = String.format(Locale.US, getString(R.string.geospatial_pose),
                    geospatialPose.getLatitude(), geospatialPose.getLongitude(),
                    geospatialPose.getHorizontalAccuracy(), geospatialPose.getAltitude(),
                    geospatialPose.getVerticalAccuracy(), quat[0], quat[1], quat[2], quat[3],
                    geospatialPose.getOrientationYawAccuracy());
        } else {
            poseText = getString(R.string.geospatial_pose_not_tracking);
        }
        runOnUiThread(() -> {
            if (geospatialPoseTextView != null) { // Check if TextView is initialized
                geospatialPoseTextView.setText(poseText);
            }
        });
    }


    // --- Backend Communication ---
    private void sendFrozenPoseToBackend(double lat, double lng, double alt, float[] quaternion, TextView statusTextViewRef) { // Renamed param for clarity
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BACKEND_URL); // Use constant
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);

                JSONObject json = new JSONObject();
                json.put("latitude", lat); json.put("longitude", lng); json.put("altitude", alt);
                JSONArray quatJsonArray = new JSONArray();
                for (float val : quaternion) { quatJsonArray.put((double) val); }
                json.put("quaternion", quatJsonArray);
                String jsonInputString = json.toString();
                Log.d("FreezeSend", "Sending JSON: " + jsonInputString);

                try (OutputStream os = conn.getOutputStream()) { os.write(jsonInputString.getBytes(StandardCharsets.UTF_8)); }

                int responseCode = conn.getResponseCode();
                Log.d("FreezeSend", "Response Code: " + responseCode);
                InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (is == null) { throw new IOException("No stream from connection"); }
                String responseBody;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder responseBuilder = new StringBuilder();
                    String line; while ((line = reader.readLine()) != null) { responseBuilder.append(line); }
                    responseBody = responseBuilder.toString();
                }
                Log.d("FreezeSend", "Response Body: " + responseBody);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    JSONObject responseJson = new JSONObject(responseBody);
                    String summary;
                    if (responseJson.has("raw_response")) {
                        Log.w("FreezeReceive", "Backend AI parse failure.");
                        summary = responseJson.optString("warning", "Backend Note");
                    } else {
                        summary = responseJson.optString("summary", "Summary not found.");
                    }
                    final String finalSummary = summary;
                    statusTextViewRef.post(() -> { // Use the passed TextView reference
                        statusTextViewRef.setText("Summary: " + finalSummary.substring(0, Math.min(finalSummary.length(), 50)) + "...");
                        if (!finalSummary.startsWith("Error") && !finalSummary.startsWith("Backend Note")) {
                            renderARSummaryBox(lat, lng, alt, quaternion, finalSummary);
                            Toast.makeText(GeospatialActivity.this, R.string.info_point_placed, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(GeospatialActivity.this, finalSummary, Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    throw new IOException("Server error: " + responseCode + "\n" + responseBody);
                }
            } catch (Exception e) {
                Log.e("FreezeError", "Request/Response Error", e);
                statusTextViewRef.post(() -> statusTextViewRef.setText("Error: " + e.getMessage()));
            } finally { if (conn != null) conn.disconnect(); }
        }).start();
    }

    // --- AR Text Box Rendering using Sceneform ---
    private void renderARSummaryBox(double lat, double lng, double alt, float[] quaternion, String summary) {
        Session currentSession = session; // Capture for thread safety
        if (currentSession == null) { Log.e(TAG, "Session null"); return; }
        Earth earth = currentSession.getEarth();
        if (earth == null || earth.getTrackingState() != TrackingState.TRACKING) { Log.e(TAG, "Earth not tracking"); return; }
        if (arFragment == null) { Log.e(TAG, "ArFragment null"); return; }

        Anchor anchor;
        try { anchor = earth.createAnchor(lat, lng, alt, quaternion[0], quaternion[1], quaternion[2], quaternion[3]); }
        catch(Exception e) { Log.e(TAG, "Anchor creation failed", e); Toast.makeText(this, "Place error:"+e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        Log.d(TAG, "Anchor created.");

        CompletableFuture<ViewRenderable> renderableFuture = ViewRenderable.builder().setView(this, R.layout.ar_text_box).build();

        renderableFuture.thenAccept(viewRenderable -> {
            TextView textView = viewRenderable.getView().findViewById(R.id.ar_text); // Use correct ID
            if (textView != null) textView.setText(summary); else Log.e(TAG,"ar_text TextView not found!");

            AnchorNode anchorNode = new AnchorNode(anchor);
            // Ensure scene is available before adding
            if (arFragment.getArSceneView() != null && arFragment.getArSceneView().getScene() != null) {
                anchorNode.setParent(arFragment.getArSceneView().getScene());
                TransformableNode textNode = new TransformableNode(arFragment.getTransformationSystem());
                textNode.setParent(anchorNode);
                textNode.setRenderable(viewRenderable);
                textNode.setLocalPosition(new Vector3(0f, 0.15f, 0f)); // Slightly higher position
                placedAnchorNodes.add(anchorNode); // Track the node
                textNode.select(); // Make it initially selected
                Log.d(TAG, "ViewRenderable placed.");
            } else {
                Log.e(TAG, "Scene or SceneView was null, couldn't place node.");
                anchor.detach(); // Clean up anchor if scene wasn't ready
            }

        }).exceptionally(throwable -> {
            Log.e(TAG, "ViewRenderable loading failed", throwable);
            Toast.makeText(this, "Error loading AR view", Toast.LENGTH_LONG).show();
            anchor.detach(); // Clean up anchor
            return null;
        });
    }


    // --- Button/Switch Handlers ---
    private void handleClearAnchorsButton() {
        Log.d(TAG, "Clearing AR text boxes...");
        // Use iterator to avoid ConcurrentModificationException if modifying list while iterating
        // CopyOnWriteArrayList is generally safe, but explicit iteration is clearer
        for (AnchorNode node : placedAnchorNodes) {
            if (node != null) {
                if (arFragment != null && arFragment.getArSceneView() != null && arFragment.getArSceneView().getScene() != null) {
                    arFragment.getArSceneView().getScene().removeChild(node);
                }
                Anchor anchor = node.getAnchor();
                if (anchor != null) anchor.detach();
                node.setRenderable(null); // Help GC
                node.setParent(null);
            }
        }
        placedAnchorNodes.clear();
        runOnUiThread(() -> {
            statusTextView.setText(R.string.info_cleared_all);
            clearAnchorsButton.setVisibility(View.INVISIBLE);
            if (freezeStatusText != null) freezeStatusText.setText("");
        });
    }

    private void onRenderStreetscapeGeometryChanged(CompoundButton button, boolean isChecked) {
        Log.d(TAG, "Streetscape Switch: " + isChecked);
        isRenderStreetscapeGeometry = isChecked;
        Toast.makeText(this, "Streetscape requires session restart.", Toast.LENGTH_LONG).show();
        /* // Reconfigure example (causes pause)
        Session currentSession = session;
        if (currentSession != null && (state == State.LOCALIZING || state == State.LOCALIZED)) {
            new Thread(() -> { // Configure off main thread
                 Log.d(TAG, "Reconfiguring session...");
                 currentSession.pause();
                 configureSession();
                 try { currentSession.resume(); } catch (CameraNotAvailableException e) { Log.e(TAG,"Cam error", e);}
            }).start();
        } */
    }


    // --- Dialog Callbacks ---
    @Override
    public void onDialogPositiveClick(DialogFragment dialog) {
        Log.d(TAG, "Privacy notice accepted.");
        if (!sharedPreferences.edit().putBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, true).commit()) {
            Log.e(TAG,"Could not save privacy preference!");
        }
        createSession();
    }

    @Override
    public void onDialogContinueClick(DialogFragment dialog) {
        Log.d(TAG, "User dismissed VPS dialog.");
        if (dialog != null) dialog.dismiss(); // Standard null check
    }

    // Removed: All SampleRender related methods (onSurfaceCreated, onSurfaceChanged, onDrawFrame)
    // Removed: All original anchor placement logic (handleTap, settingsMenuClick, create*Anchor, etc.)
    // Removed: All Streetscape Geometry rendering code using SampleRender
}