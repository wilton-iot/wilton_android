/*
 * Copyright 2017, alex at staticlibs.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package wilton.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.ThreadFactory;

import wilton.WiltonJni;
import wilton.support.rhino.WiltonRhinoEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static wilton.android.Common.jsonDyload;
import static wilton.android.Common.jsonRunscript;
import static wilton.android.Common.jsonWiltonConfig;
import static wilton.android.Common.unpackAsset;

public class DeviceService extends Service {

    public static DeviceService INSTANCE = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (null == INSTANCE) {
            INSTANCE = this;
        }
        Notification nf = createNotification();
        startForeground(2, nf);

        final Bundle bundle = intent.getExtras();
        Executors.newSingleThreadExecutor(new AppThreadFactory())
                .execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startApplication(bundle);
                        } catch (Throwable e) {
                            Log.e(getClass().getPackage().getName(), Log.getStackTraceString(e));
                        }
                    }
                });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        Process.killProcess(Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Application startup logic, runs on separate thread

    private void startApplication(Bundle bundle) {
        // options
        String rootModuleName = bundle.getString("wilton_rootModuleName");
        String application = bundle.getString("wilton_application");
        ArrayList<String> initArgs = new ArrayList<String>();
        initArgs.add(bundle.getString("wilton_startupModule"));
        for (int i = 0; true; i++) {
            String sa = bundle.getString("wilton_startupArg" + i);
            if (null == sa) {
                break;
            } else {
                initArgs.add(sa);
            }
        }

        // dirs
        File filesDir = getExternalFilesDir(null);
        File libDir = new File(getFilesDir().getParentFile(), "lib");
        String rootModulePath = new File(filesDir, "apps/" + application).getAbsolutePath();

        // init
        String wconf = jsonWiltonConfig("rhino", filesDir, libDir, rootModuleName, rootModulePath);
        Log.i(getClass().getPackage().getName(), wconf);
        WiltonJni.initialize(wconf);

        // modules
        WiltonJni.wiltoncall("dyload_shared_library", jsonDyload(libDir, "wilton_logging"));
        WiltonJni.wiltoncall("dyload_shared_library", jsonDyload(libDir, "wilton_loader"));
        WiltonJni.wiltoncall("dyload_shared_library", jsonDyload(libDir, "wilton_quickjs"));

        // rhino
        String prefix = "zip://" + filesDir.getAbsolutePath() + "/std.wlib/";
        String codeJni = WiltonJni.wiltoncall("load_module_resource", "{ \"url\": \"" + prefix + "wilton-requirejs/wilton-jni.js\" }");
        String codeReq = WiltonJni.wiltoncall("load_module_resource", "{ \"url\": \"" + prefix + "wilton-requirejs/wilton-require.js\" }");
        WiltonRhinoEnvironment.initialize(codeJni + codeReq);
        WiltonJni.registerScriptGateway(WiltonRhinoEnvironment.gateway(), "rhino");

        String rsj = jsonRunscript("wilton/android/initApp", "", initArgs.toArray(new String[0]));
        Log.i(getClass().getPackage().getName(), rsj);
        WiltonJni.wiltoncall("runscript_rhino", rsj);

        // destroy process
        stopService(new Intent(this, DeviceService.class));
    }

    // helper methods

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setAutoCancel(false)
                .setTicker("Wilton device")
                .setContentTitle("Wilton device")
                .setContentText("Wilton device is running")
                .setSmallIcon(R.drawable.ic_wilton)
                .setContentIntent(pi)
                .setOngoing(true)
                .setNumber(2)
                .build();
    }

    private static class AppThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(new ThreadGroup("device"), r, "device-thread", 1024 * 1024 * 16);
        }
    }

}
