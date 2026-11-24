package com.example.voicerecorder;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** ViewHolder-backed ListView adapter for recordings. Zero disk I/O happens during getView(). */
public final class RecordingsAdapter extends BaseAdapter {

    public static final class Item {
        public final File file;
        public final String displayName;
        public final String badge;
        public final long durationMs;
        public final String metaLine;
        public final boolean hasTranscript;
        public final boolean isPrivate;
        public final String transcriptLower; // lowercase transcript text for search filtering

        public Item(File file, String displayName, long durationMs, boolean hasTranscript,
                    boolean isPrivate, String transcriptLower) {
            this.file = file;
            this.displayName = displayName;
            this.badge = file.getName().endsWith(".mp3") ? "MP3" : "M4A";
            this.durationMs = durationMs;
            this.metaLine = Formatters.fmtDuration(durationMs)
                    + "  \u00b7  " + Formatters.fmtSize(file.length())
                    + "  \u00b7  " + Formatters.shortDate(file.lastModified());
            this.hasTranscript = hasTranscript;
            this.isPrivate = isPrivate;
            this.transcriptLower = transcriptLower == null ? "" : transcriptLower;
        }
    }

    public interface Callbacks {
        void onPlayToggle(File f);
        void onMoreClick(View anchor, Item item);
        void onTranscriptClick(File f);
        void onSkip(int deltaMs);
        void onSeek(int positionMs);
        void onSeekingChanged(boolean seeking);
        void onCycleSpeed();
    }

    private static final class Holder {
        File boundFile;
        View row, playRow, imgLock;
        TextView txtName, txtBadge, txtMeta, txtTxt, txtPos, btnBack10, btnFwd10, btnSpeed;
        ImageButton btnPlay, btnMore;
        SeekBar seek;
    }

    private final LayoutInflater inflater;
    private final PlaybackController playback;
    private final Callbacks cb;
    private final List<Item> items = new ArrayList<>();

    public RecordingsAdapter(Context c, PlaybackController playback, Callbacks cb) {
        this.inflater = LayoutInflater.from(c);
        this.playback = playback;
        this.cb = cb;
    }

    public void setItems(List<Item> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Item getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int pos, View v, ViewGroup parent) {
        Holder h;
        if (v == null) {
            v = inflater.inflate(R.layout.item_recording, parent, false);
            h = new Holder();
            h.row = v.findViewById(R.id.row);
            h.txtName = v.findViewById(R.id.txtName);
            h.txtBadge = v.findViewById(R.id.txtBadge);
            h.txtMeta = v.findViewById(R.id.txtMeta);
            h.txtTxt = v.findViewById(R.id.txtTxt);
            h.imgLock = v.findViewById(R.id.imgLock);
            h.btnPlay = v.findViewById(R.id.btnItemPlay);
            h.btnMore = v.findViewById(R.id.btnItemMore);
            h.playRow = v.findViewById(R.id.playRow);
            h.seek = v.findViewById(R.id.seek);
            h.txtPos = v.findViewById(R.id.txtPos);
            h.btnBack10 = v.findViewById(R.id.btnBack10);
            h.btnFwd10 = v.findViewById(R.id.btnFwd10);
            h.btnSpeed = v.findViewById(R.id.btnSpeed);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }

        Item item = items.get(pos);
        File f = item.file;
        h.boundFile = f;

        h.txtName.setText(item.displayName);
        h.txtBadge.setText(item.badge);
        h.txtMeta.setText(item.metaLine);
        h.txtTxt.setVisibility(item.hasTranscript ? View.VISIBLE : View.GONE);
        h.txtTxt.setOnClickListener(item.hasTranscript ? x -> cb.onTranscriptClick(f) : null);
        h.imgLock.setVisibility(item.isPrivate ? View.VISIBLE : View.GONE);

        boolean isCurrent = playback.isCurrent(f);
        boolean playing = isCurrent && playback.isPlaying();
        h.btnPlay.setImageResource(playing ? R.drawable.ic_pause_small : R.drawable.ic_play);
        h.btnPlay.setContentDescription(playing ? "Pause" : "Play");
        h.btnPlay.setOnClickListener(x -> cb.onPlayToggle(f));
        h.btnMore.setOnClickListener(x -> cb.onMoreClick(x, item));
        h.row.setOnClickListener(x -> cb.onPlayToggle(f));

        h.playRow.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        h.seek.setOnSeekBarChangeListener(null);
        if (isCurrent) {
            h.seek.setMax(playback.duration());
            h.seek.setProgress(playback.position());
            h.txtPos.setText(Formatters.fmtDuration(playback.position()));
            h.btnSpeed.setText(playback.speedLabel());
            h.btnSpeed.setContentDescription("Playback speed " + playback.speedLabel());
            h.btnBack10.setOnClickListener(x -> cb.onSkip(-10000));
            h.btnFwd10.setOnClickListener(x -> cb.onSkip(10000));
            h.btnSpeed.setOnClickListener(x -> cb.onCycleSpeed());
            h.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) {
                        cb.onSeek(p);
                        h.txtPos.setText(Formatters.fmtDuration(p));
                    }
                }
                public void onStartTrackingTouch(SeekBar s) { cb.onSeekingChanged(true); }
                public void onStopTrackingTouch(SeekBar s) { cb.onSeekingChanged(false); }
            });
        }
        return v;
    }

    /** Updates the seek bar of the visible playing row without rebuilding the list. */
    public void updateVisiblePlayback(ListView list) {
        File cur = playback.currentFile();
        if (cur == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Holder) {
                Holder h = (Holder) tag;
                if (cur.equals(h.boundFile)) {
                    int pos = playback.position();
                    h.seek.setMax(playback.duration());
                    h.seek.setProgress(pos);
                    h.txtPos.setText(Formatters.fmtDuration(pos));
                    h.btnSpeed.setText(playback.speedLabel());
                }
            }
        }
    }
}
