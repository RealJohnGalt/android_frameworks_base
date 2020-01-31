/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.stats;

import android.app.PendingIntent;
import android.app.StatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IPendingIntentRef;
import android.os.Process;
import android.os.StatsDimensionsValue;
import android.os.StatsDimensionsValueParcel;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * @hide
 */
public class StatsCompanion {
    private static final String TAG = "StatsCompanion";
    private static final boolean DEBUG = false;

    private static final int AID_STATSD = 1066;

    static void enforceStatsdCallingUid() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        if (Binder.getCallingUid() != AID_STATSD) {
            throw new SecurityException("Not allowed to access StatsCompanion");
        }
    }

    /**
     * Lifecycle class for both {@link StatsCompanionService} and {@link StatsManagerService}.
     */
    public static final class Lifecycle extends SystemService {
        private StatsCompanionService mStatsCompanionService;
        private StatsManagerService mStatsManagerService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mStatsCompanionService = new StatsCompanionService(getContext());
            mStatsManagerService = new StatsManagerService(getContext());
            mStatsCompanionService.setStatsManagerService(mStatsManagerService);
            mStatsManagerService.setStatsCompanionService(mStatsCompanionService);

            try {
                publishBinderService(Context.STATS_COMPANION_SERVICE,
                        mStatsCompanionService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_COMPANION_SERVICE);
                publishBinderService(Context.STATS_MANAGER_SERVICE,
                        mStatsManagerService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_MANAGER_SERVICE);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to publishBinderService", e);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mStatsCompanionService.systemReady();
            }
        }
    }

    /**
     * Wrapper for {@link PendingIntent}. Allows Statsd to send PendingIntents.
     */
    public static class PendingIntentRef extends IPendingIntentRef.Stub {

        private static final String TAG = "PendingIntentRef";

        /**
         * The last report time is provided with each intent registered to
         * StatsManager#setFetchReportsOperation. This allows easy de-duping in the receiver if
         * statsd is requesting the client to retrieve the same statsd data. The last report time
         * corresponds to the last_report_elapsed_nanos that will provided in the current
         * ConfigMetricsReport, and this timestamp also corresponds to the
         * current_report_elapsed_nanos of the most recently obtained ConfigMetricsReport.
         */
        private static final String EXTRA_LAST_REPORT_TIME = "android.app.extra.LAST_REPORT_TIME";
        private static final int CODE_DATA_BROADCAST = 1;
        private static final int CODE_ACTIVE_CONFIGS_BROADCAST = 1;
        private static final int CODE_SUBSCRIBER_BROADCAST = 1;

        private final PendingIntent mPendingIntent;
        private final Context mContext;

        public PendingIntentRef(PendingIntent pendingIntent, Context context) {
            mPendingIntent = pendingIntent;
            mContext = context;
        }

        @Override
        public void sendDataBroadcast(long lastReportTimeNs) {
            enforceStatsdCallingUid();
            Intent intent = new Intent();
            intent.putExtra(EXTRA_LAST_REPORT_TIME, lastReportTimeNs);
            try {
                mPendingIntent.send(mContext, CODE_DATA_BROADCAST, intent, null, null);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Unable to send PendingIntent");
            }
        }

        @Override
        public void sendActiveConfigsChangedBroadcast(long[] configIds) {
            enforceStatsdCallingUid();
            Intent intent = new Intent();
            intent.putExtra(StatsManager.EXTRA_STATS_ACTIVE_CONFIG_KEYS, configIds);
            try {
                mPendingIntent.send(mContext, CODE_ACTIVE_CONFIGS_BROADCAST, intent, null, null);
                if (DEBUG) {
                    Slog.d(TAG, "Sent broadcast with config ids " + Arrays.toString(configIds));
                }
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Unable to send active configs changed broadcast using PendingIntent");
            }
        }

        @Override
        public void sendSubscriberBroadcast(long configUid, long configId, long subscriptionId,
                long subscriptionRuleId, String[] cookies,
                StatsDimensionsValueParcel dimensionsValue) {
            enforceStatsCompanionPermission(mContext);
            Intent intent =
                    new Intent()
                            .putExtra(StatsManager.EXTRA_STATS_CONFIG_UID, configUid)
                            .putExtra(StatsManager.EXTRA_STATS_CONFIG_KEY, configId)
                            .putExtra(StatsManager.EXTRA_STATS_SUBSCRIPTION_ID, subscriptionId)
                            .putExtra(StatsManager.EXTRA_STATS_SUBSCRIPTION_RULE_ID,
                                    subscriptionRuleId)
                            .putExtra(StatsManager.EXTRA_STATS_DIMENSIONS_VALUE, dimensionsValue);

            ArrayList<String> cookieList = new ArrayList<>(cookies.length);
            cookieList.addAll(Arrays.asList(cookies));
            intent.putStringArrayListExtra(
                    StatsManager.EXTRA_STATS_BROADCAST_SUBSCRIBER_COOKIES, cookieList);

            if (DEBUG) {
                Slog.d(TAG,
                        String.format(
                                "Statsd sendSubscriberBroadcast with params {%d %d %d %d %s %s}",
                                configUid, configId, subscriptionId, subscriptionRuleId,
                                Arrays.toString(cookies),
                                // TODO (b/148604617): convert StatsDimensionsValueParcel into
                                // StatsDimensionsValue
                                dimensionsValue));
            }
            try {
                mPendingIntent.send(mContext, CODE_SUBSCRIBER_BROADCAST, intent, null, null);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG,
                        "Unable to send using PendingIntent from uid " + configUid
                                + "; presumably it had been cancelled.");
            }
        }
    }
}
