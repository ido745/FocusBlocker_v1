package com.focusapp.blocker.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device Admin Receiver for deletion protection.
 * When the app is a device administrator, Android requires the user to
 * deactivate admin status before uninstalling — adding friction in moments of weakness.
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Deletion protection enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Deletion protection disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling deletion protection will allow this app to be uninstalled. Are you sure?"
    }
}
