/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWearFace extends CanvasWatchFaceService {
    private static final String TAG = SunshineWearFace.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface THIN_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    private final String WEATHER_PATH = "/weather-info";
    private final String MAX_TEMP = "max_temp";
    private final String MIN_TEMP = "min_temp";
    private final String WEATHER_ICON = "weather_icon";
    private final String TIMESTAMP = "current_time";
    private String mMaxTemp;
    private String mMinTemp;
    private Asset mWeatherIcon;

    /**
     * Update rate in milliseconds for interactive mode. We update twice a second to blink the
     * colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWearFace.Engine> mWeakReference;

        public EngineHandler(SunshineWearFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWearFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener{
        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWearFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mDatePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        Paint mColonPaint;
        Paint mDividerPaint;

        boolean mMute;

        boolean mAmbient;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;

        private Bitmap mBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        boolean mShouldDrawColons;
        float mHourXOffset;
        float mColonWidth;
        float mTimeYOffset;

        float mDateXOffset;
        float mDateYOffset;

        float mWeatherYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(TAG, "In onCreate: ");

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWearFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWearFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.digital_weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = new Paint();
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mMinutePaint = new Paint();
            mMinutePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text_light));

            mMaxTempPaint = new Paint();
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mMinTempPaint = new Paint();
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_text_light));

            mColonPaint = new Paint();
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mDividerPaint = new Paint();
            mDividerPaint.setColor(resources.getColor(R.color.digital_text_light));
            mDividerPaint.setAntiAlias(true);
            mDividerPaint.setStrokeWidth(1f);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            return createTextPaint(textColor, THIN_TYPEFACE);
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWearFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWearFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWearFace.this.getResources();
            boolean isRound = insets.isRound();
//            mHourXOffset = resources.getDimension(isRound
//                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            mHourPaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateSize);
            mMaxTempPaint.setTextSize(tempSize);
            mMinTempPaint.setTextSize(tempSize);

            mHourXOffset = mHourPaint.measureText("0000") / 2;
            mColonWidth = mColonPaint.measureText(COLON_STRING);
            mDateXOffset = mDatePaint.measureText("XXX XXX 03 2017") / 4;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? THIN_TYPEFACE : NORMAL_TYPEFACE);
            mMaxTempPaint.setTypeface(burnInProtection ? THIN_TYPEFACE : NORMAL_TYPEFACE);
            mColonPaint.setTypeface(burnInProtection ? THIN_TYPEFACE : NORMAL_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            adjustPaintColorToCurrentMode(mDatePaint, getResources().getColor(R.color.digital_text_light),
                    getResources().getColor(R.color.digital_text));
            adjustPaintColorToCurrentMode(mMinTempPaint, getResources().getColor(R.color.digital_text_light),
                    getResources().getColor(R.color.digital_text));
            adjustPaintColorToCurrentMode(mDividerPaint, getResources().getColor(R.color.digital_text_light),
                    getResources().getColor(R.color.digital_text));

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mColonPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                    mDividerPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == SunshineWearFace.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mDatePaint.setAlpha(alpha);
                mMaxTempPaint.setAlpha(alpha);
                mMinTempPaint.setAlpha(alpha);
                invalidate();
            }
        }

        private void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                canvas.drawLine(
                        bounds.centerX() - 30,
                        bounds.centerY() + 15,
                        bounds.centerX() + 30,
                        bounds.centerY() + 15,
                        mDividerPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw the hours.
            float x = mHourXOffset;
            int hour = mCalendar.get(Calendar.HOUR);
            if (hour == 0) {
                hour = 12;
            }
            String hourString = formatTwoDigitNumber(hour);
            canvas.drawText(hourString, x, mTimeYOffset, mHourPaint);

            x += mHourPaint.measureText(hourString);

            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, mTimeYOffset, mColonPaint);
            }
            x += mColonWidth;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mTimeYOffset, mMinutePaint);
            x += mMinTempPaint.measureText(minuteString);

            // Draw the days.
            if (getPeekCardPosition().isEmpty()) {
                canvas.drawText(
                        mDateFormat.format(mDate),
                        mDateXOffset, mDateYOffset, mDatePaint);
            }

            if (mMaxTemp != null && mMinTemp != null) {

                float maxTextSize = mMaxTempPaint.measureText(mMaxTemp);
                float mWeatherXOffset = bounds.centerX() - (maxTextSize / 2);
                canvas.drawText(mMaxTemp, mWeatherXOffset, mWeatherYOffset, mMaxTempPaint);
                canvas.drawText(mMinTemp, mWeatherXOffset + maxTextSize - 10, mWeatherYOffset, mMinTempPaint);

                if (!mAmbient) {
                    float scaledWidth = (mMaxTempPaint.getTextSize() / mBitmap.getHeight()) * mBitmap.getWidth() + 15;
                    Bitmap weatherIcon = Bitmap.createScaledBitmap(mBitmap, (int) scaledWidth, (int) mMaxTempPaint.getTextSize() + 15, true);
                    float mWeatherIconXOffset = mWeatherXOffset - weatherIcon.getWidth() - 10;
                    canvas.drawBitmap(weatherIcon, mWeatherIconXOffset, mWeatherYOffset - weatherIcon.getHeight() + 15, null);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs =
                        mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().equals(
                            WEATHER_PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        long timestamp = dataMap.getLong(TIMESTAMP);
                        mMaxTemp = dataMap.getString(MAX_TEMP);
                        mMinTemp = dataMap.getString(MIN_TEMP);
                        mWeatherIcon = dataMap.getAsset(WEATHER_ICON);
                        Thread fetchingBitmap = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                mBitmap = loadBitmapFromAsset(mWeatherIcon);
                            }
                        });
                        fetchingBitmap.start();

                        Log.d(TAG, "In onDataChanged: " + mMaxTemp + " " + mMinTemp);
                        invalidate();
                    }
                }
            }
        }

        private Bitmap loadBitmapFromAsset(Asset weatherIcon) {
            if (weatherIcon == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(30, TimeUnit.SECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, weatherIcon).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "onConnected " + Long.toString(System.currentTimeMillis()));
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + bundle);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
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
}
