package de.pritcloud.scalelauncher;

import android.content.Context;
import android.widget.Toast;

final class LoggedToast {

    static Toast makeText(Context context, CharSequence message, int duration) {
        EventLog.debug(context, context.getString(R.string.log_toast, message));
        return Toast.makeText(context, message, duration);
    }

    static Toast makeText(Context context, int messageResId, int duration) {
        EventLog.debug(context, context.getString(R.string.log_toast, context.getString(messageResId)));
        return Toast.makeText(context, messageResId, duration);
    }

    private LoggedToast() {}
}
