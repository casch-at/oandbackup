package dk.jens.backup;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.openintents.openpgp.IOpenPgpService;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;
import org.openintents.openpgp.util.OpenPgpUtils;

public class Crypto
{
    final static String TAG = OAndBackup.TAG;
    private OpenPgpServiceConnection service;
    private boolean successFlag, errorFlag, testFlag;
    private File[] files;
    private long[] keyIds;
    private String[] userIds;
    private String provider;
    public Crypto(SharedPreferences prefs)
    {
        userIds = prefs.getString("cryptoUserIds", "").split(",");
        // openkeychain doesn't like it if the string is empty
        if(userIds.length == 1 && userIds[0].length() == 0)
            userIds[0] = "dummy";
        else
            for(int i = 0; i < userIds.length; i++)
                userIds[i] = userIds[i].trim();
        provider = prefs.getString("openpgpProviderList", "org.sufficientlysecure.keychain");
    }
    public void testResponse(Context context, Intent intent, long[] keyIds)
    {
        /*
         * this method is only used to cause the user interaction screen
         * to be displayed if necessary before looping over the files to
         * to be de/encrypted since that would otherwise trigger it multiple
         * times.
         */
        java.io.InputStream is = new java.io.ByteArrayInputStream(new byte[]{0});
        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        intent.setAction(OpenPgpApi.ACTION_ENCRYPT);
        // this way the key ids are remembered if the user ids are unknown
        // and a user interaction screen is shown
        if(keyIds != null)
            this.keyIds = keyIds;
        intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, userIds);
        OpenPgpApi api = new OpenPgpApi(context, service.getService());
        Intent result = api.executeApi(intent, is, os);
        handleResult(context, result, BaseActivity.OPENPGP_REQUEST_TESTRESPONSE);
    }
    public void bind(Context context)
    {
        service = new OpenPgpServiceConnection(context, provider, new OpenPgpServiceConnection.OnBound()
            {
                @Override
                public void onBound(IOpenPgpService service)
                {
                    Log.i(TAG, "openpgp-api service bound");
                }
                @Override
                public void onError(Exception e)
                {
                    Log.e(TAG, "couldn't bind openpgp service: " + e.toString());
                }
            }
        );
        service.bindToService();
    }
    public void unbind()
    {
        if(service != null)
            service.unbindFromService();
    }
    public void decryptFiles(Context context, File... filesList)
    {
        Intent intent = new Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        handleFiles(context, intent, BaseActivity.OPENPGP_REQUEST_DECRYPT, filesList);
    }
    public void decryptFiles(Context context, File file)
    {
        decryptFiles(context, new File[]{file});
    }
    public void encryptFiles(Context context, File... filesList)
    {
        Intent intent = new Intent(OpenPgpApi.ACTION_ENCRYPT);
        intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, userIds);
        handleFiles(context, intent, BaseActivity.OPENPGP_REQUEST_ENCRYPT, filesList);
    }
    public void encryptFiles(Context context, File file)
    {
        encryptFiles(context, new File[] {file});
    }
    public void decryptFromAppInfo(Context context, File backupDir, AppInfo appInfo, int mode)
    {
        LogFile log = appInfo.getLogInfo();
        if(log != null)
        {
            File backupSubDir = new File(backupDir, appInfo.getPackageName());
            int i = 0;
            File[] files = new File[3];
            if(!appInfo.isSpecial() && (mode == AppInfo.MODE_APK || mode == AppInfo.MODE_BOTH))
            {
                String apk = log.getApk();
                File apkFile = new File(backupSubDir, apk + ".gpg");
                if(apkFile.exists())
                    files[i++] = apkFile;
            }
            if(mode == AppInfo.MODE_DATA || mode == AppInfo.MODE_BOTH)
            {
                String data = log.getDataDir();
                data = data.substring(data.lastIndexOf("/") + 1);
                File dataFile = new File(backupSubDir, data + ".zip.gpg");
                if(dataFile.exists())
                    files[i++] = dataFile;
            }
            decryptFiles(context, files);
        }
    }
    public void encryptFromAppInfo(Context context, File backupDir, AppInfo appInfo, int mode, SharedPreferences prefs)
    {
        File backupSubDir = new File(backupDir, appInfo.getPackageName());
        String apk = appInfo.getSourceDir();
        apk = apk.substring(apk.lastIndexOf("/") + 1);
        String data = appInfo.getDataDir();
        data = data.substring(data.lastIndexOf("/") + 1);
        int i = 0;
        File[] files = new File[3];
        if(!appInfo.isSpecial() && (mode == AppInfo.MODE_APK || mode == AppInfo.MODE_BOTH))
            files[i++] = new File(backupSubDir, apk);
        if(mode == AppInfo.MODE_DATA || mode == AppInfo.MODE_BOTH)
            files[i++] = new File(backupSubDir, data + ".zip");
        /*
        // can only be used if external_files is zipped
        if(prefs.getBoolean("backupExternalFiles", false))
        {
            File extFiles = new File(backupSubDir, ShellCommands.EXTERNAL_FILES);
            if(extFiles.exists())
                files[i++] = extFiles;
        }
        */
        encryptFiles(context, files);
        if(!errorFlag)
        {
            LogFile.writeLogFile(backupSubDir, appInfo, mode, true);
            for(File file : files)
                if(file != null)
                    ShellCommands.deleteBackup(file);
        }
    }
    public void handleFiles(Context context, Intent intent, int requestCode, File... filesList)
    {
        waitForServiceBound();
        /*
         * a more elegant solution would be to set the filenames as an extra
         * in the intent, but that doesn't work since any extras which
         * OpenPgpApi doesn't know about seem to be removed if it goes
         * through the user interaction phase.
         */
        files = filesList;
        doAction(context, intent, requestCode);
    }
    public void doAction(Context context, Intent intent, int requestCode)
    {
        errorFlag = false;
        if(!testFlag)
        {
            testResponse(context, new Intent(), null);
            waitForResult();
        }
        try
        {
            if(files != null)
            {
                if(requestCode == BaseActivity.OPENPGP_REQUEST_ENCRYPT && keyIds != null)
                    intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds);
                for(File file : files)
                {
                    // not all slots in the File array is necessarilly used.
                    // an ArrayList would probably be better
                    if(file == null)
                        continue;
                    String outputFilename;
                    if(requestCode == BaseActivity.OPENPGP_REQUEST_DECRYPT)
                        outputFilename = file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(".gpg"));
                    else
                        outputFilename = file.getAbsolutePath() + ".gpg";
                    Log.i(TAG, "crypto input: " + file.getAbsolutePath() + " output: " + outputFilename);
                    FileInputStream is = new FileInputStream(file);
                    FileOutputStream os = new FileOutputStream(outputFilename);
                    OpenPgpApi api = new OpenPgpApi(context, service.getService());
                    Intent result = api.executeApi(intent, is, os);
                    handleResult(context, result, requestCode);
                    waitForResult();
                    os.close();
                }
            }
            else
            {
                errorFlag = true;
                Log.e(TAG, "Crypto: no files to de/encrypt");
            }
        }
        catch(IOException e)
        {
            errorFlag = true;
            Log.e(TAG, "Crypto error: " + e.toString());
        }
    }
    public void cancel()
    {
        errorFlag = true;
        Log.i(TAG, "Crypto action was cancelled");
    }
    public void setError()
    {
        // to be used if the openpgp provider crashes so there isn't any usable callback
        errorFlag = true;
        Log.e(TAG, "Crypto error set. Did the openpgp provider crash?");
    }
    private boolean waitForServiceBound()
    {
        int i = 0;
        while(service.getService() == null)
        {
            try
            {
                if(i % 20 == 0)
                    Log.i(TAG, "waiting for openpgp-api service to be bound");
                Thread.sleep(100);
                if(i > 1000)
                    break;
                i++;
            }
            catch(InterruptedException e)
            {
                Log.e(TAG, "Crypto.waitForServiceBound interrupted");
            }
        }
        return service.getService() != null;
    }
    private void waitForResult()
    {
        try
        {
            int i = 0;
            while(successFlag == false && errorFlag == false)
            {
                if(i % 200 == 0)
                    Log.i(TAG, "waiting for openpgp-api user interaction");
                Thread.sleep(100);
                if(i > 1000)
                    break;
                i++;
            }
        }
        catch(InterruptedException e)
        {
            Log.e(TAG, "Crypto.waitForResult interrupted");
        }
    }
    private void handleResult(Context context, Intent result, int requestCode)
    {
        successFlag = false;
        switch(result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR))
        {
        case OpenPgpApi.RESULT_CODE_SUCCESS:
            testFlag = true;
            successFlag = true;
            break;
        case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
            PendingIntent pi = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
            try
            {
                /*
                 * Activity is needed to use startIntentSenderFromChild
                 * which is needed to get the response in onActivityResult.
                 * but HandleScheduledBackups can only get a Context from the
                 * onReceive of the BroadcastReceiver so it must be cast.
                 */
                Activity activity = (Activity) context;
                activity.startIntentSenderFromChild(activity, pi.getIntentSender(), requestCode, null, 0, 0, 0);
            }
            catch(IntentSender.SendIntentException e)
            {
                errorFlag = true;
                Log.e(TAG, "Crypto.handleResult error: " + e.toString());
            }
            catch(ClassCastException e)
            {
                errorFlag = true;
                Log.e(TAG, "Crypto.handleResult error: " + e.toString());
            }
            break;
        case OpenPgpApi.RESULT_CODE_ERROR:
            OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
            Log.e(TAG, "Crypto.handleResult error id: " + error.getErrorId());
            Log.e(TAG, "Crypto.handleResult error message: " + error.getMessage());
            errorFlag = true;
            break;
        }
    }
    public static boolean isAvailable(Context context)
    {
        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD)
            return OpenPgpUtils.isAvailable(context);
        return false;
    }
    public boolean isErrorSet()
    {
        return errorFlag;
    }
    public static boolean needToDecrypt(File backupDir, AppInfo appInfo, int mode)
    {
        File backupSubDir = new File(backupDir, appInfo.getPackageName());
        LogFile log = appInfo.getLogInfo();
        if(log != null)
        {
            File apk = new File(backupSubDir, log.getApk() + ".gpg");
            File data = new File(backupSubDir, log.getDataDir().substring(log.getDataDir().lastIndexOf("/") + 1) + ".zip.gpg");
            return (apk.exists() || data.exists());
        }
        return false;
    }
}