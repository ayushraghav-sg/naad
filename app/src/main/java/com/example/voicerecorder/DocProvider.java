package com.example.voicerecorder;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

/**
 * Serves files with a generic MIME type. WhatsApp asks the provider for the file's type;
 * when it is not audio/image/video, WhatsApp sends it as a document instead of a voice/audio message.
 */
public class DocProvider extends FileProvider {
    @Override
    public String getType(@NonNull Uri uri) {
        return "application/octet-stream";
    }
}
