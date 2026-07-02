package com.farsi.messagereader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextView statusText;
    private ImageView statusIcon;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusIcon = findViewById(R.id.statusIcon);
        Button btnEnable = findViewById(R.id.btnEnable);
        Button btnTest = findViewById(R.id.btnTest);

        btnEnable.setOnClickListener(v -> requestPermissions());
        btnTest.setOnClickListener(v -> testVoice());

        checkAndUpdateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndUpdateStatus();
    }

    private void checkAndUpdateStatus() {
        boolean smsPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean audioPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notifListenerEnabled = isNotificationListenerEnabled();

        if (smsPermission && audioPermission && notifListenerEnabled) {
            statusText.setText("✅ سرویس فعال است\nمنتظر پیام‌های جدید...");
            statusIcon.setImageResource(R.drawable.ic_active);
        } else {
            StringBuilder sb = new StringBuilder("⚠️ نیاز به تنظیمات:\n");
            if (!smsPermission) sb.append("• دسترسی به پیام کوتاه\n");
            if (!audioPermission) sb.append("• دسترسی به میکروفون\n");
            if (!notifListenerEnabled) sb.append("• دسترسی به اعلان‌ها\n");
            statusText.setText(sb.toString());
            statusIcon.setImageResource(R.drawable.ic_inactive);
        }
    }

    private void requestPermissions() {
        // Request basic permissions
        boolean needBasicPerms = false;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needBasicPerms = true;
                break;
            }
        }

        if (needBasicPerms) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }

        // Request notification listener permission
        if (!isNotificationListenerEnabled()) {
            new AlertDialog.Builder(this)
                .setTitle("دسترسی به اعلان‌ها")
                .setMessage("برای خواندن پیام‌های واتساپ و تلگرام، لطفاً دسترسی اعلان را فعال کنید.")
                .setPositiveButton("باشه", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("بعداً", null)
                .show();
        }
    }

    private void testVoice() {
        Intent serviceIntent = new Intent(this, MessageHandlerService.class);
        serviceIntent.setAction(MessageHandlerService.ACTION_TEST);
        serviceIntent.putExtra("sender", "تست سیستم");
        serviceIntent.putExtra("message", "این یک پیام آزمایشی است. سیستم خواندن پیام فارسی به درستی کار می‌کند.");
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private boolean isNotificationListenerEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            String[] names = flat.split(":");
            for (String name : names) {
                if (name.contains(packageName)) return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkAndUpdateStatus();
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "دسترسی‌ها فعال شد ✓", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
