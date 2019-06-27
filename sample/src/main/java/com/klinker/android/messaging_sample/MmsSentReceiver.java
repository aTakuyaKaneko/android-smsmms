package com.klinker.android.messaging_sample;

import android.content.Context;
import android.content.Intent;

public class MmsSentReceiver extends com.klinker.android.send_message.MmsSentReceiver {
    @Override
    public void onMessageStatusUpdated(Context context, Intent intent, int receiverResultCode) {
    }
}
