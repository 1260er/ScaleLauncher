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
        setTitle("Health Connect – Datenschutz");

        int padding = Math.round(20f * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Verwendung der Gesundheitsdaten");
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(this);
        text.setText("ScaleLauncher verarbeitet die Messdaten der Xiaomi S400 lokal auf dem Gerät. "
                + "Du kannst selbst auswählen, welche der unterstützten Werte – Gewicht, Körperfett, "
                + "Körperwasser, Knochenmasse, fettfreie Masse, Grundumsatz und Puls – nach "
                + "Health Connect geschrieben werden.\n\n"
                + "Die App liest keine Gesundheitsdaten aus Health Connect, besitzt keinen "
                + "Internetzugriff und übermittelt keine Daten an Xiaomi oder andere Server. "
                + "Die Berechtigungen können jederzeit in den Health-Connect-Einstellungen "
                + "entzogen werden.");
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
