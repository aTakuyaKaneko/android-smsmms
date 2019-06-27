package com.klinker.android.messaging_sample;

import android.content.Context;
import android.net.Uri;

public class MmsReceivedReceiver extends com.klinker.android.send_message.MmsReceivedReceiver {
    @Override
    public MmscInformation getMmscInfoForReceptionAck(Context context) {
        Settings settings = Settings.get(context);
        return new MmscInformation(settings.getMmsc(), settings.getMmsProxy(), Integer.valueOf(settings.getMmsPort()));
    }

    public void onMessageReceived(Context context, Uri messageUri) {
    }

    @Override
    public void onError(Context context, String error) {
    }
}
