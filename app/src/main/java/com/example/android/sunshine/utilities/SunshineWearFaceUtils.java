package com.example.android.sunshine.utilities;

import android.content.ContentValues;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Created by DELL on 18-02-2017.
 */

public class SunshineWearFaceUtils implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private static final String TAG = SunshineWearFaceUtils.class.getSimpleName();
    private GoogleApiClient mGoogleApiClient;

    public void initialize(ContentValues currentContentValue, Context context) {

        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();

        if (currentContentValue != null) {
            sendCurrentData(currentContentValue);
        }
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }


    private void sendCurrentData(ContentValues contentValue) {
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/current-info");

        putDataMapRequest.getDataMap().putLong("dateInMillis", contentValue.getAsLong(WeatherContract.WeatherEntry.COLUMN_DATE));
        putDataMapRequest.getDataMap().putInt("weatherId", contentValue.getAsInteger(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));
        putDataMapRequest.getDataMap().putDouble("high", contentValue.getAsDouble(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP));
        putDataMapRequest.getDataMap().putDouble("low", contentValue.getAsDouble(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP));

        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                        if (!dataItemResult.getStatus().isSuccess()) {
                            Log.d(TAG, "Failed to send the weather data item");
                        } else {
                            Log.d(TAG, "Successfully sent the weather data item");
                        }

                    }
                });
    }
    @Override
    public void onConnected(@Nullable Bundle bundle) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnected: " + bundle);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnectionSuspended: " + i);
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnectionFailed: " + connectionResult);
        }
    }
}
