package com.aoe.mealsapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.aoe.mealsapp.util.Alarm;
import com.aoe.mealsapp.util.Config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * BroadcastReceiver that should be triggered once a day (approx. time set in config file) and
 * check whether user has forgotten to register for the next day's meal.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    private static final String OAUTH_CLIENT_ID = BuildConfig.OAUTH_CLIENT_ID;
    private static final String OAUTH_CLIENT_SECRET = BuildConfig.OAUTH_CLIENT_SECRET;

    //
    // EXTENDS BroadcastReceiver
    //

    /**
     * On daily alarm that triggers participation check:
     * - check settings: Does user want to be notified?
     * - if so: ask server for participation
     * - if user does not yet participate: notify him
     *
     * If the server is not available: retry every 5min within one hour after the planned request.
     */
    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.d(TAG, Thread.currentThread().getName() + " ### "
                + "onReceive() called with: context = [" + context + "], intent = [" + intent + "]");

        /* if received after latest reminder time: ignore */

        Calendar now = Calendar.getInstance();
        Calendar latestReminderTime;

        try {
            latestReminderTime = Config.readTime(context, Config.LATEST_REMINDER_TIME);

        } catch (IOException | ParseException e) {
            Log.e(TAG, Thread.currentThread().getName() + " ### "
                    + "onReceive: Couldn't read latest reminder time from config file. No alarm set.", e);
            return;
        }

        if (now.getTimeInMillis() > latestReminderTime.getTimeInMillis()) {
            Log.d(TAG, Thread.currentThread().getName() + " ### "
                    + "onReceive: TOO LATE");
            return;
        }

        /* if user wants to be notified: request server */

        if (userWantsToBeNotifiedForTomorrow(context)) {

            requestServerForTomorrowsParticipation(context, new Consumer<Boolean>() {
                @Override
                public void accept(Boolean userParticipatesTomorrow) {

                    if (userParticipatesTomorrow == null) {
                        Log.w(TAG, Thread.currentThread().getName() + " ### "
                                + "accept: Couldn't request server. Retry if latest reminder time hasn't passed, yet.");

                        /* try again as long as the latest reminder time hasn't passed */

                        try {
                            Calendar now = Calendar.getInstance();
                            Calendar latestReminderTime = Config.readTime(context, Config.LATEST_REMINDER_TIME);

                            if (now.getTimeInMillis() < latestReminderTime.getTimeInMillis()) {

                                Alarm.setRetryAlarm(context);
                            }

                        } catch (IOException | ParseException e) {
                            Log.e(TAG, Thread.currentThread().getName() + " ### "
                                    + "accept: Couldn't read reminder time from config file. No alarm set.", e);
                            return;
                        }

                        return;
                    }

                    if (!userParticipatesTomorrow) {
                        Notifications.INSTANCE.showMealsNotification(context);
                    }
                }
            });
        }
    }

    //
    // HELPER
    //

    /**
     * Read the user settings and compare with current weekday to determine whether to check
     * for meals participation at all.
     *
     * @return Whether the user wants to be notified for tomorrow. false if reminder frequency
     * cannot be read from shared preferences.
     */
    private boolean userWantsToBeNotifiedForTomorrow(Context context) {

        /* read set reminder frequency from default shared preferences */

        String reminderFrequencyKey = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(SharedPreferenceKeys.REMINDER_FREQUENCY, null);

        if (reminderFrequencyKey == null) {
            // TODO review: should never happen
            Log.e(TAG, Thread.currentThread().getName() + " ### "
                    + "userWantsToBeNotifiedForTomorrow: Couldn't read reminder frequency from "
                    + "shared preferences. Return false.");
            return false;
        }

        /* evaluate depending on reminder frequency and today's weekday */

        Calendar today = Calendar.getInstance();
        int dayOfWeek = today.get(Calendar.DAY_OF_WEEK);

        switch (reminderFrequencyKey) {
            case SharedPreferenceKeys.REMINDER_FREQUENCY__BEFORE_MONDAY:
                return dayOfWeek == 1;

            case SharedPreferenceKeys.REMINDER_FREQUENCY__BEFORE_EVERY_WEEKDAY:
                return 1 <= dayOfWeek && dayOfWeek <= 5;

            case SharedPreferenceKeys.REMINDER_FREQUENCY__NEVER:
                return false;
        }

        // unreachable
        return false;
    }

    /**
     * Sends two requests to the server to determine whether the user is already registered for
     * tomorrows meal:
     * 1. POST request to server for access token
     * 2. GET request to server for participation
     *
     * @param context Context used to build Volley RequestQueue
     */
    private void requestServerForTomorrowsParticipation(Context context, final Consumer<Boolean> resultConsumer) {

        /* Volley RequestQueue for server communication */

        final RequestQueue requestQueue = Volley.newRequestQueue(context);

        postLoginAndProceed(requestQueue, new Consumer<Boolean>() {
            @Override
            public void accept(Boolean isParticipating) {

                resultConsumer.accept(isParticipating);
            }
        }, context);
    }

    /**
     * Send a POST request to server with the following POST body:
     * - grant_type = password
     * - client_id = from gradle.properties
     * - client_secret = from gradle.properties
     * - username = from shared preferences
     * - password = from shared preferences
     *
     * @param requestQueue   Volley request queue that is used to send HTTP request
     * @param resultConsumer Callback for final result, passed on to getCurrentWeekAndProceed(),
     *                       delivers null if an error occurred
     */
    private void postLoginAndProceed(
            final RequestQueue requestQueue,
            final Consumer<Boolean> resultConsumer,
            final Context context) {

        /* send POST request */

        requestQueue.add(new StringRequest(
                Request.Method.POST,
                BuildConfig.SERVER_URL + "/oauth/v2/token",
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String loginResponse) {
                        Log.d(TAG, Thread.currentThread().getName() + " ### "
                                + "onResponse() called with: loginResponse = [" + loginResponse + "]");

                        /* proceed: extract access token and GET current week data */

                        try {
                            String token = new JSONObject(loginResponse).getString("access_token");
                            getCurrentWeekAndProceed(requestQueue, resultConsumer, token);

                        } catch (JSONException e) {
                            Log.e(TAG, Thread.currentThread().getName() + " ### "
                                    + "onResponse: Couldn't login. Returning null.", e);
                            resultConsumer.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d(TAG, Thread.currentThread().getName() + " ### "
                                + "onErrorResponse() called with: error = [" + error + "]");

                        Log.e(TAG, Thread.currentThread().getName() + " ### "
                                + "onErrorResponse: Couldn't login. Returning null.");
                        resultConsumer.accept(null);
                    }
                }
        ) {
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {

                /* read credentials from shared preferences */

                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

                String username = sharedPreferences.getString(SharedPreferenceKeys.USERNAME, null);
                String password = sharedPreferences.getString(SharedPreferenceKeys.PASSWORD, null);

                // TODO handle null

                /* return HTTP POST params */

                Map<String, String> params = new HashMap<>();

                params.put("grant_type", "password");
                params.put("client_id", OAUTH_CLIENT_ID);
                params.put("client_secret", OAUTH_CLIENT_SECRET);
                params.put("username", username);
                params.put("password", password);

                return params;
            }
        });
    }

    /**
     * Send a GET request to server with the following HTTP header:
     * - Authorization = Bearer &lt;token&gt;
     *
     * @param requestQueue   Volley request queue that is used to send HTTP request
     * @param resultConsumer Callback for final result, delivers null if an error occurred
     */
    private void getCurrentWeekAndProceed(
            RequestQueue requestQueue,
            final Consumer<Boolean> resultConsumer,
            final String token) {

        /* send GET request */

        requestQueue.add(new StringRequest(
                Request.Method.GET,
                BuildConfig.SERVER_URL + "/rest/v1/week/active",
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String currentWeekResponse) {
                        Log.d(TAG, Thread.currentThread().getName() + " ### "
                                + "onResponse() called with: currentWeekResponse = [" + currentWeekResponse + "]");

                        /* proceed: determine participation and send result to original caller via callback */

                        try {
                            boolean isParticipating = isParticipatingTomorrow(new JSONObject(currentWeekResponse));
                            resultConsumer.accept(isParticipating);

                        } catch (JSONException e) {
                            Log.e(TAG, Thread.currentThread().getName() + " ### "
                                    + "onResponse: Login failed. Response doesn't contain participation field. "
                                    + "Cannot notify user.", e);
                            resultConsumer.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d(TAG, Thread.currentThread().getName() + " ### "
                                + "onErrorResponse() called with: error = [" + error + "]");

                        Log.e(TAG, Thread.currentThread().getName() + " ### "
                                + "onErrorResponse: Couldn't receive current week. Returning null.");
                        resultConsumer.accept(null);
                    }
                }
        ) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();

                headers.put("Authorization", "Bearer " + token);

                return headers;
            }
        });
    }

    /**
     * Parse GET currentWeek answer for any set "isParticipate" property on tomorrow.
     *
     * @param currentWeekJsonAnswer JSON answer from GET currentWeek
     * @return user participates in at least one meal
     * @throws JSONException JSON doesn't contain "isParticipate" property at expected position
     */
    private boolean isParticipatingTomorrow(JSONObject currentWeekJsonAnswer) throws JSONException {

        /* get tomorrow's dayOfWeek in Monday = 0 format */

        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_WEEK, 1);
        int dayOfWeek = (tomorrow.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7; // Sun = 1 -> Mon = 0

        if (dayOfWeek == 4 || dayOfWeek == 5) {
            return false;
        }

        /* search JSON object for any meal participation tomorrow */

        JSONArray jsonObjectMeals = currentWeekJsonAnswer
                .getJSONObject("currentWeek")
                .getJSONArray("days").getJSONObject(dayOfWeek)
                .getJSONArray("meals");

        for (int i = 0; i < jsonObjectMeals.length(); i++) {
            if (jsonObjectMeals.getJSONObject(i).getBoolean("isParticipate")) {
                return true;
            }
        }

        return false;
    }
}
