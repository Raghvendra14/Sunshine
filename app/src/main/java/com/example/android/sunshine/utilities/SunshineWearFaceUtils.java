package com.example.android.sunshine.utilities;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Created by DELL on 18-02-2017.
 */

public class SunshineWearFaceUtils {

    private static final String TAG = SunshineWearFaceUtils.class.getSimpleName();
    private GoogleApiClient mGoogleApiClient;

    public void initialize(Context context) {

        Cursor cursor = context.getContentResolver().query(
                WeatherContract.WeatherEntry.CONTENT_URI,
                new String[] {WeatherContract.WeatherEntry.COLUMN_MAX_TEMP, WeatherContract.WeatherEntry.COLUMN_MIN_TEMP, WeatherContract.WeatherEntry.COLUMN_WEATHER_ID},
                null,
                null,
                null
        );

        if (cursor == null) {
            return;
        }

        if (!cursor.moveToFirst()) {
            cursor.close();
            return;
        }

        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();

        ConnectionResult connectionResult = mGoogleApiClient.blockingConnect(30, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            return;
        }

        sendCurrentData(cursor, context);

    }

    /* This method is used to create Asset from the Drawable via Bitmap */
    private Asset createAssetFromDrawable(int weatherIcon, Context context) {
        Drawable drawable = context.getResources().getDrawable(weatherIcon);
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    /* This method creates dataItem into DataMap form and sends them to the wearable */
    private void sendCurrentData(Cursor cursor, Context context) {
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/weather-info");

        /* To check whether data is going or not*/
        putDataMapRequest.getDataMap().putLong("current_time", System.currentTimeMillis());

        int weatherIcon = SunshineWeatherUtils.getSmallArtResourceIdForWeatherCondition(
                cursor.getInt(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID)));

        putDataMapRequest.getDataMap().putAsset("weather_icon", createAssetFromDrawable(weatherIcon, context));

        /* Temperature is only displayed in Celsius for the moment */
        putDataMapRequest.getDataMap().putString("max_temp", SunshineWeatherUtils.formatTemperature(context,
                cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP))));
        putDataMapRequest.getDataMap().putString("min_temp", SunshineWeatherUtils.formatTemperature(context,
                cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP))));

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

}
