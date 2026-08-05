package de.pritcloud.scalelauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Local privacy/rationale page linked from Android's Health Connect permission screen. */
public final class HealthPermissionsRationaleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.health_privacy_title);

        int padding = Math.round(20f * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.health_privacy_heading);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(this);
        text.setText(R.string.health_privacy_text);
        text.setTextSize(16f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = padding;
        content.addView(text, textParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }
}
