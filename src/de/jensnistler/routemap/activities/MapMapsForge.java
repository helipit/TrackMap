package de.jensnistler.routemap.activities;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.List;

import org.mapsforge.android.maps.MapActivity;
import org.mapsforge.android.maps.MapController;
import org.mapsforge.android.maps.MapView;
import org.mapsforge.android.maps.overlay.ArrayWayOverlay;
import org.mapsforge.android.maps.overlay.Overlay;
import org.mapsforge.android.maps.overlay.OverlayWay;
import org.mapsforge.android.maps.rendertheme.InternalRenderTheme;
import org.mapsforge.core.GeoPoint;
import org.mapsforge.map.reader.header.FileOpenResult;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import de.jensnistler.routemap.R;
import de.jensnistler.routemap.helper.GPXParser;
import de.jensnistler.routemap.helper.RotateViewGroup;
import de.jensnistler.routemap.helper.RouteMapViewGroup;

public class MapMapsForge extends MapActivity implements LocationListener {
    private static final int UPDATE_LOCATION = 1;

    private static final String BRIGHTNESS_NOCHANGE = "nochange";
    private static final String BRIGHTNESS_AUTOMATIC = "automatic";
    private static final String BRIGHTNESS_MAXIMUM = "maximum";
    private static final String BRIGHTNESS_MEDIUM = "medium";
    private static final String BRIGHTNESS_LOW = "low";

    private static final String DISTANCE_MILES = "mi";
    private static final String DISTANCE_KILOMETERS = "km";
    
    private static final int ID_ZOOM_IN = 1;
    private static final int ID_ZOOM_OUT = 2;

    private boolean mPreferenceStandby;
    private boolean mPreferenceRotateMap;
    private String mPreferenceBrightness;
    private String mPreferenceRouteFile;
    private String mPreferenceMapFile;
    private String mPreferenceDistanceUnit;
    private int mUserBrightnessMode;

    private MapView mMapView;
    private RotateViewGroup mMapViewGroup; 
    private RouteMapViewGroup mViewGroup;
    private RelativeLayout mImageViewGroup;
    private LocationManager mLocationManager;
    private double mLatitude;
    private double mLongitude;
    private Thread mThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPreferenceMapFile = prefs.getString("mapFile", null);
        File cacheDir = getExternalCacheDir();
        File cacheFile = new File(cacheDir, mPreferenceMapFile.replace("/", "_") + ".map");

        // add map
        mMapView = new MapView(this);
        FileOpenResult fileOpenResult = mMapView.setMapFile(cacheFile);
        if (!fileOpenResult.isSuccess()) {
            Toast.makeText(this, "Failed to open map file: " + fileOpenResult.getErrorMessage(), Toast.LENGTH_LONG).show();
        }
        mMapView.getController().setZoom(18);
        mMapView.setBuiltInZoomControls(true);
        mMapView.setClickable(false);
        mMapView.setRenderTheme(InternalRenderTheme.OSMARENDER);

        // rotate view
        mMapViewGroup = new RotateViewGroup(this);
        mMapViewGroup.addView(mMapView);

        mViewGroup = new RouteMapViewGroup(this);
        mViewGroup.addView(mMapViewGroup);

        // position marker
        LayoutParams positionViewLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        ImageView positionImageView = new ImageView(this);
        positionImageView.setScaleType(ImageView.ScaleType.CENTER);
        Drawable positionDrawable = this.getResources().getDrawable(R.drawable.mymarker);
        positionImageView.setImageDrawable(positionDrawable);

        // zoom out
        ImageButton zoomOut = new ImageButton(this);
        zoomOut.setImageResource(R.drawable.minus);
        zoomOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mMapView.getController().zoomOut();
            }
        });
        zoomOut.setId(ID_ZOOM_IN);
        RelativeLayout.LayoutParams zoomOutViewLayout = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        zoomOutViewLayout.rightMargin = 20;
        zoomOutViewLayout.bottomMargin = 20;
        zoomOutViewLayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        zoomOutViewLayout.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);

        // zoom in
        ImageButton zoomIn = new ImageButton(this);
        zoomIn.setImageResource(R.drawable.plus);
        zoomIn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mMapView.getController().zoomIn();
            }
        });
        zoomIn.setId(ID_ZOOM_OUT);
        RelativeLayout.LayoutParams zoomInViewLayout = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        zoomInViewLayout.rightMargin = 20;
        zoomInViewLayout.bottomMargin = 20;
        zoomInViewLayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        zoomInViewLayout.addRule(RelativeLayout.LEFT_OF, zoomOut.getId());

        // add image view to main view
        mImageViewGroup = new RelativeLayout(this);
        mImageViewGroup.addView(positionImageView, positionViewLayout);
        mImageViewGroup.addView(zoomOut, zoomOutViewLayout);
        mImageViewGroup.addView(zoomIn, zoomInViewLayout);
        mViewGroup.addView(mImageViewGroup);

        // set content view
        setContentView(mViewGroup);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // load preference data
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        mPreferenceStandby = prefs.getBoolean("standby", false);
        mPreferenceRotateMap = prefs.getBoolean("rotateMap", false);
        mPreferenceBrightness = prefs.getString("brightness", BRIGHTNESS_NOCHANGE).trim();
        mPreferenceRouteFile = prefs.getString("routeFile", null);
        mPreferenceDistanceUnit = prefs.getString("speed", DISTANCE_MILES);

        // save user brightness
        try {
            mUserBrightnessMode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
        }
        catch (Settings.SettingNotFoundException e) {
            // setting not found, default can not be restored
            mUserBrightnessMode = -1;
        }

        // disable standby
        if (true == mPreferenceStandby) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // disable map rotation
        if (false == mPreferenceRotateMap) {
            mMapViewGroup.setHeading(0.0f);
        }

        loadRouteFile();
        handleBrightnessPreferences();

        // start gps thread
        initializeLocationAndStartGpsThread();
    }

    /**
     * restore settings on pause
     */
    @Override
    protected void onPause() {
        // reset standby mode
        if (true == mPreferenceStandby) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // reset brightness to user default
        restoreBrightness();

        // stop gps update handler
        mThread.interrupt();

        super.onPause();
    }

    private void loadRouteFile() {
        // load route file
        if (null != mPreferenceRouteFile) {
            File routeFile = new File(mPreferenceRouteFile.trim());
            if (!routeFile.exists()) {
                Toast.makeText(this, "Failed to load route file: " + mPreferenceRouteFile, Toast.LENGTH_LONG).show();
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("routeFile", null);
                editor.commit();
            }
            else {
                renderRouteFile(routeFile);
            }
        }
    }

    /**
     * handle preferences and change system values
     */
    private void handleBrightnessPreferences() {
        // restore user settings
        if (mPreferenceBrightness.equals(BRIGHTNESS_NOCHANGE)) {
            restoreBrightness();
        }
        // automatic mode
        else if (mPreferenceBrightness.equals(BRIGHTNESS_AUTOMATIC)) {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        }
        // manual mode
        else {
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            if (mPreferenceBrightness.equals(BRIGHTNESS_MAXIMUM)) {
                layoutParams.screenBrightness = 1.0f;
            }
            else if (mPreferenceBrightness.equals(BRIGHTNESS_MEDIUM)) {
                layoutParams.screenBrightness = 0.6f;
            }
            else if (mPreferenceBrightness.equals(BRIGHTNESS_LOW)) {
                layoutParams.screenBrightness = 0.2f;
            }
            getWindow().setAttributes(layoutParams);
        }
    }

    private void restoreBrightness() {
        if (Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL != mUserBrightnessMode && Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC != mUserBrightnessMode) {
            Toast toast = Toast.makeText(this, "Failed to restore brightness settings", Toast.LENGTH_LONG);
            toast.show();
            return;
        }

        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, mUserBrightnessMode);
        if (mUserBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = -1.0f;
            getWindow().setAttributes(layoutParams);
        }
    }

    /**
     * set location to last known position and start
     * thread to handle position changes
     */
    private void initializeLocationAndStartGpsThread() {
        this.setCurrentGpsLocation(null);
        mThread = new Thread(new LocationThreadRunner());
        mThread.start();
        this.updateMyLocation();
    }

    /**
     * send a message to the update handler with either the current location
     * or the last known location.
     * 
     * @param location either null or the current location
     */
    private void setCurrentGpsLocation(Location location) {
        if (location == null) {
            mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this);
            location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

            setTitle("Waiting for GPS signal...");
        }

        try {
            mLongitude = location.getLongitude();
            mLatitude = location.getLatitude();
            Message msg = Message.obtain();
            msg.what = UPDATE_LOCATION;
            de.jensnistler.routemap.activities.MapMapsForge.this.updateHandler.sendMessage(msg);

            // rotate map
            if (true == mPreferenceRotateMap && location.hasBearing()) {
                mMapViewGroup.setHeading(location.getBearing());
            }

            // show speed in activity title
            if (true == location.hasSpeed()) {
                // meters per second
                float speed = location.getSpeed();
                // meters per hour
                speed = speed * 60 * 60;
                // kilometers per hour
                speed = speed / 1000;

                // reduce to miles
                if (mPreferenceDistanceUnit.equals(DISTANCE_MILES)) {
                    speed = speed / 1.609344f;
                }

                String speedText = new DecimalFormat("###.##").format(speed);
                if (mPreferenceDistanceUnit.equals(DISTANCE_MILES)) {
                    speedText = speedText + " mph";
                }
                else if (mPreferenceDistanceUnit.equals(DISTANCE_KILOMETERS)) {
                    speedText = speedText + " km/h";
                }

                setTitle(speedText);
            }
        } catch (NullPointerException e) {
            // don't update location
        }
    }

    /**
     * handles gps updates
     */
    Handler updateHandler = new Handler() {
        /** Gets called on every message that is received */
        // @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UPDATE_LOCATION:
                    de.jensnistler.routemap.activities.MapMapsForge.this.updateMyLocation();
                    break;
            }
            super.handleMessage(msg);
        }
    };

    /**
     * handles location updates in the background.
     */
    class LocationThreadRunner implements Runnable {
        // @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                Message m = Message.obtain();
                m.what = 0;
                de.jensnistler.routemap.activities.MapMapsForge.this.updateHandler.sendMessage(m);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * move map to my current location
     */
    private void updateMyLocation() {
        GeoPoint point = new GeoPoint((int) (mLatitude * 1E6), (int) (mLongitude * 1E6));

        MapController mapController = mMapView.getController();
        mapController.setCenter(point);
    }

    /**
     * invoked by the location service when phone's location changes
     */
    public void onLocationChanged(Location newLocation) {
        setCurrentGpsLocation(newLocation);
    }

    /**
     * resets the gps location whenever the provider is enabled
     */
    public void onProviderEnabled(String provider) {
        setCurrentGpsLocation(null);
    }

    /**
     * resets the gps location whenever the provider is disabled
     */
    public void onProviderDisabled(String provider) {
        setCurrentGpsLocation(null);
    }

    /**
     * resets the gps location whenever the provider status changes
     * we don't care about the details
     */
    public void onStatusChanged(String provider, int status, Bundle extras) {
        setCurrentGpsLocation(null);
    }

    /**
     * render all tracks contained in a gpx file
     *
     * @param routeFile
     */
    private void renderRouteFile(File routeFile) {
        List<Overlay> overlays = mMapView.getOverlays();
        if (!overlays.isEmpty()) {
            overlays.removeAll(overlays);
        }

        // create the default paint objects for overlay ways
        Paint wayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wayPaint.setStyle(Paint.Style.STROKE);
        wayPaint.setColor(Color.BLUE);
        wayPaint.setAlpha(128);
        wayPaint.setStrokeWidth(8);
        wayPaint.setStrokeJoin(Paint.Join.ROUND);

        GPXParser parser = new GPXParser(routeFile);
        List<List<Location>> tracks = parser.getTracks();

        ArrayWayOverlay wayOverlay = new ArrayWayOverlay(wayPaint, wayPaint);

        Iterator<List<Location>> trackIterator = tracks.iterator();
        while (trackIterator.hasNext()) {
            List<Location> waypoints = trackIterator.next();
            GeoPoint[][] geoPoints = new GeoPoint[waypoints.size() - 1][];

            Iterator<Location> waypointIterator = waypoints.iterator();
            Location lastWaypoint = null;

            int i = 0;
            while (waypointIterator.hasNext()) {
                Location waypoint = waypointIterator.next();

                if (null != lastWaypoint) {
                    geoPoints[i] = new GeoPoint[] {
                        new GeoPoint((int) (lastWaypoint.getLatitude() * 1E6), (int) (lastWaypoint.getLongitude() * 1E6)),
                        new GeoPoint((int) (waypoint.getLatitude() * 1E6), (int) (waypoint.getLongitude() * 1E6))
                    };
                    i++;
                }

                lastWaypoint = waypoint;
            }

            OverlayWay way = new OverlayWay(geoPoints);
            wayOverlay.addWay(way);
        }

        mMapView.getOverlays().add(wayOverlay);
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.mapmapsforge, menu);
        return super.onCreateOptionsMenu(menu);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_settings:
                startActivity(new Intent(getBaseContext(), Preferences.class));
                return true;
            case R.id.menu_managemaps:
                startActivity(new Intent(getBaseContext(), ManageMaps.class));
                return true;
            case R.id.menu_loadfromsd:
                startActivity(new Intent(getBaseContext(), LoadRouteFromSD.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}