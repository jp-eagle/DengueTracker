package com.netsdo.bestlocation;

import java.text.SimpleDateFormat;
import java.util.Date;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.netsdo.bestlocation.BestLocationListener;
import com.netsdo.denguetracker.R;

import org.json.JSONException;
import org.json.JSONObject;

public class BestLocationProvider {

	public enum LocationType {
		GPS,
		CELL,
		UNKNOWN
	}

	private static final String TAG = "BestLocationProvider";
	private static final int TOO_OLD_LOCATION_DELTA = 1000 * 60 * 2;

	private Context mContext;
	private LocationManager mLocationMgrCell;
	private LocationManager mLocationMgrGPS;
	private LocationListener mLocationListener;
	private Location mLocation;

	private Timeout mGPSTimeout;
	private Timeout mCellTimeout;

	private BestLocationListener mListener;

	//config
	private final boolean mUseGPSLocation;
	private final boolean mUseCellLocation;
	private final long mMaxGPSLocationUpdateTimespan;
	private final long mMaxCellLocationUpdateTimespan;
	private final long mMinTime;
	private final float mMinDistance;

	public BestLocationProvider(Context context, boolean useGPSLocation, boolean useCellLocation,
			long maxGPSLocationUpdateTimespan, long maxCellLocationUpdateTimespan, long minTime, float minDistance){
		this.mContext = context;
		this.mUseGPSLocation = useGPSLocation;
		this.mUseCellLocation = useCellLocation;
		this.mMaxGPSLocationUpdateTimespan = maxGPSLocationUpdateTimespan;
		this.mMaxCellLocationUpdateTimespan = maxCellLocationUpdateTimespan;
		this.mMinTime = minTime;
		this.mMinDistance = minDistance;

		initLocationListener();
		initLocationManager();
	}

	public void startLocationUpdatesWithListener(BestLocationListener listener){
		this.mListener = listener;

		Location lastKnownLocationCell = null;
		Location lastKnownLocationGPS = null;

		if(mLocationMgrCell != null){
			mLocationMgrCell.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, mMinTime, mMinDistance, mLocationListener);

			if(this.mMaxCellLocationUpdateTimespan > 0){
				mCellTimeout = new Timeout();
				mCellTimeout.setTimeout(this.mMaxCellLocationUpdateTimespan);
				mCellTimeout.setLocationType(LocationType.CELL);
				mCellTimeout.execute();
			}

			lastKnownLocationCell = mLocationMgrCell.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
		}

		if(mLocationMgrGPS != null){
			mLocationMgrGPS.requestLocationUpdates(LocationManager.GPS_PROVIDER, mMinTime, mMinDistance, mLocationListener);

			if(this.mMaxGPSLocationUpdateTimespan > 0){
				mGPSTimeout = new Timeout();
				mGPSTimeout.setTimeout(this.mMaxGPSLocationUpdateTimespan);
				mGPSTimeout.setLocationType(LocationType.GPS);
				mGPSTimeout.execute();
			}

			lastKnownLocationGPS = mLocationMgrGPS.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		}

		if(lastKnownLocationCell != null && isBetterLocation(lastKnownLocationCell, mLocation)){
			updateLocation(lastKnownLocationCell, LocationType.CELL, false);
		}

		if(lastKnownLocationGPS != null && isBetterLocation(lastKnownLocationGPS, mLocation)){
			updateLocation(lastKnownLocationGPS, LocationType.GPS, false);
		}
	}

	public void stopLocationUpdates(){
		if(mLocationMgrCell != null){
			mLocationMgrCell.removeUpdates(mLocationListener);
		}

		if(mLocationMgrGPS != null){
			mLocationMgrGPS.removeUpdates(mLocationListener);
		}

		//remove timeout threads
		if(mGPSTimeout != null){
			try { mGPSTimeout.cancel(true); } catch(Exception e) { }
			mGPSTimeout = null;
		}

		if(mCellTimeout != null){
			try { mCellTimeout.cancel(true); } catch(Exception e) { }
			mCellTimeout = null;
		}
	}

	private void restartTimeout(LocationType type){

		if(type == LocationType.GPS){
			if(mGPSTimeout != null){
				try { mGPSTimeout.cancel(true); } catch(Exception e) { }
				mGPSTimeout = new Timeout();
				mGPSTimeout.setTimeout(this.mMaxGPSLocationUpdateTimespan);
				mGPSTimeout.setLocationType(LocationType.GPS);
				mGPSTimeout.execute();
			}
		}

		if(type == LocationType.CELL){
			if(mCellTimeout != null){
				try { mCellTimeout.cancel(true); } catch(Exception e) { }
				mCellTimeout = new Timeout();
				mCellTimeout.setTimeout(this.mMaxCellLocationUpdateTimespan);
				mCellTimeout.setLocationType(LocationType.CELL);
				mCellTimeout.execute();
			}
		}
	}

	private void updateLocation(Location location, LocationType type, boolean isFresh){
		mLocation = location;
		mListener.onLocationUpdate(location, type, isFresh);
	}

	private void initLocationManager(){
		if(mUseCellLocation){
			mLocationMgrCell = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
			if(!mLocationMgrCell.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
				mLocationMgrCell = null;
			}
		}

		if(mUseGPSLocation){
			mLocationMgrGPS = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
			if(!mLocationMgrGPS.isProviderEnabled(LocationManager.GPS_PROVIDER)){
				mLocationMgrGPS = null;
			}
		}
	}

	private void initLocationListener(){
		mLocationListener = new LocationListener() {
		    public void onLocationChanged(Location location) {
		      Log.d(TAG, "onLocationChanged, LOCATION:" + locationToString(location));

		      if(isBetterLocation(location, mLocation)){
		    	  updateLocation(location, providerToLocationType(location.getProvider()), true);

		    	  if(providerToLocationType(location.getProvider()) == LocationType.CELL){
		    		  if(mCellTimeout != null){
		    			  mCellTimeout.resetTimeout();
		    		  }
		    	  }

		    	  if(providerToLocationType(location.getProvider()) == LocationType.GPS){
		    		  if(mGPSTimeout != null){
		    			  mGPSTimeout.resetTimeout();
		    		  }
		    	  }

		    	  Log.d(TAG, "onLocationChanged, BETTER LOCATION:" + locationToString(mLocation));
		      }
		    }

		    public void onStatusChanged(String provider, int status, Bundle extras) {
		    	mListener.onStatusChanged(provider, status, extras);
		    }

		    public void onProviderEnabled(String provider) {
		    	mListener.onProviderEnabled(provider);
		    }

		    public void onProviderDisabled(String provider) {
		    	mListener.onProviderDisabled(provider);
		    }
		};
	}

	private LocationType providerToLocationType(String provider){
		if(provider.equals("gps")){
			return LocationType.GPS;
		} else if(provider.equals("network")){
			return LocationType.CELL;
		} else {
			Log.w(TAG, "providerToLocationType, UNKNOWN PROVIDER:" + provider);
			return LocationType.UNKNOWN;
		}
	}

    public Location getLocation() {
        return mLocation;
    }

	public String locationToString(Location l) {
        try {
            JSONObject mObj = new JSONObject();
            if (l == null) {
                mObj.put("longitude", 0);
                mObj.put("latitude", 0);
                mObj.put("altitude", 0);
                mObj.put("speed", 0);
                mObj.put("bearing", 0);
                mObj.put("accuracy", 0);
                mObj.put("time", new SimpleDateFormat(mContext.getString(R.string.iso6301)).format(new Date()));
                mObj.put("provider", "null");
                mObj.put("elapsedrealtimenanos", 0);
                mObj.put("extras", "null");
            } else {
                mObj.put("longitude", l.getLongitude());
                mObj.put("latitude", l.getLatitude());
                mObj.put("altitude", l.getAltitude());
                mObj.put("speed", l.getSpeed());
                mObj.put("bearing", l.getBearing());
                mObj.put("accuracy", l.getAccuracy());
                mObj.put("time", new SimpleDateFormat(mContext.getString(R.string.iso6301)).format(l.getTime()));
                mObj.put("provider", l.getProvider());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    mObj.put("elapsedrealtimenanos", l.getElapsedRealtimeNanos());
                }
                mObj.put("extras", l.getExtras());
            }

            return mObj.toString();
        } catch (JSONException e) {
            e.printStackTrace();

            return null;
        }
	}


	protected boolean isBetterLocation(Location location, Location currentBestLocation) {
	    if (currentBestLocation == null) {
	        // A new location is always better than no location
	        return true;
	    }

	    // Check whether the new location fix is newer or older
	    long timeDelta = location.getTime() - currentBestLocation.getTime();
	    boolean isSignificantlyNewer = timeDelta > TOO_OLD_LOCATION_DELTA;
	    boolean isSignificantlyOlder = timeDelta < -TOO_OLD_LOCATION_DELTA;
	    boolean isNewer = timeDelta > 0;

	    // If it's been more than two minutes since the current location, use the new location
	    // because the user has likely moved
	    if (isSignificantlyNewer) {
	        return true;
	    // If the new location is more than two minutes older, it must be worse
	    } else if (isSignificantlyOlder) {
	        return false;
	    }

	    // Check whether the new location fix is more or less accurate
	    int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
	    boolean isLessAccurate = accuracyDelta > 0;
	    boolean isMoreAccurate = accuracyDelta < 0;
	    boolean isSignificantlyLessAccurate = accuracyDelta > 200;

	    // Check if the old and new location are from the same provider
	    boolean isFromSameProvider = isSameProvider(location.getProvider(),
	            currentBestLocation.getProvider());

	    // Determine location quality using a combination of timeliness and accuracy
	    if (isMoreAccurate) {
	        return true;
	    } else if (isNewer && !isLessAccurate) {
	        return true;
	    } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
	        return true;
	    }
	    return false;
	}

	/** Checks whether two providers are the same */
	private boolean isSameProvider(String provider1, String provider2) {
	    if (provider1 == null) {
	      return provider2 == null;
	    }
	    return provider1.equals(provider2);
	}

	//timeout threads
	private class Timeout extends AsyncTask<Void, Void, Void> {

		private LocationType mLocationType;
		private long mTimeout;
		private long mStartTime;

		public void setLocationType(LocationType locationtype){
			mLocationType = locationtype;
		}

		public void setTimeout(long timeout){
			mTimeout = timeout;
		}

		public void resetTimeout(){
			mStartTime = new Date().getTime();
		}

		@Override
		protected Void doInBackground(Void... params) {
			resetTimeout();

			try {
				while(new Date().getTime() < mStartTime + mTimeout){
					Thread.sleep(1000);
				}
	        	} catch (InterruptedException e) {
				Log.e(TAG, "doInBackground, LOCATION TIMEOUT EXCEPTION:" + e.getMessage());
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			mListener.onLocationUpdateTimeoutExceeded(mLocationType);
			restartTimeout(mLocationType);
		}
	}

}
