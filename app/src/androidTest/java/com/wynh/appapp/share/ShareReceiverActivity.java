package com.wynh.appapp.share;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Runs in the test APK's own UID without dependencies supplied by the target APK. */
public class ShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent("com.wynh.appapp.test.SHARE_RECEIVED").setPackage("com.wynh.appapp");
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                result.putExtra("content", bytes.toByteArray());
            }
            result.putExtra("mime", getContentResolver().getType(uri));
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) result.putExtra("name", cursor.getString(0));
            }
        } catch (Exception error) { result.putExtra("error", error.getClass().getName()); }
        sendBroadcast(result);
        finish();
    }
}
