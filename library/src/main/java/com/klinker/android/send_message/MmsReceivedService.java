package com.klinker.android.send_message;


import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;

import com.access_company.android.mms.MmsLogger;
import com.android.mms.service_alt.DownloadRequest;
import com.android.mms.service_alt.MmsConfig;
import com.android.mms.transaction.DownloadManager;
import com.android.mms.transaction.HttpUtils;
import com.android.mms.transaction.RetryScheduler;
import com.android.mms.transaction.TransactionSettings;
import com.android.mms.util.SendingProgressTokenManager;
import com.google.android.mms.InvalidHeaderValueException;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.NotifyRespInd;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.RetrieveConf;
import com.klinker.android.logger.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import static com.google.android.mms.pdu_alt.PduHeaders.STATUS_RETRIEVED;
import static com.klinker.android.send_message.MmsReceivedReceiver.EXTRA_FILE_PATH;
import static com.klinker.android.send_message.MmsReceivedReceiver.EXTRA_LOCATION_URL;
import static com.klinker.android.send_message.MmsReceivedReceiver.EXTRA_TRIGGER_PUSH;
import static com.klinker.android.send_message.MmsReceivedReceiver.EXTRA_URI;

public class MmsReceivedService extends IntentService {
    private static final String TAG = "MmsReceivedService";

    private static final String LOCATION_SELECTION =
            Telephony.Mms.MESSAGE_TYPE + "=? AND " + Telephony.Mms.CONTENT_LOCATION + " =?";

    public MmsReceivedService() {
        super("MmsReceivedService");
    }

    public MmsReceivedService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        MmsLogger.i(String.format("MmsReceivedService#onHandleIntent() MMS has finished downloading, persisting it to the database uri=%s, location=%s, byPush=%s, filePath=%s",
                intent.getParcelableExtra(MmsReceivedReceiver.EXTRA_URI),
                intent.getStringExtra(EXTRA_LOCATION_URL),
                intent.getBooleanExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, false),
                intent.getStringExtra(EXTRA_FILE_PATH)));
        Log.v(TAG, "MMS has finished downloading, persisting it to the database");

        String path = intent.getStringExtra(EXTRA_FILE_PATH);
        Log.v(TAG, path);

        FileInputStream reader = null;
        try {
            File mDownloadFile = new File(path);
            final int nBytes = (int) mDownloadFile.length();
            reader = new FileInputStream(mDownloadFile);
            final byte[] response = new byte[nBytes];
            reader.read(response, 0, nBytes);

            CommonNotificationTask task = getNotificationTask(this, intent, response);
            MmsLogger.d("MmsReceivedService#onHandleIntent() task=" + task.toString());
            executeNotificationTask(task);

            notifyReceiveCompleted(intent);

            MmsLogger.d(String.format("MmsReceivedService#onHandleIntent() start persist filePath=%s, length=%d", path, response.length));
            DownloadRequest.persist(this, response,
                    new MmsConfig.Overridden(new MmsConfig(this), null),
                    intent.getStringExtra(EXTRA_LOCATION_URL),
                    Utils.getDefaultSubscriptionId(), null);

            MmsLogger.d(String.format("MmsReceivedService#onHandleIntent() response saved successfully filePath=%s, length=%d", path, response.length));
            Log.v(TAG, "response saved successfully");
            Log.v(TAG, "response length: " + response.length);
            mDownloadFile.delete();
        } catch (FileNotFoundException e) {
            MmsLogger.d("MmsReceivedService#onHandleIntent() file not found exception", e);
            Log.e(TAG, "MMS received, file not found exception", e);
        } catch (IOException e) {
            MmsLogger.d("MmsReceivedService#onHandleIntent() io exception", e);
            Log.e(TAG, "MMS received, io exception", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "MMS received, io exception", e);
                }
            }

            handleHttpError(this, intent);
            DownloadManager.finishDownload(intent.getStringExtra(EXTRA_LOCATION_URL));
        }
    }

    protected void notifyReceiveCompleted(Intent intent){}

    private static boolean isWifiActive(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        Network[] networks = connectivityManager.getAllNetworks();
        if (networks != null) {
            for (Network net: networks) {
                NetworkInfo info = connectivityManager.getNetworkInfo(net);
                if (ConnectivityManager.TYPE_WIFI == info.getType() && info.isConnected()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void handleHttpError(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            MmsLogger.d("MmsReceivedService#handleHttpError() Current sdk version is less than KitKat");
            return;
        }

        if (!Utils.isMmsOverWifiEnabled(context) && isWifiActive(context)) {
            // Sometimes MMS can not be acquired if Wifi is enabled.
            // For example, if you are playing Youtube in the foreground.
            MmsLogger.d("MmsReceivedService#handleHttpError() Wi-Fi is enabled");
            return;
        }

        final int httpError = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0);
        if (httpError != 200) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            MmsLogger.i("MmsReceivedService#handleHttpError() Schedule retry uri=" + uri + ", httpError=" + httpError);
            RetryScheduler.getInstance(context).scheduleRetry(uri);
        }
    }

    private static NotificationInd getNotificationInd(Context context, Intent intent) throws MmsException {
        return (NotificationInd) PduPersister.getPduPersister(context).load((Uri) intent.getParcelableExtra(EXTRA_URI));
    }

    private static abstract class CommonNotificationTask {
        protected final Context mContext;
        private final TransactionSettings mTransactionSettings;
        final NotificationInd mNotificationInd;
        final String mContentLocation;

        CommonNotificationTask(Context context, TransactionSettings settings, NotificationInd ind) {
            mContext = context;
            mTransactionSettings = settings;
            mNotificationInd = ind;
            mContentLocation = new String(ind.getContentLocation());
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param pdu A byte array which contains the data of the PDU.
         * @param mmscUrl Url of the recipient MMSC.
         * @return A byte array which contains the response data.
         *         If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException if any error occurred on network interface or
         *         an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        byte[] sendPdu(byte[] pdu, String mmscUrl) throws IOException, MmsException {
            return sendPdu(SendingProgressTokenManager.NO_TOKEN, pdu, mmscUrl);
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param pdu A byte array which contains the data of the PDU.
         * @return A byte array which contains the response data.
         *         If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException if any error occurred on network interface or
         *         an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        byte[] sendPdu(byte[] pdu) throws IOException, MmsException {
            return sendPdu(SendingProgressTokenManager.NO_TOKEN, pdu,
                    mTransactionSettings.getMmscUrl());
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param token The token to identify the sending progress.
         * @param pdu A byte array which contains the data of the PDU.
         * @param mmscUrl Url of the recipient MMSC.
         * @return A byte array which contains the response data.
         *         If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException if any error occurred on network interface or
         *         an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        private byte[] sendPdu(final long token, final byte[] pdu,
                               final String mmscUrl) throws IOException, MmsException {
            if (pdu == null) {
                MmsLogger.d("sendPdu() pdu is null");
                throw new MmsException();
            }

            if (mmscUrl == null) {
                MmsLogger.d("sendPdu() mmscUrl is null");
                throw new IOException("Cannot establish route: mmscUrl is null");
            }

            if (com.android.mms.transaction.Transaction.useWifi(mContext)) {
                MmsLogger.d("send PDU with Wi-Fi");
                return HttpUtils.httpConnection(
                        mContext, token,
                        mmscUrl,
                        pdu, HttpUtils.HTTP_POST_METHOD,
                        false, null, 0);
            }

            return Utils.ensureRouteToMmsNetwork(mContext, mmscUrl, mTransactionSettings.getProxyAddress(), new Utils.Task<byte[]>() {
                @Override
                public byte[] run() throws IOException {
                    MmsLogger.d("send PDU");
                    return HttpUtils.httpConnection(
                            mContext, token,
                            mmscUrl,
                            pdu, HttpUtils.HTTP_POST_METHOD,
                            mTransactionSettings.isProxySet(),
                            mTransactionSettings.getProxyAddress(),
                            mTransactionSettings.getProxyPort());
                }
            });
        }

        public abstract void run() throws IOException;
    }

    private static class NotifyRespTask extends CommonNotificationTask {
        NotifyRespTask(Context context, NotificationInd ind, TransactionSettings settings) {
            super(context, settings, ind);
        }

        @Override
        public void run() throws IOException {
            MmsLogger.d("NotifyRespTask [start]");
            // Create the M-NotifyResp.ind
            NotifyRespInd notifyRespInd = null;
            try {
                notifyRespInd = new NotifyRespInd(
                        PduHeaders.CURRENT_MMS_VERSION,
                        mNotificationInd.getTransactionId(),
                        STATUS_RETRIEVED);

                // Pack M-NotifyResp.ind and send it
                if(com.android.mms.MmsConfig.getNotifyWapMMSC()) {
                    sendPdu(new PduComposer(mContext, notifyRespInd).make(), mContentLocation);
                    MmsLogger.i("NotifyRespTask sent M-NotifyResp.ind to content location: location=" + mContentLocation);
                } else {
                    sendPdu(new PduComposer(mContext, notifyRespInd).make());
                    MmsLogger.i("NotifyRespTask sent M-NotifyResp.ind to MMSC: location=" + mContentLocation);
                }
            } catch (MmsException e) {
                MmsLogger.w("NotifyRespTask MMSException", e);
                Log.e(TAG, "error", e);
            }
            MmsLogger.d("NotifyRespTask [end]");
        }
    }

    private static class AcknowledgeIndTask extends CommonNotificationTask {
        private final RetrieveConf mRetrieveConf;

        AcknowledgeIndTask(Context context, NotificationInd ind, TransactionSettings settings, RetrieveConf rc) {
            super(context, settings, ind);
            mRetrieveConf = rc;
        }

        @Override
        public void run() throws IOException {
            MmsLogger.d("AcknowledgeIndTask [start]");
            // Send M-Acknowledge.ind to MMSC if required.
            // If the Transaction-ID isn't set in the M-Retrieve.conf, it means
            // the MMS proxy-relay doesn't require an ACK.
            byte[] tranId = mRetrieveConf.getTransactionId();
            if (tranId != null) {
                // Create M-Acknowledge.ind
                com.google.android.mms.pdu_alt.AcknowledgeInd acknowledgeInd = null;
                try {
                    acknowledgeInd = new com.google.android.mms.pdu_alt.AcknowledgeInd(
                            PduHeaders.CURRENT_MMS_VERSION, tranId);

                    // insert the 'from' address per spec
                    String lineNumber = Utils.getMyPhoneNumber(mContext);
                    acknowledgeInd.setFrom(new EncodedStringValue(lineNumber));

                    // Pack M-Acknowledge.ind and send it
                    if(com.android.mms.MmsConfig.getNotifyWapMMSC()) {
                        sendPdu(new PduComposer(mContext, acknowledgeInd).make(), mContentLocation);
                        MmsLogger.i("AcknowledgeIndTask sent M-Acknowledge.ind to content location: location=" + mContentLocation);
                    } else {
                        sendPdu(new PduComposer(mContext, acknowledgeInd).make());
                        MmsLogger.i("AcknowledgeIndTask sent M-Acknowledge.ind to MMSC: location=" + mContentLocation);
                    }
                } catch (InvalidHeaderValueException e) {
                    MmsLogger.w("AcknowledgeIndTask InvalidHeaderValueException", e);
                    Log.e(TAG, "error", e);
                } catch (MmsException e) {
                    MmsLogger.w("AcknowledgeIndTask MMSException", e);
                    Log.e(TAG, "error", e);
                }
            }
            MmsLogger.d("AcknowledgeIndTask [end]");
        }
    }

    private static CommonNotificationTask getNotificationTask(Context context, Intent intent, byte[] response) {
        if (response.length == 0) {
            MmsLogger.i("MmsReceivedService#getNotificationTask() response is blank");
            return null;
        }

        final GenericPdu pdu =
                (new PduParser(response, new MmsConfig.Overridden(new MmsConfig(context), null).
                        getSupportMmsContentDisposition())).parse();
        if (pdu == null || !(pdu instanceof RetrieveConf)) {
            android.util.Log.e(TAG, "MmsReceivedReceiver.sendNotification failed to parse pdu");
            MmsLogger.i("MmsReceivedService#getNotificationTask() failed to parse pdu");
            return null;
        }

        try {
            NotificationInd ind = getNotificationInd(context, intent);
            TransactionSettings transactionSettings = new TransactionSettings(context, null);
            if (intent.getBooleanExtra(EXTRA_TRIGGER_PUSH, false)) {
                return new NotifyRespTask(context, ind, transactionSettings);
            } else {
                return new AcknowledgeIndTask(context, ind, transactionSettings, (RetrieveConf) pdu);
            }
        } catch (MmsException e) {
            MmsLogger.w("MmsReceivedService#getNotificationTask() MMSException", e);
            Log.e(TAG, "error", e);
            return null;
        }
    }

    private static void executeNotificationTask(CommonNotificationTask task) throws IOException {
        if (task == null) {
            MmsLogger.d("MmsReceivedService#executeNotificationTask() Task is null");
            return;
        }

        try {
            // need retry ?
            task.run();
        } catch (IOException e) {
            MmsLogger.w("MmsReceivedService#executeNotificationTask() MMS send received notification, io exception", e);
            Log.e(TAG, "MMS send received notification, io exception", e);
            throw e;
        }
    }
}
