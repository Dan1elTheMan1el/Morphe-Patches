package app.danielthemaniel.patches.defit.extension;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;

/**
 * Backing logic for the "Auto Stop After Distance" DeFit patch.
 *
 * Keeping the view reading, number parsing and stop sequence here (compiled,
 * testable Java) keeps the patch's injected smali to three one-line calls.
 *
 * Settings model (confirmed): the km target and the enable toggle are read
 * once, when the sync switch is turned on (captureSettings). While a session
 * runs, enforce() is polled from the notification refresh; once the tracked
 * session distance reaches the target it turns sync off exactly the way the
 * sync switch does -- writes switch_sync = false (which the alarm/worker gate
 * on) and stops the foreground service.
 *
 * All reads/writes are wrapped so a failure here can never crash DeFit.
 */
public final class DefitAutoStop {

    // Shared with Session Distance Notification: session_distance_m lives here.
    private static final String PREFS = "defit_patch_notification";
    private static final String KEY_DISTANCE_M = "session_distance_m";
    private static final String KEY_ENABLED = "autostop_enabled";
    private static final String KEY_KM = "autostop_km";

    // Resource entry names created by the patch's layout half.
    private static final String SWITCH_ID = "defit_autostop_switch";
    private static final String KM_ID = "defit_autostop_km";

    // DeFit's own sync-status flag (same string name in 0.8.2a and 0.9.3),
    // stored in the app's default prefs file (its package name).
    private static final String SYNC_STATUS_KEY = "switch_sync";

    private static final String NOTIFICATION_SERVICE =
        "com.googlefit.tester.NotificationService";

    private DefitAutoStop() {}

    private static int resId(Context ctx, String name, String type) {
        return ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Called when the sync switch changes: on a turn-on (checked), snapshot the
     * toggle + km field. The checked guard is here rather than as a branch in
     * the injected smali, so the call site stays branchless (a labelled branch
     * injected at onCheckedChanged index 0 failed class verification).
     */
    public static void captureSettings(Context ctx, boolean checked) {
        try {
            if (!checked || !(ctx instanceof Activity)) {
                return;
            }
            Activity activity = (Activity) ctx;

            boolean enabled = false;
            int switchId = resId(ctx, SWITCH_ID, "id");
            if (switchId != 0) {
                View v = activity.findViewById(switchId);
                if (v instanceof CompoundButton) {
                    enabled = ((CompoundButton) v).isChecked();
                }
            }

            float km = 0f;
            int kmId = resId(ctx, KM_ID, "id");
            if (kmId != 0) {
                View v = activity.findViewById(kmId);
                if (v instanceof EditText) {
                    String s = ((EditText) v).getText().toString().trim();
                    if (s.length() > 0) {
                        try {
                            km = Float.parseFloat(s);
                        } catch (NumberFormatException ignored) {
                            km = 0f;
                        }
                    }
                }
            }

            // A missing/zero target means there is nothing to stop at.
            if (km <= 0f) {
                enabled = false;
            }

            prefs(ctx).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putFloat(KEY_KM, km)
                .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Called from onResume: put the saved toggle + km back into the views. */
    public static void restore(Context ctx) {
        try {
            if (!(ctx instanceof Activity)) {
                return;
            }
            Activity activity = (Activity) ctx;
            SharedPreferences sp = prefs(ctx);

            int switchId = resId(ctx, SWITCH_ID, "id");
            if (switchId != 0) {
                View v = activity.findViewById(switchId);
                if (v instanceof CompoundButton) {
                    ((CompoundButton) v).setChecked(sp.getBoolean(KEY_ENABLED, false));
                }
            }

            float km = sp.getFloat(KEY_KM, 0f);
            int kmId = resId(ctx, KM_ID, "id");
            if (kmId != 0 && km > 0f) {
                View v = activity.findViewById(kmId);
                if (v instanceof EditText) {
                    String s = (km == Math.rint(km))
                        ? String.valueOf((int) km)
                        : String.valueOf(km);
                    ((EditText) v).setText(s);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Polled while syncing. If enabled and the session distance has reached the
     * target, turn sync off the same way the sync switch does.
     */
    public static void enforce(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx);
            if (!sp.getBoolean(KEY_ENABLED, false)) {
                return;
            }
            float km = sp.getFloat(KEY_KM, 0f);
            if (km <= 0f) {
                return;
            }
            float meters = sp.getFloat(KEY_DISTANCE_M, 0f);
            if (meters < km * 1000f) {
                return;
            }

            // Flip DeFit's own sync flag off (alarm/worker gate on this), then
            // drop the foreground service, mirroring a manual switch-off.
            int keyId = resId(ctx, SYNC_STATUS_KEY, "string");
            if (keyId != 0) {
                ctx.getSharedPreferences(ctx.getPackageName(), Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(ctx.getString(keyId), false)
                    .apply();
            }
            try {
                ctx.stopService(new Intent(ctx, Class.forName(NOTIFICATION_SERVICE)));
            } catch (Throwable ignored) {
            }

            // One-shot: don't keep re-stopping until the next session re-arms it.
            sp.edit().putBoolean(KEY_ENABLED, false).apply();
        } catch (Throwable ignored) {
        }
    }
}
