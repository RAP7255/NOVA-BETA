package com.example.nova.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.d("BootReceiver", "BOOT / PACKAGE REPLACED → starting mesh");

        Intent svc = new Intent(ctx, MeshService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(svc);
        else
            ctx.startService(svc);
    }
}
