package com.tqmane.wallart.storage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProviderAccessReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            CardStore.grantTargetUriAccess(context);
        }
    }
}
