import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.settings.sim.SimActivationNotifier;
import com.android.settings.sim.SimNotificationService;

public class SimCompleteBootReceiver extends BroadcastReceiver {
    private static final String TAG = "SimCompleteBootReceiver";
    private static boolean apduSend = false;
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.e(TAG, "Invalid broadcast received.");
            return;
        }
        if (SimActivationNotifier.getShowSimSettingsNotification(context)) {
            SimNotificationService.scheduleSimNotification(
                    context, SimActivationNotifier.NotificationType.NETWORK_CONFIG);
        }

        // Added by Tintin for delete test profile begin
        if((SystemProperties.getInt("sys.ts48.exist", 1) == 1) && !apduSend) {
            Log.d(TAG, "try delete test profile");
            final HandlerThread handlerThread = new HandlerThread("eUiccDelay");
            handlerThread.start();
            final Handler eUiccHandler = new Handler(handlerThread.getLooper());
            final Runnable runnable = new Runnable() {
                private int attempt = 0;

                @Override
                public void run() {
                    if (apduSend || attempt >= 5) {
                        handlerThread.quit();
                        return;
                    }
                    deleteAnyPreloadProfile(context);
                    attempt++;
                    if (!apduSend) {
                        eUiccHandler.postDelayed(this, 3000);
                    } else {
                        handlerThread.quit();
                    }
                }
            };
            eUiccHandler.post(runnable);
        }
        // Added by Tintin for delete test profile end
    }

    // Added by Tintin for delete test profile begin
    private void deleteAnyPreloadProfile(Context context) {
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        ApduChannelHelper helper = new ApduChannelHelper(tm, 1);
        String aid = "A0000005591010FFFFFFFF8900000100";
        int channelId = helper.openLogicalChannel(aid, 0x00);
        if (channelId < 0) {
            Log.d(TAG,"openLogicalChannel fail");
            return;
        }
        String responses = null;

        // SELECT AID (AT+CSIM=42,"0xA4040010A0000005591010FFFFFFFF8900000100")
        responses = helper.sendApdu(
            channelId,
            0x00, 0xA4, 0x00, 0x00, 0x10, // CLA, INS, P1, P2
            "A0000005591010FFFFFFFF8900000100"
        );
        Log.d(TAG,"responses: " + responses);
        // AT+CSIM=16,"8xE2910003BF2D00"BF2D0D5C0B5A909192B79F709599BF76
        responses = helper.sendApdu(
            channelId,

            0x80, 0xE2, 0x91, 0x00, 0x03, // CLA, INS, P1, P2
            "BF2D00"
        );
        Log.d(TAG,"responses: " + responses);
        if(containsTargetIccids(responses)) {
            String deleteTS48, deleteCRTC;
            // AT+CSIM=52,"8xE2910015BF33124F10A0000005591010FFFFFFFF890000C000"
            deleteCRTC = helper.sendApdu(
                channelId,
                0x80, 0xE2, 0x91, 0x00, 0x15, // CLA, INS, P1, P2
                "BF33124F10A0000005591010FFFFFFFF890000C000"
            );

            // AT+CSIM=52,"8xE2910015BF33124F10A0000005591010FFFFFFFF8900001100"
            deleteTS48 = helper.sendApdu(
            channelId,
                0x80, 0xE2, 0x91, 0x00, 0x15, // CLA, INS, P1, P2
                "BF33124F10A0000005591010FFFFFFFF8900001100"
            );

            if((deleteCRTC.trim().endsWith("9000")) && (deleteTS48.trim().endsWith("9000"))) {
                Log.d(TAG,"delete fininsh, deleteCRTC: " + deleteCRTC + ", deleteTS48: " + deleteTS48);
                SystemProperties.set("sys.ts48.exist", "0");
            } else {
                SystemProperties.set("sys.ts48.exist", "1");
                Log.d(TAG,"delete failed, deleteCRTC: " + deleteCRTC + ", deleteTS48: " + deleteTS48);

            }

        } else {
            SystemProperties.set("sys.ts48.exist", "0");
        }

        // SELECT MF multi times to ensure eUICC do memory defragmentation
        // AT+CSIM=16,"00A40004023F0000"
        for(int i = 0; i < 5; i++) {
            responses = helper.sendApdu(
                channelId,
                0x00, 0xA4, 0x00, 0x04, 0x02, // CLA, INS, P1, P2
                "3F00"
            );
        }

        apduSend = true;
        helper.closeLogicalChannel(channelId);
    }

    private boolean containsTargetIccids(String responses) {
        // Implement your logic here
        return true;
    }
}
